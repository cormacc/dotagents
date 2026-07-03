(ns org-tasks.parser-test
  "Parser/serializer tests for the `ot` Clojure port.

  Mirrors `pi/extensions/tasks/parser.test.ts` and adds byte-identical
  round-trip checks against the fixtures under
  `skills/org-tasks/scripts/test/fixtures/round-trip/`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.parser :as p]))

(def ^:private fixture-root
  "skills/org-tasks/scripts/test/fixtures/round-trip")

(defn- fixture [path]
  (slurp (str fixture-root "/" path)))

;; ── Heading + tag parsing ─────────────────────────────────────────

(deftest heading-shape
  (testing "status / priority / summary / tags parsed from a TODO heading"
    (let [{:keys [tasks]} (p/parse-tasks "* TODO [#A] Implement auth :backend:security:\n")]
      (is (= 1 (count tasks)))
      (let [t (first tasks)]
        (is (= 1     (:level t)))
        (is (= "TODO" (:status t)))
        (is (= "A"   (:priority t)))
        (is (= "Implement auth" (:summary t)))
        (is (= ["backend" "security"] (:tags t))))))

  (testing "heading without priority or tags"
    (let [{:keys [tasks]} (p/parse-tasks "** DONE Some task\n")]
      (let [t (first tasks)]
        (is (= 2     (:level t)))
        (is (= "DONE" (:status t)))
        (is (nil?    (:priority t)))
        (is (= "Some task" (:summary t)))
        (is (= [] (:tags t)))))))

;; ── CLOSED + properties + LOGBOOK ─────────────────────────────────

(deftest closed-above-drawer
  (let [{:keys [tasks]} (p/parse-tasks (fixture "closed-above-drawer.org"))
        t (first tasks)]
    (is (= "DONE" (:status t)))
    (is (= "B"    (:priority t)))
    (is (= "2026-04-25 Sat 12:00" (:closed t)))
    (is (= [":CUSTOM_ID: 11111111-2222-4333-8444-555555555555"
            ":CREATED: [2026-04-24 Fri 09:15]"]
           (:property-lines t)))
    (is (= "Some description." (:description t)))))

(deftest closed-below-drawer-parses
  (testing "CLOSED: tolerated after :END:; serializer always emits above"
    (let [input "* DONE Below\n:PROPERTIES:\n:CUSTOM_ID: x\n:END:\nCLOSED: [2026-04-25 Sat 12:00]\n"
          t (first (:tasks (p/parse-tasks input)))]
      (is (= "2026-04-25 Sat 12:00" (:closed t))))))

(deftest logbook-drawer-parses-and-roundtrips
  (let [{:keys [tasks]} (p/parse-tasks (fixture "logbook-lifecycle.org"))
        t (first tasks)]
    (is (= "STARTED" (:status t)))
    (is (= ["- Created [2026-04-28 Tue 10:49]"
            "- State \"STARTED\" from \"TODO\" [2026-04-28 Tue 11:00]"]
           (:logbook-lines t)))
    (is (= "Body text." (:description t)))))

(deftest unterminated-properties-drawer-throws-at-eof
  (testing "a :PROPERTIES: drawer never closed before end-of-file surfaces a parse error"
    (let [input "* TODO A\n:PROPERTIES:\n:CUSTOM_ID: x\n"
          e (try (p/parse-tasks input {:source-path "/repo/TASKS.org"}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= :unterminated-drawer (:code (ex-data e))))
      (is (= "/repo/TASKS.org" (:file (ex-data e)))))))

(deftest unterminated-logbook-drawer-mid-file-throws
  (testing "a :LOGBOOK: drawer left open with more content after it also surfaces"
    (let [input (str "* TODO A\n:LOGBOOK:\n- Created [2026-04-28 Tue 10:49]\n"
                     "* TODO B\nNo drawer here.\n")
          e (try (p/parse-tasks input) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= :unterminated-drawer (:code (ex-data e)))))))

;; ── #+IMPORT keyword forms ────────────────────────────────────────

(deftest import-link-forms
  (let [{:keys [tasks]} (p/parse-tasks (fixture "import-link-forms.org"))]
    (is (= 3 (count tasks)))

    (testing "bare path import"
      (let [t (nth tasks 0)]
        (is (= "design/log/bare.org" (:import-path t)))
        (is (nil? (:import-raw t)))))

    (testing "file: link import preserves raw form"
      (let [t (nth tasks 1)]
        (is (= "design/log/file-link.org" (:import-path t)))
        (is (= "[[file:design/log/file-link.org][Plan]]" (:import-raw t)))))

    (testing "plan: typed link preserves raw form"
      (let [t (nth tasks 2)]
        (is (= "plan:typed.org" (:import-path t)))
        (is (= "[[plan:typed.org]]" (:import-raw t)))))))

(deftest file-level-imports
  (let [{:keys [tasks file-imports]}
        (p/parse-tasks "#+IMPORT: design/log/root.org\n\n* TODO A\n")]
    (is (= ["design/log/root.org"] file-imports))
    (is (= "A" (:summary (first tasks))))
    (is (nil? (:import-path (first tasks))))))

;; ── Unknown keywords and drawer properties round-trip ─────────────

(deftest unknown-properties-preserved
  (let [{:keys [tasks]} (p/parse-tasks (fixture "unknown-keywords-and-properties.org"))
        t (first tasks)]
    (is (= "Top-level" (:summary t)))
    (is (= [":CUSTOM_ID: aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
            ":FOO_BAZ: alpha beta"
            ":NS_ZIM: gamma"]
           (:property-lines t)))))

;; ── Linked issues ─────────────────────────────────────────────────

(deftest linked-issues-mixed
  (let [content (fixture "linked-issues-mixed.org")
        {:keys [tasks]} (p/parse-tasks content)
        t (first tasks)
        issues (p/get-linked-issues t content)]
    (is (= 2 (count issues)))
    (is (= {:url "https://your-org.atlassian.net/browse/MBFW-123"
            :label "MBFW-123"
            :raw-token "[[jira:MBFW-123]]"}
           (first issues)))
    (is (= {:url "https://x/y"
            :label "y"
            :raw-token "[[https://x/y][y]]"}
           (second issues)))))

(deftest linked-issues-missing-template
  (let [{:keys [tasks]}
        (p/parse-tasks
          (str "* TODO Bare\n"
               ":PROPERTIES:\n:CUSTOM_ID: x\n"
               ":LINKED_ISSUES: [[jira:MBFW-123]]\n:END:\n"))
        issues (p/get-linked-issues (first tasks) "")]
    (is (= 1 (count issues)))
    (is (nil? (:url (first issues))))
    (is (= "Missing #+LINK declaration for prefix jira"
           (:error (first issues))))))

;; ── Link templates + expansion ────────────────────────────────────

(deftest link-template-parse
  (let [t (p/parse-link-templates
            "#+LINK: jira https://example.atlassian.net/browse/%s\n#+LINK: gh https://github.com/%s\n")]
    (is (= "https://example.atlassian.net/browse/%s" (get t "jira")))
    (is (= "https://github.com/%s" (get t "gh")))))

(deftest expand-org-link-target
  (let [content (str "#+LINK: jira https://example.atlassian.net/browse/%s\n"
                     "#+LINK: plan file:design/log/%s\n")]
    (testing "file: template marks the result as project-root relative"
      (let [r (p/expand-org-link-target "plan:2026-05-13-foo.org" content)]
        (is (= "design/log/2026-05-13-foo.org" (:target r)))
        (is (true? (:from-project-root r)))))
    (testing "file: template keeps `/` literal under nested keys"
      (let [r (p/expand-org-link-target "plan:subdir/foo.org" content)]
        (is (= "design/log/subdir/foo.org" (:target r)))))
    (testing "URL template URL-encodes the key"
      (let [r (p/expand-org-link-target "jira:PROJ 1" content)]
        (is (= "https://example.atlassian.net/browse/PROJ+1" (:target r)))
        (is (false? (:from-project-root r)))))
    (testing "file: typed target passes through verbatim"
      (let [r (p/expand-org-link-target "file:design/log/explicit.org" content)]
        (is (= "file:design/log/explicit.org" (:target r)))
        (is (false? (:from-project-root r)))))
    (testing "unknown prefix returns the original target"
      (let [r (p/expand-org-link-target "slack:C1234" content)]
        (is (= "slack:C1234" (:target r)))))
    (testing "plain path passes through"
      (let [r (p/expand-org-link-target "design/log/legacy.org" content)]
        (is (= "design/log/legacy.org" (:target r)))))))

;; ── Selected keyword ──────────────────────────────────────────────

(deftest parse-selected-keyword
  (is (= "abc-123" (p/parse-selected-keyword "#+SELECTED: abc-123\n")))
  (is (nil? (p/parse-selected-keyword "#+SELECTED:\n")))
  (is (nil? (p/parse-selected-keyword "no keyword here\n"))))

(deftest plan-parent-link
  (let [content "#+PARENT: [[task:80ea589b-501c-42d9-86e7-4d414c0c314e][Parent]]\n"]
    (is (= "80ea589b-501c-42d9-86e7-4d414c0c314e"
           (p/get-plan-parent-id content)))
    (is (= {:kind :task
            :uuid "80ea589b-501c-42d9-86e7-4d414c0c314e"
            :summary "Parent"}
           (p/get-plan-parent-ref content)))
    (is (nil? (p/get-plan-parent-id
                "#+PARENT: [[file:../../TASKS.org::#80ea589b][Parent]]\n")))))

(deftest rewrite-parent-link-kind
  (let [content (str "#+PARENT: [[task:80ea589b-501c-42d9-86e7-4d414c0c314e][Parent]]\n"
                     "[[task:other][Other]]\n")
        rewritten (p/rewrite-parent-link-kind
                    content "80ea589b-501c-42d9-86e7-4d414c0c314e" :archive)]
    (is (str/includes? rewritten
          "#+PARENT: [[archive:80ea589b-501c-42d9-86e7-4d414c0c314e][Parent]]"))
    (testing "non-#+PARENT lines referencing the same task: kind not rewritten"
      (is (str/includes? rewritten "[[task:other][Other]]")))))

;; ── Drawer property helpers ───────────────────────────────────────

(deftest drawer-property-get-set
  (let [t (first (:tasks (p/parse-tasks
                           (str "* TODO Subject\n"
                                ":PROPERTIES:\n"
                                ":CUSTOM_ID: 11111111-2222-4333-8444-555555555555\n"
                                ":FOO_BAZ: original\n"
                                ":END:\n"))))]
    (is (= "original" (p/get-drawer-property t "FOO_BAZ")))
    (is (= "original" (p/get-drawer-property t "foo_baz")))
    (is (nil? (p/get-drawer-property t "MISSING")))

    (let [updated (p/set-drawer-property t "FOO_BAZ" "updated")]
      (is (= "updated" (p/get-drawer-property updated "FOO_BAZ"))))

    (let [added (p/set-drawer-property t "NS_NEW" "hello")]
      (is (= "hello" (p/get-drawer-property added "NS_NEW"))))

    (let [removed (p/set-drawer-property t "FOO_BAZ" nil)]
      (is (nil? (p/get-drawer-property removed "FOO_BAZ"))))))

(deftest multi-valued-blocked-by
  (let [t (first (:tasks (p/parse-tasks
                           (str "* TODO Multi\n"
                                ":PROPERTIES:\n"
                                ":CUSTOM_ID: x\n"
                                ":BLOCKED-BY: task:dep-1\n"
                                ":BLOCKED-BY+: url:https://x\n"
                                ":BLOCKED-BY+: human: waiting\n"
                                ":END:\n"))))
        blockers (p/get-task-blockers t)]
    (is (= 3 (count blockers)))
    (is (= :task  (-> blockers (nth 0) :kind)))
    (is (= :url   (-> blockers (nth 1) :kind)))
    (is (= :human (-> blockers (nth 2) :kind)))))

(deftest task-handoff
  (let [t (first (:tasks (p/parse-tasks
                           (str "* TODO With handoff\n"
                                ":PROPERTIES:\n:CUSTOM_ID: x\n"
                                ":HANDOFF: Start at the parser delimiter.\n"
                                ":END:\n"))))]
    (is (= "Start at the parser delimiter." (p/get-task-handoff t)))
    (let [cleared (p/set-task-handoff t nil)]
      (is (nil? (p/get-task-handoff cleared))))))

;; ── Byte-level round-trip ─────────────────────────────────────────

(defn- round-trip-equal?
  "True when `(serialize-preserving-file content (parse-tasks content))`
  produces byte-identical output."
  [content]
  (let [parsed (:tasks (p/parse-tasks content))
        out    (p/serialize-tasks-preserving-file content parsed)]
    (= content out)))

(deftest fixture-round-trip
  (doseq [path ["closed-above-drawer.org"
                "logbook-lifecycle.org"
                "linked-issues-mixed.org"
                "unknown-keywords-and-properties.org"
                "import-link-forms.org"
                "setupfile-chain/TASKS.org"]]
    (testing path
      (let [content (fixture path)]
        (is (round-trip-equal? content)
            (str "Round-trip mismatch for " path))))))

;; ── Serialize-tasks (no original content) ─────────────────────────

(deftest serialize-tasks-emits-blank-between-top-level
  (let [tasks [{:level 1 :status "TODO" :priority nil :summary "First"
                :tags [] :description "" :children []
                :property-lines [":CUSTOM_ID: x"] :logbook-lines []
                :import-path nil :import-raw nil :closed nil}
               {:level 1 :status "DONE" :priority nil :summary "Second"
                :tags [] :description "" :children []
                :property-lines [":CUSTOM_ID: y"] :logbook-lines []
                :import-path nil :import-raw nil :closed nil}]
        out (p/serialize-tasks tasks)]
    (is (str/includes? out "* TODO First\n:PROPERTIES:\n:CUSTOM_ID: x\n:END:\n\n* DONE Second"))))

(deftest closed-line-emits-above-drawer
  (let [input "* DONE Below\n:PROPERTIES:\n:CUSTOM_ID: x\n:END:\nCLOSED: [2026-04-25 Sat 12:00]\n"
        tasks (:tasks (p/parse-tasks input))
        out   (p/serialize-tasks tasks)]
    (is (str/includes? out
          "* DONE Below\nCLOSED: [2026-04-25 Sat 12:00]\n:PROPERTIES:\n:CUSTOM_ID: x\n:END:\n"))
    (is (= 1 (count (re-seq #"(?m)^CLOSED:" out))))))
