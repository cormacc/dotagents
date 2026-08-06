(ns org-tasks.parser.links
  "File keyword and org-link helpers for org task files."
  (:require [clojure.string :as str]))

(def ^:private selected-keyword-re
  #"(?im)^#\+SELECTED:\s*(\S+)\s*$")
(def ^:private org-link-target-re
  #"^\[\[(?:file:)?([^\]]+?)\](?:\[[^\]]*\])?\]$")
(def ^:private org-link-full-re
  #"^\[\[(?:file:)?([^\]]+?)\](?:\[([^\]]*)\])?\]$")

(defn extract-org-link-target
  "Return the target slot of an org link expression, or nil for
  non-link text. Strips a `file:` prefix on file links."
  [^String value]
  (when value
    (let [trimmed (str/trim value)]
      (when-let [m (re-matches org-link-target-re trimmed)]
        (let [t (str/trim (m 1))]
          (when-not (empty? t) t))))))

(defn extract-org-link
  "Parse an org link expression into `{:target, :description}` or nil."
  [^String value]
  (when value
    (let [trimmed (str/trim value)]
      (when-let [m (re-matches org-link-full-re trimmed)]
        (let [target (str/trim (m 1))
              desc   (some-> (m 2) str/trim)]
          (when (seq target)
            {:target target
             :description (when (and desc (seq desc)) desc)}))))))

(defn escape-regex [^String s]
  (str/replace s #"[.*+?^${}()|\[\]\\]" "\\\\$0"))

(defn get-file-keywords
  "Return every value of a file-level `#+KEYWORD:` declaration in
  declaration order (empty when absent). Case-insensitive name match."
  [^String content ^String name]
  (let [re (re-pattern
             (str "(?im)^[\\t ]*#\\+" (escape-regex name)
                  "[\\t ]*:[\\t ]*(.*?)[\\t ]*$"))]
    (->> (re-seq re content)
         (mapv (fn [m] (or (second m) ""))))))

(defn get-file-keyword
  "First value of `#+KEYWORD:` or nil."
  [^String content ^String name]
  (first (get-file-keywords content name)))

(defn parse-selected-keyword
  "Extract the `#+SELECTED:` UUID from TASKS.local.org content, or nil."
  [^String content]
  (some-> (re-find selected-keyword-re content) second str/trim))

(defn get-plan-parent-ref
  "Extract the parent task reference from a navigable `#+PARENT:` org
  link. Returns `{:kind, :uuid, :summary}` or nil."
  [^String content]
  (when-let [raw (get-file-keyword content "PARENT")]
    (when-let [link (extract-org-link raw)]
      (when-let [m (re-matches #"(?i)^(task|archive):([^\s#\]]+)$" (:target link))]
        {:kind (keyword (str/lower-case (m 1)))
         :uuid (str/trim (m 2))
         :summary (:description link)}))))

(defn get-plan-parent-id
  "Extract the parent task UUID from a navigable `#+PARENT:` org link."
  [^String content]
  (:uuid (get-plan-parent-ref content)))

(defn rewrite-parent-link-kind
  "Rewrite only the link kind (`task:` ↔ `archive:`) on the `#+PARENT:`
  line referencing `parent-id`. Other matching links elsewhere in the
  file are left untouched."
  [^String content ^String parent-id new-kind]
  (let [parent-line-re #"(?im)^([\t ]*#\+PARENT[\t ]*:[\t ]*)(.*)$"
        link-target-re (re-pattern
                         (str "(\\[\\[)(task|archive):" (escape-regex parent-id)
                              "(\\](?:\\[[^\\]]*\\])?\\])"))
        changed? (volatile! false)
        lines (str/split-lines content)
        next-lines
        (mapv
          (fn [^String line]
            (if-let [pm (re-matches parent-line-re line)]
              (let [prefix (pm 1)
                    rest-of-line (pm 2)
                    rewritten (str/replace rest-of-line link-target-re
                                           (str "$1" (name new-kind) ":" parent-id "$3"))]
                (when (not= rewritten rest-of-line)
                  (vreset! changed? true))
                (str prefix rewritten))
              line))
          lines)]
    (if @changed?
      (str (str/join "\n" next-lines)
           (when (str/ends-with? content "\n") "\n"))
      content)))

(defn parse-link-templates
  "Parse all `#+LINK: prefix template` declarations in content into a
  map keyed by prefix. First declaration wins (matches Emacs)."
  [^String content]
  (let [re #"(?im)^[\t ]*#\+LINK[\t ]*:[\t ]*(\S+)[\t ]+(.+?)[\t ]*$"]
    (reduce
      (fn [m match]
        (let [prefix   (some-> (second match) str/trim)
              template (some-> (nth match 2) str/trim)]
          (if (and prefix template (seq prefix) (seq template)
                   (not (contains? m prefix)))
            (assoc m prefix template)
            m)))
      {}
      (re-seq re content))))

(defn- url-encode [^String s]
  (java.net.URLEncoder/encode s "UTF-8"))

(defn resolve-link-template
  "Substitute KEY into TEMPLATE's `%s` placeholder. Keys are URL-encoded for
  URL-shaped templates and left literal for `file:` templates."
  [^String template ^String key]
  (let [replacement (if (str/starts-with? template "file:") key (url-encode key))]
    (if (str/includes? template "%s")
      (str/replace template "%s" (str/re-quote-replacement replacement))
      (str template replacement))))

(defn typed-link-parts
  "Return `{:prefix, :key}` for a typed target like `plan:foo.org`, or nil
  for plain paths and URLs."
  [^String target]
  (when (and target
             (not (re-matches #"(?i)^https?://.*" target)))
    (when-let [m (re-matches #"^([A-Za-z][A-Za-z0-9+.-]*):(.+)$" target)]
      {:prefix (m 1) :key (m 2)})))

(defn expand-org-link-target
  "Expand a typed link target through a `#+LINK:` abbreviation table.

  Returns `{:target string, :from-project-root bool}`. Plain paths,
  `file:` targets, and URLs pass through unchanged.

  The second argument is either a string of org content (templates
  parsed inline) or a pre-parsed template map."
  [^String target content-or-templates]
  (let [result {:target target :from-project-root false}]
    (if (or (nil? target) (empty? target))
      result
      (let [typed (typed-link-parts target)]
        (if (or (nil? typed) (= "file" (:prefix typed)))
          result
          (let [templates (if (string? content-or-templates)
                            (parse-link-templates content-or-templates)
                            (or content-or-templates {}))
                template  (get templates (:prefix typed))]
            (if-not template
              result
              (let [expanded (resolve-link-template template (:key typed))]
                (if (str/starts-with? expanded "file:")
                  {:target (subs expanded (count "file:")) :from-project-root true}
                  {:target expanded :from-project-root false})))))))))
