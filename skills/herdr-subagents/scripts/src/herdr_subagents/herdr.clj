(ns herdr-subagents.herdr
  "Safe argv adapter for Herdr 0.7.5. No command text is passed to a shell."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def minimum-version [0 7 5])
;; The mechanical surface intentionally includes pane rename, which is useful to the
;; subagent launcher even though pi-herdr did not expose it. Keep this set explicit so a
;; missing wrapper is a test failure rather than an accidental capability regression.
(def operations
  #{["workspace" "list"] ["workspace" "create"] ["workspace" "focus"]
    ["tab" "list"] ["tab" "create"] ["tab" "focus"]
    ["pane" "list"] ["pane" "current"] ["pane" "layout"] ["pane" "split"]
    ["pane" "rename"] ["pane" "get"] ["pane" "run"] ["pane" "read"]
    ["pane" "send-text"] ["pane" "send-keys"] ["pane" "wait-output"] ["pane" "close"]
    ["agent" "list"] ["agent" "get"] ["agent" "start"] ["agent" "prompt"]
    ["agent" "wait"] ["agent" "read"] ["agent" "send-keys"] ["agent" "focus"]
    ["agent" "rename"]})
(def required-capabilities
  [[ ["pane" "layout"] ["--pane"]]
   [ ["pane" "split"] ["--pane" "--direction" "--cwd" "--env" "--no-focus"]]
   [ ["tab" "create"] ["--workspace" "--cwd" "--label" "--env" "--no-focus"]]
   [ ["pane" "rename"] []] [["pane" "get"] []] [["pane" "close"] []]
   [ ["agent" "start"] ["--kind" "--pane"]] [["agent" "prompt"] []]
   [ ["agent" "wait"] ["--timeout" "--until"]] [["agent" "get"] []] [["agent" "list"] []]
   [ ["notification" "show"] ["--body"]]])

(def max-output-lines 2000)
(def max-output-bytes (* 50 1024))

(defn- decode [s] (some-> (not-empty (str/trim s)) (json/parse-string true)))
(defn herdr-error [argv stderr]
  (let [response (decode stderr)
        error (:error response)
        detail (cond
                 (map? error) (or (:message error) (:code error))
                 (string? error) error
                 :else (not-empty (str/trim stderr)))
        command (str "herdr " (str/join " " argv))]
    {:kind :herdr :argv argv :stderr stderr :response response
     :message (str command " failed" (when detail (str ": " detail)))}))
(defn invoke [argv]
  (let [{:keys [exit out err]} @(process/process (into ["herdr"] argv) {:out :string :err :string})]
    ;; Several Herdr mutation subcommands deliberately signal success only through exit
    ;; status. `:value nil` is therefore a successful result, not a missing JSON error.
    (if (zero? exit) {:ok true :value (decode out) :out out}
        ;; Herdr usually writes its envelope to stderr, but accept stdout too as the
        ;; upstream adapter does: either stream may carry the actionable JSON error.
        {:ok false :error (assoc (herdr-error argv (if (str/blank? err) out err)) :exit exit)})))
(defn value! [argv]
  (let [result (invoke argv)]
    (if (:ok result) (:value result)
        (let [error (:error result)] (throw (ex-info (:message error) error))))))
(defn- text! [argv]
  (let [result (invoke argv)]
    (if (:ok result) (:out result)
        (let [error (:error result)] (throw (ex-info (:message error) error))))))
(defn version []
  (let [{:keys [exit out err]} @(process/process ["herdr" "--version"] {:out :string :err :string})
        found (some->> (re-find #"(\d+)\.(\d+)\.(\d+)" out) rest (mapv #(Long/parseLong %)))]
    (if (and (zero? exit) found) found (throw (ex-info "unable to determine Herdr version" {:exit exit :stderr err})))))
(defn at-least? [actual expected] (not (neg? (compare actual expected))))
(defn command-help [command]
  (let [{:keys [exit out err]} @(process/process (into ["herdr"] (conj command "--help")) {:out :string :err :string})]
    (if (#{0 2} exit) (str out err) (throw (ex-info "unable to inspect Herdr capability" {:command command :stderr err})))))
(defn preflight! []
  (when-not (= "1" (System/getenv "HERDR_ENV")) (throw (ex-info "subagent requires HERDR_ENV=1; run inside a Herdr pane" {:kind :environment})))
  (let [actual (version)]
    (when-not (at-least? actual minimum-version) (throw (ex-info "Herdr 0.7.5 or newer is required" {:actual actual :minimum minimum-version}))))
  (doseq [[command flags] required-capabilities]
    (let [text (command-help command) prefix (str "herdr " (str/join " " command))]
      (when-not (str/includes? text prefix) (throw (ex-info "installed Herdr lacks required command" {:command command})))
      (doseq [flag flags] (when-not (str/includes? text flag) (throw (ex-info "installed Herdr lacks required flag" {:command command :flag flag}))))))
  true)
(defn- env-args [env] (mapcat (fn [[k v]] ["--env" (str k "=" v)]) env))
(defn caller-rect! []
  (let [pane (System/getenv "HERDR_PANE_ID") layout (value! ["pane" "layout" "--pane" pane])
        panes (get-in layout [:result :layout :panes]) match (some #(when (= pane (:pane_id %)) %) panes)]
    (or (:rect match) (throw (ex-info "caller pane absent from Herdr layout" {:pane pane :panes panes})))))
(defn split! [{:keys [direction cwd env]}]
  (let [pane (System/getenv "HERDR_PANE_ID")]
    (get-in (value! (into ["pane" "split" "--pane" pane "--direction" direction "--cwd" cwd "--no-focus"] (env-args env))) [:result :pane])))
;; The child pane is `.result.root_pane`, not `.result.pane` (tab creation also returns
;; `.result.tab`); `--label` here sets the *tab's* label, distinct from the pane label
;; the existing rename! flow applies afterward.
(defn tab-create! [{:keys [cwd label env]}]
  (let [result (get-in (value! (into ["tab" "create" "--workspace" (System/getenv "HERDR_WORKSPACE_ID") "--cwd" cwd "--label" label "--no-focus"] (env-args env))) [:result])]
    (assoc (:root_pane result) :tab-id (get-in result [:tab :tab_id]))))
(defn rename! [pane label] (get-in (value! ["pane" "rename" pane label]) [:result :pane]))
(declare current-pane!)
(defn pane! [pane] (get-in (value! ["pane" "get" pane]) [:result :pane]))
(defn close! [pane]
  (let [caller (current-pane!)]
    (when (= pane (:pane_id caller))
      (throw (ex-info "Refusing to close the pane the caller is running in." {:pane pane :caller caller})))
    (value! ["pane" "close" pane])))
(defn agents [] (get-in (value! ["agent" "list"]) [:result :agents]))
(defn agent! [target] (get-in (value! ["agent" "get" target]) [:result :agent]))

(defn workspace-list! [] (get-in (value! ["workspace" "list"]) [:result :workspaces]))
(defn workspace-create! [{:keys [cwd label env focus]}]
  (get-in (value! (into (cond-> ["workspace" "create" "--cwd" cwd]
                          label (into ["--label" label])
                          focus (conj "--focus")
                          (not focus) (conj "--no-focus"))
                        (env-args env))) [:result]))
(defn workspace-focus! [workspace] (get-in (value! ["workspace" "focus" workspace]) [:result :workspace]))
(defn tab-list! [workspace]
  (get-in (value! (cond-> ["tab" "list"] workspace (into ["--workspace" workspace]))) [:result :tabs]))
(defn tab-focus! [tab] (get-in (value! ["tab" "focus" tab]) [:result :tab]))
(defn pane-list! [workspace]
  (get-in (value! (cond-> ["pane" "list"] workspace (into ["--workspace" workspace]))) [:result :panes]))
(defn current-pane! [] (get-in (value! ["pane" "current" "--current"]) [:result :pane]))

(defn- read-argv [object target {:keys [source lines format]}]
  (cond-> [object "read" target "--source" (or source "recent-unwrapped")]
    lines (into ["--lines" (str lines)])
    format (into ["--format" format])))
(defn- utf8-bytes [text] (alength (.getBytes ^String text "UTF-8")))
(defn- tail-utf8 [text max-bytes]
  (loop [end (count text) selected [] bytes 0]
    (if (zero? end)
      (str/join "" (rseq (vec selected)))
      (let [codepoint (Character/codePointBefore ^CharSequence text end)
            char (String. (Character/toChars codepoint))
            size (utf8-bytes char)]
        (if (<= (+ bytes size) max-bytes)
          (recur (- end (Character/charCount codepoint)) (conj selected char) (+ bytes size))
          (str/join "" (rseq (vec selected))))))))
(defn truncate-output [output]
  (let [output (str output)
        lines (vec (str/split output #"\n"))
        total-lines (count lines)
        total-bytes (utf8-bytes output)]
    (if (and (<= total-lines max-output-lines) (<= total-bytes max-output-bytes))
      output
      (let [kept (loop [remaining (rseq lines) selected [] bytes 0]
                   (if (or (empty? remaining) (>= (count selected) max-output-lines))
                     selected
                     (let [line (first remaining)
                           separator (if (seq selected) 1 0)
                           size (+ separator (utf8-bytes line))]
                       (if (<= (+ bytes size) max-output-bytes)
                         (recur (next remaining) (conj selected line) (+ bytes size))
                         (if (empty? selected)
                           [(tail-utf8 line max-output-bytes)]
                           selected)))))
            content (str/join "\n" (rseq (vec kept)))]
        (str "[Showing last " (count kept) " of " total-lines " lines]\n" content)))))
(defn- read-with-visible-fallback! [argv visible-argv]
  (let [output (text! argv)]
    (if (str/blank? output) (text! visible-argv) output)))
(defn pane-read! [pane opts]
  (truncate-output
   (read-with-visible-fallback! (read-argv "pane" pane opts)
                                (read-argv "pane" pane (assoc opts :source "visible")))))
(defn pane-run! [pane command] (value! ["pane" "run" pane command]))
(defn pane-send-text! [pane text] (value! ["pane" "send-text" pane text]))
(defn pane-send-keys! [pane keys] (value! (into ["pane" "send-keys" pane] keys)))
(defn pane-wait-output! [pane {:keys [match regex source lines timeout raw]}]
  (let [args (cond-> ["pane" "wait-output" pane (if regex "--regex" "--match") (or regex match)]
               source (into ["--source" source]) lines (into ["--lines" (str lines)])
               timeout (into ["--timeout" (str timeout)]) raw (conj "--raw"))
        result (get-in (value! args) [:result])
        output (or (get-in result [:read :text]) (:matched_line result) "")]
    (assoc result :output (truncate-output output))))
(defn agent-read! [target opts]
  (truncate-output
   (read-with-visible-fallback! (read-argv "agent" target opts)
                                (read-argv "agent" target (assoc opts :source "visible")))))
(defn agent-send-keys! [target keys] (value! (into ["agent" "send-keys" target] keys)))
(defn agent-focus! [target] (get-in (value! ["agent" "focus" target]) [:result :agent]))
(defn agent-rename! [target name clear?]
  (get-in (value! ["agent" "rename" target (if clear? "--clear" name)]) [:result :agent]))

(defn- agent-display-name [agent] (or (:name agent) (:display_agent agent) (:agent agent) (:pane_id agent)))
(defn summarize-agent [agent]
  (str (agent-display-name agent) ": [" (:pane_id agent) "] (" (:agent_status agent)
       (when (:focused agent) ", focused") ")" (when-let [cwd (:cwd agent)] (str " " cwd))))
(defn summarize-pane [pane current-pane-id]
  (let [flags (->> [(when (= (:pane_id pane) current-pane-id) "current")
                    (when (and (not= (:pane_id pane) current-pane-id) (:focused pane)) "focused")
                    (:agent pane) (when (not= "unknown" (:agent_status pane)) (:agent_status pane))]
                   (remove nil?) (str/join ", "))
        cwd (or (:foreground_cwd pane) (:cwd pane))]
    (str (or (:label pane) (:pane_id pane)) ": [" (:pane_id pane) "]"
         (when (seq flags) (str " (" flags ")")) (when cwd (str " " cwd)))))
(defn render-agents [items] (if (seq items) (str/join "\n" (map summarize-agent items)) "No agents."))
(defn render-panes [items current-pane-id] (if (seq items) (str/join "\n" (map #(summarize-pane % current-pane-id) items)) "No panes."))

;; A freshly split pane's shell can lag behind its interactive prompt, and herdr reports
;; that startup race as `agent_pane_busy`. A single immediate rerun cleared it on the
;; live smoke run that motivated this (no duration was measured), so the budget below is
;; deliberately small and hard-coded rather than tuned to an observed race length: three
;; retries at 500ms (~1.5s of backoff) trades a modest, bounded delay against burning the
;; whole spawn allocation. Retry over `invoke` (not `value!`) so no `ex-info` has to be
;; caught and re-thrown; every other error code fails on the first attempt, and no other
;; mutation (`pane split`, `agent prompt`, `pane rename`) is retried.
(def start-retry-attempts 4)
(def default-start-retry-backoff-ms 500)
(defn parse-start-retry-backoff [raw]
  (let [n (some-> raw str/trim not-empty parse-long)]
    (if (and n (pos? n)) n default-start-retry-backoff-ms)))
(defn start-retry-backoff-ms [] (parse-start-retry-backoff (System/getenv "SUBAGENT_START_RETRY_BACKOFF_MS")))
(defn start! [name kind pane native-args]
  (let [argv (into ["agent" "start" name "--kind" kind "--pane" pane] (when (seq native-args) (into ["--"] native-args)))]
    (loop [attempt 1]
      (let [outcome (invoke argv)]
        (cond
          (:ok outcome) (get-in (:value outcome) [:result :agent])
          (and (< attempt start-retry-attempts) (= "agent_pane_busy" (get-in outcome [:error :response :error :code])))
          (do (Thread/sleep (start-retry-backoff-ms)) (recur (inc attempt)))
          :else (let [error (:error outcome)] (throw (ex-info (:message error) error))))))))
(defn prompt! [target text] (value! ["agent" "prompt" target text]))
(defn wait! [target timeout] (invoke ["agent" "wait" target "--timeout" (str timeout)]))
;; Settle wait for the advisory parent push. `--until idle --until done` is *narrower*
;; than herdr's default match set (idle, done, blocked) on purpose: a blocked parent must
;; never be woken with prompt text that would land in its approval UI, so blocked has to
;; keep the wait pending until the budget elapses rather than satisfy it.
(defn wait-settled! [target timeout]
  (invoke ["agent" "wait" target "--timeout" (str timeout) "--until" "idle" "--until" "done"]))
(defn notify! [title body] (value! ["notification" "show" title "--body" body]))
