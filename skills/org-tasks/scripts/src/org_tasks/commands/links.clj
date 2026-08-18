(ns org-tasks.commands.links
  "`ot` handoff get/set/clear, blocker list/add/remove, ready,
  issue list/add/remove/urls command handlers."
  (:require [clojure.string :as str]
            [org-tasks.loader :as loader]
            [org-tasks.output :as out]
            [org-tasks.parser :as parser]
            [org-tasks.task :as task]
            [org-tasks.tree :as tree]
            [org-tasks.commands.util :refer [positional-arg load-context
                                             resolve-required-id guard-write!]]))
(defn- mutate-task-and-save* [save! {:keys [project-root tasks dry-run?]} id f]
  (when-let [target (task/find-by-id tasks id)]
    (let [updated (f target)
          tree-new (tree/update-by-id tasks id (constantly updated))]
      (when-not dry-run?
        (save! project-root tree-new))
      [updated tree-new])))

(defn- mutate-task-and-save [ctx id f]
  (mutate-task-and-save* loader/save-source-roots ctx id f))

(defn- mutate-task-and-save-locality [ctx id f]
  (mutate-task-and-save* loader/save-source-roots-locality ctx id f))
(defn- blocker->wire [b]
  {:raw (:raw b) :kind (name (:kind b)) :ref (:ref b)})
(defn- linked-issue->wire [i]
  (cond-> {:rawToken (:raw-token i) :label (:label i) :url (:url i)}
    (:error i) (assoc :error (:error i))))
(defn- normalise-blocker-token [^String raw]
  (let [trimmed (str/trim raw)]
    (if (or (re-find #"(?i)^(task|url|human|jira):" trimmed)
            (= :task (:kind (parser/parse-blocker trimmed))))
      trimmed
      (str "human: " trimmed))))
(defn- task-link-templates [task]
  (parser/parse-link-templates (or (:effective-source-content task)
                                   (:source-content task) "")))
(defn- existing-issue-tokens [task]
  (vec (filter seq
               (or (some-> (parser/get-drawer-property task "LINKED_ISSUES")
                           (str/split #"\s+"))
                   []))))
(def ^:private property-commands
  {:handoff {:list-key :handoff
             :list-fn parser/get-task-handoff
             :empty "(no :HANDOFF: set)"
             :set-fn parser/set-task-handoff
             :set-arg :text
             :set-missing "ot handoff set requires <id> <text>."
             :set-line #(str "Set handoff: " %)
             :clear-value nil
             :clear-line "Cleared handoff."}
   :blocker {:list-key :blockers
             :list-fn #(mapv blocker->wire (parser/get-task-blockers %))
             :list-lines (fn [blockers]
                           (if (empty? blockers)
                             ["(no blockers)"]
                             (mapv :raw blockers)))
             :tokens-fn #(mapv :raw (parser/get-task-blockers %))
             :set-fn parser/set-task-blockers
             :normalise normalise-blocker-token
             :set-arg :token
             :add-missing "ot blocker add requires <id> <token>."
             :remove-missing "ot blocker remove requires <id> <token>."
             :add-line #(str "Added blocker: " %)
             :remove-line #(str "Removed blocker: " %)}
   :issue   {:list-key :issues
             :list-fn #(parser/get-linked-issues % (task-link-templates %))
             :wire #(mapv linked-issue->wire %)
             :list-lines #(if (empty? %) ["(no linked issues)"] (mapv :raw-token %))
             :tokens-fn existing-issue-tokens
             :set-fn parser/set-linked-issues
             :normalise identity
             :set-arg :token
             :add-missing "ot issue add requires <id> <token>."
             :remove-missing "ot issue remove requires <id> <token>."
             :add-line #(str "Added linked-issue token: " %)
             :remove-line #(str "Removed linked-issue token: " %)
             :mut-key :tokens}})
(defn- list-property-cmd [prop {:keys [opts] :as result}]
  (let [{:keys [tasks]} (load-context opts)
        id (positional-arg result :id)
        t (resolve-required-id tasks id opts)
        {:keys [list-key list-fn list-lines empty wire]} (property-commands prop)
        value (list-fn t)
        out-value (if wire (wire value) value)]
    (out/emit-result opts
                     {:taskId (parser/get-task-id t)
                      list-key out-value
                      :text/lines (or (when list-lines (list-lines value))
                                      [(or value empty)])})))
(defn- add-remove-property-cmd [prop op {:keys [opts] :as result}]
  (let [cfg (property-commands prop)
        ctx (load-context opts)
        id (positional-arg result :id)
        token (positional-arg result (:set-arg cfg) 1)]
    (if (or (nil? id) (nil? token))
      (out/emit-error opts {:code "argument-error"
                            :message (cfg (if (= op :add) :add-missing :remove-missing))})
      (let [target (resolve-required-id (:tasks ctx) id opts)
            full-id (parser/get-task-id target)
            token' (if (= op :add) ((:normalise cfg identity) token) token)
            [updated _] (mutate-task-and-save-locality
                          (assoc ctx :dry-run? (:dry-run opts))
                          full-id
                          (fn [t]
                            (let [existing ((:tokens-fn cfg) t)
                                  next (if (= op :add)
                                         (conj existing token')
                                         (filterv #(not= % token) existing))]
                              ((:set-fn cfg) t next))))
            values ((:tokens-fn cfg) updated)]
        (out/emit-result opts
                         {:taskId full-id
                          (or (:mut-key cfg) (:list-key cfg))
                          (if (= prop :blocker)
                            (mapv blocker->wire (parser/get-task-blockers updated))
                            values)
                          :text/lines [((cfg (if (= op :add) :add-line :remove-line)) token')]})))))
(defn handoff-get-cmd [result] (list-property-cmd :handoff result))
(defn handoff-set-cmd [{:keys [opts] :as result}]
  (let [ctx (load-context opts)
        id (positional-arg result :id)
        text (positional-arg result :text 1)]
    (if (or (nil? id) (nil? text))
      (out/emit-error opts {:code "argument-error"
                            :message "ot handoff set requires <id> <text>."})
      (let [target (resolve-required-id (:tasks ctx) id opts)
            full-id (parser/get-task-id target)
            [updated _] (mutate-task-and-save-locality
                          (assoc ctx :dry-run? (:dry-run opts))
                          full-id #(parser/set-task-handoff % text))]
        (out/emit-result opts {:taskId full-id
                               :handoff (parser/get-task-handoff updated)
                               :text/lines [(str "Set handoff: " text)]})))))
(defn handoff-clear-cmd [{:keys [opts] :as result}]
  (let [ctx (load-context opts)
        id (positional-arg result :id)
        target (resolve-required-id (:tasks ctx) id opts)
        full-id (parser/get-task-id target)]
    (mutate-task-and-save-locality (assoc ctx :dry-run? (:dry-run opts))
                                    full-id #(parser/set-task-handoff % nil))
    (out/emit-result opts {:taskId full-id :handoff nil
                           :text/lines ["Cleared handoff."]})))
(defn blocker-list-cmd [result] (list-property-cmd :blocker result))
(defn blocker-add-cmd [result] (add-remove-property-cmd :blocker :add result))
(defn blocker-remove-cmd [result] (add-remove-property-cmd :blocker :remove result))
(defn issue-list-cmd [result] (list-property-cmd :issue result))
(defn issue-add-cmd [result] (add-remove-property-cmd :issue :add result))
(defn issue-remove-cmd [result] (add-remove-property-cmd :issue :remove result))
(defn- tag-mutate-cmd [op {:keys [opts] :as result}]
  (let [ctx (load-context opts)
        id (positional-arg result :id)
        ;; `:tag` is a repeatable create option, so the registry's aggregate
        ;; dispatch coercion turns that positional name into a vector. Keep
        ;; this command's positional token distinct from the option key.
        raw-tag (positional-arg result :tag-token 1)]
    (if (or (nil? id) (nil? raw-tag))
      (out/emit-error opts {:code "argument-error"
                            :message (str "ot tag " (name op)
                                          " requires <id> <tag>.")})
      (if-let [tag (parser/normalise-task-tag raw-tag)]
        (let [target (resolve-required-id (:tasks ctx) id opts)
              full-id (parser/get-task-id target)
              [updated _] (guard-write!
                           opts
                           #(mutate-task-and-save
                             (assoc ctx :dry-run? (:dry-run opts))
                             full-id
                             (if (= op :add)
                               (fn [t] (parser/add-task-tag t tag))
                               (fn [t] (parser/remove-task-tag t tag)))))]
          (out/emit-result opts
                           {:taskId full-id
                            :tags (:tags updated)
                            :text/lines [(str (if (= op :add) "Added" "Removed")
                                              " tag: " tag)]}))
        (out/emit-error opts
                        {:code "invalid-tag"
                         :message (str "Invalid tag " (pr-str raw-tag)
                                       "; expected letters, digits, and underscores")})))))
(defn tag-add-cmd [result] (tag-mutate-cmd :add result))
(defn tag-remove-cmd [result] (tag-mutate-cmd :remove result))
(defn ready-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks]} (load-context opts)
        id (positional-arg result :id)
        t (resolve-required-id tasks id opts)
        full-id (parser/get-task-id t)
        report (parser/is-task-ready t #(task/find-by-id tasks %))
        gating-wire (mapv (fn [{:keys [blocker reason]}]
                            {:blocker (blocker->wire blocker)
                             :reason (name reason)})
                          (:gating report))]
    (out/emit-result
      opts
      {:taskId full-id
       :ready (:ready report)
       :gating gating-wire
       :text/lines (if (:ready report)
                     [(str full-id ": ready")]
                     (cons (str full-id ": not ready (" (count gating-wire) " gating)")
                           (map (fn [g] (str "  - " (get-in g [:blocker :raw])
                                             " [" (:reason g) "]"))
                                gating-wire)))})))
(defn issue-urls-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks]} (load-context opts)
        id (positional-arg result :id)
        t (resolve-required-id tasks id opts)
        issues (parser/get-linked-issues t (task-link-templates t))
        urls (vec (keep :url issues))]
    (out/emit-result opts
                     {:taskId (parser/get-task-id t)
                      :urls urls
                      :text/lines (if (empty? urls)
                                    ["(no resolvable URLs)"]
                                    urls)})))
