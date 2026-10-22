(ns herdr-orch.pi-adapter
  "Runs an explicitly loaded Pi adapter operation inside the installed Pi runtime.

  The adapter extensions under scripts/pi/ own the logic that needs Pi's own session API. This
  namespace only starts `pi` with the adapter, hands it a request file, and reads the result
  file back: Pi keeps stdout for itself when stdin is not a terminal, so nothing travels there."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [herdr-orch.ledger :as ledger])
  (:import [java.util UUID]))

(defn pi-bin [] (or (not-empty (System/getenv "HERDR_ORCH_PI_BIN")) "pi"))
(def timeout-ms 90000)

(defn run-op!
  "Runs `op` (an adapter's HERDR_ORCH_ADAPTER value) with `request`, returning the parsed result.
  A refusal or a failed run throws with the adapter's own message."
  [adapter-file op request]
  (let [dir (fs/path (ledger/assignment-root) ".tmp" "herdr-orch" "adapter" (str (UUID/randomUUID)))
        request-file (str (fs/path dir "request.json"))
        result-file (str (fs/path dir "result.json"))]
    (fs/create-dirs dir)
    (try
      (spit request-file (json/generate-string request))
      (let [proc (try (process/process [(pi-bin) "--offline" "--no-session" "-ne" "-e" (str adapter-file)
                                        "--no-skills" "--no-prompt-templates" "--no-context-files" "--no-mcp" "-p" "x"]
                                       {:in (java.io.ByteArrayInputStream. (byte-array 0)) :out :string :err :string
                                        :extra-env {"HERDR_ORCH_ADAPTER" op "HERDR_ORCH_ADAPTER_REQUEST" request-file
                                                    "HERDR_ORCH_ADAPTER_RESULT" result-file "PI_OFFLINE" "1"}})
                      (catch java.io.IOException e
                        (throw (ex-info "the installed Pi runtime could not be started" {:pi (pi-bin) :cause (.getMessage e)}))))
            finished (deref proc timeout-ms ::timeout)]
        (when (= ::timeout finished)
          (process/destroy-tree proc)
          (throw (ex-info "Pi adapter timed out" {:op op :timeout-ms timeout-ms})))
        (let [result (when (fs/exists? result-file) (json/parse-string (slurp result-file) true))]
          (when-not (and result (:ok result) (zero? (:exit finished)))
            (throw (ex-info (or (:error result) (str "Pi adapter " op " failed without a result"))
                            {:op op :exit (:exit finished) :stderr (subs (str (:err finished)) 0 (min 400 (count (str (:err finished)))))})))
          result))
      (finally (fs/delete-tree dir)))))
