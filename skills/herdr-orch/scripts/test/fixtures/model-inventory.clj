(require '[cheshire.core :as json]
         '[herdr-orch.cli :as cli]
         '[herdr-orch.core :as core]
         '[herdr-orch.quota-credentials :as credentials]
         '[herdr-orch.routing :as routing])

(when-not (= "model-inventory" (System/getenv "HERDR_ORCH_ADAPTER"))
  (throw (ex-info "fake Pi accepts only offline inventory" {})))
(let [config (cli/config)
      inventory (vec (for [canonical (distinct (concat (keys (:models config))
                                                        (filter string? (vals (:aliases config)))))
                           account (cond-> [(core/provider-prefix canonical)]
                                     (= "anthropic" (core/provider-prefix canonical))
                                     (into (credentials/pi-accounts (credentials/environment))))]
                       {:canonical canonical :account account :reasoning (vec (sort routing/levels))}))]
  (spit (System/getenv "HERDR_ORCH_ADAPTER_RESULT") (json/generate-string {:ok true :inventory inventory})))
