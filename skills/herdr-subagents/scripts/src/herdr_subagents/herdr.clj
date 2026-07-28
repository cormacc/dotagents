(ns herdr-subagents.herdr
  "Safe argv adapter for Herdr 0.7.5. No command text is passed to a shell."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def minimum-version [0 7 5])
(def required-capabilities
  [[ ["pane" "layout"] ["--pane"]]
   [ ["pane" "split"] ["--pane" "--direction" "--cwd" "--env" "--no-focus"]]
   [ ["tab" "create"] ["--workspace" "--cwd" "--label" "--env" "--no-focus"]]
   [ ["pane" "rename"] []] [["pane" "get"] []] [["pane" "close"] []]
   [ ["agent" "start"] ["--kind" "--pane"]] [["agent" "prompt"] []]
   [ ["agent" "wait"] ["--timeout"]] [["agent" "get"] []] [["agent" "list"] []]
   [ ["notification" "show"] ["--body"]]])

(defn- decode [s] (some-> (not-empty (str/trim s)) (json/parse-string true)))
(defn invoke [argv]
  (let [{:keys [exit out err]} @(process/process (into ["herdr"] argv) {:out :string :err :string})]
    (if (zero? exit) {:ok true :value (decode out) :out out}
        {:ok false :error {:kind :herdr :exit exit :argv argv :stderr err :response (decode err)}})))
(defn value! [argv]
  (let [result (invoke argv)] (if (:ok result) (:value result) (throw (ex-info "Herdr command failed" (:error result))))))
(defn version []
  (let [{:keys [exit out err]} @(process/process ["herdr" "--version"] {:out :string :err :string})
        found (some->> (re-find #"(\d+)\.(\d+)\.(\d+)" out) rest (mapv #(Long/parseLong %)))]
    (if (and (zero? exit) found) found (throw (ex-info "unable to determine Herdr version" {:exit exit :stderr err})))))
(defn at-least? [actual expected] (not (neg? (compare actual expected))))
(defn command-help [command]
  (let [{:keys [exit out err]} @(process/process (into ["herdr"] (conj command "--help")) {:out :string :err :string})]
    (if (#{0 2} exit) (str out err) (throw (ex-info "unable to inspect Herdr capability" {:command command :stderr err})))))
(defn preflight! []
  (when-not (= "1" (System/getenv "HERDR_ENV")) (throw (ex-info "subagent requires HERDR_ENV=1; run inside a Herdr pane" {:kind :environment})))
  (let [actual (version)]
    (when-not (at-least? actual minimum-version) (throw (ex-info "Herdr 0.7.5 or newer is required" {:actual actual :minimum minimum-version}))))
  (doseq [[command flags] required-capabilities]
    (let [text (command-help command) prefix (str "herdr " (str/join " " command))]
      (when-not (str/includes? text prefix) (throw (ex-info "installed Herdr lacks required command" {:command command})))
      (doseq [flag flags] (when-not (str/includes? text flag) (throw (ex-info "installed Herdr lacks required flag" {:command command :flag flag}))))))
  true)
(defn caller-rect! []
  (let [pane (System/getenv "HERDR_PANE_ID") layout (value! ["pane" "layout" "--pane" pane])
        panes (get-in layout [:result :layout :panes]) match (some #(when (= pane (:pane_id %)) %) panes)]
    (or (:rect match) (throw (ex-info "caller pane absent from Herdr layout" {:pane pane :panes panes})))))
(defn split! [{:keys [direction cwd env]}]
  (let [pane (System/getenv "HERDR_PANE_ID")]
    (get-in (value! (into ["pane" "split" "--pane" pane "--direction" direction "--cwd" cwd "--no-focus"]
                           (mapcat (fn [[k v]] ["--env" (str k "=" v)]) env))) [:result :pane])))
;; The child pane is `.result.root_pane`, not `.result.pane` (tab creation also returns
;; `.result.tab`); `--label` here sets the *tab's* label, distinct from the pane label
;; the existing rename! flow applies afterward.
(defn tab-create! [{:keys [cwd label env]}]
  (let [result (get-in (value! (into ["tab" "create" "--workspace" (System/getenv "HERDR_WORKSPACE_ID") "--cwd" cwd "--label" label "--no-focus"]
                                      (mapcat (fn [[k v]] ["--env" (str k "=" v)]) env)))
                        [:result])]
    (assoc (:root_pane result) :tab-id (get-in result [:tab :tab_id]))))
(defn rename! [pane label] (get-in (value! ["pane" "rename" pane label]) [:result :pane]))
(defn pane! [pane] (get-in (value! ["pane" "get" pane]) [:result :pane]))
(defn close! [pane] (value! ["pane" "close" pane]))
(defn agents [] (get-in (value! ["agent" "list"]) [:result :agents]))
(defn agent! [target] (get-in (value! ["agent" "get" target]) [:result :agent]))
(defn start! [name kind pane native-args]
  (get-in (value! (into ["agent" "start" name "--kind" kind "--pane" pane] (when (seq native-args) (into ["--"] native-args)))) [:result :agent]))
(defn prompt! [target text] (value! ["agent" "prompt" target text]))
(defn wait! [target timeout] (invoke ["agent" "wait" target "--timeout" (str timeout)]))
(defn notify! [title body] (value! ["notification" "show" title "--body" body]))
