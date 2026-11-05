(ns herdr-orch.fixture-cli
  "Run the real CLI with a file-backed HTTP transport in offline subprocess tests."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [herdr-orch.cli :as cli]
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
            response (get responses provider)]
        (if (map? response)
          {:status (if (and (contains? response "expectedAuthorization")
                            (not= (get response "expectedAuthorization") (get headers "Authorization")))
                     401 (get response "status" 200)) :headers (get response "headers" {})
           :body (json/generate-string (get response "body"))}
          {:status 200 :body "null" :headers {}}))
      (catch Exception _ {:status 200 :body "null" :headers {}}))))

(defn -main [& argv]
  (with-redefs [usage/default-http-fn fixture-http
                http/get (fn [& _] (throw (ex-info "network escaped fixture transport" {})))]
    (apply cli/-main argv)))
