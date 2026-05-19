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
            [org-tasks.output :as out]))

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
    (binding [out/*exit-fn* (fn [code]
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

(deftest no-args-shows-summary
  (testing "ot (no args) prints the command summary"
    (let [{:keys [out exit]} (capture #(apply cli/-main []))]
      (is (zero? exit))
      (is (str/includes? out "Commands:")))))

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
