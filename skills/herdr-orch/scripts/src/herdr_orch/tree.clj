(ns herdr-orch.tree
  "Pure agent-tree model for the Herdr sidebar: the port of `herdr-app/mods/orch-spaces/tree.js`
  `spaceTree` and `agentName`. No I/O: callers pass already-parsed data.

  Contract: `(rows ledger-entries agent-rows)` -> `{pane-id {:order n :marks str :name str}}`.

  Inputs (JSON parsed with keyword keys, as the ledger and `herdr agent list --json` give them):
  - ledger entries: `:parent-session` (string), `:child-session` (nil or a map with `:value`),
    `:pane-id`, `:label`, `:started-at` (ISO-8601 string; orders siblings), and `:child` (label fallback).
  - agent rows, in `agent list` order: `:pane_id`, `:workspace_id`, `:agent_session` (nil or a map with `:value`).

  Liveness: an entry is live only when a listed agent carries its `:child-session :value`. An
  entry with no recorded session is never live, and a pane reused by another program does not
  keep its old entry alive. `:closed-at` is never read. Several rounds of one
  child share a pane; the round with the latest `:started-at` describes it.

  Parent: the listed agent carrying the entry's `:parent-session`, in the same workspace and not
  the entry itself, whether or not it has a live entry of its own. Such an entry-less parent (the
  orchestrator that started the first `oh` children) is a tree node with `:name` nil; it exists
  only while a live child in its workspace names it. Any other entry is a root. Roots keep
  agent-list order; siblings sort by `:started-at`.

  Output: live oh children and their entry-less parents appear; other agents get no row. `:order`
  is the depth-first index among the rows of the agent's workspace. `:marks` is empty for a root; otherwise the continuation lead of the
  ancestors then `\"└\"` (last sibling) or `\"├\"`, a finished branch continuing as
  `\"⠀ \"` (U+2800 BRAILLE PATTERN BLANK and a space) and an open one as `\"│ \"`. The final
  mark has no trailing space and the blank is not whitespace, because Herdr trims whitespace from
  token values. `:name` is the text after the last `/` of `:label`."
  (:require [clojure.string :as str]))

(defn- session-value [m] (some-> m :value not-empty))

(defn- agent-name [{:keys [label child]}]
  (let [label (if (str/blank? label) child label)]
    (some-> label (str/split #"/") last)))

(defn- match-agent
  "The listed agent that is `entry`'s live process, or nil."
  [by-session entry]
  (some-> (:child-session entry) session-value by-session))

(defn- walk
  "Depth-first sequence of `[node marks]` pairs for `node` and its descendants, `node` first.
  `marks` is the node's own mark string; `lead` is the continuation prefix its children extend."
  [kids node marks lead]
  (let [children (kids (:pane node))
        n        (count children)]
    (cons [node marks]
          (mapcat (fn [i child]
                    (let [last? (= i (dec n))]
                      (walk kids child
                            (str lead (if last? "└" "├"))
                            (str lead (if last? "⠀ " "│ ")))))
                  (range) children))))

(defn rows
  "See the namespace docstring."
  [ledger-entries agent-rows]
  (let [by-session (into {} (keep (fn [a] (some-> (session-value (:agent_session a)) (vector a)))) agent-rows)
        ;; one node per pane: the newest round wins
        entry-nodes (->> (sort-by #(or (:started-at %) "") ledger-entries)
                        (keep (fn [e]
                                (when-let [a (match-agent by-session e)]
                                  [(:pane_id a) {:pane      (:pane_id a)
                                                 :ws        (:workspace_id a)
                                                 :session   (session-value (:agent_session a))
                                                 :parent    (:parent-session e)
                                                 :started   (or (:started-at e) "")
                                                 :name      (agent-name e)}])))
                        (into {}))
        ;; an entry-less live agent named by a live child's parent-session in its workspace
        parents    (into {} (keep (fn [[_ n]]
                                    (let [a (some-> (:parent n) by-session)]
                                      (when (and a (not (entry-nodes (:pane_id a)))
                                                 (= (:workspace_id a) (:ws n)))
                                        [(:pane_id a) {:pane    (:pane_id a)
                                                       :ws      (:workspace_id a)
                                                       :session (session-value (:agent_session a))
                                                       :started ""
                                                       :name    nil}]))))
                          entry-nodes)
        nodes      (merge entry-nodes parents)
        by-node-session (into {} (keep (fn [[_ n]] (some-> (:session n) (vector n)))) nodes)
        direct     (fn [n]
                     (let [p (some-> (:parent n) by-node-session)]
                       (when (and p (not= (:pane p) (:pane n)) (= (:ws p) (:ws n)))
                         p)))
        ;; an entry in a parent cycle has no root above it, so it is a root itself
        parent-of  (fn [n]
                     (when-let [p (direct n)]
                       (loop [x p, hops 0]
                         (cond (= (:pane x) (:pane n)) nil
                               (> hops (count nodes)) nil
                               (direct x) (recur (direct x) (inc hops))
                               :else (:pane p)))))
        kids       (let [m (group-by #(parent-of (val %)) nodes)]
                     (fn [pane] (->> (get m pane) (map val) (sort-by :started))))
        ordered    (->> agent-rows (map :pane_id) (keep nodes))
        all        (mapcat #(walk kids % "" "") (remove parent-of ordered))]
    (->> all
         (group-by (comp :ws first))
         vals
         (mapcat (fn [ws-rows]
                   (map-indexed (fn [i [n marks]]
                                  [(:pane n) {:order i :marks marks :name (:name n)}])
                                ws-rows)))
         (into {}))))
