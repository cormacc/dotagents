(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str] '[cheshire.core :as j])
;; Over SSH there is no ambient Herdr; the probe strips HERDR_* and uses a disposable XDG tree.
(fs/create-dirs ".tmp/native")
(def log ".tmp/native/native.log")
(def proc (p/process ["bb" "design/log/2026-11-05-org-tasks-herdr-ui/native-probe.bb"] {:out :write :out-file log :err :out}))
(def result (deref proc 240000 nil))
(try
  (assert result "native probe exceeded 240 second outer bound")
  (assert (= 0 (:exit result)) "native probe failed")
  (assert (str/includes? (slurp log) "NATIVE PASS"))
  (finally
    ;; The outer bound owns cleanup even if the probe cannot reach its finally.
    (let [[_ base session] (re-find #"BASE (\S+) SESSION (\S+)" (slurp log))]
      (assert (and base session (str/starts-with? base (str (fs/cwd) "/.tmp/n"))))
      (let [env (merge (into {} (remove (fn [[k _]] (str/starts-with? k "HERDR_")) (System/getenv)))
                       {"XDG_CONFIG_HOME" (str base "/config") "XDG_STATE_HOME" (str base "/state") "XDG_RUNTIME_DIR" (str base "/runtime")})]
        (doseq [args [["server" "stop"] ["session" "delete" session "--json"]]]
          (let [cleanup (p/process (into ["herdr" "--session" session] args) {:env env :out :string :err :string})
                r (deref cleanup 8000 nil)]
            (when-not r (p/destroy-tree cleanup))
            (println "OUTER CLEANUP" args (select-keys r [:exit :out :err]))))))
    (when (nil? (deref proc 0 nil)) (p/destroy-tree proc))))
(println "INDEPENDENT NATIVE PASS")
