(ns org-tasks.paths-test
  "Sandbox regression tests for `org-tasks.paths`.

  Mirrors `pi/extensions/tasks/paths.test.ts`. Uses real temp dirs so
  traversal + symlink rejection is verified against the filesystem,
  matching how the live CLI will resolve paths."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.paths :as paths]))

(defn- with-temp-dir [f]
  (let [dir (str (fs/create-temp-dir {:prefix "ot-paths-"}))]
    (try (f dir)
         (finally (fs/delete-tree dir)))))

(deftest in-tree-paths-allowed
  (with-temp-dir
    (fn [project]
      (let [plans (str (fs/path project "design" "log"))]
        (fs/create-dirs plans)
        (let [existing (str (fs/path plans "existing.org"))]
          (spit existing "* Plan\n")
          (testing "in-tree relative path allowed"
            (is (= (str (fs/real-path existing))
                   (paths/resolve-project-path
                     project project "design/log/existing.org"))))
          (testing "in-tree absolute path allowed"
            (is (= (str (fs/real-path existing))
                   (paths/resolve-project-path project project existing))))
          (testing "non-existing in-tree scaffold path allowed via parent"
            (let [future (str (fs/path (fs/real-path plans) "future.org"))]
              (is (= future
                     (paths/resolve-project-path
                       project project "design/log/future.org"))))))))))

(deftest out-of-tree-absolute-rejected
  (with-temp-dir
    (fn [project]
      (with-temp-dir
        (fn [outside]
          (let [outside-file (str (fs/path outside "outside.org"))]
            (spit outside-file "* Outside\n")
            (is (nil? (paths/resolve-project-path project project outside-file)))))))))

(deftest parent-traversal-rejected
  (with-temp-dir
    (fn [project]
      (with-temp-dir
        (fn [outside]
          (let [traversal (str (fs/path project ".." (fs/file-name outside) "escape.org"))]
            (is (nil? (paths/resolve-project-path project project traversal)))))))))

(deftest symlink-escape-rejected
  (with-temp-dir
    (fn [project]
      (with-temp-dir
        (fn [outside]
          (let [outside-file (str (fs/path outside "outside.org"))]
            (spit outside-file "* Outside\n")
            (let [link (str (fs/path project "linked-outside.org"))]
              (fs/create-sym-link link outside-file)
              (is (nil? (paths/resolve-project-path
                          project project "linked-outside.org"))))))))))
