#!/usr/bin/env bb

;; Validate a skill's SKILL.md frontmatter against the Agent Skills specification.
;;
;; Usage: bb validate-skill.bb <skill-folder>
;; Prints one message and exits 0 when the skill is valid, 1 otherwise.
;;
;; The rules mirror the upstream scripts/quick_validate.py, which stays byte-identical
;; to the Anthropic source. scripts/test-validate-skills.bb in the dotagents repository
;; compares the two on shared fixtures, so a rule change upstream fails that test until
;; it is ported here. Python truthiness and code-point lengths are reproduced on purpose.

(ns skill-creator.validate-skill
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]))

(def allowed-properties #{"name" "description" "license" "allowed-tools" "metadata" "compatibility"})

(defn- code-points [^String s] (.codePointCount s 0 (count s)))

(defn- py-type [x]
  (cond (nil? x) "NoneType" (boolean? x) "bool" (integer? x) "int" (float? x) "float"
        (map? x) "dict" (sequential? x) "list" (string? x) "str"
        :else (str/lower-case (.getSimpleName (class x)))))

;; Python treats None, False, 0, "" and empty collections as false.
(defn- py-truthy? [x]
  (not (or (nil? x) (false? x) (and (number? x) (zero? x))
           (and (or (string? x) (coll? x)) (empty? x)))))

(defn- check-frontmatter [fm]
  (let [unexpected (remove allowed-properties (map str (keys fm)))
        name (get fm "name" "")
        description (get fm "description" "")
        compatibility (get fm "compatibility" "")]
    (cond
      (seq unexpected)
      (str "Unexpected key(s) in SKILL.md frontmatter: " (str/join ", " (sort unexpected))
           ". Allowed properties are: " (str/join ", " (sort allowed-properties)))
      (not (contains? fm "name")) "Missing 'name' in frontmatter"
      (not (contains? fm "description")) "Missing 'description' in frontmatter"
      (not (string? name)) (str "Name must be a string, got " (py-type name))
      :else
      (let [name (str/trim name)]
        (or
         (when (seq name)
           (cond
             (not (re-matches #"[a-z0-9-]+" name))
             (str "Name '" name "' should be kebab-case (lowercase letters, digits, and hyphens only)")
             (or (str/starts-with? name "-") (str/ends-with? name "-") (str/includes? name "--"))
             (str "Name '" name "' cannot start/end with hyphen or contain consecutive hyphens")
             (> (code-points name) 64)
             (str "Name is too long (" (code-points name) " characters). Maximum is 64 characters.")))
         (if-not (string? description)
           (str "Description must be a string, got " (py-type description))
           (let [description (str/trim description)]
             (when (seq description)
               (cond
                 (re-find #"[<>]" description) "Description cannot contain angle brackets (< or >)"
                 (> (code-points description) 1024)
                 (str "Description is too long (" (code-points description) " characters). Maximum is 1024 characters.")))))
         (when (py-truthy? compatibility)
           (cond
             (not (string? compatibility)) (str "Compatibility must be a string, got " (py-type compatibility))
             (> (code-points compatibility) 500)
             (str "Compatibility is too long (" (code-points compatibility) " characters). Maximum is 500 characters."))))))))

(defn validate-skill
  "Return [ok? message] for the skill folder at `dir`."
  [dir]
  (let [skill-md (fs/path dir "SKILL.md")]
    (if-not (fs/exists? skill-md)
      [false "SKILL.md not found"]
      ;; Python's read_text translates \r\n and \r to \n before the frontmatter match.
      (let [content (str/replace (slurp (str skill-md)) #"\r\n?" "\n")]
        (if-not (str/starts-with? content "---")
          [false "No YAML frontmatter found"]
          (if-let [[_ text] (re-find #"(?s)\A---\n(.*?)\n---" content)]
            (let [parsed (try {:value (yaml/parse-string text :keywords false)}
                              (catch Exception e {:error (ex-message e)}))]
              (cond
                (:error parsed) [false (str "Invalid YAML in frontmatter: " (:error parsed))]
                (not (map? (:value parsed))) [false "Frontmatter must be a YAML dictionary"]
                :else (if-let [problem (check-frontmatter (:value parsed))]
                        [false problem]
                        [true "Skill is valid!"])))
            [false "Invalid frontmatter format"]))))))

(when (= *file* (System/getProperty "babashka.file"))
  (if (not= 1 (count *command-line-args*))
    (do (println "Usage: bb validate-skill.bb <skill_directory>")
        (System/exit 1))
    (let [[ok message] (validate-skill (first *command-line-args*))]
      (println message)
      (System/exit (if ok 0 1)))))
