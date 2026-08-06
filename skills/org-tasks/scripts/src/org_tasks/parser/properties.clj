(ns org-tasks.parser.properties
  "Drawer property, blocker, and handoff helpers for org task maps."
  (:require [clojure.string :as str]))

(def ^:private id-property-re
  #"(?i)^\s*:CUSTOM_ID:\s*(\S+)\s*$")
(def ^:private started-property-re
  #"(?i)^\s*:STARTED:\s*\[([^\]]+)\]\s*$")
(def ^:private property-line-re
  #"^\s*:([A-Za-z][A-Za-z0-9_-]*):\s*(.*?)\s*$")
(def ^:private property-or-continuation-line-re
  #"^\s*:([A-Za-z][A-Za-z0-9_-]*)(\+)?:\s*(.*?)\s*$")
(def ^:private full-uuid-re
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn get-task-id [task]
  (some (fn [^String line]
          (when-let [m (re-matches id-property-re line)]
            (str/trim (m 1))))
        (:property-lines task)))

(defn task-has-id? [task]
  (some? (get-task-id task)))

(defn get-task-started [task]
  (some (fn [^String line]
          (when-let [m (re-matches started-property-re line)]
            (str/trim (m 1))))
        (:property-lines task)))

(defn task-has-started-property? [task]
  (some? (get-task-started task)))

(defn get-drawer-property
  "Return the value of an arbitrary drawer property by name (case-insensitive)."
  [task ^String name]
  (let [target (str/upper-case name)]
    (some (fn [^String line]
            (when-let [m (re-matches property-line-re line)]
              (when (= target (str/upper-case (m 1)))
                (str/trim (m 2)))))
          (:property-lines task))))

(defn set-drawer-property
  "Set or clear a drawer property. `value` nil removes the line."
  [task ^String name value]
  (let [target (str/upper-case name)
        replaced? (volatile! false)
        new-lines (reduce
                    (fn [acc ^String line]
                      (let [m (re-matches property-line-re line)]
                        (cond
                          (and m (= target (str/upper-case (m 1))))
                          (do (vreset! replaced? true)
                              (if (nil? value) acc
                                  (conj acc (str ":" (m 1) ": " value))))
                          :else (conj acc line))))
                    []
                    (:property-lines task))]
    (assoc task :property-lines
           (if (and (not @replaced?) (some? value))
             (conj new-lines (str ":" name ": " value))
             new-lines))))

(defn get-drawer-property-values
  "Collect all values for `name` and any `name+:` continuation lines
  in declaration order."
  [task ^String name]
  (let [target (str/upper-case name)]
    (reduce (fn [acc ^String line]
              (if-let [m (re-matches property-or-continuation-line-re line)]
                (if (= target (str/upper-case (m 1)))
                  (conj acc (str/trim (m 3)))
                  acc)
                acc))
            []
            (:property-lines task))))

(defn set-drawer-property-values
  "Replace every `:NAME:` / `:NAME+:` line with new values; empty
  `values` clears the property entirely."
  [task ^String name values]
  (let [target  (str/upper-case name)
        stripped (filterv (fn [^String line]
                            (let [m (re-matches property-or-continuation-line-re line)]
                              (not (and m (= target (str/upper-case (m 1)))))))
                          (:property-lines task))
        emitted (map-indexed
                  (fn [i v] (str ":" name (if (zero? i) "" "+") ": " v))
                  values)]
    (assoc task :property-lines (vec (concat stripped emitted)))))

(defn parse-blocker
  "Parse a single `:BLOCKED-BY:` token into a structured form.
  Bare full UUIDs are legacy task references; all other bare values stay
  opaque so free-form human blockers never become graph lookups."
  [^String raw]
  (let [trimmed (str/trim raw)]
    (if-let [m (re-matches #"(?i)^(task|url|human|jira):(.*)$" trimmed)]
      {:raw trimmed
       :kind (keyword (str/lower-case (m 1)))
       :ref (str/trim (m 2))}
      {:raw trimmed
       :kind (if (re-matches full-uuid-re trimmed) :task :other)
       :ref trimmed})))

(defn get-task-blockers [task]
  (mapv parse-blocker (get-drawer-property-values task "BLOCKED-BY")))

(defn set-task-blockers
  "Replace blockers. Accepts a seq of raw tokens or `TaskBlocker` maps."
  [task blockers]
  (set-drawer-property-values
    task "BLOCKED-BY"
    (mapv #(if (string? %) % (:raw %)) blockers)))

(defn get-task-handoff [task]
  (let [v (get-drawer-property task "HANDOFF")]
    (when (and v (seq v)) v)))

(defn set-task-handoff [task value]
  (set-drawer-property task "HANDOFF" (when (and value (seq value)) value)))
