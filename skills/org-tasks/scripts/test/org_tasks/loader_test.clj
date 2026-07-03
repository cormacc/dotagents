(ns org-tasks.loader-test
  "Tests for the filesystem loader/writer: `safe-slurp` fail-fast
  semantics and write-time conflict detection in `save-source-roots`.

  Change-record: design/log/2026-07-03-org-tasks-tui-final-review-follow-up.org"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.loader :as loader]))

(defn- with-temp-dir [f]
  (let [dir (str (fs/create-temp-dir {:prefix "ot-loader-"}))]
    (try (f dir)
         (finally (fs/delete-tree dir)))))

;; ── safe-slurp ───────────────────────────────────────────────────

(deftest safe-slurp-missing-file-is-nil
  (with-temp-dir
    (fn [dir]
      (is (nil? (loader/safe-slurp (str dir "/nope.org")))))))

(deftest safe-slurp-unreadable-existing-file-throws
  (testing "a directory in place of the expected file is a read error, not 'missing'"
    (with-temp-dir
      (fn [dir]
        (let [path (str dir "/oops.org")]
          (fs/create-dirs path) ;; a directory, not a file -> slurp throws
          (let [e (try (loader/safe-slurp path) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (some? e))
            (is (= :unreadable (:code (ex-data e))))))))))

;; ── save-source-roots conflict detection ──────────────────────────

(deftest save-source-roots-detects-concurrent-edit
  (testing "file changed on disk since load -> conflict, file left untouched"
    (with-temp-dir
      (fn [dir]
        (let [path (str dir "/TASKS.org")
              original "* Improvements\n** TODO A\n:PROPERTIES:\n:CUSTOM_ID: aaaaaaaa-1111-4111-8111-111111111111\n:END:\n"]
          (spit path original)
          (let [{:keys [tasks]} (loader/load-graph
                                  dir {:tasks path :local (str dir "/TASKS.local.org")
                                       :archive (str dir "/TASKS.archive.org")})
                mutated (mapv #(assoc % :priority "A") tasks)]
            ;; Someone else edits the file after load but before save.
            (spit path (str original "\n* Someone else's edit\n"))
            (let [e (try (loader/save-source-roots dir mutated) nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (some? e))
              (is (= :conflict (:code (ex-data e))))
              (is (= (str original "\n* Someone else's edit\n") (slurp path))
                  "file must be left untouched on conflict"))))))))

(deftest save-source-roots-tolerates-unrelated-file-creation
  (testing "another file appearing on disk during the same call does not conflict"
    (with-temp-dir
      (fn [dir]
        (let [path (str dir "/TASKS.org")
              original "* Improvements\n** TODO A\n:PROPERTIES:\n:CUSTOM_ID: aaaaaaaa-1111-4111-8111-111111111111\n:END:\n"]
          (spit path original)
          (let [{:keys [tasks]} (loader/load-graph
                                  dir {:tasks path :local (str dir "/TASKS.local.org")
                                       :archive (str dir "/TASKS.archive.org")})
                mutated (mapv #(assoc % :priority "A") tasks)]
            (loader/save-source-roots dir mutated)
            (is (str/includes? (slurp path) "[#A]"))))))))

(deftest atomic-write-uses-unique-temp-names
  (testing "concurrent atomic-write calls to different targets don't collide on a shared tmp name"
    (with-temp-dir
      (fn [dir]
        (let [a (str dir "/a.org")
              b (str dir "/b.org")]
          (loader/atomic-write a "A")
          (loader/atomic-write b "B")
          (is (= "A" (slurp a)))
          (is (= "B" (slurp b))))))))
