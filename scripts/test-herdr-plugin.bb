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

(defn- executable! [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text)
  (fs/set-posix-file-permissions path "rwxr-xr-x"))

(deftest home-skill-oh-comes-before-the-checkout
  (with-env
    (fn [env]
      (let [home-oh (fs/path (get env "HOME") ".agents" "skills" "herdr-orch" "scripts" "oh")]
        (testing "positive control: without a home skill the checkout oh is named"
          (let [{:keys [exit out]} (run! env "bash" "quota-stanza.sh")]
            (is (zero? exit))
            (is (str/includes? out (str (fs/canonicalize oh))) out)))
        (executable! home-oh "#!/usr/bin/env bash\necho home-oh\n")
        (let [{:keys [exit out err]} (run! env "bash" "quota-stanza.sh")]
          (is (zero? exit) err)
          (is (str/includes? out (str "command = \"'" home-oh "' usage")) out))
        (testing "a home oh that is not executable is skipped"
          (fs/set-posix-file-permissions home-oh "rw-r--r--")
          (let [{:keys [out]} (run! env "bash" "quota-stanza.sh")]
            (is (str/includes? out (str (fs/canonicalize oh))) out)))))))

(deftest missing-oh-names-both-paths-tried
  (with-env
    (fn [env]
      (let [fake-root (fs/path (get env "HOME") "clone" "herdr" "plugins" "herdr-orch")]
        (fs/create-dirs fake-root)
        (let [{:keys [exit err]} (run! (assoc env "HERDR_PLUGIN_ROOT" (str fake-root)) "bash" "quota-stanza.sh")]
          (is (= 1 exit))
          (is (str/includes? err (str (get env "HOME") "/.agents/skills/herdr-orch/scripts/oh")) err)
          (is (str/includes? err (str (fs/canonicalize (fs/path (get env "HOME") "clone")) "/skills/herdr-orch/scripts/oh")) err))))))

(defn- doctor!
  "Run doctor once with a stub `herdr` that reports `version`; `bb?` keeps or
  drops Babashka from a PATH that otherwise holds only the tools doctor and the
  stub use."
  [env {:keys [version bb?] :or {version "0.9.5" bb? true}}]
  (let [home (get env "HOME")
        bin (fs/path home "doctor-bin")
        herdr (fs/path bin "herdr")]
    (fs/delete-tree bin)
    (executable! herdr (str "#!/usr/bin/env bash\necho 'herdr " version "'\n"))
    (doseq [tool ["awk" "bash" "dirname" "env"]] (fs/create-sym-link (fs/path bin tool) (fs/which tool)))
    (when bb? (fs/create-sym-link (fs/path bin "bb") (fs/which "bb")))
    (let [result (run! (assoc env "PATH" (str bin) "HERDR_BIN_PATH" (str herdr) "DOCTOR_ONCE" "1")
                       (str (fs/which "bash")) "doctor.sh")]
      (assoc result :lines (into {} (keep #(when-let [[_ v c d] (re-matches #"(PASS|WARN|FAIL)\s+(\S+)\s+(.*)" %)] [c [v d]])
                                          (str/split-lines (:out result))))))))

(defn- tree [dir] (sort (map str (fs/glob dir "**" {:hidden true}))))

(deftest doctor-passes-a-complete-install-and-writes-nothing
  (with-env
    (fn [env]
      (let [home (get env "HOME")]
        (fs/create-dirs (fs/path home ".agents" "skills"))
        (fs/create-sym-link (fs/path home ".agents" "skills" "herdr-orch") (fs/path root "skills" "herdr-orch"))
        (fs/create-dirs (fs/path home ".pi" "agent" "extensions" "traits"))
        (doctor! env {})
        (let [before (tree home)
              {:keys [exit lines out err]} (doctor! env {})]
          (is (zero? exit) (str out err))
          (is (= #{"herdr" "babashka" "oh" "home-skill" "pi-traits"} (set (keys lines))) out)
          (is (every? #(= "PASS" (first %)) (vals lines)) out)
          (is (str/ends-with? (second (lines "oh")) "(home)") out)
          (is (= before (tree home)) "doctor writes nothing"))))))

(deftest doctor-reports-each-failure-and-completes-without-bb
  (with-env
    (fn [env]
      (fs/create-dirs (fs/path (get env "HOME") ".pi" "agent"))
      (let [{:keys [exit lines out]} (doctor! env {:version "0.9.4" :bb? false})]
        (is (= 1 exit) out)
        (is (= ["FAIL" "0.9.4 is older than 0.9.5"] (lines "herdr")) out)
        (is (= "FAIL" (first (lines "babashka"))) out)
        (is (str/includes? (second (lines "babashka")) "https://github.com/babashka/babashka#installation") out)
        (is (= "PASS" (first (lines "oh"))) "the checkout oh still resolves")
        (is (str/ends-with? (second (lines "oh")) "(checkout)") out)
        (is (= "WARN" (first (lines "home-skill"))) out)
        (is (str/includes? (second (lines "home-skill")) "install-surfaces.md") out)
        (is (= "WARN" (first (lines "pi-traits"))) out))
      (testing "a newer minor version passes the floor"
        (is (= "PASS" (first ((:lines (doctor! env {:version "0.10.0"})) "herdr"))))))))

(deftest doctor-warns-of-version-skew-and-fails-without-oh
  (with-env
    (fn [env]
      (let [home (get env "HOME")]
        (executable! (fs/path home ".agents" "skills" "herdr-orch" "scripts" "oh") "#!/usr/bin/env bash\n")
        (let [{:keys [exit lines out]} (doctor! env {})]
          (is (zero? exit) "a warning does not fail")
          (is (= "WARN" (first (lines "home-skill"))) out)
          (is (str/includes? (second (lines "home-skill")) "version skew") out))
        (fs/delete-tree (fs/path home ".agents"))
        (let [fake-root (fs/path home "clone" "herdr" "plugins" "herdr-orch")
              _ (fs/create-dirs fake-root)
              {:keys [exit lines out]} (doctor! (assoc env "HERDR_PLUGIN_ROOT" (str fake-root)) {})]
          (is (= 1 exit) out)
          (is (= "FAIL" (first (lines "oh"))) out)
          (is (str/includes? (second (lines "oh")) "oh not found at") out))))))

(deftest plugin-files-hold-no-checkout-path
  (let [files (map str (filter fs/regular-file? (fs/list-dir plugin)))]
    (is (seq files) "positive control: the plugin directory has files")
    (doseq [f files]
      (is (not (re-find #"/Users/|/home/|dotfiles" (slurp f))) f))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
