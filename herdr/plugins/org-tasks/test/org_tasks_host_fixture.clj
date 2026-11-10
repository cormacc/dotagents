(ns org-tasks-host-fixture
  (:require [babashka.fs :as fs]
            [org-tasks-host :as host]))

(defn -main [root]
  (let [tab (or (System/getenv "HERDR_TAB_ID") "w1:t1")
        state-file (fs/path root "host.json")
        dispatch
        (fn [& args]
          (case (vec (take 2 args))
            ["api" "snapshot"] {:snapshot (host/read-json state-file)}
            ["pane" "process-info"] {:process_info {:shell_pid 22}}
            ["pane" "layout"] {:layout {:panes [] :splits []}}
            ["plugin" "pane"]
            (let [env (some #(when (.startsWith (str %) "OT_HOST_INSTANCE=") %) args)
                  instance (subs env (count "OT_HOST_INSTANCE="))
                  pane {:pane_id "tasks" :terminal_id "term-tasks" :tab_id tab}
                  record {:pane "tasks" :terminal "term-tasks" :tab tab :pid 22
                          :identity "fixture" :instance instance :updatedAt (host/now)}]
              ;; The independent append observes each host open even if a broken
              ;; adapter races and overwrites its other state files.
              (spit (str (fs/path root "opens")) "open\n" :append true)
              (Thread/sleep 100)
              (host/atomic! state-file (update (host/read-json state-file) :panes conj pane))
              (host/atomic! (fs/path root instance "owner.json") record)
              {:plugin_pane {:pane pane}})))]
    (with-redefs [host/state-root! (constantly root) host/call! dispatch
                  host/identity! (constantly "fixture")]
      (host/open-session! root host/defaults))))
