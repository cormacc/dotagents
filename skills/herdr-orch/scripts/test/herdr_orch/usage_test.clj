(ns herdr-orch.usage-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.core :as core]
            [herdr-orch.test-support :as support]))

(def root (str (fs/canonicalize ".")))
(def fixture (str (fs/path root "skills/herdr-orch/scripts/test/fixtures/quota-axi")))
(def config {:providers {"anthropic" {:quota-provider "claude"}
                         "openai-codex" {:quota-provider "codex"}
                         "kimi" {:quota-provider "kimi"} "zai" {:quota-provider "zai"} "local" {}}})
(defn window [] {:id "five_hour" :kind "session" :percentUsed 90 :percentRemaining 10 :resetsAt "2030-01-01T00:00:00Z"})
(defn scope [] {:scope "all_models" :status "known" :effectivePercentRemaining 73 :boundedBy ["five_hour"]})
(defn row [] {:provider "claude" :state {:status "fresh" :stale false}
             :windows [(window)] :quotaSemantics {:status "known" :effectiveAvailability [(scope)]}})
(defn report [rows] {:schemaVersion 5 :providers (vec rows)})
(defn with-fixture [f]
  (fs/create-dirs (fs/path root ".tmp"))
  (let [dir (str (fs/create-temp-dir {:dir (fs/path root ".tmp") :prefix "quota-boundary-"}))]
    (try (f dir (support/quota-env! dir fixture)) (finally (fs/delete-tree dir)))))
(defn collect
  ([env data] (collect env data {}))
  ([env data opts]
   (spit (get env "FAKE_QUOTA_REPORT") (if (string? data) data (json/generate-string data)))
   (let [proc @(process/process
               ["bb" "--config" (str root "/bb.edn") "--deps-root" root "-e"
                (str "(require '[herdr-orch.usage :as u] '[cheshire.core :as j]) "
                     "(with-redefs [u/collection-budget-ms 500 u/version-budget-ms 500 u/cleanup-budget-ms 250] "
                     "(println (j/generate-string (u/usage-for " (pr-str config) " " (pr-str opts) "))))")]
               {:env env :out :string :err :string})]
     (when-not (zero? (:exit proc)) (throw (ex-info "boundary runner failed" proc)))
     (json/parse-string (:out proc) true))))
(defn reason [result] (:reason (first (:providers result))))
(defn remaining [result & [selector]] (core/candidate-remaining result (cond-> {:provider "anthropic"} selector (assoc :quota-scope selector))))

(deftest effective-evidence-schema-five-and-six
  (with-fixture
    (fn [_ env]
      (doseq [schema [5 6]]
        (let [rows (mapv #(assoc (row) :provider % :accountKey "private-account" :accountKeys ["one" "two"]) ["claude" "codex" "kimi" "zai"])
              result (collect env (assoc (report rows) :schemaVersion schema))]
          (is (= schema (:schemaVersion result)))
          (is (= #{"claude" "codex" "kimi" "zai"} (set (keep :provider (:providers result)))))
          (is (= 0.73 (remaining result)) "uses upstream effective value, not local window minimum")
          (is (= 1.0 (core/candidate-remaining result {:provider "local"})))
          (is (not (str/includes? (pr-str result) "private-account"))))))))

(deftest invalid-boundaries-return-fixed-reasons
  (with-fixture
    (fn [_ env]
      (is (= "wrong-version" (reason (collect (assoc env "FAKE_QUOTA_VERSION" "9.9.9 SECRET") (report [(row)])))) )
      (doseq [bad ["not-json SECRET" "{}" "{\"schemaVersion\":7,\"providers\":[]}" "{\"schemaVersion\":5,\"providers\":null}"]]
        (is (= "malformed-output" (reason (collect env bad)))))
      (is (= "oversized-output" (reason (collect env (apply str (repeat 1048577 "x"))))))
      (let [result (collect (assoc env "FAKE_QUOTA_EXIT" "1") (report [(row)]))]
        (is (= 0.73 (remaining result)))
        (is (= "missing-provider" (:reason (second (:providers result))))) )
      (fs/delete (fs/path (first (str/split (get env "PATH") #":")) "quota-axi"))
      ;; An empty PATH cannot find either the fixture or a host quota-axi. Keep bb absolute.
      (let [bb (str (fs/which "bb"))
            proc @(process/process [bb "-cp" (str root "/skills/herdr-orch/scripts/src") "-e"
                                    "(require '[herdr-orch.usage :as u]) (prn (u/usage-for {:providers {\"a\" {:quota-provider \"claude\"}}}))"]
                                   {:env (assoc env "PATH" "") :out :string :err :string})]
        (is (zero? (:exit proc)) (:err proc))
        (is (str/includes? (:out proc) "missing-executable"))))))

(deftest invalid-subscription-evidence-never-grants-credit
  (with-fixture
    (fn [_ env]
      (doseq [[label data]
              [["ambiguous accounts" (report [(row) (assoc (row) :accountKey "second")])]
               ["stale flag" (report [(assoc-in (row) [:state :stale] true)])]
               ["stale status" (report [(assoc-in (row) [:state :status] "stale")])]
               ["unavailable status" (report [(assoc-in (row) [:state :status] "error")])]
               ["windowless" (report [(assoc (row) :windows [])])]
               ["unresolved" (report [(assoc-in (row) [:quotaSemantics :unresolvedWindowIds] ["mystery"])])]
               ["missing bound" (report [(assoc-in (row) [:quotaSemantics :effectiveAvailability 0 :boundedBy] ["missing"])])]
               ["empty bound" (report [(assoc-in (row) [:quotaSemantics :effectiveAvailability 0 :boundedBy] [])])]
               ["duplicate window" (report [(assoc (row) :windows [(window) (window)])])]
               ["duplicate scope" (report [(assoc-in (row) [:quotaSemantics :effectiveAvailability] [(scope) (scope)])])]
               ["unknown scope status" (report [(assoc-in (row) [:quotaSemantics :effectiveAvailability 0 :status] "unknown")])]
               ["conflict" (report [(assoc-in (row) [:quotaSemantics :effectiveAvailability 0 :boundConflict] {:exhaustedWindowIds ["five_hour"]})])]
               ["credits" (report [(assoc-in (row) [:windows 0 :kind] "credits")])]
               ["USD" (report [(assoc-in (row) [:windows 0 :limitUsd] 10)])]
               ["credit units" (report [(assoc-in (row) [:windows 0 :limitCredits] 10)])]
               ["share" (report [(assoc-in (row) [:windows 0 :shareOf] "weekly")])]]]
        (is (nil? (remaining (collect env data))) label))
      (doseq [p [-1 101 "73" nil]]
        (is (nil? (remaining (collect env (report [(assoc-in (row) [:quotaSemantics :effectiveAvailability 0 :effectivePercentRemaining] p)])))))))))

(deftest exact-model-scope-and-account-default
  (with-fixture
    (fn [_ env]
      (let [model (assoc (scope) :scope "seven_day_opus" :effectivePercentRemaining 4)
            data (report [(assoc-in (row) [:quotaSemantics :effectiveAvailability] [(scope) model])])
            result (collect env data)]
        (is (= 0.04 (remaining result "seven_day_opus")))
        (is (= 0.73 (remaining result)) "no selector defaults to all_models in multi-scope evidence")
        (is (= 0.73 (remaining result "all_models")) "an explicit all_models selector is exact")
        (doseq [s ["opus" "SEVEN_DAY_OPUS" "seven_day_fable"]]
          (is (nil? (remaining result s)) s))
        (is (nil? (remaining (collect env (assoc-in data [:providers 0 :quotaSemantics :effectiveAvailability 1 :status] "unknown")) "seven_day_opus")))))))

(deftest public-output-strips-secret-and-identity-canaries
  (with-fixture
    (fn [_ env]
      (let [canary "SECRET-email@example.test-/private/auth.json"
            data (report [(-> (row)
                              (assoc :account {:email canary :accountId canary} :accountKey canary :accountKeys [canary]
                                     :accountLocator {:path canary} :attempts [{:error canary}] :label canary :source canary)
                              (assoc-in [:state :error] canary)
                              (assoc-in [:quotaSemantics :description] canary)
                              (assoc-in [:windows 0 :label] canary))])
            result (collect env data)]
        (is (= 0.73 (remaining result)) "positive control: valid evidence survived")
        (is (not (str/includes? (json/generate-string result) canary)))))))

(deftest argv-providers-reuse-and-ambient-controls
  (with-fixture
    (fn [dir env]
      (is (= 1.0 (core/candidate-remaining (collect env (report []) {:providers ["local"]}) {:provider "local"})))
      (is (= "not-configured" (reason (collect env (report []) {:providers ["nope"]}))))
      (is (not (fs/exists? (fs/path dir "quota-calls"))) "local and unconfigured providers do not start a collector")
      (let [env (assoc env "QUOTA_AXI_SNAPSHOT" "bad" "QUOTA_AXI_CLAUDE_PROFILES" "bad" "QUOTA_AXI_CLAUDE_SWAP" "1")]
        (is (= 0.73 (remaining (collect env (report [(row)]) {:providers ["anthropic"]}))))
        (collect env (report [(row)]) {:providers ["anthropic"] :fresh true})
        (let [calls (slurp (str (fs/path dir "quota-calls")))]
          (is (str/includes? calls "[\"--version\"]"))
          (is (str/includes? calls "[\"--json\" \"--full\" \"--no-credential-refresh\" \"--max-age\" \"5m\" \"--provider\" \"claude\"]"))
          (is (str/includes? calls "\"--max-age\" \"0\"")))))))

(deftest timeout-cleans-owned-descendants-only
  (with-fixture
    (fn [dir env]
      (let [binary (str (fs/path dir "quota-bin" "quota-axi"))
            pid-file (str (fs/path dir "child-pid"))
            sleep (str (fs/which "sleep"))
            unrelated (process/process [sleep "10"])
            shell (str (fs/which "bash"))]
        (spit binary (str "#!" shell "\nif [ \"$1\" = --version ]; then echo 0.1.69; exit; fi\n"
                          sleep " 10 &\necho $! > '" pid-file "'\nwait\n"))
        (try
          (is (= "timeout" (reason (collect env (report [(row)])))))
          (is (fs/exists? pid-file) "the descendant was actually created")
          (let [pid (Long/parseLong (str/trim (slurp pid-file)))
                handle (java.lang.ProcessHandle/of pid)]
            (is (or (not (.isPresent handle)) (not (.isAlive (.get handle)))) "owned descendant exited"))
          (is (.isAlive (:proc unrelated)) "no unrelated process was signalled")
          (finally (process/destroy-tree unrelated)))))))

(deftest cleanup-retains-descendants-after-the-leader-exits
  (with-fixture
    (fn [dir env]
      (let [binary (str (fs/path dir "quota-bin" "quota-axi"))
            pid-file (str (fs/path dir "orphan-pid"))
            sleep (str (fs/which "sleep"))
            shell (str (fs/which "bash"))]
        (spit binary (str "#!" shell "\nif [ \"$1\" = --version ]; then echo 0.1.69; exit; fi\n"
                          sleep " 10 >/dev/null 2>&1 &\necho $! > '" pid-file "'\n"
                          "printf '%s' '{\"schemaVersion\":5,\"providers\":[]}'\n"))
        (try
          (collect env (report [(row)]))
          (is (fs/exists? pid-file) "positive control: a descendant was created")
          (let [pid (Long/parseLong (str/trim (slurp pid-file)))
                handle (java.lang.ProcessHandle/of pid)]
            (is (or (not (.isPresent handle)) (not (.isAlive (.get handle))))
                "a fast-exiting leader must not leave its child alive"))
          (finally
            ;; The fixture PID is ours; cleanup also runs when the acceptance assertion fails.
            (when (fs/exists? pid-file)
              (when-let [h (.orElse (java.lang.ProcessHandle/of (Long/parseLong (str/trim (slurp pid-file)))) nil)]
                (.destroyForcibly h)))))))))

(deftest isolation-tripwire-detects-native-vendor-escape
  (with-fixture
    (fn [dir env]
      (let [proc @(process/process [(str (fs/path dir "quota-bin" "codex")) "--version"] {:env env :out :string :err :string})]
        (is (= 97 (:exit proc)))
        (is (= "vendor escape\n" (slurp (str (fs/path dir "vendor-escapes")))))))))
