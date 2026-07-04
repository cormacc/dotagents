(ns org-tasks.commands.spec
  "`ot spec list` (alias `spec discover`) command handler.

  Report-only: prints the discovered spec set with root provenance.
  No gating, no doctor coupling — see `org-tasks.spec` for the pure
  traversal engine this wraps with real filesystem access."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [org-tasks.output :as out]
            [org-tasks.spec :as spec]
            [org-tasks.commands.util :refer [load-context]]))

(def skills-dir-candidates ["skills" ".agents/skills"])

(defn real-fs
  "`fs` adapter (see `org-tasks.spec/discover`) backed by real disk
  access under `project-root`. Also used by the `ot doctor`
  declared-but-stale check to resolve declared-spec link closures."
  [project-root]
  {:exists? (fn [p] (fs/exists? (fs/path project-root p)))
   :dir?    (fn [p] (fs/directory? (fs/path project-root p)))
   :read    (fn [p] (try (slurp (str (fs/path project-root p))) (catch Throwable _ nil)))
   :list-files
   (fn [dir]
     (let [base (fs/path project-root dir)]
       (if (fs/directory? base)
         (->> (fs/glob base "**")
              (remove fs/directory?)
              (mapv (fn [p] (str (fs/relativize (fs/path project-root) p)))))
         (when (= dir "")
           ;; Root-level non-recursive listing, used only for README.* globbing.
           (->> (fs/list-dir (fs/path project-root))
                (remove fs/directory?)
                (mapv (fn [p] (str (fs/relativize (fs/path project-root) p))))))))) })

(defn- entry->text [{:keys [path provenance]}]
  (str path "  (" provenance ")"))

(defn spec-list-cmd [{:keys [opts]}]
  (let [{:keys [project-root files]} (load-context opts)
        tasks-content (try (slurp (:tasks files)) (catch Throwable _ nil))
        entries (spec/discover (real-fs project-root) tasks-content skills-dir-candidates)]
    (out/emit-result
     opts
     {:specs (mapv (fn [{:keys [path provenance]}] {:path path :provenance provenance}) entries)
      :count (count entries)
      :text/lines (if (seq entries)
                    (into [(str "ot spec list: " (count entries) " spec(s) discovered.")]
                          (map entry->text entries))
                    ["ot spec list: no specs discovered."])})))
