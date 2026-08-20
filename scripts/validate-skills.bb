#!/usr/bin/env bb

;; Validate every skill's SKILL.md frontmatter with skill-creator's validator.
;;
;; Two tiers, because we do not own every frontmatter in the tree:
;;   - first-party and adopted skills gate the exit code;
;;   - vendored skills warn only, since their bodies follow upstream rather
;;     than this repository.
;;
;; The vendored set is the one in skills/README.org § Vendored. Upstream
;; gitlab-cli-skills carries top-level `openclaw:` and `requirements:` keys,
;; which the Agent Skills specification does not define -- it defines exactly
;; name, description, license, compatibility, metadata and allowed-tools, and
;; nominates `metadata` as the extension slot. Fixing that belongs upstream.

(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

;; Mirrors skills/README.org § Vendored. Keep the two in step. skill-creator is
;; not here: it is adopted and locally rewritten, so this repository owns its
;; frontmatter and it gates like a first-party skill.
(def vendored #{"dirge" "find-skills" "gitlab-cli-skills" "herdr"})

(defn parse-args [args]
  (loop [a args m {:skills-dir "skills" :python "python3"}]
    (case (first a)
      nil m
      "--python" (recur (drop 2 a) (assoc m :python (second a)))
      "--skills-dir" (recur (drop 2 a) (assoc m :skills-dir (second a)))
      (do (binding [*out* *err*] (println "error: unknown argument:" (first a)))
          (System/exit 2)))))

(def opts (parse-args *command-line-args*))
(def skill-creator (fs/path "skills" "skill-creator"))

(when-not (fs/exists? (fs/path skill-creator "scripts" "quick_validate.py"))
  (binding [*out* *err*] (println "error: validator not found under" (str skill-creator)))
  (System/exit 2))

;; skill-creator's scripts run as modules from its own directory, so the target
;; skill must be absolute. See skills/skill-creator/SKILL.md § Validation and packaging.
(defn validate [dir]
  (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true
                                      :dir (str skill-creator)}
                                     (:python opts) "-m" "scripts.quick_validate"
                                     (str (fs/absolutize dir)))]
    {:ok (zero? exit) :msg (str/trim (str out err))}))

(let [dirs (->> (fs/list-dir (:skills-dir opts))
                (filter #(fs/exists? (fs/path % "SKILL.md")))
                (sort-by str))
      results (for [d dirs
                    :let [n (str (fs/file-name d))
                          {:keys [ok msg]} (validate d)]]
                {:name n :vendored (contains? vendored n) :ok ok :msg msg})
      failures (remove :ok results)
      gating (remove :vendored failures)]
  (when (empty? dirs)
    (binding [*out* *err*] (println "error: no skills found under" (:skills-dir opts)))
    (System/exit 2))
  (doseq [{:keys [name vendored msg]} failures]
    (println (format "%s %s: %s" (if vendored "WARN " "FAIL ") name msg)))
  (println (format "%d skills checked, %d gating failure(s), %d vendored warning(s)"
                   (count results) (count gating) (- (count failures) (count gating))))
  (System/exit (if (seq gating) 1 0)))
