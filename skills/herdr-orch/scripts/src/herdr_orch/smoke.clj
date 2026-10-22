(ns herdr-orch.smoke
  "Explicitly billable delegation smoke. Tests must stub the entire CLI launch boundary."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
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
(defn smoke-task-args [persona model task & {:keys [retro?]}]
  (cond-> ["task" "run" persona]
    retro? (conj "--retro")
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
(defn continue-leg! [model]
  (let [token (str "continuity-" (subs (str (java.util.UUID/randomUUID)) 0 8))
        first-round (-> (cli/execute (smoke-task-args "scout" model
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
;; A non-blocking stream must retain the item order across real agent waits: the first
;; capture is a report, not completion, and the terminal capture follows from the same pane.
(defn stream-leg! [model]
  (let [entry (cli/execute ["task" "start" "scout" "--model" model "--task"
                            "Run the guarded stream smoke. First publish a concise WAITING item with the injected launcher. Then, without waiting for a parent response, publish exactly one COMPLETE item with the injected launcher. Do not publish any other status."])
        task (:task entry)
        waiting (cli/execute ["task" "collect" task "--wait"])
        terminal (cli/execute ["task" "collect" task "--wait"])
        recorded (entry! terminal)]
    (when-not (and (= "WAITING" (:status waiting)) (= 1 (:item waiting))
                   (false? (:terminal? waiting)))
      (throw (ex-info "live smoke did not capture WAITING as the first non-terminal stream item"
                      {:task task :capture waiting})))
    (complete! terminal)
    (when-not (and (= 2 (:item terminal)) (true? (:terminal? terminal))
                   (= ["WAITING" "COMPLETE"] (mapv :status (:items recorded))))
      (throw (ex-info "live smoke did not retain the WAITING then terminal stream"
                      {:task task :capture terminal :items (:items recorded)})))
    (let [closed (close! task "stream")]
      {:task task :items (mapv :item (:items recorded)) :closed-at (:closed-at closed)})))

;; A cross-harness run is enabled by *generating personas that declare the kind*, not by a
;; production override: `oh` has no spawn-time kind selector, so the smoke drives the same
;; frontmatter tier an operator would. The copies keep the shipped bodies, so only the
;; harness differs. Only the three personas this smoke spawns directly need one -- the
;; grandchild the planner spawns inherits the kind Herdr measured for its own parent.
(defn- generate-personas! [kind]
  (let [dir (fs/create-temp-dir {:prefix "orch-smoke-personas-"})]
    (doseq [name ["scout" "planner" "worker"]]
      (spit (str (fs/path dir (str name ".md")))
            (str/replace-first (slurp (str (cli/roster name))) #"\A---\n" (str "---\nkind: " kind "\n"))))
    (str dir)))
;; The binding expression runs before the redef is installed, so it reads the real chain.
(defn- with-personas [kind f]
  (if-not kind
    (f)
    (with-redefs [cli/persona-directories (constantly (cons (generate-personas! kind) (cli/persona-directories)))]
      (f))))

;; The whole smoke, parameterised by the kind its personas declare.
(defn- run! [model kind]
  (let [root (-> (cli/execute (smoke-task-args "scout" model "Run the guarded root smoke: verify HERDR_ORCH_CHILD, HERDR_ORCH_TASK, and HERDR_ORCH_RESULT are set; then publish COMPLETE with a concise summary using the injected launcher.")) complete!)
        root-entry (entry! root)
        _ (when-not (re-matches #"scout-[0-9]+(?:-.+)?" (:label root-entry)) (throw (ex-info "root smoke label is invalid" {:label (:label root-entry)})))
        ;; The nested child is the planner's own, not this root's: its ledger entry records
        ;; the planner's `:parent-session`, so `close` here would refuse it and a root
        ;; `close --settled` correctly skips it. Whoever spawns a child closes it -- and that
        ;; is now the publish-side guard's job, not this assignment's: a planner that still
        ;; owns an open round *cannot* publish, so its COMPLETE result below is itself the
        ;; evidence the grandchild was dealt with. The leg therefore no longer instructs the
        ;; close, which was an LLM-compliance dependency the ledger could already answer.
        nested (-> (cli/execute (smoke-task-args "planner" model (str "Run the guarded nested smoke. Spawn exactly one blocking scout with the injected launcher using model " model "; ask it to verify its injected identity and publish COMPLETE. Wait for its result and require that it completed. Then publish your own COMPLETE result."))) complete!)
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
        continuation (continue-leg! model)
        stream (stream-leg! model)]
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
                                         :continuation continuation :stream stream
                                         :retro-leg "skipped: no retro skill installed"
                                         :root-label (:label root-entry) :nested-label (:label child-entry)
                                         :child-sessions (mapv (partial session! kind) [root-entry planner-entry child-entry])}))
      (let [retro (-> (cli/execute (smoke-task-args "worker" model
                                                    (str "Run the guarded retrospective smoke. "
                                                         "Display your assignment's current status by running `\"$HERDR_ORCH_BIN\" task status $HERDR_ORCH_TASK`. "
                                                         "Report the status in your summary, then publish COMPLETE, applying the retro instruction in this prompt to your own session as written.")
                                                    :retro? true))
                      complete!)
            retro-entry (retro-gated-in! (entry! retro))]
        (close! (:task retro) "retro")
        (println (core/json-envelope true {:root-task (:task root) :nested-task (:task nested) :retro-task (:task retro)
                                           :continuation continuation :stream stream
                                           :root-label (:label root-entry) :nested-label (:label child-entry)
                                           :retro-source (:retro-source retro-entry) :process (:process retro)
                                           :child-sessions (mapv (partial session! kind) [root-entry planner-entry child-entry retro-entry])}))))))

;; The manifest is independent of disposable measurement checkouts. Evidence changes stay
;; in memory before validation or cleanup so a failed flush cannot erase task identity.
(def ^:dynamic ^:private *context-evidence* nil)
(defn- failure-data [e]
  {:message (.getMessage e) :data (ex-data e)})

(defn- persistence-error [e]
  {:message (.getMessage e) :class (str (class e))})

(defn- flush-evidence! [state]
  (let [path (:manifest state)
        temp (str path ".tmp")]
    (spit temp (core/json-envelope true state))
    (java.nio.file.Files/move
     (java.nio.file.Paths/get temp (make-array String 0))
     (java.nio.file.Paths/get path (make-array String 0))
     (into-array java.nio.file.StandardCopyOption
                 [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                  java.nio.file.StandardCopyOption/ATOMIC_MOVE]))))

(defn- evidence! [f & args]
  (let [state (apply swap! *context-evidence* f args)]
    (try
      (flush-evidence! state)
      state
      (catch Exception e
        (swap! *context-evidence* update :persistence-errors (fnil conj []) (persistence-error e))))))

(defn- persistence-ok! []
  (let [state @*context-evidence*]
    (when (seq (:persistence-errors state))
      (throw (ex-info "context smoke manifest persistence failed"
                      {:manifest (:manifest state) :evidence state
                       :persistence-errors (:persistence-errors state)})))))

(defn- remember! [task leg fields]
  (evidence! update-in [:tasks task] merge {:task task :leg leg} fields))

(defn- terminal-capture [entry]
  (let [state (cli/stream-state entry)]
    (when (and (:sealed? state) (cli/stream-captured? state))
      (last (filter #(and (:captured-at %) (:envelope %)
                          (= (:status %) (get-in % [:envelope :status]))
                          (core/terminal-status? (:status %)))
                    (:captured state))))))

(defn- model! [model canonical entry]
  (when-not (and (= "pi" (:kind entry)) (= model (:model entry))
                 (= canonical (:model-canonical entry))
                 (= ["--model" canonical] (:model-args entry)))
    (throw (ex-info "context smoke effective model differs from the explicit selection"
                    {:task (:task entry) :expected model :canonical canonical
                     :actual (select-keys entry [:kind :model :model-canonical :model-args])}))))

(defn- role-snapshot! [entry persona]
  (let [snapshot (:role-snapshot entry)]
    (when-not (and (= persona (:persona snapshot)) (string? (:text snapshot))
                   (= snapshot (core/role-snapshot persona (:text snapshot))))
      (throw (ex-info "context smoke role snapshot is missing or inconsistent" {:task (:task entry) :persona persona})))))

(defn- captured-complete! [entry]
  (let [capture (terminal-capture entry)]
    (when-not (= "COMPLETE" (:status capture))
      (throw (ex-info "context smoke needs a captured terminal COMPLETE result"
                      {:task (:task entry) :capture capture})))
    (when (> (count (str (get-in capture [:envelope :summary]))) 120)
      (throw (ex-info "context smoke summary exceeded the post-hoc 120-character check"
                      {:task (:task entry)})))))

(defn- recorded-sessions [entry]
  (keep :value (cons (:child-session entry) (:child-session-history entry))))

(defn- descendants! [bridge]
  (loop [sources (set (recorded-sessions bridge))
         found {}]
    (let [children (filter #(and (contains? sources (:parent-session %))
                                (not (contains? found (:task %)))) (ledger/entries))]
      (if (empty? children)
        (vec (vals found))
        (do
          (doseq [entry children]
            (remember! (:task entry) "synthetic-descendant"
                       {:entry entry :disposition (if (:closed-at entry) "closed-by-owner" "outstanding")
                        :recovery (when-not (:closed-at entry)
                                    "The recorded parent session owns this descendant; root close cannot bypass that ownership. Recover through that owner, or inspect the existing orphan policy only after proving the owner ended.")}))
          (recur (into sources (mapcat recorded-sessions children))
                 (into found (map (juxt :task identity) children))))))))

(defn- dispose-context-task! [task leg measurement?]
  (try
    (let [entry (ledger/read! task)]
      (remember! task leg {:entry entry})
      (if-not (terminal-capture entry)
        (remember! task leg {:disposition "outstanding"
                            :recovery (str "No stop was attempted. Inspect task status " task
                                           " and collect " task " --wait --timeout 600000 from its owning session; a wait is non-destructive. Capture terminal evidence before guarded close and checkout removal.")})
        (try
          ;; A manifest flush must not decide whether a terminal child receives its
          ;; existing guarded close. These verbs retain their ownership and lifecycle
          ;; guards; a refusal is recovery evidence, never force-close.
          (let [closed (close! task leg)]
            (remember! task leg {:close closed :disposition "closed"})
            (when measurement?
              (let [removed (cli/execute ["worktree" "remove" task])]
                (remember! task leg {:checkout-removal removed})
                (when-not (= "removed" (:status removed))
                  (throw (ex-info "context smoke checkout removal was refused" {:task task :outcome removed}))))))
          (catch Exception e
            (remember! task leg {:disposition "recovery-required"
                                :recovery (failure-data e)})))))
    (catch Exception e
      (remember! task leg {:disposition "recovery-required"
                          :recovery (failure-data e)}))))

(defn- collect-round! [leg task timeout model bridge?]
  (let [outcome (cli/execute ["task" "collect" task "--wait" "--timeout" (str timeout)])]
    (remember! task leg {:outcome outcome})
    (persistence-ok!)
    (let [entry (ledger/read! task)]
      (remember! task leg {:entry entry})
      (persistence-ok!)
      (when bridge? (descendants! entry))
      (complete! outcome)
      (when-not (true? (:terminal? outcome))
        (throw (ex-info "context smoke capture is not terminal" {:task task :outcome outcome})))
      (captured-complete! entry)
      (model! model (:canonical @*context-evidence*) entry)
      entry)))

;; A `follow-up` assignment continues the captured first round in the same child, and the
;; newest round is the one validated and closed: `close` addresses only the newest round.
(defn- context-task! [leg command model validate! measurement? bridge? & [follow-up]]
  (let [canonical (:canonical @*context-evidence*)
        preview (cli/execute (conj command "--print-prompt"))
        _ (evidence! assoc-in [:previews leg] preview)
        _ (persistence-ok!)
        _ (model! model canonical preview)
        started (cli/execute (assoc command 1 "start"))
        first-task (:task started)
        current (volatile! first-task)]
    (when-not first-task (throw (ex-info "context smoke start returned no task identity" {:leg leg :outcome started})))
    (try
      (remember! first-task leg {:start started :disposition "outstanding"})
      (persistence-ok!)
      (let [entry (collect-round! leg first-task (:timeout preview) model bridge?)
            entry (if-not follow-up
                    entry
                    (let [continued (cli/execute ["task" "continue" first-task "--task" follow-up])
                          task (:task continued)]
                      (when-not task (throw (ex-info "context smoke continue returned no task identity" {:leg leg :outcome continued})))
                      (vreset! current task)
                      (remember! first-task leg {:disposition "continued" :successor task})
                      (remember! task leg {:start continued :continues first-task :disposition "outstanding"})
                      (persistence-ok!)
                      (collect-round! leg task (:timeout preview) model bridge?)))]
        (validate! entry))
      (finally
        (when bridge?
          (try (descendants! (ledger/read! @current))
               (catch Exception e
                 (remember! @current leg {:descendant-discovery-error (.getMessage e)}))))
        (dispose-context-task! @current leg measurement?)))
    (let [task @current
          record (get-in @*context-evidence* [:tasks task])]
      (when-not (= "closed" (:disposition record))
        (throw (ex-info "context smoke task needs recovery" {:task task :record record})))
      (persistence-ok!)
      task)))

;; A nil `persona` is the persona-less leg: it must resolve no role at all.
(defn- completed-and-closed! [leg command model persona]
  (context-task! leg command model
                 (fn [entry]
                   (when-not (= {:context "fresh" :execution "interactive"} (select-keys entry [:context :execution]))
                     (throw (ex-info "fresh context smoke did not resolve fresh interactive execution" {:task (:task entry)})))
                   (if persona
                     (do (when-not (= persona (:persona entry))
                           (throw (ex-info "fresh named smoke child resolved another persona" {:task (:task entry) :persona (:persona entry)})))
                         (role-snapshot! entry persona))
                     (when-not (and (true? (:role-absent entry)) (nil? (:role-snapshot entry)))
                       (throw (ex-info "persona-less smoke child resolved a role" {:task (:task entry)})))))
                 false false))

;; The fork child's own first user message after the adapter's marker is its launch prompt.
;; The forks are synthetic sessions, so the root may read them.
(def ^:private fork-marker "herdr-orch-fork") ; FORK_MARKER in scripts/pi/fork-adapter.ts
(defn- fork-assignment [fork]
  (let [entries (->> (str/split-lines (slurp (get-in fork [:fork :session-file])))
                     (remove str/blank?)
                     (map #(json/parse-string % true)))
        content (->> entries
                     (drop-while #(not= fork-marker (:customType %)))
                     (filter #(= "user" (get-in % [:message :role])))
                     first :message :content)]
    (if (string? content) content (str/join (keep :text content)))))

;; The fork's active history: the entries from its marker's parent back to the root. The
;; file also holds the source's later entries, off this path. `:boundary` is the marker's
;; parent entry, which must be a completed response for the history to end at a turn.
(defn- fork-history [fork]
  (let [entries (->> (str/split-lines (slurp (get-in fork [:fork :session-file])))
                     (remove str/blank?)
                     (map #(json/parse-string % true)))
        by-id (into {} (map (juxt :id identity)) entries)
        marker (first (filter #(= fork-marker (:customType %)) entries))
        boundary (get by-id (:parentId marker))]
    {:boundary boundary
     :text (->> (iterate #(get by-id (:parentId %)) boundary)
                (take-while some?)
                (map #(pr-str (or (:message %) (:content %))))
                str/join)}))
(defn- completed-response? [entry]
  (let [message (:message entry)]
    (and (= "assistant" (:role message)) (= "stop" (:stopReason message))
         (not-any? #(= "toolCall" (:type %)) (:content message)))))

;; Only the synthetic bridge's recorded session can identify its descendants. The
;; root records them even on failure but never assumes their parent's close authority.
;; A fork inherits only completed turns, so the bridge first completes a round that holds a
;; fresh code word, then forks in a continued round. Each fork must report that word from
;; inherited context: both forks see the same history, and the word is absent from their
;; assignments.
(defn- fork-context-leg! [model]
  (let [canonical (:canonical @*context-evidence*)
        code (str "synthetic-inherited-" (subs (str (java.util.UUID/randomUUID)) 0 8))
        fork-task "Publish COMPLETE with the exact summary set to the synthetic code word from the inherited conversation."
        second-round "Synthetic fork smoke, second round."
        ;; pr-str is not shell quoting; model IDs go through the existing shell helper.
        quoted-model (#'cli/shell-quote model)
        first-command (str "task run --context fork --model " quoted-model)
        second-command (str "task run scout --context fork --model " quoted-model)]
    (evidence! assoc :fork-code code)
    (context-task!
     "fork-context"
     ["task" "run" "worker" "--context" "fresh" "--model" model "--spawns" "worker scout" "--task"
      (str "Synthetic fork smoke, first round. Remember the synthetic code word " code " for a later round. Launch nothing in this round. Publish COMPLETE with the exact summary synthetic-bridge-ready.")]
     model
     (fn [entry]
       (when-not (= {:context "fresh" :execution "interactive" :persona "worker"}
                    (select-keys entry [:context :execution :persona]))
         (throw (ex-info "synthetic bridge is not a fresh worker" {:task (:task entry)})))
       (role-snapshot! entry "worker")
       (let [source (get-in entry [:child-session :value])
             forks (descendants! entry)]
         (when-not (and (= "path" (get-in entry [:child-session :kind])) source)
           (throw (ex-info "synthetic fork source has no recorded Pi session path" {:task (:task entry)})))
         (when-not (= #{["snapshot" "worker"] ["overlay" "scout"]}
                      (set (map #(vector (get-in % [:fork :role-source]) (:persona %)) forks)))
           (throw (ex-info "synthetic fork smoke did not record both fork shapes" {:task (:task entry)})))
         (when-not (= 2 (count forks))
           (throw (ex-info "synthetic fork smoke did not record exactly two descendants" {:task (:task entry)})))
         (doseq [fork forks]
           (when-not (and (= "fork" (:context fork)) (= "interactive" (:execution fork))
                          (= source (:parent-session fork)) (= source (get-in fork [:fork :source-session])))
             (throw (ex-info "fork smoke source was not the controlled fresh session" {:task (:task fork)})))
           (role-snapshot! fork (:persona fork))
           (when (= "snapshot" (get-in fork [:fork :role-source]))
             (when-not (= (:role-snapshot entry) (:role-snapshot fork))
               (throw (ex-info "fork did not inherit the worker role snapshot" {:task (:task fork)}))))
           (model! model canonical fork)
           (captured-complete! fork)
           (when-not (= code (some-> (get-in (terminal-capture fork) [:envelope :summary]) str/trim))
             (throw (ex-info "fork did not report the inherited code word" {:task (:task fork)})))
           (let [assignment (fork-assignment fork)]
             (when-not (and (str/includes? assignment fork-task) (not (str/includes? assignment code)))
               (throw (ex-info "fork assignment was not the synthetic task without the code word" {:task (:task fork)}))))
           ;; The code word alone would also pass if the fork inherited the launching round.
           (let [{inherited :text boundary :boundary} (fork-history fork)]
             (when-not (and (completed-response? boundary) (str/includes? inherited code) (not (str/includes? inherited second-round)))
               (throw (ex-info "fork history does not end at the bridge's completed first round" {:task (:task fork)}))))
           (when-not (:closed-at fork)
             (throw (ex-info "synthetic fork was not closed by its owner" {:task (:task fork)}))))))
     false true
     (str second-round " Use the injected launcher for exactly two blocking fork rounds from this Pi session: first `" first-command "` with no persona, then `" second-command "`. Before each launch, preview that same command with --print-prompt and --task '" fork-task "'; require kind pi, model " model ", model-canonical " canonical " and model-args [--model " canonical "]. Refuse a mismatch before launch. Give each child exactly that assignment text and nothing else. Capture a terminal COMPLETE and close each child before starting the next. On WAITING or pending, retain its task ID for recovery; waiting does not stop a child. Publish COMPLETE with a summary of at most 120 characters only after both closes. Do not read, report or write any non-synthetic conversation content."))))

(defn- measurement-config! [dir]
  (let [system (str (fs/path dir "system.md"))
        config (str (fs/path dir "measurement.json"))]
    (spit system "This is synthetic context-smoke data. Reply with exactly synthetic-context-smoke.")
    (spit config "{\"systemPromptFile\":\"system.md\",\"tools\":[],\"hardTimeoutMs\":120000}")
    config))

(defn- measurement-context-leg! [model config monitor?]
  (context-task!
   (if monitor? "measurement-monitor" "measurement-headless")
   (cond-> ["task" "run" "--context" "measurement" "--measurement-config" config "--model" model "--worktree" "new" "--task" "Reply with exactly synthetic-context-smoke."]
     monitor? (conj "--tab"))
   model
   (fn [entry]
     (when-not (and (= "measurement" (:context entry)) (= "headless" (:execution entry))
                    (= monitor? (boolean (:monitor entry))))
       (throw (ex-info "measurement context or monitor differed from the request" {:task (:task entry)}))))
   true false))

(defn- context-modes! [model]
  (let [dir (str (fs/create-dirs (fs/path (ledger/result-directory) "context-smoke" (str (java.util.UUID/randomUUID)))))
        manifest (str (fs/path dir "manifest.json"))]
    (binding [*context-evidence* (atom {:manifest manifest :model model :tasks {}})]
      (try
        (evidence! assoc :state "preparing")
        (persistence-ok!)
        (evidence! assoc :canonical (core/canonical-model (cli/config) model))
        (persistence-ok!)
        (let [config (measurement-config! dir)]
          (evidence! assoc :state "running" :measurement-config config)
          (persistence-ok!)
          (completed-and-closed! "fresh-named" ["task" "run" "scout" "--model" model "--task" "Publish COMPLETE with the exact summary synthetic-fresh-smoke."] model "scout")
          (completed-and-closed! "fresh-persona-less" ["task" "run" "--model" model "--task" "Publish COMPLETE with the exact summary synthetic-persona-less-smoke."] model nil)
          (fork-context-leg! model)
          (measurement-context-leg! model config false)
          (measurement-context-leg! model config true))
        (evidence! assoc :state "complete")
        (persistence-ok!)
        (println (core/json-envelope true @*context-evidence*))
        (catch Exception e
          (evidence! assoc :state "failed" :error (failure-data e))
          (throw (ex-info "context smoke failed; retain the recovery manifest"
                          {:manifest manifest :evidence @*context-evidence*
                           :persistence-errors (:persistence-errors @*context-evidence*)
                           :failure (failure-data e)} e)))))))

(defn -main [& args]
  (try
    (when-not (= "1" (required! "HERDR_ENV")) (throw (ex-info "live smoke requires HERDR_ENV=1" {})))
    (when-not (= "1" (required! "ORCH_LIVE_SMOKE")) (throw (ex-info "live smoke requires ORCH_LIVE_SMOKE=1" {})))
    (let [model (required! "ORCH_LIVE_SMOKE_MODEL")
          kind (some-> (System/getenv "ORCH_LIVE_SMOKE_KIND") str/trim not-empty)]
      (case (first args)
        nil (with-personas kind #(run! model kind))
        "context-modes" (do
                          (when kind (throw (ex-info "context-modes smoke requires the caller's Pi harness, not ORCH_LIVE_SMOKE_KIND" {:kind kind})))
                          (context-modes! model))
        (throw (ex-info "unknown live smoke mode" {:mode (first args)}))))
    (catch Exception e
      (println (core/json-envelope false {:message (.getMessage e) :data (ex-data e)}))
      (System/exit 1))))
