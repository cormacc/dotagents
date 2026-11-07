;; Child-side decision latency from real child Pi session files: for each session whose first
;; user message carries a herdr-orch RESULT path, find the assistant message holding the
;; `task publish` tool call; report the gap from the preceding toolResult (the model's decision
;; time after its last evidence) and from the session's first user message (total round time).
(require '[babashka.fs :as fs] '[cheshire.core :as j] '[clojure.string :as str])
(defn ms [s] (.toEpochMilli (java.time.Instant/parse s)))
(defn text-of [m] (let [c (:content m)] (cond (string? c) c (sequential? c) (str/join " " (keep #(or (:text %) (some-> (:arguments %) pr-str) (some-> (:input %) pr-str)) c)) :else "")))
(def rows
  (for [f (fs/glob (str (System/getProperty "user.home") "/.pi/agent/sessions") "**/*.jsonl")
        :let [lines (try (str/split-lines (slurp (str f))) (catch Exception _ nil))
              msgs (keep #(let [e (try (j/parse-string % true) (catch Exception _ nil))] (when (= "message" (:type e)) e)) (or lines []))
              first-user (first (filter #(= "user" (get-in % [:message :role])) msgs))]
        :when (and first-user (re-find #"HERDR_ORCH_RESULT|RESULT=|task publish" (text-of (:message first-user))))
        :let [indexed (map-indexed vector msgs)
              pub (first (for [[i e] indexed :when (and (= "assistant" (get-in e [:message :role])) (str/includes? (text-of (:message e)) "task publish"))] [i e]))]
        :when pub
        :let [[i e] pub
              prev-result (last (for [[k x] (take i indexed) :when (= "toolResult" (get-in x [:message :role]))] x))
              t-pub (ms (:timestamp e))]]
    {:session (fs/file-name f) :cwd (fs/file-name (fs/parent f))
     :decision-s (when prev-result (/ (- t-pub (ms (:timestamp prev-result))) 1000.0))
     :round-s (/ (- t-pub (ms (:timestamp first-user))) 1000.0)
     :messages (count msgs)}))
(defn median [xs] (let [s (vec (sort xs)) c (count s)] (when (pos? c) (if (odd? c) (s (quot c 2)) (/ (+ (s (dec (quot c 2))) (s (quot c 2))) 2.0)))))
(println "child sessions with a publish call:" (count rows))
(doseq [r (sort-by :session rows)] (println (format "%-60s decision %6.1f s  round %7.1f s  msgs %d" (subs (:session r) 0 (min 60 (count (:session r)))) (double (or (:decision-s r) -1)) (double (:round-s r)) (:messages r))))
(println "decision median s" (median (keep :decision-s rows)) "p75" (nth (vec (sort (keep :decision-s rows))) (int (* 0.75 (dec (count rows))))))
