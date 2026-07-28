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

(def ^:private excluded-segments
  #{".git" ".direnv" ".devenv" ".cache" "node_modules" "target" "build" "dist" ".next"})

(defn- safe-relative-path? [p]
  (and (string? p)
       (not (re-find #"[\x00-\x1f]" p))))

(defn- excluded-path? [p]
  (some excluded-segments (str/split p #"/")))

(defn- text-file? [path]
  (try
    (not-any? zero? (seq (java.nio.file.Files/readAllBytes (fs/path path))))
    (catch Throwable _ false)))

(defn real-fs
  "`fs` adapter (see `org-tasks.spec/discover`) backed by real disk
  access under `project-root`. Also used by the `ot doctor`
  declared-but-stale check to resolve declared-spec link closures."
  [project-root]
  (let [root (fs/path project-root)
        eligible? (fn [p]
                    (and (safe-relative-path? p)
                         (not (excluded-path? p))
                         (text-file? (fs/path root p))))]
    {:exists? (fn [p] (and (safe-relative-path? p) (fs/exists? (fs/path root p))))
     :dir?    (fn [p] (and (safe-relative-path? p) (fs/directory? (fs/path root p))))
     :eligible? eligible?
     :read    (fn [p] (when (eligible? p)
                         (try (slurp (str (fs/path root p))) (catch Throwable _ nil))))
     :list-files
     (fn [dir]
       (when (safe-relative-path? dir)
         (let [base (fs/path root dir)]
           (if (fs/directory? base)
             (->> (fs/glob base "**")
                  (remove fs/directory?)
                  (map #(str (fs/relativize root %)))
                  (filter eligible?)
                  vec)
             (when (= dir "")
               ;; Root-level non-recursive listing, used only for README.* globbing.
               (->> (fs/list-dir root)
                    (remove fs/directory?)
                    (map #(str (fs/relativize root %)))
                    (filter eligible?)
                    vec))))))}))

(defn- entry->text [{:keys [path provenance]}]
  (str path "  (" provenance ")"))

(defn spec-list-cmd [{:keys [opts]}]
  (let [{:keys [project-root files]} (load-context opts)
        tasks-content (try (slurp (:tasks files)) (catch Throwable _ nil))
        report (spec/discover-report (real-fs project-root) tasks-content skills-dir-candidates)
        entries (:entries report)]
    (out/emit-result
     opts
     {:specs (mapv (fn [{:keys [path provenance]}] {:path path :provenance provenance}) entries)
      :count (count entries)
      :text/lines (if (seq entries)
                    (into [(str "ot spec list: " (count entries) " spec(s) discovered.")]
                          (map entry->text entries))
                    ["ot spec list: no specs discovered."])}
     (:warnings report))))
