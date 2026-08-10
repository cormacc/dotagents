(ns herdr-orch.traits
  "Pure trait token scanning, resolution, and inline substitution."
  (:require [clojure.string :as str]))

(defn trait-directories [project-root home skill-dir]
  [{:source "project" :directory (str project-root "/.agents/traits")}
   {:source "home" :directory (str home "/.agents/traits")}
   {:source "packaged" :directory (str skill-dir "/traits")}])

(defn trait-candidate-paths [{:keys [directory]} trait]
  [(str directory "/" trait ".md")
   (str directory "/" trait "/prompt.md")])

(defn resolve-trait [exists? directories trait]
  (some (fn [{:keys [source] :as layer}]
          (some (fn [path]
                  (when (exists? path)
                    {:trait trait :source source :path path}))
                (trait-candidate-paths layer trait)))
        directories))

(def ^:private frontmatter-pattern
  #"(?s)\A(---[ \t]*\r?\n(.*?)\r?\n---[ \t]*(?:\r?\n|\z))(.*)\z")

(defn split-frontmatter
  "Splits optional YAML frontmatter without changing either part."
  [text]
  (if-let [[_ frontmatter yaml body] (re-matches frontmatter-pattern (str text))]
    {:frontmatter frontmatter :yaml yaml :body body}
    {:frontmatter nil :yaml nil :body (str text)}))

(defn- parse-yaml [yaml]
  (into {}
        (keep (fn [line]
                (when-let [[_ k v] (re-matches #"\s*([A-Za-z][\w-]*)\s*:\s*(.*?)\s*" line)]
                  [(keyword k) (str/replace v #"^['\"]|['\"]$" "")]))
              (str/split-lines yaml))))

(defn- fragment-content [trait path text]
  (let [{:keys [frontmatter yaml body]} (split-frontmatter text)]
    (if-not frontmatter
      body
      (let [actual (:name (parse-yaml yaml))]
        (when-not (= trait actual)
          (throw (ex-info
                  (str "trait fragment `name` mismatch for `" path "`: expected `"
                       trait "`, got `" (or actual "<missing>") "`")
                  {:trait trait :path path :expected trait :actual actual})))
        body))))

(defn- lowercase-letter? [c]
  (<= (int \a) (int c) (int \z)))

(defn- name-character? [c]
  (or (lowercase-letter? c)
      (<= (int \0) (int c) (int \9))
      (= c \-)))

(defn- token-at [^String line i]
  (let [n (.length line)]
    (when (and (= \% (.charAt line i))
               (or (zero? i) (Character/isWhitespace (.charAt line (dec i))))
               (< (inc i) n)
               (lowercase-letter? (.charAt line (inc i))))
      (let [end (loop [j (+ i 2)]
                  (if (and (< j n) (name-character? (.charAt line j)))
                    (recur (inc j))
                    j))]
        {:end end :trait (.substring line (inc i) end)}))))

(defn- backtick-run-length [^String line start]
  (let [n (.length line)]
    (loop [i start]
      (if (and (< i n) (= \` (.charAt line i)))
        (recur (inc i))
        (- i start)))))

(defn- matching-backtick-run [^String line start length]
  (let [delimiter (apply str (repeat length \`))]
    (loop [from start]
      (let [at (.indexOf line delimiter from)]
        (when-not (neg? at)
          (let [before? (and (pos? at) (= \` (.charAt line (dec at))))
                after (+ at length)
                after? (and (< after (.length line)) (= \` (.charAt line after)))]
            (if (or before? after?)
              (recur (inc at))
              at)))))))

(defn- scan-inline [^String line expand]
  (let [n (.length line)
        out (StringBuilder.)]
    (loop [i 0]
      (if (>= i n)
        (str out)
        (let [c (.charAt line i)]
          (cond
            (= c \`)
            (let [length (backtick-run-length line i)
                  close (matching-backtick-run line (+ i length) length)]
              (if close
                (let [end (+ close length)]
                  (.append out (.substring line i end))
                  (recur end))
                (do (.append out (.substring line i (+ i length)))
                    (recur (+ i length)))))

            (= c \%)
            (if-let [{:keys [end trait]} (token-at line i)]
              (do (.append out ^String (expand trait))
                  (recur end))
              (do (.append out c) (recur (inc i))))

            :else
            (do (.append out c) (recur (inc i)))))))))

(defn- lines-preserving-endings [^String text]
  (loop [start 0 out []]
    (if (>= start (.length text))
      out
      (let [newline (.indexOf text "\n" start)
            end (if (neg? newline) (.length text) (inc newline))]
        (recur end (conj out (.substring text start end)))))))

(defn- bare-line [line]
  (str/replace line #"(?:\r?\n)\z" ""))

(defn- opening-fence [line]
  (when-let [[_ run] (re-find #"^ {0,3}(`{3,}|~{3,})" (bare-line line))]
    {:character (first run) :length (count run)}))

(defn- closing-fence? [line {:keys [character length]}]
  (when-let [[_ run] (re-matches #" {0,3}(`+|~+)[ \t]*" (bare-line line))]
    (and (= character (first run)) (<= length (count run)))))

(defn- indented-code-line? [line]
  (or (str/starts-with? line "\t")
      (str/starts-with? line "    ")))

(defn- scan-body [body expand]
  (first
   (reduce (fn [[out fence] line]
             (cond
               fence [(conj out line) (when-not (closing-fence? line fence) fence)]
               (opening-fence line) [(conj out line) (opening-fence line)]
               (indented-code-line? line) [(conj out line) nil]
               :else [(conj out (scan-inline line expand)) nil]))
           [[] nil]
           (lines-preserving-endings body))))

(defn interpolate
  "Scans and substitutes trait tokens in text.

  IO is injected through exists? and read-text. Returns transformed :text plus ordered
  :resolved source maps, unresolved candidate names in :unknowns, and resolving names
  seen more than once in :repeats. Callers own unknown/repeat failure policy."
  [{:keys [text directories exists? read-text]}]
  (let [{:keys [frontmatter body]} (split-frontmatter text)
        cache (atom {})
        seen (atom #{})
        unknown-seen (atom #{})
        repeat-seen (atom #{})
        resolved (atom [])
        unknowns (atom [])
        repeats (atom [])
        expand (fn [trait]
                 (let [cached? (contains? @cache trait)
                       cached (get @cache trait)
                       resolution (if cached? (:resolution cached)
                                      (resolve-trait exists? directories trait))]
                   (if-not resolution
                     (do
                       (when-not (contains? @unknown-seen trait)
                         (swap! unknown-seen conj trait)
                         (swap! unknowns conj trait))
                       (swap! cache assoc trait {:resolution nil})
                       (str "%" trait))
                     (let [replacement (if cached?
                                         (:replacement cached)
                                         (fragment-content trait (:path resolution)
                                                           (read-text (:path resolution))))]
                       (when-not cached?
                         (swap! cache assoc trait {:resolution resolution
                                                  :replacement replacement}))
                       (if (contains? @seen trait)
                         (when-not (contains? @repeat-seen trait)
                           (swap! repeat-seen conj trait)
                           (swap! repeats conj trait))
                         (do (swap! seen conj trait)
                             (swap! resolved conj resolution)))
                       replacement))))
        transformed-body (str/join (scan-body body expand))]
    {:text (str (or frontmatter "") transformed-body)
     :resolved @resolved
     :unknowns @unknowns
     :repeats @repeats}))
