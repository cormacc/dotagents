#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests]])

(def root (str/trim (:out (process/shell {:out :string} "git" "rev-parse" "--show-toplevel"))))
(def validator (str (fs/path root "scripts/validate-skills.bb")))

(defn with-fixture [f]
  (let [dir (fs/path root ".tmp" (str "validate-skills-" (random-uuid)))
        interpreter (fs/path dir "bin/fixture-python")]
    (try
      (doseq [[rel content] {"skills/skill-creator/scripts/quick_validate.py" "# Fixture validator marker\n"
                             "skills/example/SKILL.md" "---\nname: example\ndescription: Fixture\n---\n"
                             "bin/fixture-python" (str "#!/usr/bin/env bash\n"
                                                      "test \"$1\" = -m && test \"$2\" = scripts.quick_validate || exit 7\n"
                                                      "test -f scripts/quick_validate.py && test -f \"$3/SKILL.md\" || exit 8\n"
                                                      "echo fixture-validator-ran\n")}]
        (fs/create-dirs (fs/parent (fs/path dir rel)))
        (spit (str (fs/path dir rel)) content))
      (process/shell {:out :string :err :string} "chmod" "+x" (str interpreter))
      (f (str dir) (str interpreter))
      (finally (fs/delete-tree dir)))))

(defn validate! [dir python]
  (process/shell {:dir dir :out :string :err :string :continue true
                  :extra-env {"PATH" (str (fs/path dir "bin") ":" (System/getenv "PATH"))}}
                 "bb" validator "--skills-dir" "skills" "--python" python))

(deftest interpreter-paths-use-the-callers-directory
  (with-fixture
    (fn [dir absolute]
      (doseq [python [absolute "bin/fixture-python" "./bin/fixture-python" "fixture-python"]]
        (let [{:keys [exit out err]} (validate! dir python)]
          (is (zero? exit) (str python "\n" out err))
          (is (str/includes? out "1 skills checked, 0 gating failure(s)") (str python "\n" out err)))))))

(deftest a-validator-refusal-still-fails-the-gate
  (with-fixture
    (fn [dir absolute]
      (spit absolute "#!/usr/bin/env bash\necho synthetic-validator-refusal\nexit 9\n")
      (let [{:keys [exit out]} (validate! dir absolute)]
        (is (= 1 exit) out)
        (is (str/includes? out "FAIL  example: synthetic-validator-refusal") out)
        (is (str/includes? out "1 gating failure(s)") out)))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
