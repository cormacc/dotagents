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
;;
;; It is kind-dependent, though: Herdr reports `agent_session` for pi panes and not for
;; claude ones, which is why the ledger field is best-effort by contract. Demanding one
;; from every kind failed a claude smoke whose four legs had all published COMPLETE, so a
;; kind Herdr does not track reports its absence instead of failing. pi stays strict --
;; that is the path where a missing session would be a real regression.
(defn session! [kind entry]
  (let [session (:child-session entry)
        tracked? (= "pi" (or kind "pi"))]
    (cond
      (and (nil? session) (not tracked?))
      {:kind "none" :value (str "herdr reports no agent_session for kind " kind)}

      :else
      (do
        (when-not (and (:kind session) (:value session))
          (throw (ex-info "live smoke entry has no usable :child-session" {:task (:task entry) :kind kind :child-session session})))
        (when (and (= "path" (:kind session)) (not (fs/exists? (:value session))))
          (throw (ex-info "live smoke :child-session path does not exist" {:task (:task entry) :path (:value session)})))
        session))))
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
;; No capture closes a pane, so every leg must close what it created or the smoke leaks a
;; pane per run. Each leg closes its own child by task id rather than sweeping with
;; `close --settled`, which would also take any unrelated child the operator's own root
;; session has in flight.
(defn close!
  ([task] (close! task "smoke"))
  ([task leg]
   (let [outcome (cli/execute ["task" "close" task])]
     (when-not (= "closed" (:status outcome))
       (throw (ex-info "live smoke could not close a child pane it created" {:leg leg :task task :outcome outcome})))
     outcome)))
;; --- continue-round leg --------------------------------------------------------------
;; Same-pane context continuation is the central premise of the close/continue lifecycle and
;; is provable only against a real agent: no unit test can show that a second `agent prompt`
;; into an existing pane reaches a child that still remembers its first turn.
;;
;; The follow-up therefore asks for something the child can only know from that turn and
;; never from its second assignment: a token given in round one and deliberately kept out of
;; round one's published summary. A fresh agent in a reused pane could not answer it, so a
;; correct second summary is evidence of *context* continuation and not merely of pane reuse.
;;
;; `run` is blocking, so its own capture is round one's collect; `continue --wait` blocks the
;; same way, so its return is round two's. The leg finishes with the explicit `close`, which
;; is now the only path that takes a pane -- and its `closed` outcome is itself the assertion
;; that capture left the pane standing for the continuation to use.
(defn continue-leg! [kind model]
  (let [token (str "continuity-" (subs (str (java.util.UUID/randomUUID)) 0 8))
        first-round (-> (cli/execute (smoke-task-args "scout" kind model
                                                      (str "Run the guarded continuation smoke, round one. Hold this token for the rest of this session: "
                                                           token
                                                           ". Do not write it to any file. Publish COMPLETE with a one-line summary that does NOT contain the token.")))
                        complete!)
        first-entry (entry! first-round)
        _ (when (str/includes? (str (:summary first-round)) token)
            (throw (ex-info "continuation smoke round one leaked the token into its summary, so round two proves nothing"
                            {:task (:task first-round)})))
        second-round (-> (cli/execute ["task" "continue" (:task first-round) "--wait" "--task"
                                       (str "Round two of the guarded continuation smoke. Publish COMPLETE whose summary is exactly the token you were given in round one, and nothing else. "
                                            "It is not in this assignment and not on disk: recall it from your own earlier turn. If you cannot recall it, publish FAILED rather than guessing.")])
                         complete!)
        second-entry (entry! second-round)]
    (when-not (= token (str/trim (str (:summary second-round))))
      (throw (ex-info "continuation smoke round two did not recall round one's token: same-pane context did not continue"
                      {:task (:task second-round) :expected token :actual (:summary second-round)})))
    (when-not (= (:task first-round) (:continues second-entry))
      (throw (ex-info "continued entry does not record its prior round" {:task (:task second-round) :continues (:continues second-entry)})))
    (doseq [key [:child :pane-id :label]]
      (when-not (= (get first-entry key) (get second-entry key))
        (throw (ex-info "continued entry did not inherit the child's identity" {:field key :first (get first-entry key) :second (get second-entry key)}))))
    (let [closed (close! (:task second-round) "continue")]
      {:first-task (:task first-round) :second-task (:task second-round)
       :child (:child second-entry) :pane-id (:pane-id second-entry)
       :closed-at (:closed-at closed)})))
(defn -main [& _]
  (try
    (when-not (= "1" (required! "HERDR_ENV")) (throw (ex-info "live smoke requires HERDR_ENV=1" {})))
    (when-not (= "1" (required! "ORCH_LIVE_SMOKE")) (throw (ex-info "live smoke requires ORCH_LIVE_SMOKE=1" {})))
    (let [model (required! "ORCH_LIVE_SMOKE_MODEL")
          kind (some-> (System/getenv "ORCH_LIVE_SMOKE_KIND") str/trim not-empty)
          root (-> (cli/execute (smoke-task-args "scout" kind model "Run the guarded root smoke: verify HERDR_ORCH_CHILD, HERDR_ORCH_TASK, and HERDR_ORCH_RESULT are set; then publish COMPLETE with a concise summary using the injected launcher.")) complete!)
          root-entry (entry! root)
          _ (when-not (re-matches #"scout-[0-9]+(?:-.+)?" (:label root-entry)) (throw (ex-info "root smoke label is invalid" {:label (:label root-entry)})))
          ;; The nested child is the planner's own, not this root's: its ledger entry records
          ;; the planner's `:parent-session`, so `close` here would refuse it and a root
          ;; `close --settled` correctly skips it. Whoever spawns a child closes it, so the
          ;; planner is told to -- and the assertion below makes a skipped close loud instead
          ;; of leaking one grandchild pane per smoke run.
          nested (-> (cli/execute (smoke-task-args "planner" kind model (str "Run the guarded nested smoke. Spawn exactly one blocking scout with the injected launcher using model " model "; ask it to verify its injected identity and publish COMPLETE. Wait for its result and require that it completed. Then close that child's pane with `\"$HERDR_ORCH_BIN\" task close <its full task uuid>` and require the outcome status to be `closed`: capturing a result never closes a pane, so a child you spawned is yours to close. Only then publish your own COMPLETE result."))) complete!)
          planner-entry (entry! nested)
          prefix (core/nested-prefix (:label planner-entry) "planner")
          child-entry (some #(when (str/starts-with? (:label %) (str prefix "/scout-")) %) (ledger/entries))
          ;; Runs before the retro leg's branch so both branches report it, and after the
          ;; nested leg so a failure here is never confused with a spawn or fan-in failure.
          continuation (continue-leg! kind model)]
      (when-not child-entry (throw (ex-info "nested smoke did not record a planner-prefixed scout label" {:planner-label (:label planner-entry)})))
      ;; This root owns the two children it spawned directly, so it closes them itself --
      ;; before the nested-close assertion below, not after. A grandchild the planner failed
      ;; to close is one leaked pane; throwing first would leak all three.
      (close! (:task root) "root")
      (close! (:task nested) "nested")
      ;; Read after the planner published, so its close (if it made one) is already recorded.
      ;; Naming the pane in the failure keeps the manual cleanup one command away.
      (when-not (:closed-at child-entry)
        (throw (ex-info "nested smoke child was never closed by its own parent; close its pane manually"
                        {:task (:task child-entry) :child (:child child-entry) :pane-id (:pane-id child-entry)})))
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
                                           :continuation continuation
                                           :retro-leg "skipped: no retro skill installed"
                                           :root-label (:label root-entry) :nested-label (:label child-entry)
                                           :child-sessions (mapv (partial session! kind) [root-entry planner-entry child-entry])}))
        (let [retro (-> (cli/execute (smoke-task-args "worker" kind model
                                                        (str "Run the guarded retrospective smoke. "
                                                             "Display your assignment's current status by running `\"$HERDR_ORCH_BIN\" show $HERDR_ORCH_TASK`. "
                                                             "If that does not work, find the correct invocation and complete the status check anyway. "
                                                             "Report the status and the exact invocation that worked in your summary, then publish COMPLETE, applying the retro instruction in this prompt to your own session as written.")
                                                        :retro? true))
                        complete!)
              retro-entry (entry! retro)]
          (process! retro)
          (close! (:task retro) "retro")
          (println (core/json-envelope true {:root-task (:task root) :nested-task (:task nested) :retro-task (:task retro)
                                             :continuation continuation
                                             :root-label (:label root-entry) :nested-label (:label child-entry)
                                             :retro-source (:retro-source retro-entry) :process (:process retro)
                                             :child-sessions (mapv (partial session! kind) [root-entry planner-entry child-entry retro-entry])})))))
    (catch Exception e
      (println (core/json-envelope false {:message (.getMessage e) :data (ex-data e)}))
      (System/exit 1))))
