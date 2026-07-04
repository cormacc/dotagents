(ns org-tasks.commands.test-util
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org-tasks.cli :as cli]
            [org-tasks.output :as out]
            [org-tasks.parser :as parser]
            [org-tasks.styling :as styling]))


(defn with-temp-dir [f]
  ;; Canonicalise the temp root so tests compare against the same realpath
  ;; the CLI emits. On macOS `fs/create-temp-dir` lives under /var, which is a
  ;; symlink to /private/var; the path layer realpaths its output, so a raw
  ;; root would never string-match the emitted paths.
  (let [raw (fs/create-temp-dir {:prefix "ot-cmd-"})
        dir (str (fs/real-path raw))]
    (try (f dir)
         (finally (fs/delete-tree dir)))))

(defn capture
  "Run `body-fn` while capturing stdout, stderr, and the intended exit
  code. The mock `*exit-fn*` throws a sentinel after recording so
  short-circuit semantics mirror real `System/exit`."
  [body-fn]
  (let [exit (atom nil)
        out  (java.io.StringWriter.)
        err  (java.io.StringWriter.)]
    (binding [out/*exit-fn* (fn [code & _]
                              (reset! exit code)
                              (throw (ex-info "ot-exit" {:tag :ot/exit :code code})))
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

(defn run-cli! [& args]
  (capture #(apply cli/-main args)))

(defn parse-json-result [out-str]
  (-> out-str json/parse-string clojure.walk/keywordize-keys :result))

(defn parse-json-error [err-str]
  (-> err-str json/parse-string clojure.walk/keywordize-keys :error))

(def tasks-org-preamble
  ;; Mirrors `commands/tasks-org-default` so doctor sees the full
  ;; protocol preamble during integration tests.
  (str "#+TITLE: Project Tasks\n"
       "#+LINK: task file:TASKS.org::#%s\n"
       "#+LINK: archive file:TASKS.archive.org::#%s\n"
       "#+LINK: plan file:design/log/%s\n"
       "#+LINK: proj file:%s\n"
       "#+SETUPFILE: ./TASKS.local.org\n"
       "#+SETUPFILE: ./TASKS.setup.org\n"
       "#+ARCHIVE: TASKS.archive.org::* From %s\n"
       "\n"))

(def setup-org-preamble
  (str "#+TODO: TODO(t) STARTED(s!) WAITING(w@/!) | DONE(d!) CANCELLED(c!)\n"
       "#+STARTUP: logdone logdrawer\n"
       "#+LINK: proj file:../../%s\n"
       "#+LINK: task file:../../TASKS.org::#%s\n"
       "#+LINK: archive file:../../TASKS.archive.org::#%s\n"))

(defn bootstrap-graph! [root]
  (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
  (spit (str (fs/path root "TASKS.org"))
        (str tasks-org-preamble
             "* Improvements\n"
             "** TODO [#A] First :backend:\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: 11111111-2222-4333-8444-555555555551\n"
             ":END:\n"
             "** STARTED Second\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: 22229999-2222-4333-8444-555555555552\n"
             ":STARTED: [2026-05-01 Fri 09:00]\n"
             ":END:\n"))
  (spit (str (fs/path root "TASKS.local.org"))
        (str "#+SELECTED: 22229999-2222-4333-8444-555555555552\n")))

(def linked-plan-parent-id
  "aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa")

(def linked-plan-child-id
  "bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb")

(def linked-plan-second-child-id
  "cccccccc-3333-4333-8333-cccccccccccc")

(defn linked-plan-content []
  (str "#+TITLE: Linked Plan\n"
       "#+SETUPFILE: ../../TASKS.setup.org\n"
       "\n"
       "* Summary\n"
       "Fixture for linked plan mutation tests.\n"
       "\n"
       "* Plan\n"
       "** TODO Plan child\n"
       ":PROPERTIES:\n"
       ":CUSTOM_ID: " linked-plan-child-id "\n"
       ":END:\n"
       "Acceptance criteria:\n"
       "- mutate this task from ot.\n"
       "\n"
       "** TODO Second plan child\n"
       ":PROPERTIES:\n"
       ":CUSTOM_ID: " linked-plan-second-child-id "\n"
       ":END:\n"
       "\n"
       "** TODO Hand-authored child without metadata\n"
       "Needs backfill.\n"))

(defn bootstrap-linked-plan-graph! [root]
  (fs/create-dirs (str (fs/path root "design" "log")))
  (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
  (spit (str (fs/path root "TASKS.org"))
        (str tasks-org-preamble
             "* Improvements\n"
             "** TODO Parent with linked plan\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: " linked-plan-parent-id "\n"
             ":END:\n"
             "#+IMPORT: [[plan:linked-plan.org]]\n"))
  (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
  (spit (str (fs/path root "design" "log" "linked-plan.org"))
        (linked-plan-content)))

(defn bootstrap-parent-child-graph! [root]
  (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
  (spit (str (fs/path root "TASKS.org"))
        (str tasks-org-preamble
             "* Improvements\n"
             "** TODO Parent A\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: aaaa1111-2222-4333-8444-555555555551\n"
             ":END:\n"
             "*** TODO Child A1\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: aaaa2222-2222-4333-8444-555555555552\n"
             ":END:\n"
             "*** TODO Child A2\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: aaaa3333-2222-4333-8444-555555555553\n"
             ":END:\n"
             "** TODO Parent B\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: bbbb1111-2222-4333-8444-555555555554\n"
             ":END:\n"))
  (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n"))

(defn bootstrap-three-level-graph! [root]
  (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
  (spit (str (fs/path root "TASKS.org"))
        (str tasks-org-preamble
             "* Improvements\n"
             "** TODO Parent A\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: aaaa1111-2222-4333-8444-555555555551\n"
             ":END:\n"
             "*** TODO Child A1\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: aaaa2222-2222-4333-8444-555555555552\n"
             ":END:\n"
             "**** TODO Grandchild A1a\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: aaaa3333-2222-4333-8444-555555555553\n"
             ":END:\n"
             "** TODO Parent B\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: bbbb1111-2222-4333-8444-555555555554\n"
             ":END:\n"))
  (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n"))

(defn bootstrap-local! [root id summary]
  (spit (str (fs/path root "TASKS.org")) "* Improvements\n")
  (spit (str (fs/path root "TASKS.local.org"))
        (str "#+SELECTED:\n\n"
             "* Drafts\n"
             "** TODO " summary "\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: " id "\n"
             ":END:\n")))
