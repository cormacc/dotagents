(require '[herdr-orch.cli :as cli])

;; Keep the detached PID/session across exec, but delay supervisor.json until the waiter has
;; observed the launcher's acknowledgement and exit. No subject can start before release.
(let [[gate & args] *command-line-args*
      command @#'cli/supervisor-command]
  (with-redefs-fn {#'cli/supervisor-command
                  (fn [& args]
                    (into ["bash" "-c"
                           "for i in $(seq 1 2000); do [ -e \"$1\" ] && { shift; exec \"$@\"; }; sleep 0.02; done; exit 1"
                           "supervisor-start-gate" gate]
                          (apply command args)))}
    #(apply cli/-main args)))
