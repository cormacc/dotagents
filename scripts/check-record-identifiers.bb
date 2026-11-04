#!/usr/bin/env bb

;; Check backticked non-path identifiers in specified change-records against
;; tracked Clojure, EDN and Babashka source. This is textual evidence only:
;; it does not evaluate source or resolve bindings.
;;
;; Usage: scripts/check-record-identifiers.bb [--root DIR] --control IDENTIFIER RECORD...

(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(import '[java.util.regex Pattern])

(def token "[\\p{L}*+!_?$%&=<>.-][\\p{L}\\p{N}*+!_?$%&=<>.-]*")
(def identifier-re (re-pattern (str "^(?:::?" token "(?:/" token ")?|" token "(?:/" token ")?)$")))
(def token-char-re #"[\p{L}\p{N}*+!_?$%&=<>./:-]")
(def code-span-re #"`([^`\n]+)`")
(def source-globs ["*.clj" "*.cljc" "*.cljs" "*.edn" "*.bb"])
(def path-extension-re #"\.(?:bb|clj[cs]?|edn|json|md|nix|org|py|rs|sh|tsx?|ya?ml)$")

(defn fail! [message]
  (binding [*out* *err*] (println (str "error: " message)))
  (System/exit 2))

(defn usage! [message]
  (binding [*out* *err*]
    (println (str "error: " message))
    (println "usage: scripts/check-record-identifiers.bb [--root DIR] --control IDENTIFIER RECORD..."))
  (System/exit 2))

(defn parse-args [args]
  (loop [args args opts {:root "." :control nil :records []}]
    (if-let [arg (first args)]
      (case arg
        "--root" (if-let [root (second args)]
                   (recur (drop 2 args) (assoc opts :root root))
                   (usage! "--root requires a directory"))
        "--control" (if-let [control (second args)]
                      (if (:control opts)
                        (usage! "--control may be supplied only once")
                        (recur (drop 2 args) (assoc opts :control control)))
                      (usage! "--control requires an identifier"))
        (if (str/starts-with? arg "--")
          (usage! (str "unknown argument: " arg))
          (recur (next args) (update opts :records conj arg))))
      (do
        (when-not (:control opts) (usage! "--control is required"))
        (when-not (seq (:records opts)) (usage! "at least one change-record path is required"))
        opts))))

(defn git! [dir args]
  (let [{:keys [exit out err]} (apply shell {:out :string :err :string :continue true :dir dir} "git" args)]
    (if (zero? exit)
      out
      (fail! (str "git " (first args) " failed" (when-not (str/blank? err) (str ": " (str/trim err))))))))

(defn repository-root [dir]
  (str/trim (git! dir ["rev-parse" "--show-toplevel"])))

(defn tracked-source-paths [root]
  (let [out (git! root (into ["ls-files" "-z" "--"] source-globs))
        paths (->> (str/split out (re-pattern (str (char 0))))
                   (remove str/blank?)
                   sort
                   vec)]
    (when-not (seq paths) (fail! "no tracked Clojure, EDN or Babashka source files"))
    paths))

(defn record-path [root path]
  (let [candidate (if (fs/absolute? path) (fs/path path) (fs/path root path))]
    (if (fs/regular-file? candidate)
      candidate
      (fail! (str "change-record is unreadable: " path)))))

(defn record-text [file path]
  (try
    (slurp (str file))
    (catch Exception _
      (fail! (str "change-record is unreadable: " path)))))

(defn source-text [root path]
  (let [file (fs/path root path)]
    (when-not (fs/regular-file? file)
      (fail! (str "tracked source is unreadable: " path)))
    (try
      (slurp (str file))
      (catch Exception _
        (fail! (str "tracked source is unreadable: " path))))))

(defn path-token? [s]
  (or (str/starts-with? s "./")
      (str/starts-with? s "../")
      (str/starts-with? s "/")
      (str/starts-with? s "~/")
      (and (str/includes? s "/") (re-find path-extension-re s))
      (and (not (str/includes? s "/")) (re-find path-extension-re s))))

(defn identifier? [s]
  (and (re-matches identifier-re s) (not (path-token? s))))

(defn token-char? [c]
  (and c (re-matches token-char-re (str c))))

(defn present-in? [text identifier]
  (let [matcher (re-matcher (re-pattern (Pattern/quote identifier)) text)
        n (count text)]
    (loop []
      (cond
        (not (.find matcher)) false
        (and (not (token-char? (when (pos? (.start matcher)) (.charAt text (dec (.start matcher))))))
             (not (token-char? (when (< (.end matcher) n) (.charAt text (.end matcher)))))) true
        :else (recur)))))

(defn references [path text]
  (mapcat (fn [[index line]]
            (keep (fn [[_ candidate]]
                    (when (identifier? candidate)
                      {:path path :line (inc index) :identifier candidate}))
                  (re-seq code-span-re line)))
          (map-indexed vector (str/split-lines text))))

(defn display-path [root path]
  (let [relative (str (fs/relativize root path))]
    (if (str/starts-with? relative "..") (str path) relative)))

(let [{:keys [root control records]} (parse-args *command-line-args*)
      root (repository-root root)
      _ (when-not (identifier? control) (usage! (str "--control must be a non-path identifier: " control)))
      source-paths (tracked-source-paths root)
      source-texts (mapv #(source-text root %) source-paths)
      _ (when-not (some #(present-in? % control) source-texts)
          (fail! (str "control identifier `" control "` is absent from tracked source")))
      refs (mapcat (fn [path]
                     (let [file (record-path root path)]
                       (references (display-path root file) (record-text file path))))
                   records)
      unresolved (remove #(or (str/starts-with? (:identifier %) "::")
                              (some (fn [text] (present-in? text (:identifier %))) source-texts))
                         refs)
      uncertain (filter #(str/starts-with? (:identifier %) "::") refs)]
  (doseq [{:keys [path line identifier]} unresolved]
    (println (str path ":" line ": unresolved `" identifier "`")))
  (doseq [{:keys [path line identifier]} uncertain]
    (println (str path ":" line ": uncertain `" identifier "` (auto-resolved keyword needs namespace context)")))
  (println (str "control `" control "` matched in tracked source"))
  (println (str "checked " (count refs) " identifier reference" (when (not= 1 (count refs)) "s")
                " in " (count records) " change-record" (when (not= 1 (count records)) "s")
                " against " (count source-paths) " tracked source file" (when (not= 1 (count source-paths)) "s")
                "; " (count unresolved) " unresolved, " (count uncertain) " uncertain")))
