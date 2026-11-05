(require '[cheshire.core :as json]
         '[herdr-orch.runtime :as runtime])
(try
  (let [host (runtime/for-backend! :hc)
        [op target] *command-line-args*
        result (case op
                 "identity" (runtime/identity! host)
                 "preflight" (runtime/preflight-runtime! host)
                 "status" (runtime/status! host {:scope (keyword target) :target (nth *command-line-args* 2 nil)})
                 "wait" (runtime/wait-runtime! host target 100 ["idle" "done"])
                 "read" (runtime/read-runtime! host target {:source "visible"})
                 "prompt" (runtime/prompt-runtime! host target "fixture follow-up" {})
                 "close" (runtime/close-runtime! host target)
                 "notify" (runtime/notify-runtime! host "fixture title" "fixture body"))]
    (println (json/generate-string {:ok true :result result})))
  (catch Exception e
    (println (json/generate-string {:ok false :message (.getMessage e) :data (ex-data e)}))
    (System/exit 1)))
