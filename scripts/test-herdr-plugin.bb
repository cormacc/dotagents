#!/usr/bin/env bb
;; Smoke test for herdr-plugin/: runs the quota pane and stanza entrypoints the way
;; Herdr does (plugin directory as cwd, HERDR_PLUGIN_ROOT set) against the offline
;; quota-axi fixture, in a disposable HOME.

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing run-tests]])

(def root (str/trim (:out (process/shell {:out :string} "git" "rev-parse" "--show-toplevel"))))
(def plugin (str (fs/path root "herdr-plugin")))
(def oh (str (fs/path root "skills" "herdr-orch" "scripts" "oh")))
(def fixture (str (fs/path root "skills" "herdr-orch" "scripts" "test" "fixtures" "quota-axi")))

(defn quota-row [provider remaining]
  {:provider provider :state {:status "fresh" :stale false}
   :windows [{:id "five_hour" :kind "session" :percentUsed (- 100 remaining) :percentRemaining remaining}]
   :quotaSemantics {:status "known"
                    :effectiveAvailability [{:scope "all_models" :status "known"
                                             :effectivePercentRemaining remaining :boundedBy ["five_hour"]}]}})

(defn with-env
  "Call f with an isolated environment whose PATH finds the fixture as quota-axi."
  [f]
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:prefix "herdr-plugin-"})))]
    (try
      (let [bin (fs/path dir "bin") home (fs/path dir "home") report (str (fs/path dir "report.json"))]
        (fs/create-dirs bin)
        (fs/create-dirs home)
        (fs/copy fixture (fs/path bin "quota-axi"))
        (fs/set-posix-file-permissions (fs/path bin "quota-axi") "rwxr-xr-x")
        (spit report (json/generate-string {:schemaVersion 6 :providers [(quota-row "claude" 61) (quota-row "codex" 42)]}))
        (f {"PATH" (str bin ":" (System/getenv "PATH")) "HOME" (str home)
            "XDG_DATA_HOME" (str (fs/path home ".local/share"))
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
        (is (str/includes? out "interval_seconds = 60, timeout_seconds = 75") out)
        (is (str/includes? out "tab_bar_position = \"bottom\"") out)
        (testing "the stanza command itself prints the status line"
          (let [cmd (second (re-find #"command = \"(.*)\", interval" out))
                status (run! env "/bin/sh" "-c" cmd)]
            (is (zero? (:exit status)) (:err status))
            (is (str/includes? (:out status) "anthropic 61%s") (:out status))
            (is (str/includes? (:out status) "openai-codex 42%s") (:out status))))))))

(deftest pane-prints-text-usage-once
  (with-env
    (fn [env]
      (let [{:keys [exit out err]} (run! (assoc env "QUOTA_PANE_ONCE" "1") "bash" "quota-pane.sh")]
        (is (zero? exit) err)
        (is (str/includes? out "Subscription quota") out)
        (is (str/includes? out "anthropic -- fresh; all_models 61% remaining") out)))))

(deftest entrypoints-refuse-without-herdr-plugin-root
  (with-env
    (fn [env]
      (doseq [script ["quota-stanza.sh" "quota-pane.sh"]]
        (let [{:keys [exit err]} (run! (dissoc env "HERDR_PLUGIN_ROOT") "bash" script)]
          (is (= 1 exit) script)
          (is (str/includes? err "HERDR_PLUGIN_ROOT") err))))))

(deftest plugin-files-hold-no-checkout-path
  (let [files (map str (fs/list-dir plugin))]
    (is (seq files) "positive control: the plugin directory has files")
    (doseq [f files]
      (is (not (re-find #"/Users/|/home/|dotfiles" (slurp f))) f))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
