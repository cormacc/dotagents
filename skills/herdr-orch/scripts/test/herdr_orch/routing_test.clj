(ns herdr-orch.routing-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.core :as core]
            [herdr-orch.routing :as routing]))

(def config
  {:harnesses {:pi {:model-flag "--model"}}
   :providers {"anthropic" {:reserve 0.1} "openai-codex" {} "lemonade" {}}
   :aliases {"tier" [{:model "anthropic/m" :rank 1 :quota-scope "exact"}
                      {:model "openai-codex/n" :rank 2}]}})
(def a {:canonical "anthropic/m" :account "anthropic"})
(def b {:canonical "anthropic/m" :account "anthropic-account-b"})
(def c {:canonical "openai-codex/n" :account "openai-codex"})
(def outside {:canonical "lemonade/outside" :account "lemonade"})
(defn inventory [pairs] (mapv #(assoc % :reasoning ["off" "medium" "high"]) pairs))
(def request {:schema routing/schema :model "tier" :inventory (inventory [c b a outside]) :attempted [] :reasoning "high"})
(defn evidence [fractions]
  {:providers (mapv (fn [[account remaining]]
                     {:localProvider account :baseProvider (if (str/starts-with? account "anthropic") "anthropic" account)
                      :provider "claude" :state {:status "fresh" :stale false}
                      :quotaSemantics {:effectiveAvailability
                                       [{:scope "exact" :status "known" :effectivePercentRemaining (* 100 remaining)}
                                        {:scope "all_models" :status "known" :effectivePercentRemaining 99}]}})
                   fractions)})
(defn pick [req rows] (routing/select-route config "pi" req (evidence rows)))

(deftest account-evidence-is-joined-by-selectable-identity
  (let [r (pick request {"anthropic" 0 "anthropic-account-b" 0.7 "openai-codex" 0.9})]
    (is (= "anthropic-account-b" (:account r)))
    (is (= "anthropic-account-b/m" (:concrete r)))
    (is (= [0.0 0.7 0.99] (mapv :remaining (:candidates r))))))

(deftest reserve-exact-scope-rank-and-default-first-order
  (is (= "anthropic" (:account (pick request {"anthropic" 0.5 "anthropic-account-b" 0.5}))))
  (is (= "anthropic-account-b" (:account (pick request {"anthropic" 0.1 "anthropic-account-b" 0.11}))))
  (is (= "anthropic" (:account (pick request {"anthropic-account-b" 0.01}))))
  (is (= "anthropic" (:account (pick request {}))))
  (is (= "openai-codex" (:account (pick request {"anthropic" 0 "anthropic-account-b" 0}))))
  (is (= "anthropic-account-b" (:account (pick request {"anthropic-account-b" 0.5}))))
  (is (= [] (:candidates (pick (assoc request :inventory []) {})))))

(deftest recovery-is-bounded-to-model-or-tier-and-unattempted-capable-pairs
  (let [req (assoc request :current a :attempted [a])]
    (is (= "anthropic-account-b" (:account (pick req {"anthropic-account-b" 0.2}))))
    (is (= "openai-codex" (:account (pick (assoc req :attempted [a b]) {}))))
    (is (:refused (pick (assoc req :attempted [a b c]) {})))
    (is (:refused (pick (assoc req :model "anthropic/m" :attempted [a b]) {})))
    (is (= "anthropic-account-b" (:account (pick (assoc req :model "anthropic/m") {}))))
    (is (= "anthropic-account-b" (:account (pick (assoc req :model "anthropic-account-b/m") {}))))
    (is (:refused (pick (assoc req :reasoning "max") {})))
    (is (= "openai-codex" (:account (pick (assoc req :inventory (assoc-in (:inventory req) [1 :reasoning] ["off"])) {}))))
    (is (not-any? #(= "lemonade" (:account %)) (:candidates (pick req {}))))))

(deftest recovery-prefers-same-model-before-alias-rank
  (let [cfg (assoc-in config [:aliases "tier"] [{:model "openai-codex/n" :rank 1} {:model "anthropic/m" :rank 2}])
        req (assoc request :current a :attempted [a])]
    (is (= "anthropic-account-b" (:account (routing/select-route cfg "pi" req (evidence {"anthropic-account-b" 0.2})))))))

(deftest strict-request-validation
  (is (= request (routing/validate! request)))
  (doseq [bad [nil [] {} (assoc request :schema "other") (assoc request :token "secret")
               (assoc request :attempted nil) (assoc request :current {:account "x"})
               (assoc request :reasoning "bogus") (assoc request :inventory (inventory [(assoc b :account "arbitrary")]))
               (assoc request :inventory (inventory [a a]))
               (assoc request :inventory [(assoc a :reasoning ["bogus"])])]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid routing request" (routing/validate! bad)))))
