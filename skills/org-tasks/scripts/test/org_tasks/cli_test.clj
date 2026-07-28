(ns org-tasks.cli-test
  "Smoke tests for the `ot` CLI entry point.

  Validates that the dispatch table and envelope plumbing work before
  any command implementations exist. Deeper protocol tests live in the
  per-domain `*-test` namespaces listed in
  `skills/org-tasks/scripts/docs/test-map.md`."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.cli :as cli]
            [org-tasks.commands.registry :as registry]
            [org-tasks.output :as out]))

(defn- with-user-dir [dir f]
  (let [prev (System/getProperty "user.dir")]
    (try
      (System/setProperty "user.dir" (str dir))
      (f)
      (finally
        (System/setProperty "user.dir" prev)))))

(defn- capture
  "Run `body-fn` while capturing stdout, stderr, and the intended exit
  code. Returns `{:out, :err, :exit}`. The exit code defaults to 0 when
  the body never invokes `*exit-fn*`.

  The mock `*exit-fn*` throws an `:ot/exit` sentinel after recording the
  code so callers short-circuit in the same place a real `System/exit`
  would. The helper swallows the sentinel."
  [body-fn]
  (let [exit (atom nil)
        out  (java.io.StringWriter.)
        err  (java.io.StringWriter.)]
    (binding [out/*exit-fn* (fn [code & _]
                              (reset! exit code)
                              (throw (ex-info "ot-exit" {:tag :ot/exit
                                                          :code code})))
              *out* out
              *err* err]
      (try
        (body-fn)
        (catch clojure.lang.ExceptionInfo e
          (when-not (= :ot/exit (:tag (ex-data e)))
            (throw e)))))
    {:out  (str out)
     :err  (str err)
     :exit (or @exit 0)}))

(deftest help-flag-shows-summary
  (testing "ot --help prints the command summary and exits 0"
    (let [{:keys [out exit]} (capture #(apply cli/-main ["--help"]))]
      (is (zero? exit))
      (is (str/includes? out "Usage: ot"))
      (is (str/includes? out "Commands:"))
      (is (str/includes? out "list"))
      (is (str/includes? out "doctor")))))

(deftest command-help-shows-command-options
  (testing "ot list --help prints list-specific options plus globals"
    (let [{:keys [out exit]} (capture #(apply cli/-main ["list" "--help"]))]
      (is (zero? exit))
      (is (str/includes? out "ot list"))
      (is (str/includes? out "--status-filter"))
      (is (str/includes? out "--levels"))
      (is (str/includes? out "--format"))))
  (testing "ot record create --help prints subcommand options"
    (let [{:keys [out exit]} (capture #(apply cli/-main ["record" "create" "--help"]))]
      (is (zero? exit))
      (is (str/includes? out "ot record create"))
      (is (str/includes? out "--mode"))
      (is (str/includes? out "--path"))))
  (testing "ot help list routes to command help too"
    (let [{:keys [out exit]} (capture #(apply cli/-main ["help" "list"]))]
      (is (zero? exit))
      (is (str/includes? out "--levels"))))
  (testing "commands without options still show globals"
    (let [{:keys [out exit]} (capture #(apply cli/-main ["doctor" "--help"]))]
      (is (zero? exit))
      (is (str/includes? out "ot doctor"))
      (is (not (str/includes? out "Commands:")))
      (is (str/includes? out "Global options:")))))

(deftest bare-non-tty-emits-selected-json
  (testing "ot (no args) emits selected-task JSON when no interactive terminal is available"
    ;; The bare path takes no --root, so it resolves task memory from the
    ;; working directory. Run against a scaffolded temp root so the result
    ;; cannot depend on the enclosing repo's (gitignored, developer-local)
    ;; selection state. The envelope is a discriminated shape: `:selected nil`
    ;; without a selection, a `:task` map with one.
    (let [dir (str (fs/create-temp-dir {:prefix "ot-bare"}))
          uuid "11111111-1111-4111-8111-111111111111"]
      (try
        (spit (str (fs/path dir "TASKS.org"))
              (str "* TODO Root task\n:PROPERTIES:\n:CUSTOM_ID: " uuid "\n:END:\n"))
        (testing "no selection emits {:selected nil}"
          (let [{:keys [out exit]} (capture #(with-user-dir dir
                                               (fn [] (apply cli/-main []))))
                envelope (json/parse-string out true)]
            (is (zero? exit))
            (is (= true (:ok envelope)))
            (is (= "org-tasks/v1" (:schema envelope)))
            (is (contains? (:result envelope) :selected))
            (is (nil? (get-in envelope [:result :selected])))))
        (testing "a selection emits the selected-task envelope"
          (spit (str (fs/path dir "TASKS.local.org"))
                (str "#+SELECTED: " uuid "\n"))
          (let [{:keys [out exit]} (capture #(with-user-dir dir
                                               (fn [] (apply cli/-main []))))
                envelope (json/parse-string out true)]
            (is (zero? exit))
            (is (= true (:ok envelope)))
            (is (= uuid (get-in envelope [:result :task :id])))))
        (finally (fs/delete-tree dir))))))

(deftest bare-format-json-emits-selected-json
  (testing "ot --format json emits selected-task JSON without launching the TUI"
    (let [dir (str (fs/create-temp-dir {:prefix "ot-bare-json"}))]
      (try
        (spit (str (fs/path dir "TASKS.org")) "* TODO Root task\n")
        (let [{:keys [out exit]} (capture #(with-user-dir dir
                                             (fn [] (apply cli/-main ["--format" "json"]))))
              envelope (json/parse-string out true)]
          (is (zero? exit))
          (is (= true (:ok envelope)))
          (is (= "org-tasks/v1" (:schema envelope))))
        (finally (fs/delete-tree dir))))))

(deftest unknown-command-falls-through-to-help
  (testing "an unrecognised command lands on the catch-all help row"
    (let [{:keys [out exit]} (capture #(apply cli/-main ["bogus"]))]
      ;; The default `{:cmds []}` row is the help command; it exits 0
      ;; rather than failing parse, matching upstream babashka.cli
      ;; semantics. Behaviour intentionally documented here so a future
      ;; tightening (e.g. emit an error on unknown commands) trips this
      ;; assertion.
      (is (zero? exit))
      (is (str/includes? out "Commands:")))))

(deftest list-json-includes-resolved-root
  (let [dir (str (fs/create-temp-dir {:prefix "ot-list-root"}))]
    (try
      (let [nested (fs/path dir "a" "b")]
        (fs/create-dirs nested)
        (spit (str (fs/path dir "TASKS.org")) "* TODO Root task\n:PROPERTIES:\n:CUSTOM_ID: 11111111-1111-4111-8111-111111111111\n:END:\n")
        (let [{:keys [out exit]} (capture #(with-user-dir (str nested)
                                             (fn [] (apply cli/-main ["--format" "json" "list"]))))
              envelope (json/parse-string out true)]
          (is (zero? exit))
          (is (= (str (fs/absolutize dir)) (get-in envelope [:result :root])))
          (is (= (str (fs/absolutize (fs/path dir "TASKS.org")))
                 (get-in envelope [:result :files :tasks])))))
      (finally (fs/delete-tree dir)))))

(deftest root-command-prints-resolved-root
  (let [dir (str (fs/create-temp-dir {:prefix "ot-root-cmd"}))]
    (try
      (let [nested (fs/path dir "a" "b")
            fallback (fs/path dir "fallback")]
        (fs/create-dirs nested)
        (fs/create-dirs fallback)
        (spit (str (fs/path dir "TASKS.org")) "* TODO Root task\n")
        (let [{:keys [out exit]} (capture #(with-user-dir (str nested)
                                             (fn [] (apply cli/-main ["root"]))))
              explicit (capture #(apply cli/-main ["--root" (str nested) "root"]))]
          (is (zero? exit))
          (is (= (str (fs/absolutize dir)) (str/trim out)))
          (is (= (str (fs/absolutize nested)) (str/trim (:out explicit)))))
        (fs/delete (fs/path dir "TASKS.org"))
        (let [{:keys [out exit]} (capture #(with-user-dir (str fallback)
                                             (fn [] (apply cli/-main ["root"]))))
              tasks-override (capture #(with-user-dir (str fallback)
                                         (fn [] (apply cli/-main ["--tasks" (str (fs/path dir "elsewhere.org")) "root"]))))]
          (is (zero? exit))
          (is (= (str (fs/absolutize fallback)) (str/trim out)))
          (is (= (str (fs/absolutize fallback)) (str/trim (:out tasks-override))))))
      (finally (fs/delete-tree dir)))))

(deftest id-arguments-remain-strings-at-dispatch
  (testing "the registry supplies string coercion for every ID-valued input"
    (is (= :string (:id registry/dispatch-coerce)))
    (doseq [entry registry/commands
            :when (some #{:id} (:args->opts entry))]
      (is (= :string (:id registry/dispatch-coerce)) (str (:cmds entry))))
    (let [create (some #(when (= ["create"] (:cmds %)) %) registry/commands)]
      (doseq [k [:id :parent :after :relative-to]]
        (is (= :string (get-in create [:spec k :coerce])) (name k)))))
  (testing "a scientific-notation-shaped prefix is not number-coerced"
    (let [dir (str (fs/create-temp-dir {:prefix "ot-id-string"}))
          id "1022e091-2222-4333-8444-555555555551"]
      (try
        (spit (str (fs/path dir "TASKS.org"))
              (str "* Improvements\n** TODO Target\n:PROPERTIES:\n:CUSTOM_ID: " id "\n:END:\n"))
        (spit (str (fs/path dir "TASKS.local.org")) "#+SELECTED:\n")
        (let [{:keys [out exit]} (capture #(apply cli/-main ["--root" dir "--format" "json" "status" "1022e091" "DONE"]))
              envelope (json/parse-string out true)]
          (is (zero? exit))
          (is (= id (get-in envelope [:result :task :id]))))
        (finally (fs/delete-tree dir))))))

(deftest contract-envelope-error-shape
  (testing "a structured failure renders the JSON contract envelope"
    (let [{:keys [err exit]} (capture #(apply cli/-main
                                              ["--format" "json" "show" "no-such-id"]))
          envelope (json/parse-string err true)]
      (is (= 1 exit))
      (is (= false (:ok envelope)))
      (is (= "org-tasks/v1" (:schema envelope)))
      (is (= "unknown-task" (get-in envelope [:error :code]))))))

(deftest contract-envelope-text-error-on-stderr
  (testing "default --format text writes the error line to stderr"
    (let [{:keys [out err exit]} (capture #(apply cli/-main ["show" "no-such-id"]))]
      (is (= 1 exit))
      (is (str/blank? out))
      (is (str/includes? err "unknown-task")))))

(deftest format-validates-allowed-values
  (testing "rejecting an unknown --format reports an argument-error envelope"
    (let [{:keys [err exit]} (capture #(apply cli/-main ["--format" "yaml" "doctor"]))]
      (is (= 2 exit))
      (is (str/includes? err "argument-error")))))

(deftest backfill-adds-id-and-created-metadata-to-hand-authored-task
  (testing "ot backfill repairs a manually inserted heading"
    (let [dir (str (fs/create-temp-dir {:prefix "ot-backfill"}))
          tasks-file (str dir "/TASKS.org")
          local-file (str dir "/TASKS.local.org")]
      (spit tasks-file
            (str "#+TITLE: Tasks\n"
                 "#+SETUPFILE: ./TASKS.local.org\n"
                 "\n"
                 "* Improvements\n"
                 "\n"
                 "** TODO [#B] Manually added task\n"
                 "Body text\n"))
      (spit local-file "#+SELECTED:\n")
      (let [{:keys [out exit]} (capture #(apply cli/-main
                                                ["--format" "json"
                                                 "--root" dir
                                                 "backfill"
                                                 "--created-at" "2026-05-19 Tue 12:34"]))
            envelope (json/parse-string out true)
            content (slurp tasks-file)
            id (get-in envelope [:result :changes 0 :id])]
        (is (zero? exit))
        (is (= 1 (get-in envelope [:result :changed])))
        (is (re-matches #"[0-9a-f-]{36}" id))
        (is (str/includes? content ":PROPERTIES:"))
        (is (str/includes? content (str ":CUSTOM_ID: " id)))
        (is (str/includes? content ":CREATED: [2026-05-19 Tue 12:34]"))
        (is (str/includes? content ":LOGBOOK:"))
        (is (str/includes? content "- Created [2026-05-19 Tue 12:34]")))
      (let [{:keys [out exit]} (capture #(apply cli/-main
                                                ["--format" "json"
                                                 "--root" dir
                                                 "backfill"]))
            envelope (json/parse-string out true)]
        (is (zero? exit))
        (is (= 0 (get-in envelope [:result :changed])))))))
