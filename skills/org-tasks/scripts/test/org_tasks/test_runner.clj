(ns org-tasks.test-runner
  "Discover and run every clojure.test suite under scripts/test/.

  Invoked from bb.edn's `test` task and from CI. Exit code is 0 when
  all tests pass, 1 otherwise."
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn- discover-test-namespaces
  "Walk every directory on the classpath, find Clojure files whose
  namespace symbol ends in `-test`, and return them in a stable order."
  []
  (let [roots (->> (cp/split-classpath (cp/get-classpath))
                   (map fs/path)
                   (filter fs/directory?))
        nses  (->> roots
                   (mapcat (fn [root]
                             (->> (fs/glob root "**/*_test.clj")
                                  (map (fn [p]
                                         (let [rel (str (fs/relativize root p))
                                               base (str/replace rel #"\.clj$" "")]
                                           (-> base
                                               (str/replace "/" ".")
                                               (str/replace "_" "-")
                                               symbol)))))))
                   distinct
                   sort)]
    nses))

(defn run
  "Run every discovered test namespace via clojure.test/run-tests.

  Args (parsed positionally):
    [ns-symbol ...] - explicit namespace whitelist (optional)."
  [& args]
  (let [explicit (seq (map symbol args))
        nses    (or explicit (discover-test-namespaces))]
    (when (empty? nses)
      (binding [*out* *err*]
        (println "test-runner: no test namespaces found")))
    (doseq [n nses] (require n))
    (let [{:keys [fail error]} (apply t/run-tests nses)
          exit (+ (or fail 0) (or error 0))]
      (System/exit (if (pos? exit) 1 0)))))
