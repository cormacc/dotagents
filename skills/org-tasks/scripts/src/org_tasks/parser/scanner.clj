(ns org-tasks.parser.scanner
  "Lossless Org task scanner.

  This namespace owns task-heading syntax and the stateful scan that builds
  the protocol task tree. It preserves all non-task source lines as body text."
  (:require [clojure.string :as str]
            [org-tasks.lifecycle :as lifecycle]
            [org-tasks.parser.lines :as lines]
            [org-tasks.parser.links :as links]))

(defn- heading-re
  "Build the task-heading grammar from the lifecycle status cycle."
  []
  (re-pattern
   (str "^(\\*+)\\s+(" (str/join "|" lifecycle/status-cycle)
        ")\\s+(?:\\[#([A-Z])\\]\\s+)?(.+)$")))
(def ^:private any-heading-re #"^(\*+)\s+(.*)$")
(def ^:private properties-start-re #"(?i)^\s*:PROPERTIES:\s*$")
(def ^:private logbook-start-re #"(?i)^\s*:LOGBOOK:\s*$")
(def ^:private drawer-end-re #"(?i)^\s*:END:\s*$")
(def ^:private import-keyword-re #"(?i)^\s*#\+IMPORT:\s*(.*?)\s*$")
(def ^:private closed-re #"^\s*CLOSED:\s*\[([^\]]+)\]\s*$")
(def ^:private trailing-task-tags-re #"\s+:([\w:]+):\s*$")
(def ^:private trailing-section-tags-re #"\s+:([A-Za-z0-9_@#%:-]+):\s*$")
(def ^:private tag-token-re #"^[A-Za-z0-9_]+$")

(defn strip-trailing-task-tags
  "Strip trailing heading tags, returning `[base-summary, tags-vec]`.

  The one-argument form preserves task-scanner syntax. With `section?` true,
  it accepts the broader historical section-heading tag syntax."
  ([^String text]
   (strip-trailing-task-tags text false))
  ([^String text section?]
   (let [tag-re (if section? trailing-section-tags-re trailing-task-tags-re)]
     (if-let [m (re-find tag-re text)]
       (let [match-str (first m)
             tag-str   (second m)
             base      (subs text 0 (- (count text) (count match-str)))
             tags      (filterv seq (str/split tag-str #":"))]
         [(str/trimr base) tags])
       [(str/trimr text) []]))))

(defn normalise-task-tag
  "Return a canonical Org tag token, or nil when `tag` cannot safely be
  represented in this parser's trailing-heading tag syntax.

  The accepted grammar is ASCII letters, digits, and underscores. Leading and
  trailing whitespace is ignored; a single surrounding `:tag:` pair is
  accepted as the conventional Org spelling and normalised to `tag`."
  [tag]
  (when (string? tag)
    (let [trimmed (str/trim tag)
          token (if-let [[_ inner] (re-matches #"^:([^:]+):$" trimmed)]
                  inner
                  trimmed)]
      (when (re-matches tag-token-re token)
        token))))

(defn add-task-tag
  "Add `tag` to task trailing tags once, preserving existing tag order.
  Throws `:invalid-tag` when the tag does not match [[normalise-task-tag]]."
  [task tag]
  (if-let [token (normalise-task-tag tag)]
    (update task :tags
            (fn [tags]
              (let [tags (vec (or tags []))]
                (if (some #{token} tags) tags (conj tags token)))))
    (throw (ex-info (str "Invalid tag " (pr-str tag)
                         "; expected letters, digits, and underscores")
                    {:code :invalid-tag :tag tag}))))

(defn remove-task-tag
  "Remove `tag` from task trailing tags. Absent tags are a no-op.
  Throws `:invalid-tag` when the tag does not match [[normalise-task-tag]]."
  [task tag]
  (if-let [token (normalise-task-tag tag)]
    (update task :tags
            (fn [tags]
              (filterv #(not= token %) (or tags []))))
    (throw (ex-info (str "Invalid tag " (pr-str tag)
                         "; expected letters, digits, and underscores")
                    {:code :invalid-tag :tag tag}))))

(defn- parse-heading [^String line]
  (when-let [m (re-matches (heading-re) line)]
    (let [[summary tags] (strip-trailing-task-tags (m 4))]
      {:level    (count (m 1))
       :status   (m 2)
       :priority (m 3)
       :summary  summary
       :tags     tags})))

(defn- split-content-lines [^String content]
  (str/split content #"\n" -1))

(defn parse-tasks
  "Parse `content` into `{:tasks [..], :file-imports [..]}`.

  Tasks are plain maps with the fields documented in this namespace's
  docstring. `opts` accepts:

    :source-path                 - absolute path of the file being parsed
    :source-content              - original content (defaults to `content`)
    :effective-source-content    - content with chained setupfiles
                                   prepended (for link template lookup)"
  ([content] (parse-tasks content {}))
  ([^String content {:keys [source-path source-content effective-source-content]}]
   (let [content-lines (split-content-lines content)
         line-count (count content-lines)
         source-content (or source-content content)
         effective-source-content (or effective-source-content source-content)
         roots (volatile! [])
         stack (volatile! [])
         current-path (volatile! nil)
         description-lines (volatile! [])
         file-imports (volatile! [])
         update-current! (fn [f & args]
                           (when-let [p @current-path]
                             (vswap! roots #(apply update-in % p f args))))
         flush-description! (fn []
                              (when @current-path
                                (let [desc (-> (str/join "\n" @description-lines)
                                               (str/replace #"^\n+" "")
                                               (str/replace #"\n+$" ""))]
                                  (update-current! assoc :description desc)))
                              (vreset! description-lines []))
         close-tasks! (fn [level end-line-exclusive]
                        (loop []
                          (when (and (seq @stack)
                                     (>= (:level (peek @stack)) level))
                            (let [{p :path} (peek @stack)]
                              (vswap! roots #(update-in % p assoc :end-line end-line-exclusive))
                              (vswap! stack pop)
                              (recur)))))
         new-task! (fn [parsed line-1-indexed]
                     (let [file-root? (empty? @stack)
                           task {:level (:level parsed) :status (:status parsed)
                                 :priority (:priority parsed) :summary (:summary parsed)
                                 :tags (:tags parsed) :description "" :children []
                                 :property-lines [] :logbook-lines []
                                 :import-path nil :import-raw nil :import-error nil
                                 :import-children nil :closed nil :source-path source-path
                                 :source-content source-content
                                 :effective-source-content effective-source-content
                                 :line-number line-1-indexed :end-line (inc line-count)
                                 :file-root? file-root?}
                           path (if file-root?
                                  (do (vswap! roots conj task) [(dec (count @roots))])
                                  (let [parent-path (:path (peek @stack))
                                        parent (get-in @roots parent-path)
                                        child-idx (count (:children parent))]
                                    (vswap! roots #(update-in % (conj parent-path :children) conj task))
                                    (conj parent-path :children child-idx)))]
                       (vswap! stack conj {:path path :level (:level parsed)})
                       (vreset! current-path path)))
         consume-drawer! (fn [start-idx field]
                           (loop [j start-idx collected []]
                             (cond
                               (>= j line-count)
                               (throw (ex-info
                                       (str "Unterminated drawer"
                                            (when source-path (str " in " source-path))
                                            " starting at line " start-idx)
                                       {:code :unterminated-drawer :file source-path :line start-idx}))
                               (re-matches drawer-end-re (nth content-lines j))
                               (do (update-current! update field (fnil into []) collected) (inc j))
                               :else (recur (inc j) (conj collected (nth content-lines j))))))]
     (loop [i 0 block-kind nil]
       (when (< i line-count)
         (let [line (nth content-lines i)
               in-block? (some? block-kind)
               next-block-kind (lines/next-block-kind block-kind line)
               heading (when-not in-block? (parse-heading line))
               any-heading (when-not in-block? (re-matches any-heading-re line))]
           (cond
             in-block?
             (do (when @current-path (vswap! description-lines conj line))
                 (recur (inc i) next-block-kind))
             heading
             (do (flush-description!)
                 (close-tasks! (:level heading) (inc i))
                 (new-task! heading (inc i))
                 (recur (inc i) next-block-kind))
             (and @current-path (re-matches closed-re line))
             (let [m (re-matches closed-re line)]
               (update-current! assoc :closed (str/trim (m 1)))
               (recur (inc i) next-block-kind))
             (and @current-path (re-matches properties-start-re line))
             (recur (consume-drawer! (inc i) :property-lines) next-block-kind)
             (and @current-path (re-matches logbook-start-re line))
             (recur (consume-drawer! (inc i) :logbook-lines) next-block-kind)
             any-heading
             (do (flush-description!)
                 (close-tasks! (count (any-heading 1)) (inc i))
                 (vreset! current-path nil)
                 (recur (inc i) next-block-kind))
             (re-matches import-keyword-re line)
             (let [raw (str/trim ((re-matches import-keyword-re line) 1))
                   link-target (links/extract-org-link-target raw)
                   path (or link-target raw)]
               (if @current-path
                 (do (update-current! assoc :import-path path :import-raw (when link-target raw))
                     (recur (inc i) next-block-kind))
                 (do (when (seq raw) (vswap! file-imports conj path))
                     (recur (inc i) next-block-kind))))
             :else
             (do (when @current-path (vswap! description-lines conj line))
                 (recur (inc i) next-block-kind))))))
     (flush-description!)
     (close-tasks! 1 (inc line-count))
     {:tasks @roots :file-imports @file-imports})))
