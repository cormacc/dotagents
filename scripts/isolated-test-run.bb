;; Run test suites in a per-run temporary root, then assert that the run left no git
;; worktree or orch/* branch in this repository. The root bb.edn `test` task loads this
;; file and calls `run-suites`.
(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.set :as set]
         '[clojure.string :as str])

(def keep-env
  "Set to a non-blank value to keep the per-run temporary root for debugging."
  "DOTAGENTS_KEEP_TEST_TMP")

(defn- git-lines [root & argv]
  (let [{:keys [exit out err]} @(process/process (into ["git"] argv) {:dir root :out :string :err :string})]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " argv) " failed: " (str/trim err)) {:argv argv :exit exit})))
    (remove str/blank? (str/split-lines out))))

(defn git-state
  "Registered worktree paths and orch/* branch refs of the repository at `root`."
  [root]
  {:worktrees (->> (git-lines root "worktree" "list" "--porcelain")
                   (keep #(second (re-matches #"worktree (.*)" %)))
                   set)
   :branches (set (git-lines root "for-each-ref" "--format=%(refname)" "refs/heads/orch/"))})

(defn state-changes
  "Entries added to or removed from each git-state key between `before` and `after`, or nil."
  [before after]
  (not-empty
   (into {}
         (for [k [:worktrees :branches]
               :let [added (set/difference (k after) (k before))
                     removed (set/difference (k before) (k after))]
               :when (or (seq added) (seq removed))]
           [k (cond-> {} (seq added) (assoc :added (sort added)) (seq removed) (assoc :removed (sort removed)))]))))

(defn- report-changes! [changes]
  (binding [*out* *err*]
    (println "\nFAIL: the test run changed this repository's git worktrees or orch/* branches.")
    (println "A test probably ran `oh` with this checkout as its working directory. Run each fixture subprocess from a disposable git repository.")
    (println "(A real orchestration that started during the run can also cause this failure.)")
    (doseq [[k {:keys [added removed]}] changes]
      (doseq [x added] (println (str "  + " (name k) " " x)))
      (doseq [x removed] (println (str "  - " (name k) " " x))))
    (when-let [branches (seq (get-in changes [:branches :added]))]
      (println "Clean up with: git worktree prune &&"
               (str/join " " (into ["git" "branch" "-D"] (map #(str/replace % "refs/heads/" "") branches)))))))

(def caller-session-env
  "Variables that identify the terminal or agent session that started the run. `oh` selects
  its runtime and parent identity from them, so a run inside Herdr, herdr-app or Pi must not
  pass them to in-process tests. Fixtures that need them set them explicitly."
  {:prefixes ["HC_" "HERDR_"]
   :names #{"PI_SESSION_FILE" "PI_SESSION_ID" "PI_MODEL" "PI_PROVIDER" "PI_REASONING_LEVEL"}})

(defn suite-env
  "`env` without `caller-session-env`, with TMPDIR set to `root`."
  [env root]
  (let [{:keys [prefixes names]} caller-session-env
        caller? (fn [k] (or (contains? names k) (some #(str/starts-with? k %) prefixes)))]
    (assoc (into {} (remove (comp caller? key)) env) "TMPDIR" root)))

(defn run-suites
  "Run each `bb` argument vector in `commands` with java.io.tmpdir and TMPDIR set to one fresh root outside the repository, and without `caller-session-env`.

  The root is under the system temporary directory, not under `.tmp/`: several tests need a directory outside any git checkout. The root is deleted afterwards unless DOTAGENTS_KEEP_TEST_TMP is set. Every command runs even when an earlier one fails, so one failing suite cannot hide the results of the others. The run fails, listing each failed command and its exit code, if any command fails. It exits 1 if git-state changes."
  [commands]
  (let [root (str (fs/canonicalize (fs/create-temp-dir {:prefix "dotagents-test-"})))
        repo (str/trim (:out (process/shell {:out :string} "git rev-parse --show-toplevel")))
        before (git-state repo)
        failures (vec (keep (fn [argv]
                              (let [exit (:exit @(process/process (into ["bb" (str "-Djava.io.tmpdir=" root)] argv)
                                                                   {:inherit true :env (suite-env (into {} (System/getenv)) root)}))]
                                (when-not (zero? exit) {:argv argv :exit exit})))
                            commands))
        changes (state-changes before (git-state repo))]
    (if (str/blank? (System/getenv keep-env))
      (fs/delete-tree root)
      (println (str "Kept test temporary root: " root)))
    (when changes (report-changes! changes))
    (when (seq failures)
      (println (str "\n" (count failures) " of " (count commands) " test commands failed:"))
      (doseq [{:keys [argv exit]} failures]
        (println (str "  exit " exit ": bb " (str/join " " argv)))))
    (cond
      (seq failures) (throw (ex-info (str (count failures) " test commands failed") {:failures failures}))
      changes (System/exit 1))))
