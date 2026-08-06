(ns org-tasks.parser.timestamps
  "Timestamp and LOGBOOK formatting helpers for org tasks.")

(def ^:private day-abbr
  ["Sun" "Mon" "Tue" "Wed" "Thu" "Fri" "Sat"])

(defn format-org-timestamp
  "Format a `java.time.LocalDateTime` (or `now`) as an org timestamp body,
  e.g. `2026-04-24 Fri 14:30`."
  ([] (format-org-timestamp (java.time.LocalDateTime/now)))
  ([^java.time.LocalDateTime ts]
   (let [y  (.getYear ts)
         mo (.getMonthValue ts)
         d  (.getDayOfMonth ts)
         h  (.getHour ts)
         mi (.getMinute ts)
         ;; java.time.DayOfWeek: MONDAY=1 .. SUNDAY=7. We want Sun=0..Sat=6.
         dow (let [v (.getValue (.getDayOfWeek ts))] (if (= v 7) 0 v))]
     (format "%04d-%02d-%02d %s %02d:%02d" y mo d (nth day-abbr dow) h mi))))

(defn format-org-date
  "Format a `java.time.LocalDate` (or today) as `YYYY-MM-DD Day` for
  `#+DATE:` headers where time-of-day is not meaningful."
  ([] (format-org-date (java.time.LocalDate/now)))
  ([^java.time.LocalDate d]
   (let [y  (.getYear d)
         mo (.getMonthValue d)
         dd (.getDayOfMonth d)
         dow (let [v (.getValue (.getDayOfWeek d))] (if (= v 7) 0 v))]
     (format "%04d-%02d-%02d %s" y mo dd (nth day-abbr dow)))))

(defn created-log-entry [^String timestamp]
  (str "- Created [" timestamp "]"))

(defn state-log-entry [^String new-status ^String old-status ^String timestamp]
  (str "- State \"" new-status "\" from \"" old-status "\" [" timestamp "]"))

(defn append-created-log [task ^String timestamp]
  (update task :logbook-lines (fnil conj []) (created-log-entry timestamp)))

(defn append-state-log
  ([task new-status old-status]
   (append-state-log task new-status old-status (format-org-timestamp)))
  ([task new-status old-status timestamp]
   (update task :logbook-lines (fnil conj [])
           (state-log-entry new-status old-status timestamp))))
