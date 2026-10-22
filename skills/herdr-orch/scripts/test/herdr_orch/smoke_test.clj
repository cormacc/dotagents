(ns herdr-orch.smoke-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.cli :as cli]
            [herdr-orch.core :as core]
            [herdr-orch.herdr :as herdr]
            [herdr-orch.ledger :as ledger]
            [herdr-orch.smoke :as smoke]))

(def model "openai-codex/gpt-6-luna")
(def snapshot (core/role-snapshot "worker" "---\nmodel: light\ntimeout: 1800000\n---\nSynthetic worker"))
(defn invoke [n & args] (apply (ns-resolve 'herdr-orch.smoke n) args))
(defn error-of [f] (try (f) nil (catch Exception e e)))

(defn publish [entry status summary captured?]
  (let [text (core/envelope (assoc entry :status status :summary summary))
        parsed (core/validate-envelope entry text)]
    (spit (:result entry) text)
    (if captured?
      (cli/record-item-capture entry {:item 1 :result (:result entry)}
                               {:status status :captured-at "captured" :envelope parsed})
      entry)))

;; Every billable helper is reached only inside this complete CLI replacement.
;; Unexpected commands throw, and the lower Herdr boundary also refuses escapes.
(defn offline [options]
  (let [dir (str (fs/create-temp-dir {:prefix "context-smoke-test-"}))
        entries (atom {}) calls (atom []) n (atom 0) code (atom nil)
        result (atom nil) error (atom nil)
        ;; Round 2 of the bridge is task-3-r2; per-round options use the key "3-r2".
        round-key (fn [task] (let [i (parse-long (re-find #"\d+" task))] (if (str/ends-with? task "-r2") (str i "-r2") i)))
        selected (or (:model options) model)
        canonical (get-in options [:config :aliases selected] selected)
        execute (fn [argv]
                  (swap! calls conj argv)
                  (cond
                    (some #{"--print-prompt"} argv)
                    (merge {:kind "pi" :model selected :model-canonical canonical
                            :model-args ["--model" canonical]
                            :timeout (if (= "worker" (nth argv 2)) 1800000 600000)}
                           (:preview options))

                    (= "start" (second argv))
                    (let [i (swap! n inc) task (str "task-" i)
                          measurement? (some #{"measurement"} argv)
                          entry (cond-> {:task task :child (str "child-" i) :result (str (fs/path dir (str task ".result")))
                                         :work-root dir :kind "pi" :model selected :model-canonical canonical
                                         :model-args ["--model" canonical] :parent-session "root"
                                         :context (if measurement? "measurement" "fresh")
                                         :execution (if measurement? "headless" "interactive") :items []
                                         :persona (if (= "worker" (nth argv 2)) "worker" "scout")}
                                  (= "scout" (nth argv 2)) (assoc :role-snapshot (core/role-snapshot "scout" "---\nmodel: light\n---\nSynthetic scout"))
                                  (and (= "--model" (nth argv 2)) (not measurement?)) (assoc :persona "anonymous" :role-absent true)
                                  (= i 3) (assoc :child-session {:kind "path" :value "synthetic-source"} :role-snapshot snapshot)
                                  measurement? (assoc :measurement {:evidence-dir (str dir "/evidence-" i)} :worktree {:path (str dir "/checkout-" i)})
                                  (some #{"--tab"} argv) (assoc :monitor {:pane-id "owned-monitor"}))]
                      (swap! entries assoc task entry)
                      (when (= i 3) (reset! code (re-find #"synthetic-inherited-[0-9a-f]{8}" (last argv))))
                      (when-let [change (:started options)] (swap! entries change i))
                      {:task task :status "started"})

                    ;; The bridge forks only in its continued round, as a real fork needs a
                    ;; completed earlier turn to inherit.
                    (= "continue" (second argv))
                    (let [prior (nth argv 2) task (str prior "-r2")
                          assignment (second (re-find #"--task '([^']+)'" (last argv)))]
                      (swap! entries assoc task (-> (get @entries prior) (dissoc :closed-at)
                                                    (assoc :task task :continues prior :items []
                                                           :result (str (fs/path dir (str task ".result"))))))
                      (doseq [[id role persona] [["fork-worker" "snapshot" "worker"] ["fork-scout" "overlay" "scout"]]]
                        (let [session (str (fs/path dir (str id ".jsonl")))
                              _ (spit session (str/join "\n" (map json/generate-string
                                                                  ;; Round 2 is in the copied file but off the active path.
                                                                  [{:type "session" :id id}
                                                                   {:type "message" :id "r1" :parentId nil :message {:role "user" :content (str "Remember " @code)}}
                                                                   {:type "message" :id "r1-done" :parentId "r1" :message {:role "assistant" :stopReason "stop" :content [{:type "text" :text "retained"}]}}
                                                                   {:type "message" :id "r2" :parentId "r1-done" :message {:role "user" :content (last argv)}}
                                                                   {:type "custom" :id "marker" :parentId "r1-done" :customType "herdr-orch-fork"}
                                                                   {:type "message" :id "launch" :parentId "marker" :message {:role "user" :content [{:type "text" :text (str "Fork task boundary\n" assignment)}]}}])))
                              fork {:task id :child id :result (str (fs/path dir (str id ".result")))
                                    :work-root dir :kind "pi" :model selected :model-canonical canonical :model-args ["--model" canonical]
                                    :context "fork" :execution "interactive" :persona persona :role-snapshot (if (= persona "worker") snapshot (core/role-snapshot "scout" "---\nmodel: light\n---\nSynthetic scout"))
                                    :parent-session "synthetic-source" :closed-at "owner-closed"
                                    :fork {:role-source role :source-session "synthetic-source" :session-file session}}
                              fork (publish fork "COMPLETE" @code true)]
                          (swap! entries assoc id fork)))
                      (when-let [change (:forks options)] (swap! entries change))
                      {:task task :status "prompted" :continues prior})

                    (= "collect" (second argv))
                    (let [task (nth argv 2) i (round-key task)
                          _ (when-let [check (:before-collect options)] (check dir task))
                          status (get-in options [:statuses i] "COMPLETE")
                          summary (get-in options [:summaries i] "short")
                          entry (get @entries task)
                          entry (if (= "pending" status) entry (publish entry status summary true))
                          entry (merge entry (get-in options [:recorded i]))]
                      (swap! entries assoc task entry)
                      (when-let [message (get-in options [:collect-errors i])] (throw (ex-info message {:task task})))
                      (merge {:task task :status status :summary summary :terminal? (core/terminal-status? status)}
                             (get-in options [:outcomes i])))

                    (= "close" (second argv))
                    (let [task (nth argv 2)]
                      (when-let [check (:before-close options)] (check dir task))
                      (when-let [reason (:close-refusal options)] (throw (ex-info reason {:task task})))
                      (swap! entries update task assoc :closed-at "closed")
                      {:status "closed" :task task :closed-at "closed"})

                    (= ["worktree" "remove"] (subvec argv 0 2))
                    (if (:remove-refusal options)
                      (throw (ex-info "checkout is dirty" {:task (nth argv 2)}))
                      {:status "removed" :task (nth argv 2)})

                    :else (throw (ex-info "unexpected offline CLI command" {:argv argv}))))]
    (try
      (with-redefs [cli/execute execute cli/config (constantly (:config options {}))
                    ledger/result-directory (constantly dir)
                    ledger/read! (fn [task] (or (get @entries task) (throw (ex-info "missing fake task" {:task task}))))
                    ledger/entries (fn [] (vals @entries))
                    herdr/invoke (fn [argv] (throw (ex-info "LIVE HERDR FORBIDDEN" {:argv argv})))]
        (reset! result (with-out-str (reset! error (error-of #(invoke 'context-modes! selected))))))
      (let [manifests (fs/glob dir "context-smoke/*/manifest.json")
            manifest (when-let [path (first manifests)]
                       (let [envelope (json/parse-string (slurp (str path)) true)]
                         (assert (:ok envelope)) (:result envelope)))]
        {:manifest manifest :error @error :stdout @result :calls @calls :entries @entries})
      (finally (fs/delete-tree dir)))))

(defn record-for [r task] (get-in r [:manifest :tasks (keyword task)]))
(defn failure-evidence [r] (get-in (ex-data (:error r)) [:evidence]))
(defn failure-record-for [r task] (get-in (failure-evidence r) [:tasks task]))
(defn commands [r verb] (filter #(= verb (second %)) (:calls r)))

(deftest complete-path-records-every-round-and-closes-only-direct-children
  (let [r (offline {})]
    (is (nil? (:error r)))
    (is (= "complete" (get-in r [:manifest :state])))
    (is (= 8 (count (get-in r [:manifest :tasks]))))
    (is (= 5 (count (commands r "close"))))
    (is (= 2 (count (commands r "remove"))))
    (is (= #{"task-1" "task-2" "task-3-r2" "task-4" "task-5"} (set (map #(nth % 2) (commands r "close")))))
    (is (= {:disposition "continued" :successor "task-3-r2"} (select-keys (record-for r "task-3") [:disposition :successor])))
    (is (= ["task-3"] (mapv #(nth % 2) (commands r "continue"))))
    (is (= "closed-by-owner" (:disposition (record-for r "fork-worker"))))
    (is (= "closed-by-owner" (:disposition (record-for r "fork-scout"))))
    (is (every? #(= "closed" (:disposition (record-for r (str "task-" %)))) [1 2 "3-r2" 4 5]))
    (is (every? #(= "removed" (get-in (record-for r (str "task-" %)) [:checkout-removal :status])) [4 5]))
    (is (every? #(= model (get-in % [:entry :model-canonical])) (vals (get-in r [:manifest :tasks]))))
    (is (= ["600000" "600000" "1800000" "1800000" "600000" "600000"] (mapv last (commands r "collect"))))))

(deftest fork-code-word-is-in-the-completed-first-round-and-not-in-the-fork-assignment
  (let [r (offline {})
        first-round (last (first (filter #(and (= "start" (second %)) (= "worker" (nth % 2))) (:calls r))))
        second-round (last (first (commands r "continue")))
        code (get-in r [:manifest :fork-code])]
    (is (nil? (:error r)))
    (is (re-matches #"synthetic-inherited-[0-9a-f]{8}" code))
    (is (str/includes? first-round code))
    (is (str/includes? first-round "Launch nothing in this round"))
    (is (not (str/includes? second-round code)))
    (is (str/includes? second-round "task run --context fork"))))

(deftest returned-task-is-durable-before-collect
  (let [observed (atom [])
        r (offline {:before-collect
                    (fn [dir task]
                      (let [path (first (fs/glob dir "context-smoke/*/manifest.json"))
                            envelope (json/parse-string (slurp (str path)) true)]
                        (assert (:ok envelope))
                        (swap! observed conj (get-in envelope [:result :tasks (keyword task) :start :task]))))})]
    (is (nil? (:error r)))
    (is (= ["task-1" "task-2" "task-3" "task-3-r2" "task-4" "task-5"] @observed))))

(deftest terminal-failure-and-oversized-summary-still-close
  (doseq [options [{:statuses {1 "FAILED"}} {:statuses {1 "BLOCKED"}} {:summaries {1 (apply str (repeat 121 "x"))}}]]
    (let [r (offline options)]
      (is (some? (:error r)))
      (is (= 1 (count (commands r "close"))))
      (is (= "closed" (:disposition (record-for r "task-1"))))))
  (let [r (offline {:statuses {2 "FAILED"}})]
    (is (some? (:error r)))
    (is (= #{:task-1 :task-2} (set (keys (get-in r [:manifest :tasks])))))
    (is (= "closed" (:disposition (record-for r "task-1"))))
    (is (= "FAILED" (get-in (record-for r "task-2") [:outcome :status])))))

(deftest pending-and-waiting-are-outstanding-not-stopped
  (doseq [status ["pending" "WAITING"] i [1 3 "3-r2" 4]]
    (let [r (offline {:statuses {i status}}) task (str "task-" i)]
      (is (some? (:error r)))
      (is (= "outstanding" (:disposition (record-for r task))))
      (is (str/includes? (:recovery (record-for r task)) "No stop was attempted"))
      (is (not-any? #(str/starts-with? (nth % 2) "task-3") (when (#{3 "3-r2"} i) (commands r "close"))))
      (is (not-any? #(= task (nth % 2)) (commands r "close")))
      (is (empty? (commands r "remove")))
      ;; An unfinished first round is never continued, so no fork exists yet.
      (when (= i 3) (is (empty? (commands r "continue"))) (is (nil? (record-for r "fork-worker"))))
      (when (= i "3-r2") (is (some? (record-for r "fork-worker")))))))

(deftest measurement-cleanup-is-guarded-and-failure-evidence-survives
  (let [r (offline {:statuses {4 "FAILED"}})]
    (is (some? (:error r)))
    (is (= "closed" (:disposition (record-for r "task-4"))))
    (is (= "removed" (get-in (record-for r "task-4") [:checkout-removal :status])))
    (is (str/includes? (get-in (record-for r "task-4") [:entry :measurement :evidence-dir]) "evidence-4")))
  (doseq [options [{:close-refusal "working or unknown occupant"} {:remove-refusal true}]]
    (let [r (offline options) task (if (:close-refusal options) "task-1" "task-4")]
      (is (some? (:error r)))
      (is (= "recovery-required" (:disposition (record-for r task))))
      (is (some? (:recovery (record-for r task))))
      (when (:close-refusal options) (is (empty? (commands r "remove"))))))
  (let [r (offline {:collect-errors {4 "lost wait"} :statuses {4 "pending"}})]
    (is (some? (:error r)))
    (is (= "outstanding" (:disposition (record-for r "task-4"))))
    (is (= 7 (count (get-in r [:manifest :tasks]))))))

(deftest manifest-persistence-failures-retain-terminal-cleanup-and-recovery-evidence
  (doseq [status ["COMPLETE" "FAILED"]]
    (let [original-spit spit
          armed? (atom false)
          r (with-redefs [clojure.core/spit
                          (fn [path content & options]
                            (if (and @armed? (str/ends-with? (str path) "/manifest.json.tmp"))
                              (throw (java.io.IOException. "synthetic persistent manifest refusal"))
                              (apply original-spit path content options)))]
              (offline {:statuses {1 status}
                        :before-collect (fn [_ _] (reset! armed? true))}))]
      (is (some? (:error r)))
      (is (= status (get-in r [:entries "task-1" :items 0 :envelope :status])))
      (is (= 1 (count (commands r "close"))))
      (is (= "closed" (:disposition (failure-record-for r "task-1"))))
      (is (seq (get-in (ex-data (:error r)) [:persistence-errors])))
      (is (= 1 (count (commands r "start")))))))

(deftest transient-manifest-refusal-remains-a-failure-after-later-flush
  (let [original-spit spit
        armed? (atom false)
        remaining (atom 1)
        r (with-redefs [clojure.core/spit
                        (fn [path content & options]
                          (if (and @armed? (str/ends-with? (str path) "/manifest.json.tmp") (pos? @remaining))
                            (do (swap! remaining dec)
                                (throw (java.io.IOException. "synthetic transient manifest refusal")))
                            (apply original-spit path content options)))]
            (offline {:before-collect (fn [_ _] (reset! armed? true))}))
        error-data (ex-data (:error r))
        envelope (json/parse-string (core/json-envelope false {:message (.getMessage (:error r)) :data error-data}) true)]
    (is (some? (:error r)))
    (is (= 1 (count (commands r "close"))))
    (is (= 1 (count (commands r "start"))))
    (is (= "closed" (:disposition (failure-record-for r "task-1"))))
    (is (= "failed" (:state (:manifest r))))
    (is (= 1 (count (:persistence-errors (:manifest r)))))
    (is (false? (:ok envelope)))
    (is (= (get error-data :manifest) (get-in envelope [:error :data :manifest])))))

(deftest manifest-obstruction-preserves-prior-tasks-and-primary-failure-in-memory
  (let [r (offline {:summaries {2 (apply str (repeat 121 "x"))}
                    :before-close (fn [dir task]
                                    (when (= "task-2" task)
                                      (let [manifest (first (fs/glob dir "context-smoke/*/manifest.json"))]
                                        (assert (fs/regular-file? manifest))
                                        (fs/create-dir (str manifest ".tmp")))))})
        error-data (ex-data (:error r))]
    (is (some? (:error r)))
    (is (= 2 (count (commands r "close"))))
    (is (= "closed" (:disposition (failure-record-for r "task-1"))))
    (is (= "closed" (:disposition (failure-record-for r "task-2"))))
    (is (= "COMPLETE" (get-in (failure-record-for r "task-2") [:outcome :status])))
    (is (str/includes? (get-in error-data [:failure :message]) "summary exceeded"))
    (is (seq (:persistence-errors error-data)))
    (is (nil? (get-in (record-for r "task-2") [:close])))
    (is (= "outstanding" (:disposition (record-for r "task-2"))))
    (is (= 2 (count (commands r "start"))))))

(deftest cleanup-failure-and-pending-capture-remain-safe-when-persistence-fails
  (let [r (offline {:close-refusal "guarded close refused"
                    :before-close (fn [dir task]
                                    (when (= "task-1" task)
                                      (let [manifest (first (fs/glob dir "context-smoke/*/manifest.json"))]
                                        (assert (fs/regular-file? manifest))
                                        (fs/create-dir (str manifest ".tmp")))))})]
    (is (some? (:error r)))
    (is (= 1 (count (commands r "close"))))
    (is (= "recovery-required" (:disposition (failure-record-for r "task-1"))))
    (is (= "guarded close refused" (get-in (failure-record-for r "task-1") [:recovery :message])))
    (is (seq (get-in (ex-data (:error r)) [:persistence-errors]))))
  (let [original-spit spit
        armed? (atom false)
        r (with-redefs [clojure.core/spit
                        (fn [path content & options]
                          (if (and @armed? (str/ends-with? (str path) "/manifest.json.tmp"))
                            (throw (java.io.IOException. "synthetic pending manifest refusal"))
                            (apply original-spit path content options)))]
            (offline {:statuses {1 "WAITING"}
                      :before-collect (fn [_ _] (reset! armed? true))}))]
    (is (some? (:error r)))
    (is (= "outstanding" (:disposition (failure-record-for r "task-1"))))
    (is (empty? (commands r "close")))
    (is (empty? (commands r "remove")))))

(deftest measurement-removal-follows-successful-close-despite-transient-manifest-refusal
  (let [original-spit spit
        armed? (atom false)
        remaining (atom 1)
        r (with-redefs [clojure.core/spit
                        (fn [path content & options]
                          (if (and @armed? (str/ends-with? (str path) "/manifest.json.tmp") (pos? @remaining))
                            (do (swap! remaining dec)
                                (throw (java.io.IOException. "synthetic measurement manifest refusal")))
                            (apply original-spit path content options)))]
            (offline
             {:before-close (fn [_ task]
                              (when (= "task-4" task) (reset! armed? true)))}))]
    (is (some? (:error r)))
    (is (= 4 (count (commands r "close"))))
    (is (= 1 (count (commands r "remove"))))
    (is (= "closed" (:disposition (failure-record-for r "task-4"))))
    (is (= "removed" (get-in (failure-record-for r "task-4") [:checkout-removal :status])))
    (is (= 4 (count (commands r "start"))))))

(deftest fork-treatments-source-capture-model-and-disposition-are-required
  (doseq [[label change]
          [["two overlays" #(-> %
                                (assoc-in ["fork-worker" :fork :role-source] "overlay")
                                (assoc-in ["fork-worker" :persona] "scout")
                                (assoc-in ["fork-worker" :role-snapshot] (get-in % ["fork-scout" :role-snapshot])))]
           ["extra descendant" #(assoc % "extra" (assoc (get % "fork-scout") :task "extra"))]
           ["wrong source" #(assoc-in % ["fork-worker" :fork :source-session] "root")]
           ["different snapshot" #(assoc-in % ["fork-worker" :role-snapshot :sha256] "bad")]
           ["missing snapshot" #(update % "task-3-r2" dissoc :role-snapshot)]
           ["wrong overlay role" #(assoc-in % ["fork-scout" :role-snapshot] snapshot)]
           ["uncaptured" #(assoc-in % ["fork-worker" :items] [])]
           ["partial published file" (fn [entries]
                                       (spit (get-in entries ["fork-worker" :result]) "partial")
                                       (assoc-in entries ["fork-worker" :items] []))]
           ["partial capture" #(update % "fork-worker" publish "WAITING" "still working" true)]
           ["failed fork" #(update % "fork-worker" publish "FAILED" "failed" true)]
           ["unclosed" #(update % "fork-worker" dissoc :closed-at)]
           ["canonical mismatch" #(assoc-in % ["fork-worker" :model-canonical] "anthropic/claude-sonnet-5-5")]
           ["no explicit model" #(assoc-in % ["fork-worker" :model] "light")]]]
    (testing label
      (let [r (offline {:forks change})]
        (is (some? (:error r)))
        (is (= "closed" (:disposition (record-for r "task-3-r2"))))
        (is (some? (record-for r "fork-worker")))
        (is (not-any? #(str/starts-with? (nth % 2) "fork-") (commands r "close")))))))

;; Inheritance is proved only when each fork reports the first round's code word and its own
;; launch prompt carries the synthetic task without that word.
(deftest fork-inheritance-evidence-is-required
  (let [rewrite (fn [fork f] (fn [entries]
                               (let [path (get-in entries [fork :fork :session-file])]
                                 (spit path (f (slurp path) (get-in entries [fork :items 0 :envelope :summary])))
                                 entries)))]
    (doseq [[label change reason]
            [["wrong code word" #(update % "fork-worker" publish "COMPLETE" "synthetic-fork-smoke" true) "inherited code word"]
             ["code word in assignment" (rewrite "fork-scout" #(str/replace %1 "Fork task boundary" %2)) "without the code word"]
             ["assignment without the fork task" (rewrite "fork-worker" (fn [text _] (str/replace text "from the inherited conversation" "from elsewhere"))) "without the code word"]
             ;; The old batch-parent boundary: the fork inherits the round that launched it.
             ["fork inherits the launching round" (rewrite "fork-scout" (fn [text _] (str/replace text "\"id\":\"marker\",\"parentId\":\"r1-done\"" "\"id\":\"marker\",\"parentId\":\"r2\""))) "completed first round"]
             ;; Too early: round 1's request without its completed answer.
             ["fork ends before the completed first round" (rewrite "fork-worker" (fn [text _] (str/replace text "\"id\":\"marker\",\"parentId\":\"r1-done\"" "\"id\":\"marker\",\"parentId\":\"r1\""))) "completed first round"]
             ["fork without the inherited round" (rewrite "fork-worker" (fn [text _] (str/replace text "\"id\":\"marker\",\"parentId\":\"r1-done\"" "\"id\":\"marker\",\"parentId\":null"))) "completed first round"]]]
      (testing label
        (let [r (offline {:forks change})]
          (is (str/includes? (str (get-in (ex-data (:error r)) [:failure :message])) reason))
          (is (= "closed" (:disposition (record-for r "task-3-r2"))))
          (is (not-any? #(str/starts-with? (nth % 2) "fork-") (commands r "close"))))))))

;; Each fresh leg must resolve its own role shape, not merely fresh interactive execution.
(deftest fresh-legs-require-their-role-shape
  (doseq [[label i change reason] [["persona-less leg gains a role" 2 #(assoc-in % ["task-2" :role-snapshot] snapshot) "persona-less smoke child resolved a role"]
                                   ["persona-less leg not marked role-absent" 2 #(update % "task-2" dissoc :role-absent) "persona-less smoke child resolved a role"]
                                   ["named leg resolves another persona" 1 #(assoc-in % ["task-1" :persona] "worker") "resolved another persona"]
                                   ["named leg without a snapshot" 1 #(update % "task-1" dissoc :role-snapshot) "role snapshot is missing"]]]
    (testing label
      (let [r (offline {:started (fn [entries n] (if (= n i) (change entries) entries))})]
        (is (str/includes? (str (get-in (ex-data (:error r)) [:failure :message])) reason))
        (is (= "closed" (:disposition (record-for r (str "task-" i)))))))))

(deftest incomplete-return-and-unconsumed-terminal-are-not-completion
  (let [r (offline {:outcomes {3 {:terminal? false}}})]
    (is (some? (:error r)))
    (is (= "closed" (:disposition (record-for r "task-3")))))
  (let [r (offline {:forks (fn [entries]
                            (let [entry (get entries "fork-worker")
                                  entry (publish entry "WAITING" "still working" true)]
                              (spit (ledger/item-path (:result entry) 2)
                                    (core/envelope (assoc entry :status "COMPLETE" :summary "done")))
                              (assoc entries "fork-worker" entry)))})]
    (is (some? (:error r)))
    (is (some? (record-for r "fork-worker")))))

(deftest bridge-failure-retains-all-session-linked-descendants-without-root-authority
  (let [r (offline {:statuses {"3-r2" "FAILED"}
                    :forks #(-> %
                                (assoc-in ["fork-worker" :child-session] {:kind "path" :value "nested-source"})
                                (assoc-in ["fork-worker" :child-session-history] [{:kind "path" :value "past-source"}])
                                (update "fork-worker" dissoc :closed-at)
                                (assoc "grandchild" {:task "grandchild" :parent-session "nested-source"})
                                (assoc "past-child" {:task "past-child" :parent-session "past-source"})
                                (assoc "user-peer" {:task "user-peer" :parent-session "root"}))})]
    (is (some? (:error r)))
    (is (= #{:task-1 :task-2 :task-3 :task-3-r2 :fork-worker :fork-scout :grandchild :past-child} (set (keys (get-in r [:manifest :tasks])))))
    (is (= "outstanding" (:disposition (record-for r "fork-worker"))))
    (is (str/includes? (:recovery (record-for r "grandchild")) "root close cannot bypass"))
    (is (= ["task-1" "task-2" "task-3-r2"] (mapv #(nth % 2) (commands r "close"))))))

(deftest explicit-model-is-previewed-propagated-and-checked-without-provider-policy
  (let [r (offline {})
        assignment (last (first (commands r "continue")))]
    (is (nil? (:error r)))
    (is (str/includes? assignment (str "task run --context fork --model '" model "'")))
    (is (str/includes? assignment (str "task run scout --context fork --model '" model "'")))
    (is (str/includes? assignment "--print-prompt")))
  (doseq [options [{:preview {:model-canonical "wrong"}}
                   {:preview {:model "light"}}
                   {:preview {:model-args ["--model" "wrong"]}}]]
    (let [r (offline options)]
      (is (some? (:error r)))
      (is (empty? (commands r "start")))))
  (let [r (offline {:recorded {1 {:model-canonical "wrong"}}})]
    (is (some? (:error r)))
    (is (= "closed" (:disposition (record-for r "task-1")))))
  (is (nil? (:error (offline {:model "anthropic/synthetic-only"}))))
  (is (nil? (:error (offline {:model "approved-alias" :config {:aliases {"approved-alias" model}}})))))

(deftest real-resolver-proves-explicit-fork-model-wins-over-role-frontmatter
  (let [dir (fs/create-temp-dir {:prefix "fork-preview-test-"})
        source (str (fs/path dir "source.jsonl"))
        worker (with-redefs [cli/own-persona (constantly nil)] (slurp (str (cli/roster "worker"))))]
    (try
      (spit source "synthetic")
      (with-redefs [cli/execute (fn [argv] (throw (ex-info "LAUNCH FORBIDDEN" {:argv argv})))
                    herdr/preflight! (constantly true)
                    herdr/invoke (fn [argv] (throw (ex-info "LIVE HERDR FORBIDDEN" {:argv argv})))
                    cli/own-persona (constantly "worker") cli/own-role-absent? (constantly false)
                    cli/own-task (constantly "synthetic-caller-task")
                    cli/parent-identity (constantly {:parent-kind "pi" :parent-model model :parent-session-kind "path" :parent-session source})
                    ledger/read! (constantly {:role-snapshot (core/role-snapshot "worker" worker)})]
        (doseq [persona [nil "scout"]]
          (let [opts {:context ["fork"] :task ["synthetic"]}
                implicit (cli/preview! persona opts "blocking")
                explicit (cli/preview! persona (assoc opts :model [model]) "blocking")]
            (is (= "anthropic/claude-sonnet-5-5" (:model-canonical implicit)))
            (is (= model (:model-canonical explicit)))
            (is (= ["--model" model] (:model-args explicit)))
            (is (= (if persona "overlay" "snapshot") (get-in explicit [:fork :role-source])))
            (is (= (if persona 600000 1800000) (:timeout explicit))))))
      (finally (fs/delete-tree dir)))))

(deftest live-consent-refusal-precedes-launch
  (let [dir (str (fs/create-temp-dir {:prefix "smoke-refusal-"}))
        script (str (fs/path dir "refusal.clj"))
        env (dissoc (into {} (System/getenv)) "ORCH_LIVE_SMOKE" "ORCH_LIVE_SMOKE_MODEL")]
    (try
      (spit script "(require '[herdr-orch.smoke :as s] '[herdr-orch.cli :as c])\n(with-redefs [c/execute (fn [argv] (throw (ex-info \"LAUNCH ESCAPED\" {:argv argv})))] (s/-main \"context-modes\"))")
      (let [r @(process/process ["timeout" "--kill-after=5" "20" "bb" (str "-Djava.io.tmpdir=" dir) script]
                               {:env (assoc env "HERDR_ENV" "1") :out :string :err :string})
            envelope (json/parse-string (:out r) true)]
        (is (= 1 (:exit r)))
        (is (false? (:ok envelope)))
        (is (= "ORCH_LIVE_SMOKE" (get-in envelope [:error :data :missing])))
        (is (not (str/includes? (:out r) "LAUNCH ESCAPED"))))
      (finally (fs/delete-tree dir)))))

(deftest source-resource-positive-control
  (let [resource (io/resource "herdr_orch/smoke.clj")]
    (println "SMOKE SOURCE" resource "TEST SOURCE" (io/resource "herdr_orch/smoke_test.clj"))
    (is (some? resource))
    (is (str/includes? (slurp resource) "context smoke failed; retain the recovery manifest"))))
