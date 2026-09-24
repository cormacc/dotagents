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

(defn run-suites
  "Run each `bb` argument vector in `commands` with java.io.tmpdir and TMPDIR set to one fresh root outside the repository.

  The root is under the system temporary directory, not under `.tmp/`: several tests need a directory outside any git checkout. The root is deleted afterwards unless DOTAGENTS_KEEP_TEST_TMP is set. The run fails if a command fails or if git-state changes."
  [commands]
  (let [root (str (fs/canonicalize (fs/create-temp-dir {:prefix "dotagents-test-"})))
        repo (str/trim (:out (process/shell {:out :string} "git rev-parse --show-toplevel")))
        before (git-state repo)
        failure (try
                  (doseq [argv commands]
                    (process/check
                     (process/process (into ["bb" (str "-Djava.io.tmpdir=" root)] argv)
                                      {:inherit true :extra-env {"TMPDIR" root}})))
                  nil
                  (catch Exception e e))
        changes (state-changes before (git-state repo))]
    (if (str/blank? (System/getenv keep-env))
      (fs/delete-tree root)
      (println (str "Kept test temporary root: " root)))
    (when changes (report-changes! changes))
    (cond
      failure (throw failure)
      changes (System/exit 1))))
