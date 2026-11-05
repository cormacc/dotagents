(ns herdr-orch.backend
  "Select the innermost terminal host from process ancestry."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.lang ProcessHandle]))

(defn- decode [text]
  (try (some-> text str/trim not-empty (json/parse-string true))
       (catch Exception _ nil)))

(defn- command-value [argv]
  (try
    (let [{:keys [exit out]} @(process/process argv {:out :string :err :string})
          value (when (zero? exit) (decode out))]
      (when (and value (not= false (:ok value))) value))
    (catch Exception _ nil)))

(defn- parent-pid [pid]
  (let [{:keys [exit out]} @(process/process ["ps" "-o" "ppid=" "-p" (str pid)] {:out :string :err :string})]
    (when (zero? exit) (some-> out str/trim not-empty parse-long))))

(defn- process-ancestry []
  (if-let [fixture (not-empty (System/getenv "FAKE_ANCESTOR_PIDS"))]
    (mapv #(Long/parseLong %) (str/split fixture #","))
    (loop [pid (.pid (ProcessHandle/current)) ancestry []]
      (if (or (nil? pid) (zero? pid) (some #{pid} ancestry))
        ancestry
        (recur (parent-pid pid) (conj ancestry pid))))))

(defn- herdr-candidate []
  (when-let [pane (not-empty (System/getenv "HERDR_PANE_ID"))]
    (when-let [pid (get-in (command-value ["herdr" "pane" "process-info" "--pane" pane]) [:result :process_info :shell_pid])]
      {:backend :herdr :pid (long pid)})))

(defn- hc-candidate []
  (when-let [terminal (not-empty (System/getenv "HC_TERMINAL_ID"))]
    (when-not (fs/which "hc")
      (throw (ex-info "backend-mismatch: hc is not on PATH; install herdr-core" {:backend :hc})))
    (let [terminal-data (command-value ["hc" "get" terminal])]
      (when-let [pid (or (:pid terminal-data) (get-in terminal-data [:terminal :pid]))]
        {:backend :hc :pid (long pid)}))))

(defn- detect-host! []
  ;; Herdr's established environment is unambiguous until an hc terminal variable appears.
  ;; Only the competing case needs process probes, which keeps local, offline commands pure.
  (if-not (not-empty (System/getenv "HC_TERMINAL_ID"))
    (when (= "1" (System/getenv "HERDR_ENV")) {:backend :herdr})
    (let [ancestry (process-ancestry)
          hc (hc-candidate)
          _ (when-not hc
              (throw (ex-info "backend-mismatch: unable to resolve HC_TERMINAL_ID" {:backend :hc})))
          candidates (keep identity [(herdr-candidate) hc])
          matches (keep (fn [candidate]
                        (when-let [depth (first (keep-indexed #(when (= (:pid candidate) %2) %1) ancestry))]
                          (assoc candidate :depth depth))) candidates)]
    ;; The first PID in the ancestry is the caller, so its least depth is the innermost host.
    ;; Both hosts normally appear during nesting; refusing there would reintroduce env precedence.
    (or (first (sort-by :depth matches))
        (throw (ex-info "backend-mismatch: terminal variables do not identify a caller ancestor"
                        {:candidates (vec candidates) :ancestry ancestry}))))))

(def ^:dynamic *context* nil)
(defn detect! [] (if *context* @*context* (detect-host!)))
(defn call-context [f]
  ;; One CLI call has immutable ancestry and environment, but separate calls may differ.
  (binding [*context* (or *context* (delay (detect-host!)))] (f)))

(defn preflight! []
  (when-let [{:keys [backend]} (detect!)]
    (case backend
      :herdr (when-not (= "1" (System/getenv "HERDR_ENV"))
               (throw (ex-info "backend-mismatch: Herdr caller requires HERDR_ENV=1" {:backend backend})))
      :hc (when-not (not-empty (System/getenv "HC_TERMINAL_ID"))
            (throw (ex-info "backend-mismatch: hc caller requires HC_TERMINAL_ID" {:backend backend})))
      nil))
  true)
