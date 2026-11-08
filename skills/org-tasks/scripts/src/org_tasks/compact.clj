(ns org-tasks.compact
  "View model for the compact selected-task context.

  One source for every compact renderer: the terminal TUI's compact mode and
  `ot compact`, whose JSON feeds native hosts such as the herdr-app widget.
  Renderers own only presentation (colour, width, alignment); selection of
  rows, ordering, emphasis, progress and counts live here.

  Pure and charm-free. Input is the TUI state shape built by
  `org-tasks.tui.tasks/initial-state` from `ot list --full` rows."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [org-tasks.lifecycle :as lifecycle]
            [org-tasks.task :as task]
            [org-tasks.tui.tasks :as tasks]))

(defn home-shortened [path]
  (when path
    (let [home (System/getProperty "user.home")
          prefix (str (str/replace home #"/+$" "") "/")]
      (cond
        (= path home) "~"
        (str/starts-with? path prefix) (str "~/" (subs path (count prefix)))
        :else path))))

(defn- plan-file-name
  "File name of an import link such as `plan:2026-11-05-x.org`."
  [plan]
  (some-> plan (str/replace #"^[a-z][a-z0-9-]*:" "") fs/file-name str))

(defn- selected-context
  "The selected task with its top-level parent, nearest linked plan and
  descendants."
  [state]
  (let [rows (:tasks state)
        ;; A task without :CUSTOM_ID: has a nil id; it must not match an
        ;; absent selection.
        selected (when-let [id (:selected-id state)] (first (filter #(= id (:id %)) rows)))
        ancestors (tasks/ancestor-ids rows (:selected-id state))
        by-id (into {} (map (juxt :id identity)) rows)
        plan-owner (first (filter :importPath (take-while some?
                                                          (iterate #(get by-id (:parentId %)) selected))))]
    {:selected selected
     :parent (first (filter #(and (contains? ancestors (:id %))
                                  (not (contains? ancestors (:parentId %)))) rows))
     :plan (:importPath plan-owner)
     :descendants (filter #(contains? (tasks/ancestor-ids rows (:id %))
                                      (:selected-id state)) rows)}))

(def ^:private completed? #(contains? lifecycle/closed-statuses (:status %)))
(def ^:private started? #(= "STARTED" (:status %)))

(defn- row [role t current? token]
  {:role role
   :id (:id t)
   :status (:status t)
   :summary (:summary t)
   :label (str (:summary t) " [" (task/id-prefix (:id t)) "]")
   :current current?
   :token token})

(defn model
  "Compact view model.

  `:rows` holds the selected task (`task`), its top-level parent (`parent`),
  every STARTED descendant (`started`) and the next pending descendant
  (`next`). STARTED descendants are current; the selected task is current
  when none has started. The first STARTED row carries the `done/total`
  descendant progress `:token`, which the task row carries when nothing has
  started; the next row's token is `...` when more pending work is hidden.

  `:memory` is false when the project has no TASKS.org. `:root` is the
  resolved project root, for watching.
  `:selection` is `selected`, `none` or `stale`. Without a selection,
  `:counts` lists each status with a non-zero count in canonical order.
  `:files` lists the task files the view reads, for change watching."
  [state]
  (let [{:keys [selected parent plan descendants]} (selected-context state)
        started (filter started? descendants)
        [next-task & hidden] (remove #(or (completed? %) (started? %)) descendants)
        current-ids (set (map :id (or (seq started) [selected])))
        current? #(contains? current-ids (:id %))
        progress (str (count (filter completed? descendants)) "/" (count descendants))
        folder (some-> (:task-memory-path state) fs/parent str home-shortened)
        plan-name (plan-file-name plan)
        counts (frequencies (map :status (:tasks state)))]
    {:title (str/join " :: " (remove nil? ["Tasks" folder plan-name]))
     :folder folder
     :root (some-> (:task-memory-path state) fs/parent str)
     :memory (boolean (some-> (:task-memory-path state) fs/exists?))
     :plan plan-name
     :selectedId (:selected-id state)
     :selection (cond selected "selected" (:selected-id state) "stale" :else "none")
     :total (count (:tasks state))
     :counts (vec (for [status lifecycle/status-cycle
                        :let [n (get counts status 0)]
                        :when (pos? n)]
                    {:status status :count n}))
     :rows (if selected
             (vec (concat [(row "task" selected (current? selected) (when (empty? started) progress))]
                          (when parent [(row "parent" parent false nil)])
                          (map-indexed #(row "started" %2 (current? %2) (when (zero? %1) progress)) started)
                          (when next-task [(row "next" next-task false (when (seq hidden) "..."))])))
             [])
     :files (vec (:watch-files state))}))

(defn status-summary
  "`<N> tasks: <n> TODO / <n> STARTED ...`. `style-status` styles each
  status word."
  ([m] (status-summary m identity))
  ([m style-status]
   (let [parts (for [{:keys [status count]} (:counts m)]
                 (str count " " (style-status status)))]
     (str (:total m) " tasks" (when (seq parts) (str ": " (str/join " / " parts)))))))

(defn watch-files
  "Task files the compact view reads: protocol files except the archive, plus
  every loaded source (imports and setup files)."
  [list-result]
  (let [tasks-file (get-in list-result [:files :tasks])
        setup (some-> tasks-file fs/parent (fs/path "TASKS.setup.org") str)]
    (vec (distinct (concat (vals (dissoc (:files list-result) :archive))
                           (when (and setup (fs/exists? setup)) [setup])
                           (map name (keys (:sources list-result))))))))
