(ns herdr-orch.quota-setup
  "Explicit user-local setup; check and install never read subscription data."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [herdr-orch.usage :as usage]))

(def install-budget-ms 120000)

(defn node-failure []
  (if-let [node (some-> (fs/which "node") str)]
    (let [checked (usage/execute node ["--version"] usage/version-budget-ms)
          parts (some->> (:out checked) str/trim (re-matches #"v(\d+)\.(\d+)\.(\d+)") rest (mapv parse-long))]
      (or (:reason checked)
          (when-not (and (= 0 (:exit checked)) (= 3 (count parts))
                         (not (neg? (compare parts [22 19 0])))) "node-version")))
    "missing-node"))

(defn check []
  (let [reason (or (node-failure) (usage/version-failure (usage/executable)))]
    {:ready (nil? reason) :version usage/version :reason reason}))

(defn install []
  (let [prefix (usage/install-directory)
        reason (or (node-failure)
                   (when-not (fs/absolute? prefix) "invalid-prefix")
                   (if-let [npm (some-> (fs/which "npm") str)]
                     (let [result (usage/execute npm ["install" "--prefix" prefix "--ignore-scripts" "--save-exact"
                                                      "--no-audit" "--no-fund" (str "quota-axi@" usage/version)] install-budget-ms)]
                       (or (:reason result) (when-not (= 0 (:exit result)) "install-failed")))
                     "missing-npm"))]
    (if reason {:ready false :version usage/version :reason reason}
        (let [managed (str (fs/path prefix "node_modules/.bin/quota-axi"))
              reason (usage/version-failure managed)]
          {:ready (nil? reason) :version usage/version :reason reason}))))

(defn -main [& args]
  (let [args (remove #{"--"} args)
        result (try
                 (case (vec args)
                   ([] ["check"]) (check)
                   ["install"] (install)
                   {:ready false :version usage/version :reason "use-check-or-install"})
                 (catch Exception _ {:ready false :version usage/version :reason "setup-failed"}))]
    (println (json/generate-string result))))
