(ns herdr-orch.usage-test
  "Offline tests: credential fixtures under a temp home and a fake HTTP function. No test
  contacts a provider or reads a real credential store."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.usage :as usage])
  (:import [java.util Base64]))

(def tok-a "sk-ant-oat01-FIXTURESECRET-aaaa")
(def tok-c "eyJFIXTURECODEXSECRET")
(def now 1800000000)

(defn- jwt [exp]
  (let [enc #(.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.getBytes ^String % "UTF-8"))]
    (str (enc "{\"alg\":\"none\"}") "." (enc (json/generate-string {:exp exp, :marker "FIXTUREJWTSECRET"})) ".sig")))

(defn- tmp [] (str (fs/create-temp-dir {:prefix "usage-test"})))
(defn- write! [home rel data]
  (let [p (fs/path home rel)] (fs/create-dirs (fs/parent p)) (spit (str p) (json/generate-string data))))

(defn- home-with
  ([] (home-with {}))
  ([{:keys [pi-exp claude-exp codex-token] :or {pi-exp (* (+ now 3600) 1000)}}]
   (let [h (tmp)]
     (write! h ".pi/agent/auth.json"
             {:anthropic {:access tok-a :expires pi-exp :refresh "REFRESHSECRET" :type "oauth"}
              :openai-codex {:access tok-c :accountId "acct-1" :expires pi-exp :refresh "REFRESHSECRET"}})
     (when claude-exp
       (write! h ".claude/.credentials.json" {:claudeAiOauth {:accessToken tok-a :expiresAt claude-exp}}))
     (when codex-token
       (write! h ".codex/auth.json" {:tokens {:access_token codex-token :account_id "acct-2"}}))
     h)))

(def config
  {:providers {"anthropic" {:usage :anthropic-oauth :credentials [:pi :claude] :cache-ttl-s 300 :reserve 0.1}
               "openai-codex" {:usage :codex-oauth :credentials [:pi :codex] :cache-ttl-s 60}
               "local" {}}})

(def anthropic-flat
  {:five_hour {:utilization 42.0 :resets_at "2030-01-01T00:00:00.000Z"}
   :seven_day {:utilization 17.0 :resets_at "2030-01-07T00:00:00+00:00"}
   :seven_day_opus nil
   :seven_day_sonnet {:utilization 90.0 :resets_at "2030-01-07T00:00:00.000000+00:00"}
   :extra_usage {:is_enabled true :utilization 12.5}})
(def anthropic-limits
  {:five_hour nil :seven_day nil
   :limits [{:kind "session" :percent 42 :resets_at "2030-01-01T00:00:00Z" :scope nil}
            {:kind "weekly_all" :percent 17 :resets_at "2030-01-07T00:00:00Z" :scope nil}
            {:kind "weekly_scoped" :percent 90 :resets_at "2030-01-07T00:00:00Z"
             :scope {:model {:display_name "Sonnet"}}}]})
(def codex-body
  {:plan_type "plus"
   :rate_limit {:primary_window {:used_percent 42 :limit_window_seconds 18000 :reset_at 1900000000}
                :secondary_window {:used_percent 84 :limit_window_seconds 604800 :reset_at 1900500000}}
   :credits {:balance "0"}})

(defn- fake
  "HTTP fake: RESPONSES is a fn of the call count -> response map (or throws). Returns
  [http-fn calls-atom]."
  [f]
  (let [calls (atom [])]
    [(fn [req] (swap! calls conj req) (f (count @calls))) calls]))
(defn- ok [body] {:status 200 :headers {} :body (json/generate-string body)})

(defn- run
  ([http opts-extra] (run http opts-extra ["anthropic"]))
  ([http opts-extra names]
   (usage/usage-for config (merge {:providers names :http-fn http :now now} opts-extra))))

(defn- approx= [a b] (< (Math/abs (- (double a) (double b))) 1e-9))
(defn- opts [& kvs] (apply hash-map kvs))

(def ^:dynamic *pause-after-initial-read* false)

(defn- raced-fetch-calls
  "Run caller B after its initial state read pauses, while caller A completes."
  [response]
  (let [calls (atom 0)
        h (home-with)
        dir (tmp)
        opts {:providers ["anthropic"] :home h :cache-dir dir :now now
              :http-fn (fn [_] (swap! calls inc) response)}
        original @#'usage/read-state
        reads (atom 0)
        paused (promise)
        resume (promise)
        run #(usage/usage-for config opts)]
    (with-redefs [usage/read-state (fn [cache-dir provider]
                                     (let [state (original cache-dir provider)]
                                       (when (and *pause-after-initial-read*
                                                  (= 1 (swap! reads inc)))
                                         (deliver paused true)
                                         (is (= true (deref resume 5000 :timeout))
                                             "test control: caller B resumed"))
                                       state))]
      (let [b (future (binding [*pause-after-initial-read* true] (run)))]
        (is (= true (deref paused 5000 :timeout)) "test control: caller B paused")
        (run)
        (deliver resume true)
        (is (map? (deref b 5000 :timeout)) "test control: caller B completed")))
    @calls))

;; ---------------------------------------------------------------------------

(deftest read-credential-stores
  (testing "pi: ms expiry, account id"
    (let [h (home-with)]
      (is (= {:token tok-a :expires-at (+ now 3600) :source :pi :account-id nil}
             (usage/read-credential :pi "anthropic" {:home h :now now})))
      (is (= "acct-1" (:account-id (usage/read-credential :pi "openai-codex" {:home h :now now}))))))
  (testing "claude: seconds and milliseconds both accepted"
    (let [h1 (home-with {:claude-exp (+ now 100)}) h2 (home-with {:claude-exp (* (+ now 100) 1000)})]
      (is (= (+ now 100) (:expires-at (usage/read-credential :claude "anthropic" {:home h1 :now now}))))
      (is (= (+ now 100) (:expires-at (usage/read-credential :claude "anthropic" {:home h2 :now now}))))))
  (testing "codex: JWT exp and account id"
    (let [h (home-with {:codex-token (jwt (+ now 50))})
          c (usage/read-credential :codex "openai-codex" {:home h :now now})]
      (is (= (+ now 50) (:expires-at c)))
      (is (= "acct-2" (:account-id c)))))
  (testing "expired and missing"
    (let [h (home-with {:pi-exp (* (- now 1) 1000) :codex-token (jwt (- now 1))})]
      (is (= {:reason :expired} (usage/read-credential :pi "anthropic" {:home h :now now})))
      (is (= {:reason :expired} (usage/read-credential :codex "openai-codex" {:home h :now now})))
      (is (= {:reason :missing} (usage/read-credential :claude "anthropic" {:home h :now now})))
      (is (= {:reason :missing} (usage/read-credential :pi "anthropic" {:home (tmp) :now now})))
      (is (= {:reason :missing} (usage/read-credential :claude "openai-codex" {:home h :now now})))))
  (testing "never writes the store"
    (let [h (home-with) f (fs/path h ".pi/agent/auth.json") before (slurp (str f))
          mt (fs/last-modified-time f)]
      (usage/read-credential :pi "anthropic" {:home h :now (+ now 10000000)})
      (is (= before (slurp (str f))))
      (is (= mt (fs/last-modified-time f))))))

(deftest normalisation
  (testing "both Anthropic shapes give the same windows"
    (let [a (usage/normalise-anthropic anthropic-flat) b (usage/normalise-anthropic anthropic-limits)]
      (is (= (set a) (set b)))
      (is (= #{"five_hour" "seven_day" "seven_day_sonnet"} (set (map :name a))))
      (is (= {"sonnet" 1} (frequencies (keep :scope a))))
      (is (= 1893456000 (:resets-at (first (filter #(= "five_hour" (:name %)) a)))))))
  (testing "null bucket and 0% with null reset are skipped"
    (is (= ["seven_day"]
           (map :name (usage/normalise-anthropic
                       {:five_hour {:utilization 0 :resets_at nil} :seven_day {:utilization 3 :resets_at "2030-01-07T00:00:00Z"}
                        :seven_day_opus nil})))))
  (testing "Codex null window skipped; single window routes"
    (is (= ["secondary"] (map :name (usage/normalise-codex
                                     {:rate_limit {:primary_window nil
                                                   :secondary_window {:used_percent 10 :reset_at 5}}}))))
    (is (= 0.9 (:remaining-fraction
                (usage/summarise (usage/normalise-codex {:rate_limit {:primary_window nil
                                                                        :secondary_window {:used_percent 10 :reset_at 5}}}))))))
  (testing "remaining is account-wide only; scoped stays in windows"
    (let [s (usage/summarise (usage/normalise-anthropic anthropic-flat))]
      (is (approx= 0.58 (:remaining-fraction s)))
      (is (= 3 (count (:windows s))))))
  (testing "no account-wide window gives nil, never full credit"
    (is (nil? (usage/summarise [])))
    (is (nil? (usage/summarise [{:name "seven_day_sonnet" :scope "sonnet" :used-fraction 0.5}])))))

(deftest anthropic-adapter-request-and-ok
  (let [[http calls] (fake (fn [_] (ok anthropic-flat)))
        dir (tmp) h (home-with)
        r (get (run http (opts :home h :cache-dir dir)) "anthropic")]
    (is (= :ok (:status r)))
    (is (= :pi (:source r)))
    (is (= 0.1 (:reserve r)))
    (is (< (Math/abs (- 0.58 (:remaining-fraction r))) 1e-9))
    (is (= 3 (count (:windows r))))
    (let [req (first @calls)]
      (is (= "https://api.anthropic.com/api/oauth/usage" (:url req)))
      (is (= 5000 (:timeout-ms req)))
      (is (= (str "Bearer " tok-a) (get-in req [:headers "Authorization"])))
      (is (= "oauth-2025-04-20" (get-in req [:headers "anthropic-beta"])))
      (is (str/starts-with? (get-in req [:headers "User-Agent"]) "claude-code/")))))

(deftest captured-live-responses-normalise-offline
  (let [fixtures (into {}
                       (for [[provider fixture] [["anthropic" "anthropic"]
                                                 ["openai-codex" "codex"]]]
                         [provider (json/parse-string
                                    (slurp (str (fs/path "design/log/2026-11-04-subscription-routing"
                                                        (str fixture "-live-redacted.json"))))
                                    true)]))
        calls (atom [])
        http (fn [{:keys [url] :as request}]
               (swap! calls conj request)
               (let [provider (case url
                                "https://api.anthropic.com/api/oauth/usage" "anthropic"
                                "https://chatgpt.com/backend-api/wham/usage" "openai-codex"
                                (throw (ex-info "unexpected fixture request" {:url url})))
                     response (get fixtures provider)]
                 (is (= 200 (:status response)) (str provider " captured status"))
                 (update response :body json/generate-string)))
        results (run http (opts :home (home-with) :cache-dir (tmp)) ["anthropic" "openai-codex"])
        anthropic (get results "anthropic")
        codex (get results "openai-codex")]
    (is (= #{"https://api.anthropic.com/api/oauth/usage"
             "https://chatgpt.com/backend-api/wham/usage"}
           (set (map :url @calls))))
    (is (= 2 (count @calls)) "fake HTTP handled only the two captured responses")
    (is (= :ok (:status anthropic)))
    (is (= #{{:name "five_hour" :scope nil :used-fraction 0.63 :resets-at 1793813400}
             {:name "seven_day" :scope nil :used-fraction 0.4 :resets-at 1794319200}}
           (set (:windows anthropic))))
    (is (= 0.37 (:remaining-fraction anthropic)))
    (is (= :ok (:status codex)))
    (is (= [{:name "primary" :scope nil :used-fraction 0.02 :resets-at 1794392109}]
           (:windows codex)))
    (is (= 0.98 (:remaining-fraction codex)))))

(deftest codex-adapter-request
  (let [[http calls] (fake (fn [_] (ok codex-body)))
        r (get (run http (opts :home (home-with) :cache-dir (tmp)) ["openai-codex"]) "openai-codex")
        req (first @calls)]
    (is (= :ok (:status r)))
    (is (approx= 0.16 (:remaining-fraction r)) "1 - max(0.42 0.84)")
    (is (= "https://chatgpt.com/backend-api/wham/usage" (:url req)))
    (is (= "acct-1" (get-in req [:headers "ChatGPT-Account-Id"])))
    (is (= "application/json" (get-in req [:headers "Accept"])))
    (is (= 0 (:reserve r)))))

(deftest local-provider-needs-no-fetch
  (let [[http calls] (fake (fn [_] (throw (ex-info "no" {}))))
        r (get (run http (opts :home (tmp) :cache-dir (tmp)) ["local"]) "local")]
    (is (= :ok (:status r)))
    (is (= 1.0 (:remaining-fraction r)))
    (is (empty? @calls))))

(deftest unconfigured-name-is-unknown
  (is (= "not-configured" (:reason (get (run (constantly nil) (opts :cache-dir (tmp)) ["nope"]) "nope")))))

(deftest credential-failures-are-unknown-without-fetch
  (let [[http calls] (fake (fn [_] (ok anthropic-flat)))]
    (testing "expired"
      (let [h (home-with {:pi-exp (* (- now 5) 1000)})
            r (get (run http (opts :home h :cache-dir (tmp))) "anthropic")]
        (is (= [:unknown "expired"] [(:status r) (:reason r)]))
        (is (empty? @calls))))
    (testing "missing"
      (let [r (get (run http (opts :home (tmp) :cache-dir (tmp))) "anthropic")]
        (is (= [:unknown "missing"] [(:status r) (:reason r)]))))
    (testing "a later valid store is used when the first is expired"
      (let [h (home-with {:pi-exp (* (- now 5) 1000) :claude-exp (* (+ now 100) 1000)})
            r (get (run http (opts :home h :cache-dir (tmp))) "anthropic")]
        (is (= [:ok :claude] [(:status r) (:source r)]))))))

(deftest http-error-classification
  (doseq [[status reason] [[401 "auth"] [403 "scope"] [404 "http-404"]]]
    (let [[http _] (fake (fn [_] {:status status :headers {} :body "{}"}))
          r (get (run http (opts :home (home-with) :cache-dir (tmp))) "anthropic")]
      (is (= [:unknown reason] [(:status r) (:reason r)]))))
  (testing "timeout"
    (let [[http _] (fake (fn [_] (throw (java.net.http.HttpTimeoutException. "request timed out"))))
          r (get (run http (opts :home (home-with) :cache-dir (tmp))) "anthropic")]
      (is (= [:unknown "timeout"] [(:status r) (:reason r)]))))
  (testing "other exceptions are `network`"
    (let [[http _] (fake (fn [_] (throw (java.net.ConnectException. "refused"))))
          r (get (run http (opts :home (home-with) :cache-dir (tmp))) "anthropic")]
      (is (= "network" (:reason r)))))
  (testing "unknown shapes degrade to unknown, not full credit"
    (doseq [body [{} {:something "else"} {:five_hour {:utilization "x"}} [] nil]]
      (let [[http _] (fake (fn [_] {:status 200 :headers {} :body (json/generate-string body)}))
            r (get (run http (opts :home (home-with) :cache-dir (tmp))) "anthropic")]
        (is (= [:unknown "shape"] [(:status r) (:reason r)]) (pr-str body))
        (is (nil? (:remaining-fraction r)))))
    (let [[http _] (fake (fn [_] {:status 200 :headers {} :body "<html>"}))]
      (is (= "shape" (:reason (get (run http (opts :home (home-with) :cache-dir (tmp))) "anthropic")))))))

(deftest ttl-and-fresh
  (let [[http calls] (fake (fn [_] (ok anthropic-flat)))
        h (home-with) dir (tmp) o #(opts :home h :cache-dir dir :now %)]
    (run http (o now))
    (testing "inside TTL: cached"
      (run http (o (+ now 299)))
      (is (= 1 (count @calls))))
    (testing "after TTL: refetch"
      (run http (o (+ now 301)))
      (is (= 2 (count @calls))))
    (testing ":fresh bypasses"
      (run http (assoc (o (+ now 302)) :fresh true))
      (is (= 3 (count @calls))))
    (testing "cache is keyed by provider and carries the provider TTL"
      (is (fs/exists? (fs/path dir "anthropic.json")))
      (is (not (fs/exists? (fs/path dir "openai-codex.json"))))
      (run http (assoc (o (+ now 302)) :providers ["openai-codex"]))
      (is (fs/exists? (fs/path dir "openai-codex.json")))
      (is (= 4 (count @calls)))
      (run http (assoc (o (+ now 302 59)) :providers ["openai-codex"]))
      (is (= 4 (count @calls)))
      (run http (assoc (o (+ now 302 61)) :providers ["openai-codex"]))
      (is (= 5 (count @calls))))
    (testing "claim marker is released after a fetch"
      (is (not (fs/exists? (fs/path dir "anthropic.claim")))))
    (testing "no temp files left by atomic writes"
      (is (empty? (filter #(str/ends-with? (str %) ".tmp") (fs/list-dir dir)))))))

(deftest backoff-and-stale
  (let [mode (atom :ok) h (home-with {:pi-exp (* (+ now 100000) 1000)}) dir (tmp)
        [http calls] (fake (fn [_] (case @mode
                                     :ok (ok anthropic-flat)
                                     :429 {:status 429 :headers {"retry-after" "900"} :body "{}"}
                                     :503 {:status 503 :headers {} :body "x"})))
        o #(opts :home h :cache-dir dir :now %)]
    (run http (o now))
    (reset! mode :429)
    (let [r (get (run http (o (+ now 301))) "anthropic")]
      (is (= 2 (count @calls)))
      (is (= :stale (:status r)) "last good payload served stale")
      (is (= "http-429" (:reason r)))
      (is (some? (:remaining-fraction r))))
    (testing "backoff honours Retry-After above ttl: no call at +301+899"
      (run http (o (+ now 301 899)))
      (is (= 2 (count @calls))))
    (testing "backoff expiry allows a retry"
      (run http (o (+ now 301 901)))
      (is (= 3 (count @calls))))
    (testing "ttl floors a small Retry-After"
      (reset! mode :503)
      (run http (opts :home h :cache-dir dir :now (+ now 2000) :fresh true))
      (is (= 4 (count @calls)))
      (run http (o (+ now 2000 299)))
      (is (= 4 (count @calls)))
      (is (= :stale (:status (get (run http (o (+ now 2000 299))) "anthropic")))))
    (testing "stale ceiling: snapshot older than 60 min is not served"
      (let [r (get (run http (opts :home h :cache-dir dir :now (+ now 3601) :fresh true)) "anthropic")]
        (is (= [:unknown "stale-expired"] [(:status r) (:reason r)]))
        (is (nil? (:remaining-fraction r)))))
    (testing "just under the ceiling is still stale"
      (let [r (get (run http (opts :home h :cache-dir dir :now (+ now 3599) :fresh true)) "anthropic")]
        (is (= :stale (:status r)))))))

(deftest negative-cache-without-snapshot
  (let [[http calls] (fake (fn [_] {:status 429 :headers {} :body "{}"}))
        h (home-with) dir (tmp)
        o #(opts :home h :cache-dir dir :now %)]
    (is (= [:unknown "http-429"] ((juxt :status :reason) (get (run http (o now)) "anthropic"))))
    (run http (o (+ now 100)))
    (is (= 1 (count @calls)) "negative cache suppresses refetch inside ttl")
    (run http (o (+ now 301)))
    (is (= 2 (count @calls)))))

(deftest negative-cache-race-after-claim-re-read
  (testing "success response race remains suppressed"
    (is (= 1 (raced-fetch-calls (ok anthropic-flat)))))
  (testing "sequential negative-cache reads remain suppressed"
    (let [[http calls] (fake (fn [_] {:status 429 :headers {"retry-after" "900"} :body "{}"}))
          h (home-with) dir (tmp)
          options {:providers ["anthropic"] :home h :cache-dir dir :now now :http-fn http}]
      (usage/usage-for config options)
      (usage/usage-for config options)
      (is (= 1 (count @calls)))))
  (testing "a negative response completed during caller B's pause suppresses B's fetch"
    (is (= 1 (raced-fetch-calls {:status 429 :headers {"retry-after" "900"} :body "{}"}))))
  (testing ":fresh bypasses a negative cache"
    (let [[http calls] (fake (fn [_] {:status 429 :headers {"retry-after" "900"} :body "{}"}))
          h (home-with) dir (tmp)
          options {:providers ["anthropic"] :home h :cache-dir dir :now now :http-fn http}]
      (usage/usage-for config options)
      (usage/usage-for config (assoc options :fresh true))
      (is (= 2 (count @calls))))))

(deftest claim-marker
  (testing "exactly one concurrent claimer wins"
    (let [dir (tmp)
          wins (doall (pmap (fn [_] (usage/claim! dir "anthropic" now)) (range 16)))]
      (is (= 1 (count (filter true? wins))))))
  (testing "a second caller inside the claim window reads the cache and does not fetch"
    (let [[http calls] (fake (fn [_] (ok anthropic-flat)))
          h (home-with) dir (tmp)
          o #(opts :home h :cache-dir dir :now %)]
      (run http (o now))                                  ; fills cache, releases claim
      (is (usage/claim! dir "anthropic" (+ now 400)))     ; another caller is mid-fetch
      (let [r (get (run http (o (+ now 410))) "anthropic")] ; ttl expired, claim live
        (is (= 1 (count @calls)))
        (is (= :stale (:status r)))
        (is (= "claimed" (:reason r))))
      (testing "claim not released by the caller that lost it"
        (is (fs/exists? (fs/path dir "anthropic.claim"))))
      (testing "an expired claim is taken over"
        (run http (o (+ now 400 31)))
        (is (= 2 (count @calls)))))))

(deftest no-secrets-anywhere
  (let [secrets [tok-a tok-c "FIXTURESECRET" "FIXTURECODEXSECRET" "FIXTUREJWTSECRET" "REFRESHSECRET"]
        h (home-with {:claude-exp (* (+ now 100) 1000) :codex-token (jwt (+ now 100))})
        dir (tmp)
        leak? (fn [s] (some #(str/includes? s %) secrets))
        ;; http client ex-data that embeds the Authorization header, as real clients do
        boom (fn [req] (throw (ex-info (str "failed " (get-in req [:headers "Authorization"]))
                                       {:request req :reason :boom})))
        outs (atom [])]
    (doseq [http [(fn [_] (ok anthropic-flat)) (fn [_] (ok codex-body)) boom
                  (fn [_] {:status 401 :headers {} :body (str "bad token " tok-a)})
                  (fn [_] {:status 200 :headers {} :body (str "{\"x\":\"" tok-a "\"}")})
                  (fn [_] {:status 429 :headers {} :body tok-c})]
            fresh [true false]]
      (swap! outs conj (pr-str (usage/usage-for config (opts :http-fn http :home h :cache-dir dir
                                                              :now now :fresh fresh)))))
    (is (seq @outs))
    (is (not-any? leak? @outs))
    (let [files (filter fs/regular-file? (fs/list-dir dir))]
      (is (seq files) "positive control: cache files exist")
      (is (not-any? #(leak? (slurp (str %))) files)))
    (testing "positive control: the leak detector fires on a secret"
      (is (leak? (str "x" tok-a))))))

(deftest credential-store-untouched-by-usage-for
  (let [h (home-with) f (str (fs/path h ".pi/agent/auth.json")) before (slurp f)
        [http _] (fake (fn [_] {:status 401 :headers {} :body "{}"}))]
    (run http (opts :home h :cache-dir (tmp) :fresh true))
    (is (= before (slurp f)))
    (is (not (fs/exists? (fs/path h ".claude"))))))

(def codex-extra
  {:limit_name "GPT-5.3-Codex-Spark" :metered_feature "codex_bengalfox"
   :rate_limit {:primary_window {:used_percent 95 :reset_at 1900000100}
                :secondary_window nil}})

(deftest codex-additional-rate-limits
  (testing "documented shape: limit_name, metered_feature, nested rate_limit"
    (let [ws (usage/normalise-codex (assoc codex-body :additional_rate_limits [codex-extra]))
          scoped (filter :scope ws)]
      (is (= 3 (count ws)))
      (is (= [{:name "gpt-5.3-codex-spark-primary" :scope "gpt-5.3-codex-spark"
               :used-fraction 0.95 :resets-at 1900000100}]
             (vec scoped)))
      (testing "unmatched scope stays visible but the account-wide aggregate ignores it"
        (is (approx= 0.16 (:remaining-fraction (usage/summarise ws))))
        (is (= 3 (count (:windows (usage/summarise ws))))))))
  (testing "an optional normal_model_slug is preferred when present"
    (is (= ["gpt-5.3-codex-spark"]
           (map :scope (usage/normalise-codex
                        {:additional_rate_limits [(assoc codex-extra :normal_model_slug "GPT-5.3-Codex-Spark"
                                                         :limit_name "Other")]})))))
  (testing "null nested rate_limit, missing name and non-list are ignored"
    (is (empty? (usage/normalise-codex {:additional_rate_limits [(assoc codex-extra :rate_limit nil)
                                                                 {:rate_limit codex-extra}]})))
    (is (empty? (usage/normalise-codex {:additional_rate_limits "x"})))))

(deftest non-transient-failure-stays-unknown-on-cached-reads
  (doseq [[label resp reason] [["401" {:status 401 :headers {} :body "{}"} "auth"]
                               ["403" {:status 403 :headers {} :body "{}"} "scope"]
                               ["malformed 200" {:status 200 :headers {} :body "{\"x\":1}"} "shape"]
                               ["404" {:status 404 :headers {} :body "{}"} "http-404"]]]
    (testing label
      (let [mode (atom :good) h (home-with {:pi-exp (* (+ now 100000) 1000)}) dir (tmp)
            [http calls] (fake (fn [_] (if (= :good @mode) (ok anthropic-flat) resp)))
            o #(opts :home h :cache-dir dir :now %)
            st (fn [t & {:as extra}] ((juxt :status :reason :remaining-fraction)
                                      (get (run http (merge (o t) extra)) "anthropic")))]
        (is (= :ok (first (st now))) "control: good snapshot")
        (reset! mode :bad)
        (is (= [:unknown reason nil] (st (+ now 10) :fresh true)) "failing fetch")
        (let [n (count @calls)]
          (is (= [:unknown reason nil] (st (+ now 11))) "cached read, same verdict")
          (is (= [:unknown reason nil] (st (+ now 200))))
          (is (= n (count @calls)) "served from negative cache"))
        (reset! mode :good)
        (is (= :ok (first (st (+ now 400)))) "recovers once backoff elapses"))))
  (testing "positive control: transient 429 after a good snapshot is stale, on both reads"
    (let [mode (atom :good) h (home-with {:pi-exp (* (+ now 100000) 1000)}) dir (tmp)
          [http _] (fake (fn [_] (if (= :good @mode) (ok anthropic-flat) {:status 429 :headers {} :body "{}"})))
          o #(opts :home h :cache-dir dir :now %)
          st (fn [t & {:as extra}] ((juxt :status :reason) (get (run http (merge (o t) extra)) "anthropic")))]
      (st now)
      (reset! mode :bad)
      (is (= [:stale "http-429"] (st (+ now 10) :fresh true)))
      (is (= [:stale "http-429"] (st (+ now 11)))))))

(deftest expired-credential-is-unknown-on-every-read
  (let [dir (tmp) good (home-with {:pi-exp (* (+ now 1000) 1000)})
        expired (home-with {:pi-exp (* (+ now 50) 1000)})
        [http calls] (fake (fn [_] (ok anthropic-flat)))
        st (fn [h t & {:as extra}] ((juxt :status :reason :remaining-fraction)
                                    (get (run http (merge (opts :home h :cache-dir dir :now t) extra)) "anthropic")))]
    (is (= :ok (first (st good now))))
    (is (= [:unknown "expired" nil] (st expired (+ now 60) :fresh true)))
    (is (= [:unknown "expired" nil] (st expired (+ now 61))) "cached read agrees with the fresh one")
    (is (= 1 (count @calls)))))

(deftest stale-marker-takeover-has-one-winner
  (let [dir (tmp)]
    (fs/create-dirs dir)
    (dotimes [round 25]
      (let [t (+ now (* round 100))]
        (spit (str (fs/path dir "anthropic.claim")) (str (- t 1))) ; expired marker
        (let [wins (doall (pmap (fn [_] (usage/claim! dir "anthropic" t)) (range 16)))]
          (is (= 1 (count (filter true? wins))) (str "round " round)))))
    (testing "a live marker refuses every contender"
      (is (not-any? true? (doall (pmap (fn [_] (usage/claim! dir "anthropic" (+ now 2400 5))) (range 8))))))))

(deftest release-only-deletes-own-marker
  (let [dir (tmp)]
    (is (usage/claim! dir "anthropic" now))
    (is (usage/claim! dir "anthropic" (+ now 31)) "takeover after expiry")
    (usage/release! dir "anthropic" now)                 ; slow original owner
    (is (fs/exists? (fs/path dir "anthropic.claim")) "new owner's marker survives")
    (usage/release! dir "anthropic" (+ now 31))
    (is (not (fs/exists? (fs/path dir "anthropic.claim"))))))

;; ---------------------------------------------------------------------------
;; Review fixes: claim lock, whole-exchange timeout, malformed shapes

(defn- with-claim-lock [dir f] (#'usage/with-claim-lock dir "anthropic" f))

(deftest claim-lock-runs-callback-exactly-once
  (let [ro (tmp)
        blocker (let [p (fs/path (tmp) "file")] (spit (str p) "x") p)]
    (try
      (fs/set-posix-file-permissions ro "r-xr-xr-x")
      (doseq [[label dir] [["writable" (tmp)] ["read-only" ro] ["uncreatable" (str (fs/path blocker "sub"))]]]
        (testing label
          (let [n (atom 0)]
            (is (= :result (with-claim-lock dir (fn [] (swap! n inc) :result))))
            (is (= 1 @n) "callback ran once")))
        (testing (str label ": callback IOException propagates and is not rerun")
          (let [n (atom 0)]
            (is (thrown? java.io.IOException
                         (with-claim-lock dir (fn [] (swap! n inc) (throw (java.io.IOException. "cb"))))))
            (is (= 1 @n)))))
      (finally (fs/set-posix-file-permissions ro "rwxr-xr-x")))))

(deftest unwritable-cache-degrades-to-uncoordinated-fetch
  (let [ro (tmp)
        blocker (let [p (fs/path (tmp) "file")] (spit (str p) "x") p)]
    (try
      (fs/set-posix-file-permissions ro "r-xr-xr-x")
      (doseq [[label dir] [["writable" (tmp)] ["read-only" ro] ["uncreatable" (str (fs/path blocker "sub"))]]]
        (testing label
          (let [[http calls] (fake (fn [_] (ok anthropic-flat)))
                r (run http (opts :home (home-with) :cache-dir dir) ["anthropic" "openai-codex" "local"])]
            (is (= :ok (:status (get r "anthropic"))))
            (is (= :ok (:status (get r "local"))))
            (is (= 2 (count @calls)) "anthropic and codex fetched once each"))
          (is (true? (usage/claim! dir "p" now)))))
      (testing "writable dir keeps coordination; unwritable marker does not block"
        (let [d (tmp)]
          (is (true? (usage/claim! d "p" now)))
          (is (nil? (usage/claim! d "p" (inc now))))))
      (testing "an unreadable cache entry (directory in its place) is a miss, not an error"
        (let [d (tmp) [http _] (fake (fn [_] (ok anthropic-flat)))]
          (fs/create-dirs (fs/path d "anthropic.json"))
          (is (= :ok (:status (get (run http (opts :home (home-with) :cache-dir d)) "anthropic"))))))
      (finally (fs/set-posix-file-permissions ro "rwxr-xr-x")))))

(defn- serve
  "Localhost server for one connection. MODE :fast, :silent (no response), :stall-body
  (headers, 6 body bytes of 100, then silence). Returns {:ss :port :closed} where :closed
  delivers true once the client side closes the connection."
  [mode]
  (let [ss (java.net.ServerSocket. 0 1 (java.net.InetAddress/getLoopbackAddress))
        closed (promise)]
    (future
      (try
        (let [s (.accept ss) out (.getOutputStream s) in (.getInputStream s)]
          (.read in (byte-array 4096))
          (case mode
            :fast (do (.write out (.getBytes "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}")) (.flush out))
            :silent nil
            :stall-body (do (.write out (.getBytes "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n{\"five"))
                            (.flush out)))
          (.setSoTimeout s 8000)
          (deliver closed (try (neg? (.read in)) (catch Exception _ false)))
          (.close s))
        (catch Exception _ (deliver closed false))))
    {:ss ss :port (.getLocalPort ss) :closed closed}))

(defn- exchange [mode timeout-ms]
  (let [{:keys [ss port closed]} (serve mode)
        t0 (System/currentTimeMillis)
        r (try (usage/default-http-fn {:url (str "http://127.0.0.1:" port "/") :headers {} :timeout-ms timeout-ms})
               (catch Exception e e))
        elapsed (- (System/currentTimeMillis) t0)]
    (try {:r r :elapsed elapsed :closed (deref closed 8000 :no-close)}
         (finally (.close ss)))))

(deftest default-http-fn-bounds-whole-exchange
  (testing "control: a prompt response is returned whole"
    (let [{:keys [r elapsed]} (exchange :fast 2000)]
      (is (= [200 "{}"] ((juxt :status :body) r)))
      (is (< elapsed 1500))))
  (doseq [mode [:silent :stall-body]]
    (testing (name mode)
      (let [{:keys [r elapsed closed]} (exchange mode 800)]
        (is (instance? java.net.http.HttpTimeoutException r) (str (class r)))
        (is (#'usage/timeout? r))
        (is (< elapsed 3000) (str "bounded: " elapsed " ms"))
        (is (true? closed) "client closed the connection (no stranded body reader)")))))

(deftest real-stalled-body-is-unknown-timeout-through-fetch
  (let [{:keys [ss port]} (serve :stall-body)
        orig usage/default-http-fn]
    (try
      (let [out (usage/fetch :anthropic-oauth {:token "t"}
                             (fn [req] (orig (assoc req :url (str "http://127.0.0.1:" port "/") :timeout-ms 800))))]
        (is (= {:reason "timeout" :transient false} out)))
      (finally (.close ss)))))

(deftest timeout-is-unknown-even-with-a-good-snapshot
  (let [mode (atom :ok) h (home-with {:pi-exp (* (+ now 100000) 1000)}) dir (tmp)
        [http calls] (fake (fn [_] (case @mode
                                     :ok (ok anthropic-flat)
                                     :timeout (throw (java.net.http.HttpTimeoutException. "t"))
                                     :network (throw (java.net.ConnectException. "refused")))))
        o #(opts :home h :cache-dir dir :now %)]
    (run http (o now))
    (testing "control: network failure may serve stale"
      (reset! mode :network)
      (is (= [:stale "network"] ((juxt :status :reason) (get (run http (o (+ now 301))) "anthropic")))))
    (reset! mode :timeout)
    (testing "immediate read"
      (let [r (get (run http (opts :home h :cache-dir dir :now (+ now 700) :fresh true)) "anthropic")]
        (is (= [:unknown "timeout"] ((juxt :status :reason) r)))
        (is (nil? (:remaining-fraction r)))))
    (testing "cached read inside backoff, without a new call"
      (let [n (count @calls)
            r (get (run http (o (+ now 800))) "anthropic")]
        (is (= n (count @calls)))
        (is (= [:unknown "timeout"] ((juxt :status :reason) r)))))))

(deftest malformed-fields-are-shape-not-network
  (let [bad (fn [body] {:status 200 :headers {} :body (json/generate-string body)})
        codex (fn [reset-at] {:rate_limit {:primary_window {:used_percent 10 :reset_at reset-at}}})]
    (testing "positive control"
      (is (some? (:windows (usage/fetch :codex-oauth {:token "t"} (fn [_] (bad (codex 5))))))))
    (doseq [v [1e300 -1e300]]
      (testing (str "reset_at " v)
        (is (= {:reason "shape" :transient false}
               (usage/fetch :codex-oauth {:token "t"} (fn [_] (bad (codex v))))))))
    (testing "good snapshot is not served stale after a malformed 200"
      (let [mode (atom :ok) h (home-with {:pi-exp (* (+ now 100000) 1000)}) dir (tmp)
            [http _] (fake (fn [_] (if (= :ok @mode) (bad (codex 5)) (bad (codex 1e300)))))
            run-c #(get (usage/usage-for config (merge {:providers ["openai-codex"] :http-fn http
                                                        :home h :cache-dir dir} %))
                        "openai-codex")]
        (is (= :ok (:status (run-c {:now now}))))
        (reset! mode :bad)
        (is (= [:unknown "shape"] ((juxt :status :reason) (run-c {:now (+ now 61)}))))
        (is (= [:unknown "shape"] ((juxt :status :reason) (run-c {:now (+ now 62)}))) "cached read")))))
