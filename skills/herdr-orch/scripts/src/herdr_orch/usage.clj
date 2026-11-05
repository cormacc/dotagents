(ns herdr-orch.usage
  "Bounded quota-axi CLI collection and allowlisted subscription evidence.

  Only fresh, resolved effective subscription scopes grant routing credit. Native
  credential selection and refresh behaviour belong to the pinned executable."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io ByteArrayOutputStream]
           [java.time Instant]
           [java.util.concurrent TimeUnit]))

(def version "0.1.69")
(def version-budget-ms 5000)
(def collection-budget-ms 60000)
(def cleanup-budget-ms 2000)
(def stdout-limit 1048576)
(def statuses #{"fresh" "stale" "unavailable" "auth_required" "rate_limited" "error"})
(def window-kinds #{"session" "weekly" "monthly" "model" "credits" "unknown"})
(def cleared-env ["QUOTA_AXI_SNAPSHOT" "QUOTA_AXI_CLAUDE_PROFILES" "QUOTA_AXI_CLAUDE_SWAP"])

(defn- percent? [x]
  (and (number? x) (Double/isFinite (double x)) (<= 0 x 100)))
(defn- identifier? [s]
  (and (string? s) (boolean (re-matches #"[a-z0-9_:.+\-]{1,128}" s))))
(defn- instant? [s]
  (and (string? s) (try (Instant/parse s) true (catch Exception _ false))))
(defn- ids? [xs] (and (vector? xs) (every? identifier? xs)))

(defn- read-output [input]
  (with-open [input input]
    (let [out (ByteArrayOutputStream.) buf (byte-array 8192)]
      (loop [total 0]
        (let [n (.read input buf 0 (int (min 8192 (inc (- stdout-limit total)))))]
          (cond
            (= -1 n) {:out (.toString out "UTF-8")}
            (> (+ total n) stdout-limit) {:reason "oversized-output"}
            :else (do (.write out buf 0 n) (recur (+ total n)))))))))

(defn execute
  "Run argv in an owned process group with bounded output and cleanup; discard stderr."
  [executable args budget]
  (try
    (let [node (some-> (fs/which "node") str)
          helper (str (io/file (io/resource "herdr_orch/quota_process.mjs")))
          builder (ProcessBuilder. (into [node helper executable] args))
          _ (doseq [k cleared-env] (.remove (.environment builder) k))
          _ (.redirectError builder java.lang.ProcessBuilder$Redirect/DISCARD)
          p (.start builder)
          output (future (read-output (.getInputStream p)))
          deadline (+ (System/nanoTime) (* budget 1000000))]
      (try
        (loop []
          (cond
            (and (realized? output) (:reason @output)) @output
            (and (not (.isAlive p)) (realized? output)) (assoc @output :exit (.exitValue p))
            (>= (System/nanoTime) deadline) {:reason "timeout"}
            :else (do (Thread/sleep 5) (recur))))
        (finally
          ;; EOF asks the supervisor to stop its still-owned process group.
          (.close (.getOutputStream p))
          (when-not (.waitFor p (long cleanup-budget-ms) TimeUnit/MILLISECONDS)
            (.destroyForcibly p))
          (.close (.getInputStream p))
          (future-cancel output))))
    (catch Exception _ {:reason "process-error"})))

(defn install-directory []
  (str (fs/path (or (not-empty (System/getenv "XDG_DATA_HOME"))
                        (fs/path (or (not-empty (System/getenv "HOME")) (System/getProperty "user.home")) ".local/share"))
                "herdr-orch/quota-axi")))

(defn package-directory
  "Root of the checkout or Pi package that contains this skill. A Pi package install
  puts its declared quota-axi dependency in the root node_modules. The environment
  override lets tests isolate discovery from the developer's checkout."
  []
  (or (not-empty (System/getenv "HERDR_ORCH_PACKAGE_ROOT"))
      (some-> (io/resource "herdr_orch/usage.clj") io/file fs/canonicalize
              ;; src/herdr_orch/usage.clj -> skills/herdr-orch/scripts/src -> root
              (fs/path "../../../../../..") fs/normalize str)))

(defn executable
  "First present candidate: package dependency, managed user-local install, then PATH.
  A present candidate with a wrong version does not fall through to the next one."
  []
  (or (some->> [(some-> (package-directory) (fs/file "node_modules/.bin/quota-axi"))
                (fs/file (install-directory) "node_modules/.bin/quota-axi")]
               (filter #(and % (.isFile %) (.canExecute %)))
               first
               str)
      (some-> (fs/which "quota-axi") str)))

(defn version-failure [executable]
  (if-not executable "missing-executable"
    (let [checked (execute executable ["--version"] version-budget-ms)]
      (or (:reason checked)
          (when (or (not= 0 (:exit checked)) (not= version (str/trim (:out checked)))) "wrong-version")))))

(defn- safe-window [w]
  (when (and (map? w) (identifier? (:id w)) (window-kinds (:kind w)))
    (cond-> (select-keys w [:id :kind])
      (percent? (:percentUsed w)) (assoc :percentUsed (:percentUsed w))
      (percent? (:percentRemaining w)) (assoc :percentRemaining (:percentRemaining w))
      (instant? (:resetsAt w)) (assoc :resetsAt (:resetsAt w)))))

(defn- subscription-bound? [w]
  (and w (#{"session" "weekly" "monthly" "model"} (:kind w))
       (not-any? #(contains? w %) [:spentUsd :limitUsd :limitCredits :usedCredits :remainingCredits :creditUnit :shareOf])
       (percent? (:percentRemaining w))))

(defn- safe-scope [scope windows unresolved?]
  (when (and (map? scope) (identifier? (:scope scope)))
    (let [bounds (:boundedBy scope)
          valid? (and (= "known" (:status scope))
                      (not unresolved?) (not (:boundConflict scope))
                      (percent? (:effectivePercentRemaining scope))
                      (ids? bounds) (seq bounds)
                      (= (count bounds) (count (distinct bounds)))
                      (every? #(subscription-bound? (get windows %)) bounds))]
      (cond-> {:scope (:scope scope) :status (if valid? "known" "unknown")
               :boundedBy (if (ids? bounds) bounds [])}
        valid? (assoc :effectivePercentRemaining (:effectivePercentRemaining scope))))))

(defn- unknown [local provider reason]
  {:localProvider local :provider provider :state {:status "unavailable" :stale false}
   :reason reason :windows [] :quotaSemantics {:status "unknown" :effectiveAvailability []}})

(defn- provider-evidence [local provider rows]
  (if (not= 1 (count rows))
    (unknown local provider (if (empty? rows) "missing-provider" "ambiguous-account"))
    (let [row (first rows)
          windows (:windows row)
          semantics (:quotaSemantics row)
          scopes (:effectiveAvailability semantics)
          state (:state row)
          valid? (and (vector? windows) (every? safe-window windows)
                      (= (count windows) (count (distinct (map :id windows))))
                      (vector? scopes) (every? #(identifier? (:scope %)) scopes)
                      (= (count scopes) (count (distinct (map :scope scopes))))
                      (statuses (:status state)) (boolean? (:stale state)))
          unresolved? (or (not (#{"known" "partial"} (:status semantics)))
                          (seq (:unresolvedWindowIds semantics)))]
      (if-not valid?
        (unknown local provider "invalid-evidence")
        (cond-> {:localProvider local :provider provider
                 :state (select-keys state [:status :stale])
                 :windows (mapv safe-window windows)
                 :quotaSemantics {:status (if (#{"known" "partial" "unknown"} (:status semantics)) (:status semantics) "unknown")
                                  :effectiveAvailability (mapv #(safe-scope % (into {} (map (juxt :id identity) windows)) unresolved?) scopes)}}
          (or (:stale state) (not= "fresh" (:status state))) (assoc :reason "provider-unavailable"))))))

(defn usage-for
  "Collect an allowlisted evidence object for configured local provider names.
  Options: :providers limits collection; :fresh requests zero upstream reuse age.
  Discovery order is in `executable`; collection never installs software."
  ([config] (usage-for config {}))
  ([config {:keys [providers fresh]}]
   (let [names (or providers (sort (keys (:providers config))))
         configured (:providers config)
         remote (distinct (keep #(get-in configured [% :quota-provider]) names))
         executable (when (seq remote) (executable))
         failure (when (seq remote) (version-failure executable))
         collected (when (and (seq remote) (not failure))
                     (execute executable ["--json" "--full" "--no-credential-refresh" "--max-age" (if fresh "0" "5m")
                                          "--provider" (str/join "," remote)] collection-budget-ms))
         report (when (:out collected) (try (json/parse-string (:out collected) true) (catch Exception _ nil)))
         failure (or failure (:reason collected)
                     (when (and (seq remote) (not (and (#{5 6} (:schemaVersion report)) (vector? (:providers report))))) "malformed-output"))]
     (cond-> {:providers
              (mapv (fn [local]
                      (if-let [row (get configured local)]
                        (if-let [provider (:quota-provider row)]
                          (if failure (unknown local provider failure)
                              (provider-evidence local provider (filter #(= provider (:provider %)) (:providers report))))
                          {:localProvider local :local true :state {:status "fresh" :stale false} :windows []})
                        (unknown local nil "not-configured"))) names)}
       (and (not failure) report) (assoc :schemaVersion (:schemaVersion report))))))
