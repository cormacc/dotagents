#!/usr/bin/env bb

;; Check that every section reference in a repository's tracked org and
;; Markdown files resolves. The forms are the ones AGENTS.md section 'Section
;; references' prescribes, plus custom-ID links:
;;
;;   org       [[file:path::*Heading]]  [[*Heading]]
;;             [[file:path::#id]]       [[#id]]   (CUSTOM_ID, or a toc-org _gh anchor)
;;             [[file:path.md]] section 'Heading'
;;   Markdown  [text](path#anchor)      [text](#anchor)
;;
;; Paths must be relative to the linking file. Org headings match exactly, after
;; removing a TODO keyword, priority cookie, COMMENT and tags, as
;; `org-get-heading` does. Markdown anchors are GitHub heading anchors, also
;; computed for org targets. Links inside code spans and code blocks are
;; examples, not references, and are skipped.
;;
;; Usage: scripts/check-section-links.bb [--root DIR] [--exclude PATH-PREFIX]...

(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(defn parse-args [args]
  (loop [a args m {:root nil :exclude []}]
    (case (first a)
      nil m
      "--root" (recur (drop 2 a) (assoc m :root (second a)))
      "--exclude" (recur (drop 2 a) (update m :exclude conj (second a)))
      (do (binding [*out* *err*] (println "error: unknown argument:" (first a)))
          (System/exit 2)))))

(def opts (parse-args *command-line-args*))
(def root
  (let [dir (or (:root opts) ".")
        {:keys [exit out]} (shell {:out :string :err :string :continue true :dir dir}
                                  "git" "rev-parse" "--show-toplevel")]
    (when-not (zero? exit)
      (binding [*out* *err*] (println "error: not inside a git repository:" dir))
      (System/exit 2))
    (str/trim out)))

(defn tracked-docs []
  (->> (str/split-lines (:out (shell {:out :string :dir root} "git" "ls-files" "--" "*.org" "*.md")))
       (remove str/blank?)
       (remove (fn [p] (some #(str/starts-with? p %) (:exclude opts))))
       (filter #(fs/regular-file? (fs/path root %)))))

;; --- document structure -------------------------------------------------------

(defn org? [p] (str/ends-with? p ".org"))

(defn org-title [line]
  (when-let [[_ t] (re-matches #"\*+\s+(.*?)\s*" line)]
    (-> t
        (str/replace #"^(TODO|DONE|STARTED|WAITING|CANCELLED|OPEN|DECIDED)\s+" "")
        (str/replace #"^\[#[A-Z0-9]\]\s+" "")
        (str/replace #"^COMMENT\s+" "")
        (str/replace #"\s+:[\w@#%:]+:$" "")
        str/trim)))

(defn md-title [line]
  (some-> (re-matches #"#{1,6}\s+(.*?)(?:\s+#+)?\s*" line) second str/trim))

(defn outside-blocks
  "Lines of `text` as [line-number line] pairs, excluding code blocks: #+begin_/#+end_
  in org, and backtick or tilde fences in Markdown, closed only by a matching fence."
  [org-file text]
  (loop [[l & more] (str/split-lines text) n 1 fence nil out []]
    (if (nil? l)
      out
      (let [low (str/lower-case l)
            md-fence (when-not org-file (second (re-find #"^\s{0,3}(`{3,}|~{3,})" l)))]
        (cond
          org-file (cond (re-find #"^\s*#\+begin_" low) (recur more (inc n) :org out)
                         (re-find #"^\s*#\+end_" low) (recur more (inc n) nil out)
                         fence (recur more (inc n) fence out)
                         :else (recur more (inc n) nil (conj out [n l])))
          (nil? fence) (if md-fence
                         (recur more (inc n) md-fence out)
                         (recur more (inc n) nil (conj out [n l])))
          (and md-fence (= (first md-fence) (first fence)) (>= (count md-fence) (count fence))
               (re-matches #"\s*[`~]+\s*" l))
          (recur more (inc n) nil out)
          :else (recur more (inc n) fence out))))))

(def doc*
  (memoize
   (fn [p]
     (let [lines (map second (outside-blocks (org? p) (slurp (str (fs/path root p)))))
           titles (keep (if (org? p) org-title md-title) lines)
           ;; A table-of-contents generator may keep a TODO keyword that org did not
           ;; recognise when it ran, so anchors also cover the unstripped title.
           raw-titles (when (org? p)
                        (keep #(some-> (re-matches #"\*+\s+(.*?)(?:\s+:[\w@#%:]+:)?\s*" %) second) lines))
           slug #(-> % str/lower-case (str/replace #"[^\p{L}\p{N} _-]" "") (str/replace " " "-"))
           anchors (loop [[t & more] titles seen {} out #{}]
                     (if-not t out
                             (let [s (slug t) k (get seen s 0)]
                               (recur more (assoc seen s (inc k)) (conj out (if (zero? k) s (str s "-" k)))))))
           anchors (into anchors (map slug raw-titles))
           html-ids (set (map second (re-seq #"<a\s+(?:id|name)=\"([^\"]+)\"" (str/join "\n" lines))))
           custom-ids (set (keep #(second (re-matches #"\s*:CUSTOM_ID:\s+(\S+)\s*" %)) lines))]
       {:headings (set titles) :anchors (into anchors html-ids) :custom-ids custom-ids}))))

;; --- reference extraction ----------------------------------------------------

(def org-verbatim-re
  ;; Org =verbatim= and ~code~: the marker follows line start, whitespace or an
  ;; opening delimiter, the body does not start or end with whitespace, and the
  ;; closing marker precedes whitespace, punctuation or line end.
  #"(?:^|(?<=[\s({'\"]))([=~])(\S|\S.*?\S)\1(?=[\s.,;:!?)}'\"-]|$)")
(def md-code-re #"(`+)(.+?)\1")

(defn code-ranges [org-file line]
  (let [m (re-matcher (if org-file org-verbatim-re md-code-re) line)]
    (loop [out []] (if (.find m) (recur (conj out [(.start m) (.end m)])) out))))

(defn in-code-span? [ranges start]
  (some (fn [[s e]] (and (<= s start) (< start e))) ranges))

(def org-link-re #"\[\[([^\]\n]+)\](?:\[[^\]\n]*\])?\](\s+section\s+'([^'\n]+)')?")
(def md-link-re #"\[[^\]\n]*\]\(([^)\s]+)\)")

(defn local-path? [s] (not (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" s)))

(defn references [p]
  (let [org-file (org? p)]
    (for [[n line] (outside-blocks org-file (slurp (str (fs/path root p))))
          :let [ranges (code-ranges org-file line)]
          [re kind] (if org-file [[org-link-re :org] [md-link-re :md]] [[md-link-re :md]])
          :let [m (re-matcher re line)]
          match (take-while some? (repeatedly #(when (.find m) [(.start m) (re-groups m)])))
          :let [[start groups] match]
          :when (not (in-code-span? ranges start))]
      {:path p :line n :kind kind :groups groups})))

;; --- checks -------------------------------------------------------------------

(defn target-path [from rel]
  (str (fs/relativize root (fs/normalize (fs/absolutize (fs/path root (or (fs/parent from) "") rel))))))

(defn check-target [from rel]
  (cond (str/starts-with? rel "~") "path is not relative to the linking file"
        (fs/absolute? rel) "path is not relative to the linking file"
        (not (fs/exists? (fs/path root (target-path from rel)))) "target file does not exist"))

;; A #id link resolves through a CUSTOM_ID, or, with toc-org's GitHub-style
;; (`_gh`) tables of contents, through a heading anchor that toc-org-mode
;; translates when the link is followed.
(defn id-target? [p id]
  (let [d (doc* p)] (or ((:custom-ids d) id) ((:anchors d) id))))

(defn check-org [{:keys [path groups]}]
  (let [[_ target _ quoted] groups
        [kind body] (cond (str/starts-with? target "file:") [:file (subs target 5)]
                          (str/starts-with? target "*") [:heading (subs target 1)]
                          (str/starts-with? target "#") [:custom-id (subs target 1)]
                          :else [:other target])]
    (case kind
      :heading (when-not ((:headings (doc* path)) body) (str "no heading '" body "' in this file"))
      :custom-id (when-not (id-target? path body) (str "no CUSTOM_ID or heading anchor '" body "' in this file"))
      :other nil
      :file (let [[rel search] (str/split body #"::" 2)
                  t (when-not (str/blank? rel) (target-path path rel))]
              (or (when (str/blank? rel) "file link without a path")
                  (when (or search quoted) (check-target path rel))
                  (cond
                    (and search (str/starts-with? search "*"))
                    (cond (not (org? t)) "org heading search into a non-org file; use [[file:path]] section 'Heading'"
                          (not ((:headings (doc* t)) (subs search 1))) (str "no heading '" (subs search 1) "' in " t))
                    (and search (str/starts-with? search "#"))
                    (when-not (id-target? t (subs search 1)) (str "no CUSTOM_ID or heading anchor '" (subs search 1) "' in " t))
                    (and quoted (re-find #"\.(md|org)$" t))
                    (when-not ((:headings (doc* t)) quoted) (str "no heading '" quoted "' in " t))))))))

(defn check-md [{:keys [path groups]}]
  (let [[_ href] groups
        [rel frag] (str/split href #"#" 2)]
    (when (and frag (local-path? href) (or (str/blank? rel) (re-find #"\.(md|org)$" rel)))
      (let [t (if (str/blank? rel) path (target-path path rel))]
        (or (when-not (str/blank? rel) (check-target path rel))
            (when-not ((:anchors (doc* t)) frag) (str "no anchor '#" frag "' in " t)))))))

(defn checkable? [{:keys [kind groups]}]
  (case kind
    :org (let [[_ target _ quoted] groups]
           (or (str/starts-with? target "*") (str/starts-with? target "#")
               (and (str/starts-with? target "file:") (or quoted (str/includes? target "::")))))
    :md (let [[_ href] groups] (and (str/includes? href "#") (local-path? href)))))

(let [files (tracked-docs)
      refs (filter checkable? (mapcat references files))
      problems (keep #(when-let [msg ((if (= :org (:kind %)) check-org check-md) %)]
                        (str (:path %) ":" (:line %) ": " msg))
                     refs)]
  (doseq [p problems] (println p))
  (println (str "checked " (count refs) " section references in " (count files) " files; "
                (count problems) " problem" (when (not= 1 (count problems)) "s")))
  (System/exit (if (empty? problems) 0 1)))
