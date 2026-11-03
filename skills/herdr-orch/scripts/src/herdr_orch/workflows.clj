(ns herdr-orch.workflows
  "Discover ordinary skills that opt into the herdr-orch workflow catalogue."
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [org.yaml.snakeyaml.error YAMLException]))

(defn directories
  "Return skill roots in precedence order, including a skill-repository checkout."
  [project-root home packaged-root]
  [{:source "project" :directory (str (fs/path project-root ".agents" "skills"))}
   {:source "project" :directory (str (fs/path project-root "skills"))}
   {:source "home" :directory (str (fs/path home ".agents" "skills"))}
   {:source "packaged" :directory (str packaged-root)}])

(defn- skill-files [directory ancestors]
  (when (fs/directory? directory)
    (let [canonical (str (fs/canonicalize directory))
          skill (fs/path directory "SKILL.md")]
      (when-not (contains? ancestors canonical)
        ;; A skill owns its descendants; bundled examples are not separate skills.
        (if (fs/regular-file? skill)
          [skill]
          (mapcat #(skill-files % (conj ancestors canonical))
                  (->> (fs/list-dir directory)
                       (remove #(let [n (str (fs/file-name %))]
                                  (or (str/starts-with? n ".")
                                      (= n "node_modules"))))
                       (sort-by str))))))))

(defn- frontmatter [path]
  ;; Stop at the closing delimiter rather than reading workflow instructions.
  (with-open [reader (io/reader (str path))]
    (when (= "---" (.readLine reader))
      (loop [lines []]
        (when-let [line (.readLine reader)]
          (if (= "---" (str/trim line))
            (try
              (yaml/parse-string (str/join "\n" lines)
                                 :keywords false :allow-duplicate-keys false)
              ;; Malformed skills are not candidates, as with a missing header.
              (catch YAMLException _ nil))
            (recur (conj lines line))))))))

(defn- skill-entry [source path]
  (let [header (frontmatter path)
        skill-name (get header "name")
        description (get header "description")]
    (when (and (string? skill-name) (not (str/blank? skill-name))
               (string? description) (not (str/blank? description)))
      {:name skill-name :description description :source source :path (str path)
       :workflow? (= "workflow" (get-in header ["metadata" "herdr-orch"]))})))

(defn discover
  "Return marked skills, sorted by name, from ordered `roots`.
  Resolve duplicate names before filtering the exact metadata marker. Follow
  directory symlinks without revisiting ancestors; stop at each SKILL.md.
  Ignore malformed or incomplete declarations. Propagate file access errors."
  [roots]
  (->> roots
       (mapcat (fn [{:keys [source directory]}]
                 (keep #(skill-entry source %) (skill-files directory #{}))))
       (reduce (fn [winners {:keys [name] :as entry}]
                 (if (contains? winners name) winners (assoc winners name entry)))
               {})
       vals
       (filter :workflow?)
       (sort-by :name)
       (mapv #(dissoc % :workflow?))))
