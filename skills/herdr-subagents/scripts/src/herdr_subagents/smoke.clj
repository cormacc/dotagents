(ns herdr-subagents.smoke
  "Explicitly billable root and nested delegation smoke. Never call from tests."
  (:require [clojure.string :as str]
            [herdr-subagents.core :as core]
            [herdr-subagents.cli :as cli]
            [herdr-subagents.ledger :as ledger]))

(defn required! [name]
  (let [value (System/getenv name)]
    (when (str/blank? value) (throw (ex-info "live smoke is guarded; require HERDR_ENV=1, SUBAGENT_LIVE_SMOKE=1, and SUBAGENT_LIVE_SMOKE_MODEL" {:missing name}))) value))
(defn complete! [result]
  (when-not (= "COMPLETE" (:status result)) (throw (ex-info "live smoke child did not publish COMPLETE" {:result result}))) result)
(defn entry! [result] (ledger/read! (:task result)))
(defn -main [& _]
  (try
    (when-not (= "1" (required! "HERDR_ENV")) (throw (ex-info "live smoke requires HERDR_ENV=1" {})))
    (when-not (= "1" (required! "SUBAGENT_LIVE_SMOKE")) (throw (ex-info "live smoke requires SUBAGENT_LIVE_SMOKE=1" {})))
    (let [model (required! "SUBAGENT_LIVE_SMOKE_MODEL")
          root (-> (cli/execute ["run" "scout" "--model" model "--task" "Run the guarded root smoke: verify HERDR_SUBAGENT_CHILD, HERDR_SUBAGENT_TASK, HERDR_SUBAGENT_RESULT, and HERDR_SUBAGENT_WAITING_POLICY are set; then publish COMPLETE with a concise summary using the injected launcher."]) complete!)
          root-entry (entry! root)
          _ (when-not (re-matches #"scout-[0-9]+(?:-.+)?" (:label root-entry)) (throw (ex-info "root smoke label is invalid" {:label (:label root-entry)})))
          nested (-> (cli/execute ["run" "planner" "--model" model "--task" (str "Run the guarded nested smoke. Spawn exactly one blocking scout with the injected launcher using model " model "; ask it to verify its injected identity and publish COMPLETE. Wait for its result, require that it completed, then publish your own COMPLETE result.")]) complete!)
          planner-entry (entry! nested)
          prefix (core/nested-prefix (:label planner-entry) "planner")
          child-entry (some #(when (str/starts-with? (:label %) (str prefix "/scout-")) %) (ledger/entries))]
      (when-not child-entry (throw (ex-info "nested smoke did not record a planner-prefixed scout label" {:planner-label (:label planner-entry)})))
      (println (core/json-envelope true {:root-task (:task root) :nested-task (:task nested) :root-label (:label root-entry) :nested-label (:label child-entry)})))
    (catch Exception e
      (println (core/json-envelope false {:message (.getMessage e) :data (ex-data e)}))
      (System/exit 1))))
