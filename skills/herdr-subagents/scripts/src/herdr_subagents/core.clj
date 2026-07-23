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

(defn direction [{:keys [width height]}]
  (if (and (>= width 80) (>= width (* 2 height))) "right" "down"))
(defn model-basename [model] (some-> model (str/split #"/") last))
(defn resolve-kind [{:keys [requested frontmatter parent-kind]}]
  (or requested (:kind frontmatter) parent-kind
      (throw (ex-info "could not resolve agent kind" {}))))
(defn resolve-model [{:keys [requested resolved-kind frontmatter parent-kind parent-model]}]
  (cond requested requested
        (and (:model frontmatter) (= resolved-kind (:kind frontmatter))) (:model frontmatter)
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

(defn envelope [{:keys [child task result status summary artifacts findings next]}]
  (when-not (statuses status) (throw (ex-info "invalid result status" {:status status})))
  (doseq [[k v] [[:child child] [:task task] [:result result] [:summary summary]]]
    (single-line! (name k) v))
  (findings-limit! findings)
  (doseq [v (concat artifacts findings (when next [next]))] (single-line! "envelope item" v))
  (str "--- HERDR RESULT v1 ---\nCHILD: " child "\nTASK: " task "\nRESULT: " result "\nSTATUS: " status "\nSUMMARY: " summary "\n"
       "ARTIFACTS:\n" (if (seq artifacts) (str/join "\n" (map #(str "- " %) artifacts)) "- none") "\n"
       "FINDINGS:\n" (if (seq findings) (str/join "\n" (map #(str "- " %) findings)) "- none") "\n"
       "NEXT: " (or next "none") "\n--- END HERDR RESULT ---\n"))

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
(defn parse-envelope [text]
  (let [lines (str/split-lines text)]
    (when-not (and (= "--- HERDR RESULT v1 ---" (first lines)) (= "--- END HERDR RESULT ---" (last lines)))
      (throw (ex-info "invalid result envelope markers" {})))
    {:child (field! lines "CHILD") :task (field! lines "TASK") :result (field! lines "RESULT")
     :status (field! lines "STATUS") :summary (field! lines "SUMMARY")
     :artifacts (section-lines lines "ARTIFACTS:" "FINDINGS:")
     :findings (section-lines lines "FINDINGS:" (str "NEXT: " (field! lines "NEXT")))
     :next (field! lines "NEXT") :text text}))

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
    parsed))
(defn json-envelope [ok payload]
  (json/generate-string (if ok {:ok true :schema schema :result payload} {:ok false :schema schema :error payload})))
