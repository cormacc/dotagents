(ns herdr-orch.test-support
  "Shared helpers for herdr-orch tests."
  (:require [babashka.fs :as fs]))

(defn call-with-temp-dir
  "Call `f` with the canonical path string of a new temporary directory, and delete the
  directory afterwards, also when `f` throws. `fs/delete-tree` removes a symlink inside the
  directory without following it.

  Use this for each new test directory. The root `bb test` task also deletes its per-run
  temporary root, but a focused run outside that task does not."
  [prefix f]
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:prefix prefix})))]
    (try
      (f dir)
      (finally (fs/delete-tree dir)))))
