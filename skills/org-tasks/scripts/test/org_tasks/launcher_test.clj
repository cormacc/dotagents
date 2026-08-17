(ns org-tasks.launcher-test
  "Launcher coverage for full-checkout, deployment-symlink, and bare-skill use."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def root
  (str (fs/canonicalize (fs/path (fs/parent *file*) "../../../../.."))))
(def scripts-dir (str (fs/path root "skills" "org-tasks" "scripts")))
(def repo-bin (str (fs/path scripts-dir "ot")))

(defn- with-temp-dir [f]
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:prefix "ot-launcher-"})))]
    (try
      (f dir)
      (finally (fs/delete-tree dir)))))

(defn- run-root [bin cwd root]
  @(process/process [bin "root" "--root" root]
                    {:out :string :err :string :dir cwd}))

(defn- resolved-path [proc]
  (str (fs/canonicalize (fs/path (str/trim (:out proc))))))

(deftest launcher-preserves-caller-cwd-for-relative-root
  (with-temp-dir
    (fn [dir]
      (let [caller (str (fs/path dir "caller"))
            _ (fs/create-dirs caller)
            full (run-root repo-bin caller ".")
            deploy (str (fs/path dir "deploy"))
            _ (fs/create-sym-link deploy (fs/path root "skills"))
            symlinked (run-root (str (fs/path deploy "org-tasks" "scripts" "ot")) caller ".")
            bare (str (fs/path dir "bare" "x" "y" "scripts"))
            _ (fs/create-dirs (fs/parent bare))
            _ (fs/copy-tree scripts-dir bare)
            fallback (run-root (str (fs/path bare "ot")) caller ".")
            absolute (run-root repo-bin caller dir)]
        (doseq [proc [full symlinked fallback absolute]]
          (is (zero? (:exit proc)) (:err proc)))
        (is (= caller (resolved-path full)))
        (is (= caller (resolved-path symlinked)))
        (is (= caller (resolved-path fallback)))
        (is (= dir (resolved-path absolute)))))))
