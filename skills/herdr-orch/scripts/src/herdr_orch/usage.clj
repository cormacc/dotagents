(ns herdr-orch.usage
  "Read-only subscription allowance adapters, normalisation and a per-provider cache.

  Contract:
  - Credentials come from harness stores and are never refreshed, rotated or written.
  - A token never leaves `read-credential`/the HTTP request: results, cache files,
    reasons and exception data carry no token, header or raw response body.
  - Any unrecognised shape, status or failure degrades to status `:unknown`; it never
    yields credit.

  Provider result (see `usage-for`):
    {:status :ok|:stale|:unknown :reason string-or-nil :remaining-fraction 0..1-or-nil
     :windows [{:name :scope :used-fraction :resets-at}] :source keyword-or-nil
     :fetched-at epoch-s-or-nil :reserve number}
  `:scope` is nil for an account-wide window, else a lower-case family string.
  `:remaining-fraction` is `1 - max(used)` over account-wide windows only; scoped
  windows stay in `:windows` for the router."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [herdr-orch.ledger :as ledger])
  (:import [java.nio.file Files Paths StandardCopyOption StandardOpenOption FileAlreadyExistsException]
           [java.time Instant OffsetDateTime ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Base64]))

(def request-timeout-ms 5000)
(def claim-s 30)
(def stale-ceiling-s 3600)
(def default-ttl-s 300)

(def anthropic-url "https://api.anthropic.com/api/oauth/usage")
(def codex-url "https://chatgpt.com/backend-api/wham/usage")
;; Header constants follow @ogulcancelik/pi-minimal-footer 0.1.15 `index.ts`
;; `fetchClaudeUsage`/`fetchCodexUsage`, which receive 200s from both endpoints.
;; The footer's comment attributes a stricter rate-limit bucket (persistent 429s) to other
;; user agents; that causality is its claim, not verified here.
(def anthropic-user-agent "claude-code/2.1.282")
(def codex-user-agent "pi-agent")

(defn now-s [] (quot (System/currentTimeMillis) 1000))

;; ---------------------------------------------------------------------------
;; Credentials

(defn- read-json [path]
  (try (when (fs/regular-file? path) (json/parse-string (slurp (str path)) true))
       (catch Exception _ nil)))

(defn- ->seconds
  "Epoch value in seconds or milliseconds (>= 1e11 is ms) -> epoch seconds."
  [n]
  (when (number? n) (if (>= n 1e11) (quot (long n) 1000) (long n))))

(defn- jwt-exp [jwt]
  (try
    (let [payload (second (str/split (str jwt) #"\."))
          claims (json/parse-string (String. (.decode (Base64/getUrlDecoder) ^String payload) "UTF-8") true)]
      (->seconds (:exp claims)))
    (catch Exception _ nil)))

(defn- home-path [opts rel]
  (fs/path (or (:home opts) (System/getProperty "user.home")) rel))

(defn- raw-credential
  "Token material for STORE/PROVIDER, or nil when the store has none."
  [store provider opts]
  (case store
    :pi (let [k (case provider "anthropic" :anthropic "openai-codex" :openai-codex nil)
              e (some-> (when k (read-json (home-path opts ".pi/agent/auth.json"))) (get k))]
          (when (string? (:access e))
            {:token (:access e) :expires-at (->seconds (:expires e)) :account-id (:accountId e)}))
    :claude (when (= provider "anthropic")
              (let [e (:claudeAiOauth (read-json (home-path opts ".claude/.credentials.json")))]
                (when (string? (:accessToken e))
                  {:token (:accessToken e) :expires-at (->seconds (:expiresAt e))})))
    :codex (when (= provider "openai-codex")
             (let [t (:tokens (read-json (home-path opts ".codex/auth.json")))]
               (when (string? (:access_token t))
                 ;; No readable `exp` leaves expiry unknown: the endpoint answers 401 -> `auth`.
                 {:token (:access_token t) :expires-at (jwt-exp (:access_token t))
                  :account-id (:account_id t)})))
    nil))

(defn read-credential
  "(read-credential store provider opts) -> {:token :expires-at :source :account-id}
  or {:reason :missing|:expired}. STORE is :pi, :claude or :codex; PROVIDER a provider
  name string (\"anthropic\" or \"openai-codex\"); OPTS {:home root :now epoch-s}.
  Read-only: never refreshes. `:expires-at` is epoch seconds or nil (unknown)."
  [store provider opts]
  (if-let [c (raw-credential store provider opts)]
    (let [now (or (:now opts) (now-s))]
      (if (and (:expires-at c) (>= now (:expires-at c)))
        {:reason :expired}
        (assoc c :source store)))
    {:reason :missing}))

(defn- first-credential
  "First usable credential over STORES; else {:reason :expired} if any expired, else :missing."
  [stores provider opts]
  (let [rs (map #(read-credential % provider opts) stores)]
    (or (first (filter :token rs))
        {:reason (if (some #(= :expired (:reason %)) rs) :expired :missing)})))

;; ---------------------------------------------------------------------------
;; Normalisation

(defn- clamp01 [x] (max 0.0 (min 1.0 (double x))))
(defn- pct->fraction [p] (when (number? p) (clamp01 (/ (double p) 100.0))))

(defn- parse-instant [s]
  (when (string? s)
    (try (.getEpochSecond (.toInstant (OffsetDateTime/parse s)))
         (catch Exception _
           (try (.getEpochSecond (Instant/parse s)) (catch Exception _ nil))))))

(defn- anthropic-window [name scope bucket]
  (when (map? bucket)
    (let [used (pct->fraction (or (:utilization bucket) (:percent bucket)))
          reset (parse-instant (:resets_at bucket))]
      ;; A 0 % bucket without a reset is a placeholder for an unused window.
      (when (and used (not (and (zero? used) (nil? reset))))
        {:name name :scope scope :used-fraction used :resets-at reset}))))

(defn- flat-windows [data]
  (keep (fn [[k bucket]]
          (let [n (name k)]
            (cond
              (#{"five_hour" "seven_day"} n) (anthropic-window n nil bucket)
              (str/starts-with? n "seven_day_")
              (anthropic-window n (str/lower-case (subs n 10)) bucket))))
        data))

(defn- limit-window [{:keys [kind scope] :as l}]
  (case kind
    "session" (anthropic-window "five_hour" nil l)
    "weekly_all" (anthropic-window "seven_day" nil l)
    "weekly_scoped"
    (when-let [fam (some-> (get-in scope [:model :display_name]) str str/trim
                           (str/split #"\s+") first not-empty str/lower-case)]
      (anthropic-window (str "seven_day_" fam) fam l))
    nil))

(defn normalise-anthropic
  "Anthropic usage body (flat buckets or `limits[]`, keyword keys) -> windows vector.
  Flat buckets win when they yield any window; otherwise `limits[]` is read."
  [data]
  (let [flat (vec (flat-windows data))]
    (if (seq flat) flat (vec (keep limit-window (when (sequential? (:limits data)) (:limits data)))))))

(defn- codex-window [name scope w]
  (when (and (map? w) (number? (:used_percent w)))
    {:name name :scope scope :used-fraction (pct->fraction (:used_percent w))
     :resets-at (when (number? (:reset_at w)) (long (:reset_at w)))}))

(defn- codex-pair [prefix scope rl]
  (keep identity [(codex-window (str prefix "primary") scope (:primary_window rl))
                  (codex-window (str prefix "secondary") scope (:secondary_window rl))]))

(defn normalise-codex
  "Codex `/wham/usage` body -> windows vector. Null windows are skipped. Each
  `additional_rate_limits` entry (`limit_name`, `metered_feature`, nested `rate_limit`) becomes
  model-scoped windows. Source: openai/codex 021fccb0 `additional_rate_limit_details.rs`
  (those three fields) and `backend-client/src/client.rs` `make_additional_rate_limit_snapshot`,
  which reads the windows from the nested `rate_limit`. The family is the lower-cased
  `normal_model_slug` when an entry carries one (a different endpoint does), else the
  lower-cased `limit_name`. The generated type shows no example values, so whether
  `limit_name` matches a candidate's model token is for the router to decide; an unmatched
  scope stays visible in `:windows`. An entry without a usable name is ignored."
  [data]
  (let [rl (:rate_limit data)
        extras (when (sequential? (:additional_rate_limits data)) (:additional_rate_limits data))]
    (vec (concat
          (codex-pair "" nil rl)
          (mapcat (fn [e]
                    (when-let [fam (some-> (or (:normal_model_slug e) (:limit_name e)) str str/trim
                                           not-empty str/lower-case)]
                      (codex-pair (str fam "-") fam (:rate_limit e))))
                  extras)))))

(defn summarise
  "Windows -> {:windows :remaining-fraction}, or nil when no account-wide window exists
  (the aggregate would otherwise be unfounded credit)."
  [windows]
  (let [acct (filter (comp nil? :scope) windows)]
    (when (seq acct)
      {:windows (vec windows)
       :remaining-fraction (- 1.0 (apply max (map :used-fraction acct)))})))

;; ---------------------------------------------------------------------------
;; HTTP + adapters

(defn default-http-fn
  "(http-fn {:url :headers :timeout-ms}) -> {:status :headers :body}. May throw.
  TIMEOUT-MS bounds the whole exchange (connect, headers and body); the client's own
  `:timeout` stops at the headers. On expiry the exchange is cancelled, the open response
  stream closed, and `java.net.http.HttpTimeoutException` thrown."
  [{:keys [url headers timeout-ms]}]
  (let [stream (promise)
        work (future
               (let [resp (http/get url {:headers headers :timeout timeout-ms :throw false :as :stream})
                     body (:body resp)]
                 (deliver stream body)
                 (try (assoc (select-keys resp [:status :headers])
                             :body (slurp body))
                      (finally (.close body)))))
        ;; The client's own timeout can fire just before the bound below; surface its cause, not
        ;; the future's wrapper, so a timeout always reaches the caller as HttpTimeoutException.
        out (try (deref work timeout-ms ::timeout)
                 (catch java.util.concurrent.ExecutionException e (throw (or (.getCause e) e))))]
    (if (= ::timeout out)
      (do (future-cancel work)
          (when (realized? stream) (try (.close @stream) (catch Exception _ nil)))
          (throw (java.net.http.HttpTimeoutException. (str "exchange exceeded " timeout-ms " ms"))))
      out)))

(defn- timeout? [^Throwable e]
  (loop [t e n 0]
    (cond (nil? t) false
          (> n 6) false
          (instance? java.net.http.HttpTimeoutException t) true
          (= :timeout (:reason (ex-data t))) true
          :else (recur (.getCause t) (inc n)))))

(defn- header [headers k]
  (some (fn [[hk v]] (when (= (str/lower-case (name hk)) k) v)) headers))

(defn- retry-after-s [headers]
  (when-let [v (some-> (header headers "retry-after") str str/trim not-empty)]
    (or (try (let [n (Double/parseDouble v)] (when (>= n 0) (long (Math/ceil n)))) (catch Exception _ nil))
        (try (max 0 (- (.toEpochSecond (ZonedDateTime/parse v DateTimeFormatter/RFC_1123_DATE_TIME))
                       (now-s)))
             (catch Exception _ nil)))))

(def adapters
  {:anthropic-oauth
   {:url anthropic-url
    :headers (fn [c] {"Authorization" (str "Bearer " (:token c))
                      "anthropic-beta" "oauth-2025-04-20"
                      "User-Agent" anthropic-user-agent
                      "Accept" "application/json"})
    :normalise normalise-anthropic}
   :codex-oauth
   {:url codex-url
    :headers (fn [c] (cond-> {"Authorization" (str "Bearer " (:token c))
                              "User-Agent" codex-user-agent
                              "Accept" "application/json"}
                       (:account-id c) (assoc "ChatGPT-Account-Id" (:account-id c))))
    :normalise normalise-codex}})

(defn fetch
  "(fetch adapter-key credential http-fn) -> {:windows :remaining-fraction} on success, or
  {:reason string :transient bool :retry-after-s n-or-nil}. Transient failures (429, 5xx,
  network) may be answered from a stale snapshot; a timeout is non-transient, so it reads
  as `unknown` on every read. HTTP-FN receives
  {:url :headers :timeout-ms 5000}. Nothing from the request or response is retained."
  [adapter-key credential http-fn]
  (let [{:keys [url headers normalise]} (adapters adapter-key)]
    (try
      (let [{:keys [status] :as resp} (http-fn {:url url :headers (headers credential)
                                                :timeout-ms request-timeout-ms})]
        (cond
          (= 200 status)
          ;; A malformed 200 is a shape failure, never a transient one.
          (or (try (let [b (:body resp)
                         data (if (string? b) (json/parse-string b true) b)]
                     (when (map? data) (summarise (normalise data))))
                   (catch Exception _ nil))
              {:reason "shape" :transient false})
          (= 401 status) {:reason "auth" :transient false}
          (= 403 status) {:reason "scope" :transient false}
          (or (= 429 status) (and (number? status) (<= 500 status 599)))
          {:reason (str "http-" status) :transient true :retry-after-s (retry-after-s (:headers resp))}
          :else {:reason (str "http-" status) :transient false}))
      (catch Exception e
        ;; Never carry the exception: client ex-data can embed request headers.
        ;; A timeout is never served stale (`unknown` "timeout"); a network failure may be.
        (if (timeout? e)
          {:reason "timeout" :transient false}
          {:reason "network" :transient true})))))

;; ---------------------------------------------------------------------------
;; Cache and claim marker

(defn default-cache-dir [] (fs/path (ledger/directory) "usage"))

(defn- cache-file [dir provider] (fs/path dir (str (ledger/safe-id provider) ".json")))
(defn- claim-file [dir provider] (fs/path dir (str (ledger/safe-id provider) ".claim")))

(defn- read-state [dir provider]
  (let [s (read-json (cache-file dir provider))]
    (when (map? s) s)))

(defn- atomic-write! [path content]
  (let [tmp (fs/path (fs/parent path) (str (fs/file-name path) "." (random-uuid) ".tmp"))]
    (try (spit (str tmp) content)
         (Files/move (Paths/get (str tmp) (make-array String 0)) (Paths/get (str path) (make-array String 0))
                     (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
         (finally (fs/delete-if-exists tmp)))))

(defn- write-state!
  "Best-effort: an unwritable cache directory costs caching, not correctness."
  [dir provider state]
  (try (fs/create-dirs dir)
       (atomic-write! (cache-file dir provider) (json/generate-string state))
       (catch Exception _ nil)))

(defn- with-claim-lock
  "Run F holding a mutual-exclusion lock on the provider's claim marker: a JVM monitor
  (an OS lock does not exclude threads of one JVM) plus an exclusive OS file lock across
  processes. If acquiring the lock fails, F runs once, unlocked: no coordination beats no
  answer. F is never inside the acquisition `try`, so its own IOException is not caught."
  [dir provider f]
  (locking (.intern (str (claim-file dir provider)))
    (let [ch (try (fs/create-dirs dir)
                  (let [ch (java.nio.channels.FileChannel/open
                            (Paths/get (str (fs/path dir (str (ledger/safe-id provider) ".claimlock"))) (make-array String 0))
                            (into-array java.nio.file.OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
                    (try (.lock ch) ch ; released when the channel closes
                         (catch Throwable t (.close ch) (throw t))))
                  (catch java.io.IOException _ nil))]
      (if ch
        (try (f) (finally (.close ch)))
        (f)))))

(defn claim!
  "Claim the provider's fetch for `claim-s` seconds from NOW. True when this caller owns
  it, false while another live claim exists. The marker holds its expiry epoch second;
  read-check-write runs under `with-claim-lock`, so concurrent contenders, including
  several that all see the same expired marker, produce exactly one winner."
  [dir provider now]
  (with-claim-lock
    dir provider
    (fn []
      (let [path (claim-file dir provider)
            until (try (Double/parseDouble (str/trim (slurp (str path)))) (catch Exception _ 0))]
        (when (<= until now)
          ;; An unwritable marker cannot coordinate: fetch uncoordinated rather than fail.
          (try (atomic-write! path (str (+ now claim-s))) (catch Exception _ nil))
          true)))))

(defn release!
  "Delete the claim marker only when it is still the one NOW's `claim!` wrote, so a slow
  owner cannot delete the marker of a caller that took over after expiry."
  [dir provider now]
  (try (with-claim-lock
         dir provider
         (fn []
           (let [path (claim-file dir provider)]
             (when (= (str (+ now claim-s)) (try (str/trim (slurp (str path))) (catch Exception _ nil)))
               (fs/delete-if-exists path)))))
       (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; Provider orchestration

(defn- result [pcfg m]
  (merge {:status :unknown :reason nil :remaining-fraction nil :windows [] :source nil :fetched-at nil}
         m {:reserve (or (:reserve pcfg) 0)}))

(defn- from-snapshot
  "Cached-state result. A failure that is not transient (`auth`, `scope`, `shape`, other
  statuses) is `:unknown` however good the retained snapshot, on every read; only a
  transient failure or a claim held elsewhere may serve the snapshot as `:stale`, and only
  within the ceiling (`:unknown` `stale-expired` beyond it). A fresh unflagged snapshot is `:ok`."
  [pcfg state ttl now fallback-reason]
  (let [{:keys [fetched-at] :as snap} (:snapshot state)
        src (some-> (or (:source state) (:source snap)) keyword)]
    (cond
      (and (:reason state) (not (:transient state)))
      (result pcfg {:reason (:reason state) :source src})
      (not (and snap (number? fetched-at)))
      (result pcfg {:reason (or (:reason state) fallback-reason) :source src})
      (>= (- now fetched-at) stale-ceiling-s)
      (result pcfg {:reason "stale-expired" :fetched-at fetched-at :source src})
      :else
      (let [fresh? (and (< (- now fetched-at) ttl) (not (:reason state)))]
        (result pcfg {:status (if fresh? :ok :stale)
                      :reason (when-not fresh? (or (:reason state) fallback-reason))
                      :remaining-fraction (:remaining-fraction snap)
                      :windows (:windows snap)
                      :source src
                      :fetched-at fetched-at})))))

(defn- fresh-snapshot? [state ttl now]
  (let [t (get-in state [:snapshot :fetched-at])]
    (and (number? t) (not (:reason state)) (<= 0 (- now t)) (< (- now t) ttl))))

(defn- cached-answer [pcfg state ttl now fresh]
  (when-not fresh
    (cond
      (fresh-snapshot? state ttl now) (from-snapshot pcfg state ttl now nil)
      (> (or (:next-fetch-at state) 0) now) (from-snapshot pcfg state ttl now "backoff"))))

(defn- fetch-provider [provider pcfg {:keys [fresh cache-dir http-fn now] :as opts}]
  (let [ttl (or (:cache-ttl-s pcfg) default-ttl-s)
        ;; Credentials are checked before the cache so a missing or expired token reads
        ;; the same (`unknown`) whether or not a snapshot exists. The read is local.
        cred (first-credential (or (seq (:credentials pcfg)) []) provider opts)
        state (read-state cache-dir provider)]
    (cond
      (not (:token cred))
      (result pcfg {:reason (name (:reason cred))})

      (cached-answer pcfg state ttl now fresh)
      (cached-answer pcfg state ttl now fresh)

      (not (claim! cache-dir provider now))
      (from-snapshot pcfg state ttl now "claimed")

      :else
      (try
        ;; Re-read under the claim: another caller may have filled the cache or set backoff.
        (let [state (read-state cache-dir provider)]
          (if-let [answer (cached-answer pcfg state ttl now fresh)]
            answer
            (let [out (fetch (:usage pcfg) cred http-fn)]
              (if (:windows out)
                (let [snap {:windows (:windows out) :remaining-fraction (:remaining-fraction out)
                            :source (name (:source cred)) :fetched-at now}]
                  (write-state! cache-dir provider {:snapshot snap :next-fetch-at (+ now ttl)})
                  (from-snapshot pcfg {:snapshot snap} ttl now nil))
                (let [backoff (max ttl (or (:retry-after-s out) 0))
                      st (cond-> {:reason (:reason out) :transient (boolean (:transient out))
                                  :source (name (:source cred)) :next-fetch-at (+ now backoff)}
                           (:snapshot state) (assoc :snapshot (:snapshot state)))]
                  (write-state! cache-dir provider st)
                  (from-snapshot pcfg st ttl now nil))))))
        (finally (release! cache-dir provider now))))))

(defn usage-for
  "(usage-for config) / (usage-for config opts) -> {provider-name provider-result}.
  CONFIG's `:providers` is keyed by provider name STRING; each value may carry `:usage`
  (adapter keyword), `:credentials` (vector of :pi/:claude/:codex), `:cache-ttl-s`,
  `:reserve`. A provider without `:usage` is `{:status :ok :remaining-fraction 1.0}` with
  no fetch. OPTS:
    :providers  names to include (default all configured); an unconfigured name yields
                an `:unknown` result with reason \"not-configured\"
    :fresh      true bypasses TTL and backoff (a live claim by another caller still holds)
    :http-fn    (fn [{:url :headers :timeout-ms}]) -> {:status :headers :body}; default
                babashka.http-client with a 5 s timeout
    :home       credential root (default user.home)
    :cache-dir  default `<ledger>/usage`
    :now        epoch seconds (default wall clock)
  Every provider result also carries `:reserve` from its config (0 when absent)."
  ([config] (usage-for config {}))
  ([config opts]
   (let [providers (:providers config)
         opts (assoc (merge {:http-fn default-http-fn} opts)
                     :now (or (:now opts) (now-s)))
         opts (cond-> opts (nil? (:cache-dir opts)) (assoc :cache-dir (default-cache-dir)))]
     (into {}
           (for [p (or (seq (:providers opts)) (keys providers))
                 :let [pcfg (get providers p)]]
             [p (cond
                  (nil? pcfg) (result nil {:reason "not-configured"})
                  (nil? (:usage pcfg)) (result pcfg {:status :ok :remaining-fraction 1.0})
                  (nil? (adapters (:usage pcfg))) (result pcfg {:reason "unknown-adapter"})
                  :else (fetch-provider p pcfg opts))])))))
