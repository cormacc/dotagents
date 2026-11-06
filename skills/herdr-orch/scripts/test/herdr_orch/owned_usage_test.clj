(ns herdr-orch.owned-usage-test
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [herdr-orch.quota-credentials :as credentials]
            [herdr-orch.core :as core]
            [herdr-orch.quota-credentials-test :as stores]
            [herdr-orch.usage :as usage]))

(use-fixtures :each
  (fn [f]
    (with-redefs [credentials/environment (fn [] (throw (ex-info "ambient credentials forbidden" {})))] (f))))

(def config {:providers {"anthropic" {:quota-provider "claude"}
                         "openai-codex" {:quota-provider "codex"} "local" {}}})
(defn remaining [report provider & [scope]]
  (core/candidate-remaining report (cond-> {:provider provider} scope (assoc :quota-scope scope))))
(defn options [context body]
  {:credential-context context :cache-dir (str (fs/path (:home context) "cache"))
   :http-fn (fn [_] {:status 200 :body body})})

(deftest owned-codex-retains-long-base-window-credit
  (stores/with-context
    (fn [context]
      (stores/seed! context)
      (let [report (usage/usage-for config
                     (assoc (options context {:rate_limit {:primary_window {:used_percent 2 :limit_window_seconds 2592000}}})
                            :providers ["openai-codex"]))]
        (is (= 0.98 (remaining report "openai-codex")))
        (is (= 2592000 (get-in report [:providers 0 :windows 0 :windowSeconds])))))))

(defn codex-body [used seconds]
  {:rate_limit {:primary_window {:used_percent used :limit_window_seconds seconds :reset_at 1900000000}}})
(defn run-usage [context provider body & [opts]]
  (usage/usage-for config (merge (options context body) {:providers [provider]} opts)))
(defn row [report] (first (:providers report)))

(deftest account-and-exact-model-bounds
  (stores/with-context
    (fn [context]
      (stores/seed! context)
      (doseq [seconds [18000 604800 2592000 1728000]]
        (let [r (run-usage context "openai-codex" (codex-body 25 seconds) {:fresh true})]
          (is (= 0.75 (remaining r "openai-codex")))
          (is (= seconds (get-in r [:providers 0 :windows 0 :windowSeconds])))))
      (let [body (assoc (codex-body 25 604800) :additional_rate_limits
                        [{:normal_model_slug "fable" :rate_limit {:primary_window {:used_percent 96 :limit_window_seconds 18000}}}])
            r (run-usage context "openai-codex" body {:fresh true})]
        (is (= 0.75 (remaining r "openai-codex")))
        (is (= 0.04 (remaining r "openai-codex" "model:fable")))
        (is (nil? (remaining r "openai-codex" "fable")))
        (is (nil? (remaining r "openai-codex" "model:other"))))
      (let [r (run-usage context "openai-codex"
                        (assoc (codex-body 98 604800) :additional_rate_limits
                               [{:normal_model_slug "fable" :rate_limit {:primary_window {:used_percent 4}}}]) {:fresh true})]
        (is (= 0.02 (remaining r "openai-codex" "model:fable")) "account bounds constrain model bounds")))))

(deftest anthropic-shape-precedence-and-scopes
  (stores/with-context
    (fn [context]
      (stores/seed! context)
      (let [flat {:five_hour {:utilization 20 :resets_at "2030-01-01T00:00:00+00:00"}
                  :seven_day {:utilization 30} :seven_day_sonnet {:utilization 90} :extra_usage {:used_credits 100}}]
        (let [r (run-usage context "anthropic" flat)]
          (is (= 0.7 (remaining r "anthropic")))
          (is (= 0.1 (remaining r "anthropic" "model:sonnet")))
          (is (= "2030-01-01T00:00:00Z" (get-in r [:providers 0 :windows 0 :resetsAt]))))
        (let [r (run-usage context "anthropic" (assoc flat :limits
                         [{:kind "session" :percent 95}
                          {:kind "weekly_all" :percent 3}
                          {:kind "weekly_scoped" :percent 99 :scope {:model {:display_name "Sonnet"}}}]) {:fresh true})]
          (is (= 0.05 (remaining r "anthropic")))
          (is (= 0.01 (remaining r "anthropic" "model:sonnet"))))
        (doseq [limits [nil {} [] [{:kind "weekly_scoped" :percent 12}]
                        [{:kind "session" :percent 1} {:kind "mystery" :percent 99}]
                        [{:group "weekly" :percent 1 :scope {:unknown true}}]]]
          (fs/delete-tree (fs/path (:home context) "cache"))
          (is (nil? (remaining (run-usage context "anthropic" (assoc flat :limits limits) {:fresh true}) "anthropic"))
              (pr-str limits)))))))

(deftest invalid-percentages-and-unresolved-shapes
  (stores/with-context
    (fn [context]
      (stores/seed! context)
      (doseq [bad [-1 101 ##NaN ##Inf "4" nil]]
        (is (nil? (remaining (run-usage context "openai-codex" (codex-body bad 18000) {:fresh true}) "openai-codex")))
        ;; Avoid negative-cache reuse hiding a later case.
        (fs/delete-tree (fs/path (:home context) "cache")))
      (doseq [body [{:credits {:balance 400}}
                    {:rate_limit {:primary_window {:used_percent 1}} :additional_rate_limits [{:limit_name "opaque" :rate_limit {:primary_window {:used_percent 99}}}]}
                    {:rate_limit {:primary_window {:used_percent 1} :secondary_window "malformed"}}
                    {:code_review_rate_limit {:primary_window {:used_percent 1}}}
                    {:rate_limit {:primary_window {:used_percent 1}} :additional_rate_limits {}}]]
        (is (nil? (remaining (run-usage context "openai-codex" body {:fresh true}) "openai-codex")))
        (fs/delete-tree (fs/path (:home context) "cache"))))))

(deftest credential-context-and-expiry-invalidate-cache
  (stores/with-context
    (fn [context]
      (stores/seed! context)
      (let [calls (atom [])
            opts {:http-fn (fn [req] (swap! calls conj (:headers req)) {:status 200 :body (codex-body 2 2592000)})}
            run (fn [context opts] (run-usage context "openai-codex" nil opts))]
        (is (= 0.98 (remaining (run context opts) "openai-codex")))
        (is (= 0.98 (remaining (run context opts) "openai-codex")))
        (is (= 1 (count @calls)))
        (is (= "Bearer pi-codex" (get (first @calls) "Authorization")))
        (is (= "fixture-account" (get (first @calls) "ChatGPT-Account-Id")))
        (is (= "expired-credentials" (:reason (row (run (assoc context :now-ms stores/later-ms) opts)))))
        (is (= 1 (count @calls)))
        (stores/put-json! (stores/pi-path context) {:openai-codex (stores/oauth "replacement")})
        (is (= 0.98 (remaining (run context opts) "openai-codex")))
        (is (= "Bearer replacement" (get (last @calls) "Authorization")))
        (is (= 2 (count @calls)))
        (is (= 0.98 (remaining (run context (assoc opts :harness :codex)) "openai-codex")))
        (is (= 3 (count @calls)))
        (fs/delete (stores/pi-path context))
        (is (= "missing-credentials" (:reason (row (run context opts)))))
        (is (= 3 (count @calls)))))))

(deftest ttl-fresh-negative-cache-and-provider-backoff
  (stores/with-context
    (fn [context]
      (stores/seed! context)
      ;; Long-lived fixture credentials let the cache boundary move both ways.
      (stores/put-json! (stores/pi-path context) {:openai-codex (assoc (stores/oauth "cache-token") :expires (+ stores/now-ms 2000000))})
      (let [calls (atom 0) response (atom {:status 200 :body (codex-body 2 18000)})
            opts {:http-fn (fn [_] (swap! calls inc) @response)}
            at (fn [offset fresh] (run-usage (update context :now-ms + offset) "openai-codex" nil (assoc opts :fresh fresh)))]
        (is (= 0.98 (remaining (at 0 false) "openai-codex")))
        (at 59999 false) (is (= 1 @calls))
        (at 60000 false) (is (= 2 @calls))
        (at 60001 true) (is (= 3 @calls))
        (reset! response {:status 429 :headers {"Retry-After" "120"} :body "SECRET"})
        (let [r (at 60002 true)]
          (is (= "rate-limited" (:reason (row r))))
          (is (seq (:windows (row r))) "diagnostic windows retained")
          (is (nil? (remaining r "openai-codex"))))
        (at 180001 true) (is (= 4 @calls) "fresh does not bypass provider backoff")
        (at 180002 true) (is (= 5 @calls) "backoff boundary permits a fetch")
        (reset! response {:status 401})
        (let [r (at 300002 true)]
          (is (= "auth" (:reason (row r))))
          (is (empty? (:windows (row r))))
          (is (nil? (remaining r "openai-codex"))))
        (at 330001 true) (is (= 6 @calls))
        (at 330002 true) (is (= 7 @calls))))))

(deftest stale-diagnostic-windows-are-labelled-and-expire-at-the-ceiling
  (doseq [failure-at [1 3599999 3600000]]
    (stores/with-context
      (fn [context]
        (stores/seed! context)
        (stores/put-json! (stores/pi-path context)
                         {:openai-codex (assoc (stores/oauth "long-lived") :expires (+ stores/now-ms 10000000))})
        (let [calls (atom 0) response (atom {:status 200 :body (codex-body 25 18000)})
              at (fn [offset]
                   (run-usage (update context :now-ms + offset) "openai-codex" nil
                              {:fresh true :http-fn (fn [_] (swap! calls inc) @response)}))]
          (is (= 0.75 (remaining (at 0) "openai-codex")))
          (reset! response {:status 429 :headers {"Retry-After" "7200"}})
          (let [r (at failure-at)]
            (is (nil? (remaining r "openai-codex")))
            (is (= (< failure-at 3600000) (boolean (seq (:windows (row r))))))
            (is (= (< failure-at 3600000) (get-in (row r) [:state :stale]))))
          (when (< failure-at 3600000)
            (let [before (at 3599999) after (at 3600000)]
              (is (seq (:windows (row before))))
              (is (true? (get-in (row before) [:state :stale])))
              (is (nil? (remaining before "openai-codex")))
              (is (empty? (:windows (row after))))
              (is (false? (get-in (row after) [:state :stale])))
              (is (nil? (remaining after "openai-codex")))))
          (is (= 2 @calls) "failure backoff survives fresh requests and diagnostic expiry"))))))

(deftest public-output-and-cache-discard-private-data
  (stores/with-context
    (fn [context]
      (stores/seed! context)
      (let [secret "SECRET-user@example.test-/private/auth.json"
            body (assoc (codex-body 25 18000) :account_id secret :email secret :error secret)
            r (run-usage context "openai-codex" body)
            cache (apply str (map #(slurp (str %)) (fs/glob (fs/path (:home context) "cache") "*.json")))]
        (is (= 0.75 (remaining r "openai-codex")))
        (is (str/includes? cache "primary_window") "positive control: cache exists")
        (doseq [s [secret "pi-codex" "fixture-refresh" "fixture-account"]]
          (is (not (str/includes? (pr-str r) s)))
          (is (not (str/includes? cache s)))))
      (let [r (run-usage context "anthropic" nil {:http-fn (fn [_] (throw (ex-info "SECRET" {:headers {"Authorization" "SECRET"}})))})]
        (is (= "network" (:reason (row r))))
        (is (not (str/includes? (pr-str r) "SECRET")))))))

(deftest unwritable-or-corrupt-cache-does-not-prevent-collection
  (stores/with-context
    (fn [context]
      (stores/seed! context)
      (let [path (str (fs/path (:home context) "not-directory"))]
        (spit path "block parent creation")
        (is (= 0.75 (remaining (run-usage context "openai-codex" (codex-body 25 18000) {:cache-dir (str (fs/path path "child"))}) "openai-codex"))))
      (let [r (run-usage context "openai-codex" (codex-body 25 18000))
            path (first (fs/glob (fs/path (:home context) "cache") "*.json"))]
        (is (= 0.75 (remaining r "openai-codex")))
        (spit (str path) "{not valid")
        (is (= 0.6 (remaining (run-usage context "openai-codex" (codex-body 40 18000)) "openai-codex")))
        (let [state (json/parse-string (slurp (str path)) true)]
          (spit (str path) (json/generate-string (assoc-in state [:evidence :quotaSemantics :effectiveAvailability 0 :effectivePercentRemaining] 99)))
          (is (= 0.5 (remaining (run-usage context "openai-codex" (codex-body 50 18000)) "openai-codex"))))))))

(deftest fixed-http-reasons-and-zero-exhausted-boundaries
  (let [credential {:access "test" :account-id "test"}]
    (doseq [[status reason] [[401 "auth"] [403 "scope"] [429 "rate-limited"] [500 "http-error"] [302 "http-error"]]]
      (let [r (usage/fetch "codex" credential (fn [_] {:status status :body "SECRET"}) stores/now-ms)]
        (is (= reason (:reason r)))
        (is (not (str/includes? (pr-str r) "SECRET")))))
    (doseq [body ["not JSON SECRET" "[]" "{}"]]
      (is (= "invalid-response" (:reason (usage/fetch "codex" credential (fn [_] {:status 200 :body body}) stores/now-ms)))))
    (is (= "timeout" (:reason (usage/fetch "codex" credential (fn [_] (throw (java.net.http.HttpTimeoutException. "SECRET"))) stores/now-ms))))
    (let [r (usage/fetch "codex" credential (fn [_] {:status 429 :headers {"retry-after" "Fri, 15 Jan 2027 08:05:20 GMT"}}) stores/now-ms)]
      (is (number? (:retry-ms r)))))
  (stores/with-context
    (fn [context]
      (stores/seed! context)
      (is (= 1.0 (remaining (run-usage context "openai-codex" (codex-body 0 18000)) "openai-codex")))
      (is (= 0.0 (remaining (run-usage context "openai-codex" (codex-body 100 18000) {:fresh true}) "openai-codex"))))))

(deftest named-accounts-isolate-evidence-and-credential-generations
  (stores/with-context
    (fn [context]
      (stores/seed! context)
      (let [path (stores/pi-path context)
            a "anthropic-1" b "anthropic-2"
            auth (assoc (json/parse-string (slurp path) true)
                        (keyword a) (stores/oauth "token-a")
                        (keyword b) (stores/oauth "token-b")
                        :anthropic-01 (stores/oauth "ignored")
                        (keyword (str "anthropic" "-account-c")) (stores/oauth "ignored"))
            calls (atom [])
            opts (assoc (options context nil) :providers ["anthropic"]
                        :http-fn (fn [req]
                                   (let [token (get-in req [:headers "Authorization"])]
                                     (swap! calls conj token)
                                     {:status 200 :body {:five_hour {:utilization (if (= token "Bearer token-b") 99 1)}}})))
            run #(usage/usage-for config opts)]
        (stores/put-json! path auth)
        (let [before (slurp path) report (run)]
          (is (= ["anthropic" a b] (mapv :localProvider (:providers report))))
          (is (= ["anthropic" "anthropic" "anthropic"] (mapv :baseProvider (:providers report))))
          (is (= ["anthropic" a b] (mapv :accountId (:providers report))))
          (is (= 0.99 (remaining report a)))
          (is (= 0.01 (remaining report b)))
          (is (= before (slurp path)) "read-only collection")
          (let [files (fs/glob (fs/path (:home context) "cache") "*.json")
                cache (apply str (map #(slurp (str %)) files))]
            (is (= 3 (count files)) "one cache per account")
            (is (str/includes? cache "five_hour") "cache positive control")
            (doseq [secret ["token-a" "token-b" "fixture-refresh" "fixture-account"]]
              (is (not (str/includes? (pr-str report) secret)))
              (is (not (str/includes? cache secret))))))
        (is (= 3 (count @calls)))
        (is (= 0.01 (remaining (run) b)))
        (is (= 3 (count @calls)) "account-specific cache hits")
        (stores/put-json! path (assoc-in auth [(keyword a) :refresh] "replacement-refresh"))
        (is (= 0.99 (remaining (run) a)))
        (is (= 4 (count @calls)) "refresh generation invalidates only its account")
        (stores/put-json! path (assoc-in auth [(keyword b) :expires] stores/now-ms))
        (let [report (run)]
          (is (nil? (remaining report b)))
          (is (= "expired-credentials" (:reason (last (:providers report)))))
          (is (= 0.99 (remaining report a))))
        (let [report (usage/usage-for config (assoc opts :providers ["anthropic-9"]))]
          (is (= "missing-credentials" (:reason (row report))))
          (is (nil? (remaining report "anthropic-9"))))))))

(deftest named-accounts-never-resolve-a-native-or-misconfigured-base-credential
  (stores/with-context
    (fn [context]
      (stores/seed! context)
      (let [account "anthropic-1"
            configured (assoc-in config [:providers account] {:quota-provider "claude"})
            opts (assoc (options context nil) :providers [account]
                        :http-fn (fn [_] (throw (ex-info "must not fetch" {}))))]
        (doseq [[config harness] [[configured :claude] [configured :codex]
                                  [(update configured :providers dissoc "anthropic") :pi]
                                  [(assoc-in configured [:providers "anthropic" :quota-provider] "codex") :pi]]]
          (let [report (usage/usage-for config (assoc opts :harness harness))]
            (is (= "unsupported-auth-context" (:reason (row report))))
            (is (nil? (remaining report account)))))))))

(deftest named-account-removal-or-replacement-during-fetch-discards-credit
  (doseq [replacement [nil (stores/oauth "new-token")]]
    (stores/with-context
      (fn [context]
        (stores/seed! context)
        (let [path (stores/pi-path context)
              account "anthropic-1"
              auth (assoc (json/parse-string (slurp path) true)
                          (keyword account) (stores/oauth "old-token"))
              opts (assoc (options context nil) :providers [account]
                          :http-fn (fn [_]
                                     (stores/put-json! path
                                       (if replacement (assoc auth (keyword account) replacement)
                                           (dissoc auth (keyword account))))
                                     {:status 200 :body {:five_hour {:utilization 1}}}))]
          (stores/put-json! path auth)
          (let [report (usage/usage-for config opts)]
            (is (= (if replacement "credentials-changed" "missing-credentials") (:reason (row report))))
            (is (nil? (remaining report account)))
            (is (empty? (:windows (row report)))))
          (is (= "pi-claude" (:access (credentials/credentials :pi "claude" context))))
          (when-not replacement
            (is (= "missing-credentials" (:reason (credentials/credentials :pi "claude" context account))))))))))

(deftest locals-and-unconfigured-providers-do-not-read-credentials
  (let [r (usage/usage-for config {:providers ["local" "missing"]})]
    (is (= 1.0 (remaining r "local")))
    (is (= "not-configured" (get-in r [:providers 1 :reason]))))
  (is (= [] (:providers (usage/usage-for config {:providers []})))))
