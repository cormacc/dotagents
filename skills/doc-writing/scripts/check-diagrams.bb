#!/usr/bin/env bb

;; Render every Mermaid and PlantUML block in Org or Markdown files, so that a
;; syntax error shows before a reader sees a broken diagram.
;;
;; Usage: check-diagrams.bb [--out DIR] [--format png|svg] FILE...
;;   --out     where to write the rendered images (default: a new temporary
;;             directory, printed at the end). Look at the images: a block can
;;             render without error and still be unreadable.
;;   --format  png (default, easy to view) or svg.
;;
;; Mermaid needs `mmdc` (@mermaid-js/mermaid-cli); PlantUML needs `plantuml`.
;; A missing tool makes its blocks UNAVAILABLE, not PASS.
;;
;; Exit codes: 0 every block passed, 1 a block failed, 2 usage error,
;; 3 no failure but at least one block was unavailable.

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str])

(def render-timeout-ms 120000)

(defn- org-open [line]
  (some->> (re-find #"(?i)^\s*#\+begin_src\s+(mermaid|plantuml)\b" line) second str/lower-case keyword))

(defn- md-open [line]
  (when-let [[_ fence lang] (re-find #"^\s{0,3}(`{3,}|~{3,})\s*(mermaid|plantuml)\b" line)]
    [fence (keyword (str/lower-case lang))]))

(defn extract
  "Return the diagram blocks in `text`, a document in `format` (:org or :md):
  [{:lang :mermaid|:plantuml :line first-body-line :body \"...\"}]."
  [format text]
  (let [lines (vec (str/split-lines text))]
    (loop [i 0 open nil blocks []]
      (if (>= i (count lines))
        blocks
        (let [line (nth lines i)]
          (if open
            (if (if (= format :org)
                  (re-find #"(?i)^\s*#\+end_src" line)
                  (re-find (re-pattern (str "^\\s{0,3}" (java.util.regex.Pattern/quote (:fence open)) "\\s*$")) line))
              (recur (inc i) nil (conj blocks (-> open (dissoc :fence) (update :body #(str/join "\n" %)))))
              (recur (inc i) (update open :body conj line) blocks))
            (if-let [opened (if (= format :org)
                              (when-let [lang (org-open line)] {:lang lang})
                              (when-let [[fence lang] (md-open line)] {:lang lang :fence fence}))]
              (recur (inc i) (assoc opened :line (+ i 2) :body []) blocks)
              (recur (inc i) nil blocks))))))))

(defn warnings
  "Return known rendering traps in `block` as strings."
  [{:keys [lang body]}]
  (cond-> []
    (and (= lang :plantuml) (str/includes? body "[["))
    (conj "PlantUML renders [[...]] as a hyperlink; write nested vectors another way")))

(defn- tool [lang] (if (= lang :mermaid) "mmdc" "plantuml"))

(defn- run-with-timeout [cmd]
  (let [p (process/process cmd {:out :string :err :string})
        result (deref p render-timeout-ms ::timeout)]
    (if (= ::timeout result)
      (do (process/destroy-tree p) {:exit -1 :err (str "timed out after " render-timeout-ms " ms")})
      result)))

(defn render
  "Render `block` into `dir` as `stem`.`ext`. Return {:status :pass|:fail|:unavailable ...}."
  [{:keys [lang body]} dir stem ext]
  (if-not (fs/which (tool lang))
    {:status :unavailable :detail (str (tool lang) " is not on PATH")}
    (let [src (str (fs/path dir (str stem (if (= lang :mermaid) ".mmd" ".puml"))))
          img (str (fs/path dir (str stem "." ext)))
          text (if (and (= lang :plantuml) (not (re-find #"(?m)^\s*@start" body)))
                 (str "@startuml\n" body "\n@enduml\n")
                 body)
          _ (spit src text)
          {:keys [exit err out]} (run-with-timeout
                                  (if (= lang :mermaid)
                                    ["mmdc" "-q" "-i" src "-o" img]
                                    ["plantuml" "-failfast2" (str "-t" ext) "-o" (str dir) src]))]
      (if (and (zero? exit) (fs/exists? img))
        {:status :pass :image img}
        {:status :fail :detail (let [msg (str/trim (str err "\n" out))]
                                 (if (str/blank? msg) (str "exit " exit ", no image") (last (str/split-lines msg))))}))))

(defn- file-format [path]
  (cond (str/ends-with? path ".org") :org
        (re-find #"\.(?:md|markdown)$" path) :md))

(defn check
  "Check each file in `paths`, rendering into `dir`. Print one line per block
  and a summary. Return the exit code."
  [paths dir ext]
  (let [results
        (vec (for [path paths
                   :let [fmt (file-format path)]
                   [n block] (map-indexed vector (extract fmt (slurp path)))]
               (let [stem (format "%s-%02d" (fs/strip-ext (fs/file-name path)) (inc n))
                     result (render block dir stem ext)
                     where (str path ":" (:line block) " " (name (:lang block)))]
                 (println (case (:status result)
                            :pass (str "PASS " where " -> " (:image result))
                            :fail (str "FAIL " where ": " (:detail result))
                            :unavailable (str "UNAVAILABLE " where ": " (:detail result))))
                 (doseq [w (warnings block)] (println (str "WARN " where ": " w)))
                 (assoc result :warnings (count (warnings block))))))
        n (fn [s] (count (filter #(= s (:status %)) results)))]
    (println (format "%d blocks: %d passed, %d failed, %d unavailable, %d warnings. Images: %s"
                     (count results) (n :pass) (n :fail) (n :unavailable)
                     (reduce + (map :warnings results)) dir))
    (cond (pos? (n :fail)) 1
          (pos? (n :unavailable)) 3
          :else 0)))

(defn -main [& args]
  (let [{:keys [out ext paths]}
        (loop [[a b & more :as as] args m {:ext "png" :paths []}]
          (cond (empty? as) m
                (= a "--out") (recur more (assoc m :out b))
                (= a "--format") (recur more (assoc m :ext b))
                :else (recur (rest as) (update m :paths conj a))))
        bad (remove file-format paths)]
    (when (or (empty? paths) (seq bad) (not (#{"png" "svg"} ext)))
      (binding [*out* *err*]
        (println "Usage: check-diagrams.bb [--out DIR] [--format png|svg] FILE.org|FILE.md ...")
        (when (seq bad) (println (str "Not an Org or Markdown file: " (str/join ", " bad)))))
      (System/exit 2))
    ;; Absolute: plantuml resolves a relative -o against the source file's directory.
    (let [dir (str (fs/absolutize (or (some-> out fs/create-dirs) (fs/create-temp-dir {:prefix "check-diagrams-"}))))]
      (System/exit (check paths dir ext)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
