(ns herdr-orch.routing
  "Validated, pure model/account selection shared by launches and primary recovery."
  (:require [herdr-orch.core :as core]))

(def schema "herdr-orch-route/v1")
(def levels #{"off" "minimal" "low" "medium" "high" "xhigh" "max"})
(defn- reject! []
  (throw (ex-info "invalid routing request" {:error-kind :usage})))
(defn- text? [x]
  (and (string? x) (<= 1 (count x) 256) (not (re-find #"[\s\p{Cntrl}]" x))))
(defn- canonical? [x]
  (and (text? x) (boolean (re-matches #"[^/]+/.+" x))))
(defn- pair? [x]
  (and (map? x) (= #{:canonical :account} (set (keys x)))
       (canonical? (:canonical x)) (text? (:account x))))
(defn validate!
  "Reject unknown fields and malformed bounded requests without IO. Inventory is
  authenticated, registered availability supplied by the caller, never credentials."
  [request]
  (when-not (and (map? request)
                 (every? #{:schema :model :current :inventory :attempted :reasoning} (keys request))
                 (= schema (:schema request)) (text? (:model request))
                 (or (nil? (:current request)) (pair? (:current request)))
                 (vector? (:inventory request)) (<= (count (:inventory request)) 4096)
                 (every? (fn [entry]
                           (and (map? entry) (= #{:canonical :account :reasoning} (set (keys entry)))
                                (pair? (select-keys entry [:canonical :account]))
                                (let [base (core/provider-prefix (:canonical entry)) account (:account entry)]
                                  (or (= base account)
                                      (and (= base "anthropic")
                                           (re-matches #"anthropic-account-[a-z][a-z0-9_-]{0,31}" account))))
                                (vector? (:reasoning entry))
                                (every? levels (:reasoning entry)))) (:inventory request))
                 (= (count (:inventory request)) (count (distinct (map #(select-keys % [:canonical :account]) (:inventory request)))))
                 (vector? (:attempted request)) (<= (count (:attempted request)) 4096)
                 (every? pair? (:attempted request))
                 (or (nil? (:reasoning request)) (levels (:reasoning request))))
    (reject!))
  request)

(defn candidates
  "Return configured model candidates for a validated alias or direct request."
  [config kind model]
  (if (core/vector-alias? config model)
    (core/candidates config kind model)
    (let [resolved (core/canonical-model config model)
          prefix (core/provider-prefix resolved)
          canonical (if (and prefix (re-matches #"anthropic-account-[a-z][a-z0-9_-]{0,31}" prefix))
                      (str "anthropic" (subs resolved (count prefix))) resolved)
          provider (core/provider-prefix canonical)
          allowed (get-in config [:harnesses (keyword kind) :providers])]
      (when-not (canonical? canonical) (reject!))
      (if (or (nil? allowed) (contains? allowed provider))
        [{:model model :canonical canonical :provider provider :rank 0
          :reserve (or (get-in config [:providers provider :reserve]) 0)}] []))))

(defn select-route
  "Select only eligible pairs. Recovery prefers same-model siblings, then the
  configured alias policy. Direct requests cannot change canonical models."
  [config kind request evidence]
  (validate! request)
  (let [base (candidates config kind (:model request))
        attempted (set (:attempted request))
        pairs (->> (core/account-candidates base (:inventory request))
                   (remove #(attempted (select-keys % [:canonical :account])))
                   (filter #(or (nil? (:reasoning request))
                                (some #{(:reasoning request)} (:reasoning %)))) vec)
        siblings (filterv #(and (:current request)
                                (= (:canonical %) (get-in request [:current :canonical]))
                                (not= (:account %) (get-in request [:current :account]))) pairs)
        sibling-route (when (seq siblings) (core/route siblings evidence))
        result (if (and sibling-route (not (:refused sibling-route)))
                 sibling-route (core/route pairs evidence))]
    (assoc result :schema schema)))
