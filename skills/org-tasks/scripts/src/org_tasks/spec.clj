(ns org-tasks.spec
  "Pure spec-discovery traversal engine backing `ot spec list`.

  Implements the rooted/transitive discovery convention documented in
  org-plan SKILL.md § Spec discovery (`#+SPEC:`):

    - `#+SPEC:` roots declared in TASKS.org, or the default root
      `./design/SPEC.org` when none are declared and it exists.
    - Implicit specs: repo-root `README.*`, `AGENTS.md`, and the
      project skills directory.
    - Folder roots expand recursively to every file beneath them.
    - Org `[[file:...]]` / `[[proj:...]]` links inside a root's content
      are followed transitively, resolved relative to the linking
      document, with a visited-set cycle guard.

  Also follows Markdown `[text](path)` links and org `#+INCLUDE:`
  directives, relative to the linking document; external (http/https)
  targets are never followed.

  No filesystem access here — the CLI layer (`commands/spec.clj`)
  supplies an `fs` map of pure lookup functions so this namespace stays
  testable with an in-memory fixture."
  (:require [clojure.string :as str]
            [org-tasks.doctor :as doctor]
            [org-tasks.parser :as parser]))

(def default-root-path "design/SPEC.org")

(def ^:private org-link-re
  #"\[\[(file|proj):([^\]\[]+?)\](?:\[[^\]]*\])?\]")

(def ^:private markdown-link-re
  #"\[[^\]]*\]\(([^)\s]+)\)")

(def ^:private org-include-re
  #"(?im)^[\t ]*#\+INCLUDE:[\t ]*\"?([^\t \"\n]+)\"?")

(defn extract-transitive-links
  "Return a seq of `{:kind (:file|:proj|:markdown|:include) :target str}`
  for every org `[[file:...]]` / `[[proj:...]]` link, Markdown
  `[text](path)` link, and org `#+INCLUDE:` directive found anywhere in
  `content`. External (http/https) targets are included here and
  filtered out later by `resolve-link-target`."
  [^String content]
  (let [content (or content "")]
    (concat
     (->> (re-seq org-link-re content)
          (map (fn [[_ kind target]] {:kind (keyword kind) :target (str/trim target)})))
     (->> (re-seq markdown-link-re content)
          (map (fn [[_ target]] {:kind :markdown :target (str/trim target)})))
     (->> (re-seq org-include-re content)
          (map (fn [[_ target]] {:kind :include :target (str/trim target)}))))))

(defn- normalize-repo-path
  "Collapse `./` and `../` segments in a repo-relative path string."
  [path]
  (let [segments (str/split path #"/")]
    (loop [segs segments
           out []]
      (if (empty? segs)
        (str/join "/" out)
        (let [s (first segs)]
          (cond
            (or (= s "") (= s ".")) (recur (rest segs) out)
            (= s "..") (recur (rest segs) (if (seq out) (pop out) out))
            :else (recur (rest segs) (conj out s))))))))

(defn resolve-link-target
  "Resolve a `{:kind :file|:proj :target}` link found in `linking-path`
  (a repo-relative path) to a repo-relative path. `proj:` links are
  always repo-root relative; `file:` links are relative to the
  linking document's directory (or repo-root when the target is
  already repo-root anchored with no `../` prefix ambiguity). Returns
  nil for external (http/https) targets."
  [linking-path {:keys [kind target]}]
  (when (and target (not (re-find #"(?i)^https?://" target)))
    (if (= kind :proj)
      (normalize-repo-path target)
      (let [dir (if (str/includes? linking-path "/")
                  (subs linking-path 0 (str/last-index-of linking-path "/"))
                  "")]
        (normalize-repo-path
         (if (str/starts-with? target "/")
           (subs target 1)
           (if (seq dir) (str dir "/" target) target)))))))

(defn- declared-spec-paths
  "Extract `#+SPEC:` root paths from TASKS.org `content`. Malformed
  values are silently skipped here — `ot doctor` reports those."
  [tasks-content]
  (->> (parser/get-file-keywords (or tasks-content "") "SPEC")
       (map str/trim)
       (remove str/blank?)
       distinct
       (keep doctor/extract-proj-link-path)))

(defn implicit-spec-paths
  "Return `{:path :provenance}` entries for the always-considered
  implicit specs that exist per `fs`: root `README.*`, `AGENTS.md`,
  and the skills directory (first of `skills-dir-candidates` that
  exists as a directory)."
  [{:keys [exists? dir? list-files] :as fs} skills-dir-candidates]
  (concat
   (for [readme (->> (or (list-files "") [])
                     (filter #(re-matches #"(?i)README\..+" %)))]
     {:path readme :provenance "implicit: README"})
   (when (exists? "AGENTS.md")
     [{:path "AGENTS.md" :provenance "implicit: AGENTS.md"}])
   (when-let [dir (first (filter dir? skills-dir-candidates))]
     [{:path dir :provenance (str "implicit: skills (" dir ")")}])))

(defn- expand-root
  "Expand one discovery root path into its constituent file paths.
  A folder root expands recursively via `list-files`; a file root is
  itself. Non-existent roots expand to nothing."
  [{:keys [exists? dir? list-files]} path]
  (cond
    (dir? path) (vec (list-files path))
    (exists? path) [path]
    :else []))

(defn linked-paths-from
  "BFS from a single `root-path` (typically a declared `#+SPEC:` path),
  following transitive org `[[file:...]]`/`[[proj:...]]` links only
  (no implicit specs, no `#+SPEC:` re-declaration, no folder
  expansion beyond the root itself). Returns the set of repo-relative
  paths reached, excluding `root-path` itself. Used by the `ot doctor`
  declared-but-stale advisory to know what a declared spec links to."
  [fs root-path]
  (loop [queue (expand-root fs root-path)
         visited #{}]
    (if (empty? queue)
      (disj visited root-path)
      (let [path (first queue)
            rest-queue (rest queue)]
        (if (contains? visited path)
          (recur rest-queue visited)
          (let [visited' (conj visited path)
                content ((:read fs) path)
                links (when content (extract-transitive-links content))
                new-paths (->> links
                              (keep (fn [link] (resolve-link-target path link)))
                              (remove #(contains? visited' %))
                              (mapcat #(expand-root fs %)))]
            (recur (into rest-queue new-paths) visited')))))))

(defn discover
  "Run the full rooted/transitive discovery traversal.

  `fs` supplies:
    :exists?    (fn [repo-rel-path]) -> bool
    :dir?       (fn [repo-rel-path]) -> bool
    :list-files (fn [repo-rel-dir]) -> seq of repo-relative file paths
                nested recursively under `repo-rel-dir` (dir `\"\"` means
                repo root, non-recursive — used only for README globbing)
    :read       (fn [repo-rel-path]) -> file content string or nil

  `tasks-content` is the parsed TASKS.org content (or nil).
  `skills-dir-candidates` is an ordered seq of candidate skills-dir
  paths, first existing directory wins (e.g. `[\"skills\" \".agents/skills\"]`).

  Returns a vector of `{:path :provenance}` maps in discovery order,
  one entry per distinct discovered file (dedup by `:path`, first
  provenance wins)."
  [fs tasks-content skills-dir-candidates]
  (let [declared (declared-spec-paths tasks-content)
        roots (if (seq declared)
                (for [p declared] {:path p :provenance (str "#+SPEC: " p)})
                (when ((:exists? fs) default-root-path)
                  [{:path default-root-path :provenance (str "default root: " default-root-path)}]))
        implicit (implicit-spec-paths fs skills-dir-candidates)
        seed-roots (concat roots implicit)]
    (loop [queue (vec (mapcat (fn [{:keys [path provenance]}]
                                (map (fn [p] {:path p :provenance provenance})
                                     (expand-root fs path)))
                              seed-roots))
           visited {}
           order []]
      (if (empty? queue)
        (mapv (fn [p] {:path p :provenance (get visited p)}) order)
        (let [{:keys [path provenance]} (first queue)
              rest-queue (rest queue)]
          (if (contains? visited path)
            (recur rest-queue visited order)
            (let [visited' (assoc visited path provenance)
                  order' (conj order path)
                  content ((:read fs) path)
                  links (when content (extract-transitive-links content))
                  new-roots (->> links
                                (keep (fn [link]
                                        (when-let [target (resolve-link-target path link)]
                                          target)))
                                (remove #(contains? visited' %))
                                (mapcat (fn [target]
                                          (map (fn [p] {:path p :provenance (str "link from " path)})
                                               (expand-root fs target)))))]
              (recur (into rest-queue new-roots) visited' order'))))))))
