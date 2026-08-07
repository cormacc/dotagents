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
;; A `PROCESS` candidate is a content judgement made by an LLM against `retro`'s own
;; threshold, not a mechanical fact, so this leg must never require one -- the retro skill
;; correctly emits nothing when its threshold is not met, and a gate that demanded a
;; candidate anyway once failed a child for reasoning correctly (design record
;; 2026-08-06-herdr-orch-support-resident-reviewers-fo, task 377ad650). What *is*
;; mechanical, and what this checks, is the resolved gate itself: `:retro`/`:retro-source`
;; on the captured entry. Whether the instruction text actually reaches the rendered
;; prompt is proven separately by a deterministic unit test over `prompt-text`, never by a
;; live child's classification.
(defn retro-gated-in! [entry]
  (when-not (:retro entry)
    (throw (ex-info "retro leg was not gated in by --retro" {:task (:task entry) :retro-source (:retro-source entry)})))
  (when-not (= "flag" (:retro-source entry))
    (throw (ex-info "retro leg's :retro-source did not resolve from --retro" {:task (:task entry) :retro-source (:retro-source entry)})))
  entry)
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
                                                      (str "Run the guarded continuation smoke, round one. Hold this token in context for the rest of this session: "
                                                           token
                                                           ". Do not write it to any file, and keep it out of this round's summary. That restriction exists so a later round can prove same-pane recall rather than reading it back off disk; it is not a confidentiality guard, and a later round will ask you to produce the token deliberately. Publish COMPLETE with a one-line summary that does NOT contain the token.")))
                        complete!)
        first-entry (entry! first-round)
        _ (when (str/includes? (str (:summary first-round)) token)
            (throw (ex-info "continuation smoke round one leaked the token into its summary, so round two proves nothing"
                            {:task (:task first-round)})))
        second-round (-> (cli/execute ["task" "continue" (:task first-round) "--wait" "--task"
                                       (str "Round two of the guarded continuation smoke. Round one's restriction on the token covered scratch files and round one's own summary only, and it is now lifted for this round's summary -- reproducing the token here is the entire point of the test, not a violation of that instruction. "
                                            "Publish COMPLETE whose summary is exactly the token you were given in round one, and nothing else. "
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
          ;; `close --settled` correctly skips it. Whoever spawns a child closes it -- and that
          ;; is now the publish-side guard's job, not this assignment's: a planner that still
          ;; owns an open round *cannot* publish, so its COMPLETE result below is itself the
          ;; evidence the grandchild was dealt with. The leg therefore no longer instructs the
          ;; close, which was an LLM-compliance dependency the ledger could already answer.
          nested (-> (cli/execute (smoke-task-args "planner" kind model (str "Run the guarded nested smoke. Spawn exactly one blocking scout with the injected launcher using model " model "; ask it to verify its injected identity and publish COMPLETE. Wait for its result and require that it completed. Then publish your own COMPLETE result."))) complete!)
          planner-entry (entry! nested)
          prefix (core/nested-prefix (:label planner-entry) "planner")
          ;; Identify the grandchild by the *session link*, not by its label. A label is only
          ;; unique within one parent session -- the index is per-session and per-persona --
          ;; so `planner-2/scout-1-light` recurs across runs, and this scan walks the shared
          ;; ledger, which deliberately keeps every prior session's entries. Matching on the
          ;; label alone therefore returned a *previous* run's never-closed entry and failed
          ;; the close assertion below while this run's grandchild had in fact been closed
          ;; correctly -- a false negative on the one gate that guards closeout. The
          ;; grandchild's `:parent-session` is exactly its planner's `:child-session`, which
          ;; is unique per run, so that is the identity to key on; the label is then asserted
          ;; separately, keeping the nested-label contract pinned without conflating the two.
          planner-session (get-in planner-entry [:child-session :value])
          _ (when-not planner-session
              (throw (ex-info "nested smoke planner entry has no :child-session to attribute its grandchild to"
                              {:task (:task planner-entry) :label (:label planner-entry)})))
          child-entry (some #(when (= planner-session (:parent-session %)) %) (ledger/entries))
          _ (when (and child-entry (not (str/starts-with? (str (:label child-entry)) (str prefix "/scout-"))))
              (throw (ex-info "nested smoke grandchild label does not carry its planner's nested prefix"
                              {:expected-prefix (str prefix "/scout-") :actual (:label child-entry) :task (:task child-entry)})))
          ;; Runs before the retro leg's branch so both branches report it, and after the
          ;; nested leg so a failure here is never confused with a spawn or fan-in failure.
          continuation (continue-leg! kind model)]
      (when-not child-entry (throw (ex-info "nested smoke did not record a planner-prefixed scout label" {:planner-label (:label planner-entry)})))
      ;; This root owns the two children it spawned directly, so it closes them itself --
      ;; before the nested-close assertion below, not after. A grandchild the planner failed
      ;; to close is one leaked pane; throwing first would leak all three.
      (close! (:task root) "root")
      (close! (:task nested) "nested")
      ;; Read after the planner published, so its close is already recorded. The publish guard
      ;; makes a *published* planner proof that no open round of its own remained, but it
      ;; discharges a round whose child has vanished without closing anything, so this still
      ;; pins the stronger fact: the pane was actually taken. Naming it keeps cleanup one
      ;; command away.
      (when-not (:closed-at child-entry)
        (throw (ex-info "nested smoke child was never closed by its own parent; close its pane manually"
                        {:task (:task child-entry) :child (:child child-entry) :pane-id (:pane-id child-entry)})))
      (no-process! root)
      (when (:retro root-entry)
        (throw (ex-info "scout leg was not gated out by its frontmatter" {:retro-source (:retro-source root-entry)})))
      ;; Third leg: a gated-in child on an ordinary assignment -- no planted fault. This
      ;; leg proves only the mechanical facts named above; it never asks the child for a
      ;; PROCESS candidate and never grades whether it emits one. The leg is skipped, not
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
                                                             "Display your assignment's current status by running `\"$HERDR_ORCH_BIN\" task status $HERDR_ORCH_TASK`. "
                                                             "Report the status in your summary, then publish COMPLETE, applying the retro instruction in this prompt to your own session as written.")
                                                        :retro? true))
                        complete!)
              retro-entry (retro-gated-in! (entry! retro))]
          (close! (:task retro) "retro")
          (println (core/json-envelope true {:root-task (:task root) :nested-task (:task nested) :retro-task (:task retro)
                                             :continuation continuation
                                             :root-label (:label root-entry) :nested-label (:label child-entry)
                                             :retro-source (:retro-source retro-entry) :process (:process retro)
                                             :child-sessions (mapv (partial session! kind) [root-entry planner-entry child-entry retro-entry])})))))
    (catch Exception e
      (println (core/json-envelope false {:message (.getMessage e) :data (ex-data e)}))
      (System/exit 1))))
