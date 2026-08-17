#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests]])
(import '[java.time Instant])

(def root
  (str/trim (:out @(process/process ["git" "rev-parse" "--show-toplevel"]
                                    {:out :string :err :string}))))
(def runner (str (fs/path root "scripts" "analyse-anthropic-cache-ttl.bb")))
(def base-rate 0.00001)

(defn timestamp [millis]
  (str (Instant/ofEpochMilli millis)))

(defn assistant-entry [millis cache-read cache-write cache-write-1h content]
  (let [input 1
        input-cost (* input base-rate)
        read-cost (* cache-read base-rate 0.1)
        short-write (- cache-write cache-write-1h)
        write-cost (+ (* short-write base-rate 1.25)
                      (* cache-write-1h base-rate 2.0))]
    {:type "message"
     :timestamp (timestamp millis)
     :message {:role "assistant"
               :provider "anthropic"
               :model "claude-test"
               :timestamp millis
               :content [{:type "text" :text content}]
               :usage {:input input
                       :output 0
                       :cacheRead cache-read
                       :cacheWrite cache-write
                       :cacheWrite1h cache-write-1h
                       :cost {:input input-cost
                              :output 0
                              :cacheRead read-cost
                              :cacheWrite write-cost
                              :total (+ input-cost read-cost write-cost)}}}}))

(defn write-session! [directory name entries]
  (let [path (fs/path directory (str name ".jsonl"))]
    (spit (str path) (str (str/join "\n" (map json/generate-string entries)) "\n"))
    (str path)))

(defn run-analysis! [session-root session-count]
  @(process/process ["bb" runner
                     "--root" (str session-root)
                     "--sessions" (str session-count)
                     "--scan-limit" (str session-count)]
                    {:dir root :out :string :err :string}))

(deftest compares-short-active-and-resumed-sessions-without-emitting-content
  (let [temp (fs/create-temp-dir {:dir (str (fs/path root ".tmp"))
                                  :prefix "cache-ttl-test-"})
        t0 1767225600000
        _ (write-session! temp "active"
                          [(assistant-entry t0 0 100 0 "SECRET_SENTINEL")
                           (assistant-entry (+ t0 120000) 100 20 0 "active")])
        _ (write-session! temp "resumed"
                          [(assistant-entry t0 0 100 0 "resumed-first")
                           (assistant-entry (+ t0 600000) 0 120 0 "resumed-second")])
        proc (run-analysis! temp 2)
        output (:out proc)
        report (when (zero? (:exit proc)) (json/parse-string output true))
        sessions (into {} (map (juxt :fileName identity) (:sessions report)))
        active (get sessions "active.jsonl")
        resumed (get sessions "resumed.jsonl")]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "prompt-cache-ttl-analysis/v1" (:schema report)))
    (is (= 2 (get-in report [:totals :sessions])))
    (is (= 1 (get-in report [:totals :candidateResumes])))
    (is (= 100 (get-in report [:totals :candidateRescuedTokens])))
    (is (pos? (:optimisticDeltaUsd active)) "one-hour retention costs more during active use")
    (is (neg? (:optimisticDeltaUsd resumed)) "one-hour retention can save a ten-minute resume")
    (is (not (str/includes? output "SECRET_SENTINEL")) "assistant content is not emitted")))

(deftest excludes-sessions-that-already-used-one-hour-writes
  (let [temp (fs/create-temp-dir {:dir (str (fs/path root ".tmp"))
                                  :prefix "cache-ttl-long-test-"})
        t0 1767225600000
        _ (write-session! temp "long" [(assistant-entry t0 0 100 100 "long")])
        proc (run-analysis! temp 1)
        report (when (zero? (:exit proc)) (json/parse-string (:out proc) true))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= 1 (:longSessionsExcluded report)))
    (is (zero? (:shortSessionsCompared report)))
    (is (= ["long.jsonl"] (mapv :fileName (:excludedLongSessions report))))))

(deftest help-is-side-effect-free
  (let [proc @(process/process ["bb" runner "--help"]
                               {:dir root :out :string :err :string})]
    (is (zero? (:exit proc)) (:err proc))
    (is (str/includes? (:out proc) "Usage: bb prompt-cache-ttl"))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
