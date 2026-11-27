#!/usr/bin/env bb
;; Fixture tests for sessions.bb: a temporary git repository with a linked
;; worktree and a temporary pi sessions directory. Never reads ~/.pi.

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing run-tests]])

(def script (str (fs/path (fs/parent (fs/absolutize *file*)) "sessions.bb")))

(defn sh! [dir & args]
  (let [p @(process/process (vec args) {:dir (str dir) :out :string :err :string})]
    (assert (zero? (:exit p)) (:err p))
    (:out p)))

(defn session-file! [sdir dirname id ts cwd & {:keys [version body] :or {version 3}}]
  (let [f (fs/path sdir dirname (str ts "_" id ".jsonl"))]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (str (json/generate-string {:type "session" :version version :id id :timestamp ts :cwd cwd}) "\n"
                       (or body "")))
    (str f)))

(defn fixture!
  "A repo at <tmp>/agents with a managed worktree and a linked one outside it, and an empty sessions dir."
  []
  (let [tmp (str (fs/real-path (fs/create-temp-dir {:prefix "sessions-test-"})))
        repo (str (fs/path tmp "agents"))
        wt (str (fs/path repo ".tmp" "herdr-orch" "worktrees" "abc"))
        ext (str (fs/path tmp "linked-elsewhere"))]
    (fs/create-dirs repo)
    (sh! repo "git" "init" "-q")
    (sh! repo "git" "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-q" "--allow-empty" "-m" "init")
    (fs/create-dirs (fs/parent wt))
    (sh! repo "git" "worktree" "add" "-q" "-b" "wt" wt)
    (sh! repo "git" "worktree" "add" "-q" "-b" "wt2" ext)
    {:tmp tmp :repo repo :wt wt :ext ext :sdir (str (fs/path tmp "sessions"))}))

(defn run [{:keys [repo sdir]} cmd & args]
  (let [p @(process/process (into ["bb" script cmd "--root" repo "--sessions-dir" sdir] args)
                            {:out :string :err :string})]
    (assoc p :json (when (zero? (:exit p)) (json/parse-string (:out p) true)))))

(defn ids [r] (set (map :id (get-in r [:json :sessions]))))

(deftest prefix-sibling-excluded
  (let [{:keys [tmp repo sdir] :as fx} (fixture!)]
    (session-file! sdir "--a--" "in" "2026-11-20T10:00:00.000Z" repo)
    (session-file! sdir "--b--" "sibling" "2026-11-20T10:00:00.000Z" (str tmp "/agents-other"))
    (session-file! sdir "--c--" "subdir" "2026-11-20T10:00:00.000Z" (str repo "/skills"))
    (let [r (run fx "list")]
      (is (zero? (:exit r)) (:err r))
      (is (= #{"in" "subdir"} (ids r)))
      (is (= 3 (get-in r [:json :scanned]))))))

(deftest managed-worktree-included
  (let [{:keys [wt ext sdir] :as fx} (fixture!)]
    (session-file! sdir "--a--" "managed" "2026-11-20T10:00:00.000Z" wt)
    (session-file! sdir "--c--" "linked" "2026-11-20T10:00:00.000Z" (str ext "/src"))
    (session-file! sdir "--b--" "elsewhere" "2026-11-20T10:00:00.000Z" "/nowhere/else")
    (let [r (run fx "list")]
      (is (zero? (:exit r)) (:err r))
      (is (= #{"managed" "linked"} (ids r))))))

(deftest empty-window-control
  (let [{:keys [repo sdir] :as fx} (fixture!)]
    (session-file! sdir "--a--" "old" "2026-11-01T10:00:00.000Z" repo)
    (session-file! sdir "--a--" "old2" "2026-11-02T10:00:00.000Z" repo)
    (testing "control: the unwindowed list finds the sessions"
      (is (= #{"old" "old2"} (ids (run fx "list")))))
    (let [r (run fx "list" "--since" "2026-12-01")]
      (is (zero? (:exit r)) (:err r))
      (is (empty? (get-in r [:json :sessions])))
      (is (= 2 (get-in r [:json :scanned]))))))

(deftest unknown-version-fails
  (let [{:keys [repo sdir] :as fx} (fixture!)]
    (session-file! sdir "--a--" "ok" "2026-11-20T10:00:00.000Z" repo)
    (is (zero? (:exit (run fx "list"))) "control: version 3 is accepted")
    (session-file! sdir "--a--" "future" "2026-11-21T10:00:00.000Z" repo :version 4)
    (let [r (run fx "list")]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "unsupported-session-version")))))

(defn write-run! [fx date]
  (spit (str (fs/path (:repo fx) "RETROLOG.org"))
        (str "#+TITLE: Retro evidence log\n\n* " date " -- wide retro\n\n** Wide retro\n:PROPERTIES:\n:KIND: wide-run\n:WINDOW: 2026-11-01..2026-11-10\n:END:\n")))

(deftest not-due-recent-run
  (let [{:keys [repo sdir] :as fx} (fixture!)]
    (session-file! sdir "--a--" "s" "2026-11-26T10:00:00.000Z" repo)
    (testing "control: with no run entry the same sessions make it due"
      (is (true? (get-in (run fx "due" "--today" "2026-11-27") [:json :due]))))
    (write-run! fx "2026-11-24 Tue")
    (let [r (run fx "due" "--today" "2026-11-27")]
      (is (zero? (:exit r)) (:err r))
      (is (false? (get-in r [:json :due])))
      (is (= "2026-11-24" (get-in r [:json :last_wide_run])))
      (is (= 1 (get-in r [:json :sessions_since]))))))

(deftest marker-in-other-checkout-counts
  (let [{:keys [repo wt sdir] :as fx} (fixture!)]
    (session-file! sdir "--a--" "s" "2026-11-26T10:00:00.000Z" repo)
    (write-run! (assoc fx :repo repo) "2026-11-24 Tue")
    (let [r (run (assoc fx :repo wt) "due" "--today" "2026-11-27")]
      (is (zero? (:exit r)) (:err r))
      (is (false? (get-in r [:json :due])))
      (is (= "2026-11-24" (get-in r [:json :last_wide_run]))))))

(deftest exclude-id-skips-calling-session
  (let [{:keys [repo sdir] :as fx} (fixture!)]
    (session-file! sdir "--a--" "me" "2026-11-26T10:00:00.000Z" repo)
    (is (true? (get-in (run fx "due" "--today" "2026-11-27") [:json :due])) "control")
    (let [r (run fx "due" "--today" "2026-11-27" "--exclude-id" "me")]
      (is (false? (get-in r [:json :due])))
      (is (= 0 (get-in r [:json :sessions_since]))))))

(deftest indented-kind-line-counts
  (let [{:keys [repo sdir] :as fx} (fixture!)]
    (session-file! sdir "--a--" "s" "2026-11-26T10:00:00.000Z" repo)
    (spit (str (fs/path repo "RETROLOG.org"))
          "* 2026-11-24 Tue -- wide retro\n** Wide retro\n  :PROPERTIES:\n  :KIND: wide-run\n  :END:\n")
    (is (= "2026-11-24" (get-in (run fx "due" "--today" "2026-11-27") [:json :last_wide_run])))))

(deftest not-due-no-sessions
  (let [{:keys [repo sdir] :as fx} (fixture!)]
    (session-file! sdir "--a--" "outside" "2026-11-26T10:00:00.000Z" "/nowhere/else")
    (session-file! sdir "--a--" "stale" "2026-10-01T10:00:00.000Z" repo)
    (let [r (run fx "due" "--today" "2026-11-27")]
      (is (zero? (:exit r)) (:err r))
      (is (false? (get-in r [:json :due])))
      (is (= 0 (get-in r [:json :sessions_since])))
      (is (= 2 (get-in r [:json :scanned]))))))

(deftest due-after-seven-days
  (let [{:keys [repo sdir] :as fx} (fixture!)]
    (session-file! sdir "--a--" "s" "2026-11-26T10:00:00.000Z" repo)
    (write-run! fx "2026-11-20 Fri")
    (testing "six days after the run: not due"
      (is (false? (get-in (run fx "due" "--today" "2026-11-26") [:json :due]))))
    (testing "seven days after the run: due"
      (let [r (run fx "due" "--today" "2026-11-27")]
        (is (true? (get-in r [:json :due])))
        (is (= "2026-11-20" (get-in r [:json :last_wide_run])))))))

(deftest extract-writes-only-under-repo-tmp
  (let [{:keys [repo sdir] :as fx} (fixture!)
        body (str (json/generate-string {:type "message" :timestamp "2026-11-20T10:00:01.000Z" :message {:role "user" :content [{:type "text" :text "hello"}]}}) "\n"
                  (json/generate-string {:type "message" :timestamp "2026-11-20T10:00:02.000Z" :message {:role "toolResult" :toolName "bash" :isError true :content [{:type "text" :text "boom"}]}}) "\n")]
    (session-file! sdir "--a--" "s1" "2026-11-20T10:00:00.000Z" repo :body body)
    (let [r (run fx "extract" "--run" "t1")
          f (get-in r [:json :files 0 :file])]
      (is (zero? (:exit r)) (:err r))
      (is (str/starts-with? f (str repo "/.tmp/retro/t1/")))
      (is (str/includes? (slurp f) "USER hello"))
      (is (str/includes? (slurp f) "FAIL bash boom")))
    (is (= 1 (:exit (run fx "extract" "--run" "../escape"))))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
