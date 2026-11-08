#!/usr/bin/env bb
;; Smoke test for herdr/plugins/herdr-orch/: runs the quota pane and stanza entrypoints the way
;; Herdr does (plugin directory as cwd, HERDR_PLUGIN_ROOT set) against the offline
;; owned HTTP fixture, in a disposable HOME.

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing run-tests]])

(def root (str/trim (:out (process/shell {:out :string} "git" "rev-parse" "--show-toplevel"))))
(def plugin (str (fs/path root "herdr" "plugins" "herdr-orch")))
(def oh (str (fs/path root "skills" "herdr-orch" "scripts" "oh")))
(defn with-env
  "Call f with isolated credentials and a test-only, file-backed HTTP transport."
  [f]
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:prefix "herdr-plugin-"})))
        real-bb (str (fs/which "bb"))]
    (try
      (let [bin (fs/path dir "bin") home (fs/path dir "home") report (str (fs/path dir "report.json"))
            agent (fs/path home ".pi/agent")]
        (fs/create-dirs bin)
        (fs/create-dirs agent)
        ;; The real oh launcher remains under test. Only its final bb invocation is replaced.
        (spit (str (fs/path bin "bb"))
              (str "#!/usr/bin/env bash\nset -euo pipefail\n"
                   "[ \"${1:-}\" = --config ] && [ \"${5:-}\" = -m ] && [ \"${6:-}\" = herdr-orch.cli ] && [ \"${7:-}\" = -- ] || exit 96\n"
                   "shift 7\nexec \"$ORCH_TEST_ROOT/skills/herdr-orch/scripts/test/fixtures/http-oh\" \"$@\"\n"))
        (fs/set-posix-file-permissions (fs/path bin "bb") "rwxr-xr-x")
        (spit report (json/generate-string {:claude {:body {:five_hour {:utilization 39}}}
                                           :codex {:body {:rate_limit {:primary_window {:used_percent 58 :limit_window_seconds 18000}}}}}))
        (spit (str (fs/path agent "auth.json"))
              (json/generate-string {:anthropic {:type "oauth" :refresh "fixture" :access "fixture-claude" :expires 4102444800000}
                                     :openai-codex {:type "oauth" :refresh "fixture" :access "fixture-codex" :expires 4102444800000 :accountId "fixture-account"}}))
        (f {"PATH" (str bin ":" (System/getenv "PATH")) "HOME" (str home)
            "XDG_CACHE_HOME" (str (fs/path home ".cache"))
            "ORCH_TEST_ROOT" root "ORCH_TEST_BB" real-bb
            "FAKE_QUOTA_REPORT" report "FAKE_QUOTA_LOG" (str (fs/path dir "quota-calls"))
            "HERDR_PLUGIN_ROOT" plugin}))
      (finally (fs/delete-tree dir)))))

(defn run! [env & argv]
  @(process/process (vec argv) {:dir plugin :env env :out :string :err :string}))

(deftest stanza-names-the-checkout-oh-and-bounded-timing
  (with-env
    (fn [env]
      (let [{:keys [exit out err]} (run! env "bash" "quota-stanza.sh")]
        (is (zero? exit) err)
        (is (str/includes? out (str "command = \"'" (fs/canonicalize oh) "' usage --format status\"")) out)
        (is (str/includes? out "interval_seconds = 60, timeout_seconds = 30") out)
        (is (str/includes? out "tab_bar_position = \"bottom\"") out)
        (testing "the stanza command itself prints the status line"
          (let [cmd (second (re-find #"command = \"(.*)\", interval" out))
                status (run! env "/bin/sh" "-c" cmd)]
            (is (zero? (:exit status)) (:err status))
            (is (str/includes? (:out status) "anthropic 39%s") (:out status))
            (is (str/includes? (:out status) "openai-codex 58%s") (:out status))))))))

(deftest pane-prints-text-usage-once
  (with-env
    (fn [env]
      (let [{:keys [exit out err]} (run! (assoc env "QUOTA_PANE_ONCE" "1") "bash" "quota-pane.sh")]
        (is (zero? exit) err)
        (is (str/includes? out "Subscription quota") out)
        (is (str/includes? out "anthropic (fresh)\n- all_models :: 61% remaining") out)))))

(deftest entrypoints-refuse-without-herdr-plugin-root
  (with-env
    (fn [env]
      (doseq [script ["quota-stanza.sh" "quota-pane.sh"]]
        (let [{:keys [exit err]} (run! (dissoc env "HERDR_PLUGIN_ROOT") "bash" script)]
          (is (= 1 exit) script)
          (is (str/includes? err "HERDR_PLUGIN_ROOT") err))))))

(deftest plugin-files-hold-no-checkout-path
  (let [files (map str (filter fs/regular-file? (fs/list-dir plugin)))]
    (is (seq files) "positive control: the plugin directory has files")
    (doseq [f files]
      (is (not (re-find #"/Users/|/home/|dotfiles" (slurp f))) f))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
