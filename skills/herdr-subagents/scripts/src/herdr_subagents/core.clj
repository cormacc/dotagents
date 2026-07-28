(ns herdr-subagents.core
  "Pure protocol helpers for the subagent CLI."
  (:require [clojure.string :as str]
            [cheshire.core :as json]))

(def schema "herdr-subagents/v1")
(def statuses #{"COMPLETE" "BLOCKED" "FAILED"})
(def policies #{"blocking" "non-blocking"})
(def max-findings 5)
(defn findings-limit! [findings]
  (when (< max-findings (count findings))
    (throw (ex-info "result envelope FINDINGS exceeds the five-item limit" {:count (count findings) :limit max-findings})))
  findings)
;; PROCESS is an optional, discardable retro annotation. The cap is enforced hard here
;; (called only from `envelope`, i.e. at publish) and degraded silently at validation,
;; because a validation throw is recorded as the terminal ledger status `invalid` and a
;; discardable annotation must never destroy an otherwise valid result.
(def max-process 5)
(defn process-limit! [process]
  (when (< max-process (count process))
    (throw (ex-info "result envelope PROCESS exceeds the five-item limit" {:count (count process) :limit max-process})))
  process)

(defn single-line! [label value]
  (when (or (str/blank? (str value)) (re-find #"[\r\n]" (str value)))
    (throw (ex-info (str label " must be a non-empty single line") {:label label :value value})))
  (str value))

(defn parse-frontmatter [text]
  (let [[_ yaml] (re-find #"(?s)\A---\s*\n(.*?)\n---" text)]
    (when-not yaml (throw (ex-info "persona has no YAML frontmatter" {})))
    (into {} (keep (fn [line]
                     (when-let [[_ k v] (re-matches #"\s*([A-Za-z][\w-]*)\s*:\s*(.*?)\s*" line)]
                       [(keyword k) (str/replace v #"^['\"]|['\"]$" "")]))
                   (str/split-lines yaml)))))

(defn roster-path [exists? project-root home persona]
  (let [project (str project-root "/.agents/subagents/" persona ".md")
        global (str home "/.agents/subagents/" persona ".md")]
    (cond (exists? project) project (exists? global) global :else nil)))
;; Skills resolve like the roster, plus a bare `<root>/skills/` probe because a skill
;; repository holds its own skills there rather than under `.agents/skills/`.
(defn skill-path [exists? project-root home skill]
  (some #(when (exists? %) %)
        [(str project-root "/.agents/skills/" skill "/SKILL.md")
         (str project-root "/skills/" skill "/SKILL.md")
         (str home "/.agents/skills/" skill "/SKILL.md")]))

;; `parse-frontmatter` yields strings, and every non-empty string is truthy in Clojure,
;; so `retro: false` must be coerced explicitly or it silently gates the persona *in*.
(defn frontmatter-boolean [persona key value]
  (when (some? value)
    (let [coerced (get {"true" true "false" false} value ::invalid)]
      (when (= ::invalid coerced)
        (throw (ex-info (str "persona frontmatter `" (name key) "` must be true or false")
                        {:persona persona :key (name key) :value value})))
      coerced)))
;; Two-dimensional gating: this resolves only whether the child runs the retro step at
;; all. Whether that step emits anything is the `retro` skill's own threshold, applied by
;; the child at runtime — "enabled" never forces a retro.
;; An installation without the `retro` skill degrades silently for the frontmatter and
;; default sources — the retro step is optional equipment — but an explicit `--retro`
;; is an operator request that must not become a silent no-op, so it fails fast.
(defn resolve-retro [{:keys [persona flag frontmatter retro-skill]}]
  (let [declared (frontmatter-boolean persona :retro (:retro frontmatter))
        [enabled source] (cond (some? flag) [flag "flag"]
                               (some? declared) [declared "frontmatter"]
                               :else [true "default"])]
    (cond
      (and enabled (nil? retro-skill) (= source "flag"))
      (throw (ex-info "--retro requested but no retro skill is installed" {:persona persona :skill "retro"}))
      (and enabled (nil? retro-skill))
      {:retro false :retro-source "skill-missing"}
      :else {:retro enabled :retro-source source})))

;; A `spawns:` frontmatter value is a whitespace- and/or comma-separated allow-list.
;; Blank (or absent, arriving as nil) means leaf; `distinct` dedupes while preserving
;; declaration order.
(defn parse-spawns [value]
  (->> (str/split (str value) #"[,\s]+")
       (remove str/blank?)
       distinct
       vec))
;; Spawn-policy precedence: `--spawns` flag > frontmatter `spawns:` > default deny.
;; The literal flag value `none` forces the empty (leaf) policy without consulting the
;; roster. `resolve-persona` is the injected roster lookup (name → path or nil, like
;; `roster-path`'s `exists?`): an unresolvable name fails fast — mirroring the `retro:`
;; invalid-value precedent — rather than silently degrading to leaf.
(defn resolve-spawns [{:keys [persona flag frontmatter resolve-persona]}]
  (let [[names source] (cond (some? flag) [(when-not (= "none" flag) (parse-spawns flag)) "flag"]
                             (some? (:spawns frontmatter)) [(parse-spawns (:spawns frontmatter)) "frontmatter"]
                             :else [nil "default"])]
    (doseq [n names]
      (when (nil? (resolve-persona n))
        (throw (ex-info (str "spawns policy for persona `" persona "` names unresolvable persona `" n "` (source: " source ")")
                        {:persona persona :spawn n :source source}))))
    {:spawns (vec names) :spawns-source source}))

(defn direction [{:keys [width height]}]
  (if (and (>= width 80) (>= width (* 2 height))) "right" "down"))
(defn model-basename [model] (some-> model (str/split #"/") last))
(defn resolve-kind [{:keys [requested frontmatter parent-kind]}]
  (or requested (:kind frontmatter) parent-kind
      (throw (ex-info "could not resolve agent kind" {}))))
(defn resolve-model [{:keys [requested resolved-kind frontmatter parent-kind parent-model]}]
  (cond requested requested
        (and (:model frontmatter)
             (or (= resolved-kind (:kind frontmatter))
                 (and (= "pi" resolved-kind) (nil? (:kind frontmatter))))) (:model frontmatter)
        (= resolved-kind parent-kind) parent-model
        :else nil))
(defn model-args [kind model]
  (if (and model (#{"pi" "claude"} kind)) ["--model" model] []))
(defn persona-system-prompt [kind path body]
  (if (= kind "pi") path body))
(defn root-label [persona index model]
  (str persona "-" index (when-let [m (model-basename model)] (str "-" m))))
(defn nested-prefix [parent-label parent-persona]
  (or (second (re-find (re-pattern (str "\\A(" (java.util.regex.Pattern/quote parent-persona) "-[0-9]+)(?:-|\\z)")) parent-label))
      (throw (ex-info "parent pane label lacks anchored persona/index prefix" {:label parent-label :persona parent-persona}))))
(defn child-label [{:keys [parent-label parent-persona persona index model]}]
  (str (when parent-label (str (nested-prefix parent-label parent-persona) "/"))
       (root-label persona index model)))

(defn envelope [{:keys [child task result status summary artifacts findings next process]}]
  (when-not (statuses status) (throw (ex-info "invalid result status" {:status status})))
  (doseq [[k v] [[:child child] [:task task] [:result result] [:summary summary]]]
    (single-line! (name k) v))
  (findings-limit! findings)
  (process-limit! process)
  (doseq [v (concat artifacts findings process (when next [next]))] (single-line! "envelope item" v))
  (str "--- HERDR RESULT v1 ---\nCHILD: " child "\nTASK: " task "\nRESULT: " result "\nSTATUS: " status "\nSUMMARY: " summary "\n"
       "ARTIFACTS:\n" (if (seq artifacts) (str/join "\n" (map #(str "- " %) artifacts)) "- none") "\n"
       "FINDINGS:\n" (if (seq findings) (str/join "\n" (map #(str "- " %) findings)) "- none") "\n"
       "NEXT: " (or next "none") "\n"
       ;; Trailing and omitted-when-empty: an unmodified v1 reader ends FINDINGS at the
       ;; literal `NEXT:` line and ignores anything after it, so no version bump is
       ;; needed and envelopes without PROCESS stay byte-identical to v1 output.
       (when (seq process) (str "PROCESS:\n" (str/join "\n" (map #(str "- " %) process)) "\n"))
       "--- END HERDR RESULT ---\n"))

(defn- field! [lines label]
  (let [matches (filter #(str/starts-with? % (str label ": ")) lines)]
    (when-not (= 1 (count matches)) (throw (ex-info "result envelope field is missing or repeated" {:field label})))
    (single-line! label (subs (first matches) (+ 2 (count label))))))
(defn- section-lines [lines start end]
  (let [a (.indexOf lines start) b (.indexOf lines end)]
    (when-not (and (<= 0 a) (< a b)) (throw (ex-info "result envelope section is malformed" {:section start})))
    (let [items (subvec (vec lines) (inc a) b)]
      (when-not (every? #(str/starts-with? % "- ") items) (throw (ex-info "result envelope list item is malformed" {:section start})))
      (let [values (mapv #(subs % 2) items)] (if (= ["none"] values) [] values)))))
;; `section-lines` needs a known end marker and rejects foreign lines; the trailing
;; PROCESS section is bounded only by the END marker, so it is parsed tolerantly:
;; non `- ` lines are ignored rather than invalidating the publication.
(defn- process-items [lines]
  (let [start (.indexOf lines "PROCESS:")
        end (.indexOf lines "--- END HERDR RESULT ---")]
    (if (or (neg? start) (< end start)) []
        (let [values (->> (subvec (vec lines) (inc start) end)
                          (filter #(str/starts-with? % "- "))
                          (mapv #(subs % 2)))]
          (if (= ["none"] values) [] values)))))
(defn parse-envelope [text]
  (let [lines (str/split-lines text)]
    (when-not (and (= "--- HERDR RESULT v1 ---" (first lines)) (= "--- END HERDR RESULT ---" (last lines)))
      (throw (ex-info "invalid result envelope markers" {})))
    {:child (field! lines "CHILD") :task (field! lines "TASK") :result (field! lines "RESULT")
     :status (field! lines "STATUS") :summary (field! lines "SUMMARY")
     :artifacts (section-lines lines "ARTIFACTS:" "FINDINGS:")
     :findings (section-lines lines "FINDINGS:" (str "NEXT: " (field! lines "NEXT")))
     :next (field! lines "NEXT") :process (process-items lines) :text text}))

(defn artifact-path [line]
  (let [[path _] (str/split line #" — " 2)]
    (when-not (str/starts-with? path "/") (throw (ex-info "artifact path must be absolute" {:artifact line :path path})))
    path))
(defn validate-envelope [ledger text]
  (let [parsed (parse-envelope text)]
    (doseq [key [:child :task :result]]
      (when-not (= (str (get ledger key)) (get parsed key))
        (throw (ex-info "result envelope identity does not match ledger" {:field key :expected (get ledger key) :actual (get parsed key)}))))
    (when-not (statuses (:status parsed)) (throw (ex-info "invalid envelope status" {:status (:status parsed)})))
    (findings-limit! (:findings parsed))
    (doseq [artifact (:artifacts parsed)] (artifact-path artifact))
    (if (< max-process (count (:process parsed)))
      (assoc parsed :process (vec (take max-process (:process parsed))) :process-overflow true)
      parsed)))
(defn json-envelope [ok payload]
  (json/generate-string (if ok {:ok true :schema schema :result payload} {:ok false :schema schema :error payload})))
