(ns org-tasks.root-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [org-tasks.root :as root]))

(defn- with-temp-dir [f]
  (let [dir (str (fs/create-temp-dir {:prefix "ot-root-"}))]
    (try (f dir)
         (finally (fs/delete-tree dir)))))

(deftest explicit-root-wins
  (with-temp-dir
    (fn [outer]
      (with-temp-dir
        (fn [explicit]
          (spit (str (fs/path outer "TASKS.org")) "* Outer\n")
          (is (= (str (fs/absolutize explicit))
                 (root/resolve-root {:root explicit} outer))))))))

(deftest cwd-containing-tasks-org-is-root
  (with-temp-dir
    (fn [dir]
      (spit (str (fs/path dir "TASKS.org")) "* Tasks\n")
      (is (= (str (fs/absolutize dir))
             (root/resolve-root {} dir))))))

(deftest nearest-parent-containing-tasks-org-is-root
  (with-temp-dir
    (fn [root-dir]
      (let [nested (fs/path root-dir "a" "b" "c")]
        (spit (str (fs/path root-dir "TASKS.org")) "* Tasks\n")
        (fs/create-dirs nested)
        (is (= (str (fs/absolutize root-dir))
               (root/resolve-root {} (str nested))))))))

(deftest nearest-tasks-org-wins-over-higher-ancestor
  (with-temp-dir
    (fn [outer]
      (let [inner (fs/path outer "inner")
            nested (fs/path inner "child")]
        (spit (str (fs/path outer "TASKS.org")) "* Outer\n")
        (fs/create-dirs nested)
        (spit (str (fs/path inner "TASKS.org")) "* Inner\n")
        (is (= (str (fs/absolutize inner))
               (root/resolve-root {} (str nested))))))))

(deftest falls-back-to-cwd-when-no-tasks-org-ancestor
  (with-temp-dir
    (fn [dir]
      (let [nested (fs/path dir "a" "b")]
        (fs/create-dirs nested)
        (is (= (str (fs/absolutize nested))
               (root/resolve-root {} (str nested))))))))
