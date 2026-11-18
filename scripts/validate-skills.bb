#!/usr/bin/env bb

;; Validate every skill's SKILL.md frontmatter with skill-creator's validator.
;;
;; Two tiers, because we do not own every frontmatter in the tree:
;;   - first-party and adopted skills gate the exit code;
;;   - vendored skills warn only, since their bodies follow upstream rather
;;     than this repository.
;;
;; The vendored set is the one in skills/README.org section 'Vendored'. Upstream
;; gitlab-cli-skills carries top-level `openclaw:` and `requirements:` keys,
;; which the Agent Skills specification does not define -- it defines exactly
;; name, description, license, compatibility, metadata and allowed-tools, and
;; nominates `metadata` as the extension slot. Fixing that belongs upstream.

(require '[babashka.fs :as fs])

;; The validator comes from this repository, not from --skills-dir, so a fixture tree
;; is checked by the same rules as the real one.
(load-file (str (fs/path (fs/parent (fs/absolutize *file*)) ".." "skills" "skill-creator" "validate-skill.bb")))
(def validate-skill (resolve 'skill-creator.validate-skill/validate-skill))

;; Mirrors skills/README.org section 'Vendored'. Keep the two in step -- a skill that
;; leaves the vendored class must leave this set, or its frontmatter silently
;; stops gating. skill-creator, find-skills and clojure are not here: they are
;; adopted and locally rewritten, so this repository owns their frontmatter and
;; they gate like first-party skills. The adapted caveman skill also gates;
;; its upstream provenance metadata is retained. dirge is first-party.
(def vendored #{"gitlab-cli-skills" "herdr"})

(defn parse-args [args]
  (loop [a args m {:skills-dir "skills"}]
    (case (first a)
      nil m
      "--skills-dir" (recur (drop 2 a) (assoc m :skills-dir (second a)))
      (do (binding [*out* *err*] (println "error: unknown argument:" (first a)))
          (System/exit 2)))))

(def opts (parse-args *command-line-args*))

(let [dirs (->> (fs/list-dir (:skills-dir opts))
                (filter #(fs/exists? (fs/path % "SKILL.md")))
                (sort-by str))
      results (for [d dirs
                    :let [n (str (fs/file-name d))
                          [ok msg] (validate-skill d)]]
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
