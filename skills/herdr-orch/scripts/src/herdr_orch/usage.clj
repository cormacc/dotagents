(ns herdr-orch.usage
  "Read-only subscription collection with harness-owned credentials.

  Only fresh, resolved account and exact model bounds grant routing credit."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [herdr-orch.quota-credentials :as credentials])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.channels FileChannel]
           [java.nio.file Files Paths StandardCopyOption StandardOpenOption]
           [java.time Instant OffsetDateTime ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(def request-timeout-ms 5000)
(def body-limit 1048576)
(def negative-ttl-ms 30000)
(def stale-ceiling-ms 3600000)
(def ttl-ms {"claude" 300000 "codex" 60000})
(def endpoints {"claude" "https://api.anthropic.com/api/oauth/usage"
                "codex" "https://chatgpt.com/backend-api/wham/usage"})

(defn- percent? [x]
  (and (number? x) (Double/isFinite (double x)) (<= 0 x 100)))
(defn- identifier? [s]
  (and (string? s) (boolean (re-matches #"[a-z0-9_:.+\-]{1,128}" s))))
(defn- model-id [s]
  (when (string? s)
    (let [s (str/lower-case (str/trim s))]
      (when (identifier? s) (str "model:" s)))))
(defn- iso-time [s]
  (when (string? s)
    (try (str (.toInstant (OffsetDateTime/parse s))) (catch Exception _ nil))))
(defn- epoch-time [n]
  (when (and (number? n) (Double/isFinite (double n)) (pos? n))
    (try (str (Instant/ofEpochSecond (long n))) (catch Exception _ nil))))
(defn- window [id kind scope used seconds reset]
  (when (and (identifier? id) (identifier? scope) (percent? used))
    (cond-> {:id id :kind kind :scope scope :percentUsed used :percentRemaining (- 100 used)}
      (pos-int? seconds) (assoc :windowSeconds seconds)
      reset (assoc :resetsAt reset))))

(defn- anthropic-window [id kind scope seconds bucket]
  (when (map? bucket)
    (window id kind scope (if (contains? bucket :percent) (:percent bucket) (:utilization bucket))
            seconds (iso-time (:resets_at bucket)))))
(defn- anthropic-limit [index entry]
  (when (map? entry)
    (let [kind (:kind entry) group (:group entry)
          model (or (get-in entry [:scope :model :id]) (get-in entry [:scope :model :display_name]))
          scope (model-id model)]
      (cond
        (and scope (or (= kind "weekly_scoped") (= group "weekly")))
        (anthropic-window (str "limit:" index) "model" scope 604800 entry)
        (and (nil? (:scope entry)) (or (= kind "session") (= group "session")))
        (anthropic-window (str "limit:" index) "session" "all_models" 18000 entry)
        (and (nil? (:scope entry)) (or (= kind "weekly_all") (= group "weekly")))
        (anthropic-window (str "limit:" index) "weekly" "all_models" 604800 entry)))))
(defn normalise-anthropic
  "Return safe windows and resolution state. A present limits array is authoritative;
  an unrecognised, malformed or unresolved entry prevents numeric credit."
  [data]
  (if (contains? data :limits)
    (let [entries (:limits data)
          windows (when (vector? entries) (mapv anthropic-limit (range) entries))]
      {:windows (vec (keep identity windows))
       :resolved? (and (vector? entries) (seq entries) (every? some? windows))})
    (let [entries (for [[k v] data
                        :let [id (name k)]
                        :when (and (some? v) (or (= id "five_hour") (= id "seven_day")
                                                 (str/starts-with? id "seven_day_")))]
                    (let [model? (str/starts-with? id "seven_day_")
                          scope (if model? (model-id (subs id 10)) "all_models")]
                      (anthropic-window id (if model? "model" (if (= id "five_hour") "session" "weekly"))
                                        scope (if (= id "five_hour") 18000 604800) v)))]
      {:windows (vec (keep identity entries)) :resolved? (and (seq entries) (every? some? entries))})))

(defn- codex-pair [prefix scope rate]
  (when (map? rate)
    (for [key [:primary_window :secondary_window] :let [entry (get rate key)] :when (some? entry)]
      (let [seconds (:limit_window_seconds entry)
            kind (if (not= scope "all_models") "model"
                     (case seconds 18000 "session" 604800 "weekly" 2592000 "monthly" "unknown"))]
        (when (map? entry)
          (window (str prefix (name key)) kind scope (:used_percent entry) seconds (epoch-time (:reset_at entry))))))))
(defn normalise-codex
  "Return safe subscription windows. Base duration does not determine scope.
  Additional limits need an explicit normal_model_slug; opaque feature labels
  remain unresolved rather than being guessed into a model identity."
  [data]
  (let [extras (:additional_rate_limits data)
        base (codex-pair "" "all_models" (:rate_limit data))
        extra-windows (when (vector? extras)
                        (mapcat (fn [index entry]
                                  (if-let [scope (model-id (:normal_model_slug entry))]
                                    (or (seq (codex-pair (str "extra:" index ":") scope (:rate_limit entry))) [nil])
                                    [nil])) (range) extras))
        windows (concat base extra-windows)]
    {:windows (vec (keep identity windows))
     :resolved? (and (or (nil? extras) (vector? extras)) (seq base) (seq windows) (every? some? windows))}))

(defn- semantics [{:keys [windows resolved?]}]
  (let [account (filter #(= "all_models" (:scope %)) windows)
        scopes (cons "all_models" (sort (disj (set (map :scope windows)) "all_models")))
        known? (and resolved? (seq account))]
    {:status (if known? "known" "unknown")
     :effectiveAvailability
     (mapv (fn [scope]
             (let [bounds (filter #(or (= "all_models" (:scope %)) (= scope (:scope %))) windows)]
               (cond-> {:scope scope :status (if known? "known" "unknown") :boundedBy (mapv :id bounds)}
                 known? (assoc :effectivePercentRemaining (apply min (map :percentRemaining bounds)))))) scopes)}))
(defn- unknown [reason]
  {:state {:status (cond
                    (#{"missing-credentials" "expired-credentials" "invalid-credentials" "not-subscription-auth" "auth"} reason) "auth_required"
                    (= reason "rate-limited") "rate_limited"
                    :else "unavailable") :stale false}
   :reason reason :windows [] :quotaSemantics {:status "unknown" :effectiveAvailability []}})

(defn- diagnostic-windows [evidence windows]
  (-> evidence (assoc :windows windows) (assoc-in [:state :stale] (boolean (seq windows)))))

(def ^:private authentication-failures
  #{"auth" "scope" "credentials-changed" "missing-credentials" "expired-credentials"
    "invalid-credentials" "unreadable-credentials" "not-subscription-auth"
    "unsupported-auth-context" "os-store-unavailable"})

(defn- read-body [input]
  (with-open [input input]
    (let [out (ByteArrayOutputStream.) buf (byte-array 8192)]
      (loop [total 0]
        (let [n (.read input buf 0 (int (min 8192 (inc (- body-limit total)))))]
          (cond
            (= -1 n) (.toString out "UTF-8")
            (> (+ total n) body-limit) (throw (ex-info "response too large" {:reason "oversized-response"}))
            :else (do (.write out buf 0 n) (recur (+ total n)))))))))
(defn default-http-fn
  "GET with a whole-exchange timeout, a byte-bounded body and no redirects.
  Return status, headers and body; throw on timeout or excess body size."
  [{:keys [url headers timeout-ms]}]
  (let [stream (atom nil)
        cancelled? (atom false)
        work (future
               (let [response (http/get url {:headers headers :timeout timeout-ms :throw false :as :stream
                                            :client (http/client {:follow-redirects :never :connect-timeout timeout-ms})})
                     body (:body response)]
                 (reset! stream body)
                 (when @cancelled?
                   (.close body)
                   (throw (java.net.http.HttpTimeoutException. "quota exchange timeout")))
                 (assoc (select-keys response [:status :headers]) :body (read-body body))))
        out (try (deref work timeout-ms ::timeout)
                 (catch java.util.concurrent.ExecutionException e (throw (or (.getCause e) e))))]
    (if (= out ::timeout)
      (do (reset! cancelled? true)
          (future-cancel work)
          (when-let [body @stream] (try (.close body) (catch Exception _ nil)))
          (throw (java.net.http.HttpTimeoutException. "quota exchange timeout")))
      out)))
(defn- retry-after-ms [headers now]
  (when-let [value (some (fn [[k v]] (when (= "retry-after" (str/lower-case (name k))) (str v))) headers)]
    (or (try (let [n (Double/parseDouble value)]
               (when (and (Double/isFinite n) (<= 0 n 31536000)) (long (Math/ceil (* 1000 n)))))
             (catch Exception _ nil))
        (try (max 0 (- (.toEpochMilli (.toInstant (ZonedDateTime/parse value DateTimeFormatter/RFC_1123_DATE_TIME))) now))
             (catch Exception _ nil)))))
(defn- failure-reason [e]
  (loop [e e n 0]
    (cond
      (or (nil? e) (> n 6)) "network"
      (= "oversized-response" (:reason (ex-data e))) "oversized-response"
      (instance? java.net.http.HttpTimeoutException e) "timeout"
      :else (recur (.getCause e) (inc n)))))
(defn fetch
  "Fetch one provider using private credential inputs. Return only safe evidence
  and an optional backoff duration; never retain raw bodies or exception data."
  [provider credential http-fn now]
  (if-not (endpoints provider)
    (unknown "unsupported-provider")
    (try
      (let [headers (cond-> {"Authorization" (str "Bearer " (:access credential)) "Accept" "application/json"}
                      (= provider "claude") (assoc "anthropic-beta" "oauth-2025-04-20" "User-Agent" "claude-code/2.1.317")
                      (= provider "codex") (assoc "User-Agent" "pi-agent" "ChatGPT-Account-Id" (:account-id credential)))
            {:keys [status body headers]} (http-fn {:url (endpoints provider) :headers headers :timeout-ms request-timeout-ms})]
        (case status
          200 (if (and (string? body) (> (alength (.getBytes body "UTF-8")) body-limit))
                (unknown "oversized-response")
                (let [data (if (string? body) (try (json/parse-string body true) (catch Exception _ nil)) body)
                      result (when (map? data) ((if (= provider "claude") normalise-anthropic normalise-codex) data))]
                  (if-not (seq (:windows result)) (unknown "invalid-response")
                    {:state {:status "fresh" :stale false} :windows (:windows result) :quotaSemantics (semantics result)})))
          401 (unknown "auth")
          403 (unknown "scope")
          429 (assoc (unknown "rate-limited") :retry-ms (retry-after-ms headers now))
          (unknown "http-error")))
      (catch Exception e (unknown (failure-reason e))))))

(defn- default-cache-dir []
  (fs/path (or (not-empty (System/getenv "XDG_CACHE_HOME"))
               (fs/path (or (not-empty (System/getenv "HOME")) (System/getProperty "user.home")) ".cache"))
           "herdr-orch/quota-v1"))
(def ^:private failure-reasons
  #{"unsupported-provider" "missing-credentials" "expired-credentials" "invalid-credentials"
    "unreadable-credentials" "not-subscription-auth" "unsupported-auth-context" "os-store-unavailable"
    "auth" "scope" "rate-limited" "http-error" "invalid-response" "oversized-response"
    "timeout" "network" "credentials-changed"})
(defn- cached-evidence? [evidence]
  (let [windows (:windows evidence)
        valid-windows? (and (vector? windows) (= (count windows) (count (distinct (map :id windows))))
                            (every? (fn [w]
                                      (and (#{"session" "weekly" "monthly" "model" "unknown"} (:kind w))
                                           (= w (window (:id w) (:kind w) (:scope w) (:percentUsed w)
                                                        (:windowSeconds w) (iso-time (:resetsAt w)))))) windows))]
    (and valid-windows?
         (if-let [reason (:reason evidence)]
           (and (failure-reasons reason) (= evidence (diagnostic-windows (unknown reason) windows)))
           (and (seq windows)
                (= evidence {:state {:status "fresh" :stale false} :windows windows
                             :quotaSemantics (semantics {:windows windows
                                                         :resolved? (= "known" (get-in evidence [:quotaSemantics :status]))})}))))))
(defn- read-state [path]
  (try (when (and (fs/regular-file? path) (<= (fs/size path) body-limit))
         (let [state (json/parse-string (slurp (str path)) true)]
           (when (and (map? state) (= 1 (:version state)) (string? (:revision state))
                      (every? #(and (number? %) (Double/isFinite (double %)))
                              [(:fetched-at state) (:next-fetch-at state)])
                      (cached-evidence? (:evidence state))
                      (or (nil? (:snapshot state))
                          (and (number? (:snapshot-at state)) (cached-evidence? (:snapshot state))))) state)))
       (catch Exception _ nil)))
(defn- write-state! [path state]
  (let [tmp (str path "." (random-uuid) ".tmp")]
    (try
      (spit tmp (json/generate-string state))
      (Files/move (fs/path tmp) (fs/path path)
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (catch Exception _ nil)
      (finally (try (fs/delete-if-exists tmp) (catch Exception _ nil))))))
(defn- with-cache-lock [path f]
  (let [channel (try (fs/create-dirs (fs/parent path))
                     (FileChannel/open (Paths/get (str path) (make-array String 0))
                                       (into-array java.nio.file.OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
                     (catch Exception _ nil))]
    (if-not channel (f)
      (try
        (let [deadline (+ (System/nanoTime) (* (+ request-timeout-ms 1000) 1000000))
              lock (loop []
                     (if-let [lock (try (.tryLock channel) (catch Exception _ nil))] lock
                       (when (< (System/nanoTime) deadline) (Thread/sleep 10) (recur))))]
          (if lock (f) (unknown "in-flight")))
        (finally (.close channel))))))
(defn- cached-answer [state now fresh initial]
  (when state
    (let [evidence (:evidence state)
          failure? (some? (:reason evidence))
          coalesced? (not= (:revision initial) (:revision state))]
      (cond
        (and failure? (<= (:fetched-at state) now) (< now (:next-fetch-at state)))
        (if (>= (- now (:snapshot-at state now)) stale-ceiling-ms) (diagnostic-windows evidence []) evidence)
        (and (not failure?) (<= (:fetched-at state) now) (< now (:next-fetch-at state))
             (or (not fresh) coalesced?)) evidence))))
(defn- collect-provider [provider harness account {:keys [credential-context cache-dir http-fn fresh]}]
  (let [context (or credential-context (credentials/environment))
        now (:now-ms context)
        credential (credentials/credentials harness provider context account)]
    (if-not (= "ready" (:status credential)) (unknown (:reason credential))
      (let [path (fs/path (or cache-dir (default-cache-dir)) (str (:context-key credential) ".json"))
            initial (read-state path)]
        (with-cache-lock (str path ".lock")
          (fn []
            (let [current-context (or credential-context (credentials/environment))
                  current (credentials/credentials harness provider current-context account)
                  now (:now-ms current-context)
                  state (read-state path)]
              (if (not= (:context-key credential) (:context-key current))
                (unknown (or (:reason current) "credentials-changed"))
                (or (cached-answer state now fresh initial)
                  (let [out (fetch provider credential (or http-fn default-http-fn) now)
                        latest (credentials/credentials harness provider (or credential-context (credentials/environment)) account)
                        out (if (= (:context-key credential) (:context-key latest)) out
                                (unknown (or (:reason latest) "credentials-changed")))
                        success? (= "fresh" (get-in out [:state :status]))
                        snapshot (if success? out (:snapshot state))
                        ;; Stale windows are diagnostic only; no effective credit survives.
                        out (if (and (not success?) (not (authentication-failures (:reason out)))
                                     snapshot (< (- now (:snapshot-at state now)) stale-ceiling-ms))
                              (diagnostic-windows out (:windows snapshot)) out)
                        delay (if success? (get ttl-ms provider 60000) (max negative-ttl-ms (or (:retry-ms out) 0)))
                        evidence (dissoc out :retry-ms)
                        state (cond-> {:version 1 :revision (str (random-uuid)) :fetched-at now
                                       :next-fetch-at (+ now delay) :evidence evidence}
                                (and snapshot (not (authentication-failures (:reason out))))
                                (assoc :snapshot snapshot :snapshot-at (if success? now (:snapshot-at state now))))]
                    (write-state! path state)
                    evidence))))))))))

(defn usage-for
  "Collect owned evidence for configured local providers. Options: :providers
  (nil means all; Pi anthropic expands to its validated named accounts),
  :harness (:pi by default, or explicit :claude/:codex), :fresh
  bypasses TTL but not in-flight coalescing or failure backoff. Tests can supply
  :credential-context, :http-fn and :cache-dir. Never refresh or install software."
  ([config] (usage-for config {}))
  ([config {:keys [providers harness] :or {harness :pi} :as opts}]
   (let [accounts (delay (credentials/pi-accounts
                          (or (:credential-context opts) (credentials/environment))))
         ;; known-limit: sequential account reads can exceed a UI command budget;
         ;; revisit scheduling if configured accounts hit that limit.
         identities (distinct
                     (mapcat (fn [local]
                               (if (and (= harness :pi) (= local "anthropic")
                                        (= "claude" (get-in config [:providers local :quota-provider])))
                                 (cons local @accounts)
                                 [local]))
                             (or providers (sort (keys (:providers config))))))]
     {:providers
      (mapv (fn [local]
              (let [account (when (credentials/pi-account? local) local)
                    base (if account "anthropic" local)]
                (if (and account (or (not= harness :pi)
                                     (not= "claude" (get-in config [:providers base :quota-provider]))))
                  (assoc (unknown "unsupported-auth-context")
                         :localProvider local :provider "claude" :baseProvider base :accountId local)
                  (if-let [row (get-in config [:providers base])]
                    (if-let [provider (:quota-provider row)]
                      (assoc (collect-provider provider harness account opts)
                             :localProvider local :provider provider
                             :baseProvider base :accountId local)
                      {:localProvider local :local true :state {:status "fresh" :stale false} :windows []})
                    (assoc (unknown "not-configured") :localProvider local)))))
            identities)})))
