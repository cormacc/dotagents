(ns herdr-orch.fixture-cli
  "Run the real CLI with a file-backed HTTP transport in offline subprocess tests."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [herdr-orch.cli :as cli]
            [herdr-orch.core :as core]
            [herdr-orch.pi-adapter :as pi-adapter]
            [herdr-orch.quota-credentials :as credentials]
            [herdr-orch.routing :as routing]
            [herdr-orch.usage :as usage]))

(defn fixture-http
  "Return only a fixture response for an exact subscription endpoint.
  Missing or malformed fixtures fail closed without contacting the network."
  [{:keys [url headers]}]
  (let [provider (some (fn [[provider endpoint]] (when (= endpoint url) provider)) usage/endpoints)]
    (when-not provider (throw (ex-info "unexpected fixture endpoint" {})))
    (spit (System/getenv "FAKE_QUOTA_LOG") (str provider "\n") :append true)
    (try
      (let [responses (json/parse-string (slurp (System/getenv "FAKE_QUOTA_REPORT")))
            response (if-let [accounts (get-in responses [provider "accounts"])]
                       (get accounts (get headers "Authorization"))
                       (get responses provider))]
        (if (map? response)
          {:status (if (and (contains? response "expectedAuthorization")
                            (not= (get response "expectedAuthorization") (get headers "Authorization")))
                     401 (get response "status" 200)) :headers (get response "headers" {})
           :body (json/generate-string (get response "body"))}
          {:status 200 :body "null" :headers {}}))
      (catch Exception _ {:status 200 :body "null" :headers {}}))))

(defn -main [& argv]
  (let [run-op pi-adapter/run-op!]
  (with-redefs [pi-adapter/run-op!
                (fn [file op request]
                  (if (= op "model-inventory")
                    {:ok true :inventory
                     (vec (for [canonical (distinct (concat (keys (:models (cli/config)))
                                                           (filter string? (vals (:aliases (cli/config))))))
                                account (cond-> [(core/provider-prefix canonical)]
                                          (= "anthropic" (core/provider-prefix canonical))
                                          (into (credentials/pi-accounts (credentials/environment))))]
                            {:canonical canonical :account account :reasoning (vec (sort routing/levels))}))}
                    (run-op file op request)))
                usage/default-http-fn fixture-http
                http/get (fn [& _] (throw (ex-info "network escaped fixture transport" {})))]
    (apply cli/-main argv))))
