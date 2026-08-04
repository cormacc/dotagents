(ns herdr-orch.smoke
  "Explicitly billable root and nested delegation smoke. Never call from tests."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [herdr-orch.core :as core]
            [herdr-orch.cli :as cli]
            [herdr-orch.ledger :as ledger]))

(defn required! [name]
  (let [value (System/getenv name)]
    (when (str/blank? value) (throw (ex-info "live smoke is guarded; require HERDR_ENV=1, ORCH_LIVE_SMOKE=1, and ORCH_LIVE_SMOKE_MODEL" {:missing name}))) value))
(defn complete! [result]
  (when-not (= "COMPLETE" (:status result)) (throw (ex-info "live smoke child did not publish COMPLETE" {:result result}))) result)
(defn entry! [result] (ledger/read! (:task result)))
;; A recorded session is the only durable transcript reference once the pane is gone, so
;; the smoke proves it exists and, for the `path` discriminator, resolves.
(defn session! [entry]
  (let [session (:child-session entry)]
    (when-not (and (:kind session) (:value session))
      (throw (ex-info "live smoke entry has no usable :child-session" {:task (:task entry) :child-session session})))
    (when (and (= "path" (:kind session)) (not (fs/exists? (:value session))))
      (throw (ex-info "live smoke :child-session path does not exist" {:task (:task entry) :path (:value session)})))
    session))
;; The signal-manufacturing leg must not pass on silence: an empty section there means
;; either the prompt or `retro`'s threshold left the default-enabled path inert.
(defn process! [result]
  (let [items (:process result)]
    (when-not (seq items)
      (throw (ex-info "live smoke retro leg published no PROCESS candidates" {:task (:task result)})))
    (doseq [item items]
      (when-not (re-find #"\S.*\u2192.*\S.*\u2192.*\S" item)
        (throw (ex-info "PROCESS item is not shaped `signal → category → proposed rule`" {:item item}))))
    items))
(defn no-process! [result]
  (when (seq (:process result))
    (throw (ex-info "a frontmatter-opted-out leg published PROCESS candidates" {:task (:task result) :process (:process result)})))
  result)
(defn smoke-task-args [persona kind model task & {:keys [retro?]}]
  (cond-> ["task" "run" persona]
    retro? (conj "--retro")
    kind (into ["--kind" kind])
    true (into ["--model" model "--task" task])))
(defn -main [& _]
  (try
    (when-not (= "1" (required! "HERDR_ENV")) (throw (ex-info "live smoke requires HERDR_ENV=1" {})))
    (when-not (= "1" (required! "ORCH_LIVE_SMOKE")) (throw (ex-info "live smoke requires ORCH_LIVE_SMOKE=1" {})))
    (let [model (required! "ORCH_LIVE_SMOKE_MODEL")
          kind (some-> (System/getenv "ORCH_LIVE_SMOKE_KIND") str/trim not-empty)
          root (-> (cli/execute (smoke-task-args "scout" kind model "Run the guarded root smoke: verify HERDR_ORCH_CHILD, HERDR_ORCH_TASK, HERDR_ORCH_RESULT, and HERDR_ORCH_WAITING_POLICY are set; then publish COMPLETE with a concise summary using the injected launcher.")) complete!)
          root-entry (entry! root)
          _ (when-not (re-matches #"scout-[0-9]+(?:-.+)?" (:label root-entry)) (throw (ex-info "root smoke label is invalid" {:label (:label root-entry)})))
          nested (-> (cli/execute (smoke-task-args "planner" kind model (str "Run the guarded nested smoke. Spawn exactly one blocking scout with the injected launcher using model " model "; ask it to verify its injected identity and publish COMPLETE. Wait for its result, require that it completed, then publish your own COMPLETE result."))) complete!)
          planner-entry (entry! nested)
          prefix (core/nested-prefix (:label planner-entry) "planner")
          child-entry (some #(when (str/starts-with? (:label %) (str prefix "/scout-")) %) (ledger/entries))]
      (when-not child-entry (throw (ex-info "nested smoke did not record a planner-prefixed scout label" {:planner-label (:label planner-entry)})))
      (no-process! root)
      (when (:retro root-entry)
        (throw (ex-info "scout leg was not gated out by its frontmatter" {:retro-source (:retro-source root-entry)})))
      ;; Third leg: a gated-in child whose assignment manufactures a real tool-invocation
      ;; failure, so `retro`'s threshold has something genuine to admit. The prompt never
      ;; asks for a candidate directly — that would test compliance, not the threshold — and
      ;; never reveals the failure is scripted: an announced trap fails `retro`'s eligibility
      ;; gate as a known one-off, so the child must experience `show` as a genuinely wrong
      ;; instruction and discover the working subcommand itself. The leg is skipped, not
      ;; failed, when no `retro` skill is installed: the retro step is optional equipment
      ;; and a third-party adoption without that skill is a supported configuration.
      (if-not (cli/retro-skill-path)
        (println (core/json-envelope true {:root-task (:task root) :nested-task (:task nested)
                                           :retro-leg "skipped: no retro skill installed"
                                           :root-label (:label root-entry) :nested-label (:label child-entry)
                                           :child-sessions (mapv session! [root-entry planner-entry child-entry])}))
        (let [retro (-> (cli/execute (smoke-task-args "worker" kind model
                                                        (str "Run the guarded retrospective smoke. "
                                                             "Display your assignment's current status by running `\"$HERDR_ORCH_BIN\" show $HERDR_ORCH_TASK`. "
                                                             "If that does not work, find the correct invocation and complete the status check anyway. "
                                                             "Report the status and the exact invocation that worked in your summary, then publish COMPLETE, applying the retro instruction in this prompt to your own session as written.")
                                                        :retro? true))
                        complete!)
              retro-entry (entry! retro)]
          (process! retro)
          (println (core/json-envelope true {:root-task (:task root) :nested-task (:task nested) :retro-task (:task retro)
                                             :root-label (:label root-entry) :nested-label (:label child-entry)
                                             :retro-source (:retro-source retro-entry) :process (:process retro)
                                             :child-sessions (mapv session! [root-entry planner-entry child-entry retro-entry])})))))
    (catch Exception e
      (println (core/json-envelope false {:message (.getMessage e) :data (ex-data e)}))
      (System/exit 1))))
