(ns herdr-orch.ledger
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.nio.file Files FileAlreadyExistsException Paths StandardCopyOption]
           [java.util UUID]))

;; `ORCH_ASSIGNMENT_ROOT` relocates ledger, index markers, result paths, and
;; project-roster lookup together (they are all per-project notions). A blank value
;; is treated as unset; a relative value is absolutised against the process cwd so
;; RESULT is always absolute; a value that is not an existing directory is rejected.
(defn resolve-override [raw]
  (when-let [value (some-> raw str/trim not-empty)]
    (let [path (fs/absolutize value)]
      (when-not (fs/directory? path)
        (throw (ex-info "ORCH_ASSIGNMENT_ROOT must name an existing directory" {:value value :resolved (str path)})))
      (str (fs/canonicalize path)))))
(defn assignment-root []
  (or (resolve-override (System/getenv "ORCH_ASSIGNMENT_ROOT"))
      (let [{:keys [exit out]} @(process/process ["git" "rev-parse" "--show-toplevel"] {:out :string :err :string})]
        (if (zero? exit) (str/trim out) (str (fs/absolutize "."))))))
(defn directory [] (fs/path (assignment-root) ".tmp" "herdr-orch" "ledger"))
(defn ensure! [] (fs/create-dirs (directory)) (directory))
(defn assignment-path [task] (fs/path (ensure!) (str task ".json")))
(defn result-directory [] (fs/path (assignment-root) ".tmp" "herdr-orch"))
(defn fresh-task [] (str (UUID/randomUUID)))
(defn fresh-result [task]
  (fs/create-dirs (result-directory))
  (loop [] (let [path (fs/path (result-directory) (str task "-" (UUID/randomUUID) ".result"))]
             (if (fs/exists? path) (recur) (str path)))))
(defn write! [entry]
  (let [path (assignment-path (:task entry)) temp (fs/path (str path "." (UUID/randomUUID) ".tmp"))]
    (spit (str temp) (json/generate-string entry))
    (Files/move (Paths/get (str temp) (make-array String 0)) (Paths/get (str path) (make-array String 0))
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
    path))
(defn read! [task]
  (let [path (assignment-path task)]
    (when-not (fs/exists? path) (throw (ex-info "unknown assignment task" {:task task})))
    (json/parse-string (slurp (str path)) true)))
(defn entries [] (->> (fs/glob (ensure!) "*.json") (map #(json/parse-string (slurp (str %)) true)) (sort-by :created-at)))
(defn update! [task f & args] (let [next (apply f (read! task) args)] (write! next) next))
(defn safe-id [s] (str/replace (str s) #"[^A-Za-z0-9_.-]" "_"))
(defn stable-id [s] (str (UUID/nameUUIDFromBytes (.getBytes (str s) "UTF-8"))))
(defn allocate-index! [parent-session persona]
  (let [dir (fs/path (ensure!) "indices" (stable-id parent-session) (safe-id persona))]
    (fs/create-dirs dir)
    (loop [n 1]
      (if (try (Files/createFile (Paths/get (str (fs/path dir (str n))) (make-array String 0)) (make-array java.nio.file.attribute.FileAttribute 0)) true
               (catch FileAlreadyExistsException _ false)) n (recur (inc n))))))
