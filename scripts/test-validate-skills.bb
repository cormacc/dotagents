#!/usr/bin/env bb

;; Tests for skills/skill-creator/validate-skill.bb and the two-tier gate in
;; scripts/validate-skills.bb.
;;
;; Set SKILL_CREATOR_PYTHON to an interpreter with skills/skill-creator/requirements.txt
;; installed to also compare each fixture with the upstream scripts/quick_validate.py.
;; scripts/check.sh does this; `bb test` does not, and the run says so.

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing run-tests]])

(def root (str/trim (:out (process/shell {:out :string} "git" "rev-parse" "--show-toplevel"))))
(def skill-creator (str (fs/path root "skills" "skill-creator")))
(def gate (str (fs/path root "scripts" "validate-skills.bb")))
(load-file (str (fs/path skill-creator "validate-skill.bb")))
(def validate-skill (resolve 'skill-creator.validate-skill/validate-skill))
(def python (not-empty (System/getenv "SKILL_CREATOR_PYTHON")))

(defn- skill-md [& lines] (str "---\n" (str/join "\n" lines) "\n---\n# Body\n"))
(def ok-name "name: example")
(def ok-desc "description: Fixture")

;; [case SKILL.md-content-or-nil expected-ok?]. Each rule has a passing and a failing
;; side where it has a boundary, so a rule that is never enforced cannot pass here.
(def cases
  [["valid" (skill-md ok-name ok-desc) true]
   ["valid-crlf" (str/replace (skill-md ok-name ok-desc) "\n" "\r\n") true]
   ["all-allowed-keys" (skill-md ok-name ok-desc "license: MIT" "allowed-tools: Read" "compatibility: bb" "metadata:\n  extra: 1") true]
   ["no-skill-md" nil false]
   ["no-frontmatter" "# Title\n" false]
   ["unclosed-frontmatter" "---\nname: example\n" false]
   ["invalid-yaml" (skill-md "name: [unclosed" ok-desc) false]
   ["not-a-map" (skill-md "- a" "- b") false]
   ["empty-frontmatter" "---\n\n---\n" false]
   ["unexpected-key" (skill-md ok-name ok-desc "openclaw: x") false]
   ["missing-name" (skill-md ok-desc) false]
   ["missing-description" (skill-md ok-name) false]
   ["name-number" (skill-md "name: 123" ok-desc) false]
   ["name-null" (skill-md "name:" ok-desc) false]
   ["name-blank" (skill-md "name: ''" ok-desc) true]
   ["name-uppercase" (skill-md "name: Example" ok-desc) false]
   ["name-leading-hyphen" (skill-md "name: -example" ok-desc) false]
   ["name-double-hyphen" (skill-md "name: a--b" ok-desc) false]
   ["name-64" (skill-md (str "name: " (apply str (repeat 64 "a"))) ok-desc) true]
   ["name-65" (skill-md (str "name: " (apply str (repeat 65 "a"))) ok-desc) false]
   ["description-list" (skill-md ok-name "description: [a]") false]
   ["description-angle" (skill-md ok-name "description: a <b> c") false]
   ["description-1024" (skill-md ok-name (str "description: " (apply str (repeat 1024 "d")))) true]
   ["description-1025" (skill-md ok-name (str "description: " (apply str (repeat 1025 "d")))) false]
   ;; 1024 code points but 2048 UTF-16 units: the limit counts characters.
   ["description-1024-astral" (skill-md ok-name (str "description: " (apply str (repeat 1024 "\uD83D\uDE00")))) true]
   ["compatibility-zero" (skill-md ok-name ok-desc "compatibility: 0") true]
   ["compatibility-list" (skill-md ok-name ok-desc "compatibility: [a]") false]
   ["compatibility-500" (skill-md ok-name ok-desc (str "compatibility: " (apply str (repeat 500 "c")))) true]
   ["compatibility-501" (skill-md ok-name ok-desc (str "compatibility: " (apply str (repeat 501 "c")))) false]])

;; YAML parser messages differ between PyYAML and SnakeYAML; every other message matches.
(def parser-message-cases #{"invalid-yaml"})

(defn- with-cases [f]
  (let [dir (fs/path root ".tmp" (str "validate-skills-" (random-uuid)))]
    (try
      (doseq [[case content] cases]
        (fs/create-dirs (fs/path dir case))
        (when content (spit (str (fs/path dir case "SKILL.md")) content)))
      (f (str dir))
      (finally (fs/delete-tree dir)))))

(defn- python-validate [skill-dir]
  (let [{:keys [exit out err]} (process/shell {:dir skill-creator :out :string :err :string :continue true}
                                              python "-m" "scripts.quick_validate" (str skill-dir))]
    [(zero? exit) (str/trim (str out err))]))

(deftest each-rule-accepts-and-rejects-its-fixtures
  (with-cases
    (fn [dir]
      (doseq [[case _ expected] cases]
        (let [[ok msg] (validate-skill (fs/path dir case))]
          (is (= expected ok) (str case ": " msg)))))))

(deftest upstream-quick-validate-agrees-on-every-fixture
  (if-not python
    (println "SKIPPED parity with skills/skill-creator/scripts/quick_validate.py: SKILL_CREATOR_PYTHON is unset")
    (with-cases
      (fn [dir]
        (doseq [[case _ expected] cases]
          (let [[ok msg] (validate-skill (fs/path dir case))
                [py-ok py-msg] (python-validate (fs/path dir case))]
            (testing case
              (is (= expected py-ok) (str "python: " py-msg))
              (when-not (parser-message-cases case)
                (is (= py-msg msg))))))))))

(defn- gate! [skills-dir]
  (process/shell {:out :string :err :string :continue true} "bb" gate "--skills-dir" skills-dir))

(deftest a-first-party-failure-fails-the-gate-and-a-vendored-one-warns
  (let [dir (fs/path root ".tmp" (str "validate-skills-gate-" (random-uuid)))
        write! (fn [skill content] (fs/create-dirs (fs/path dir skill)) (spit (str (fs/path dir skill "SKILL.md")) content))]
    (try
      (write! "example" (skill-md ok-name ok-desc))
      (write! "herdr" (skill-md "name: herdr" ok-desc "openclaw: x"))
      (let [{:keys [exit out]} (gate! (str dir))]
        (is (zero? exit) out)
        (is (str/includes? out "WARN  herdr: Unexpected key(s)") out)
        (is (str/includes? out "2 skills checked, 0 gating failure(s), 1 vendored warning(s)") out))
      (write! "broken" (skill-md "name: Broken" ok-desc))
      (let [{:keys [exit out]} (gate! (str dir))]
        (is (= 1 exit) out)
        (is (str/includes? out "FAIL  broken: Name 'Broken' should be kebab-case") out)
        (is (str/includes? out "3 skills checked, 1 gating failure(s), 1 vendored warning(s)") out))
      (finally (fs/delete-tree dir)))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
