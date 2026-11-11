#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests]])

(def root (str/trim (:out @(process/process ["git" "rev-parse" "--show-toplevel"]
                                             {:out :string :err :string}))))

(deftest regeneration-replaces-the-complete-result
  (let [dir (fs/create-temp-dir {:prefix "readme-roster-"})
        skill (fs/path dir "skills" "herdr-orch")
        readme (str (fs/path skill "README.org"))
        config (str (fs/path skill "subagents" "config.edn"))
        run! (fn []
               (let [p @(process/process ["bb" "readme-roster"]
                                         {:dir (str dir) :out :string :err :string})]
                 (when-not (zero? (:exit p))
                   (throw (ex-info "roster regeneration failed" p)))))]
    (try
      (fs/create-dirs (fs/parent skill))
      (fs/copy-tree (fs/path root "skills" "herdr-orch") skill)
      (fs/copy (fs/path root "bb.edn") (fs/path dir "bb.edn"))
      (run!)
      (let [first-run (slurp readme)
            old "- =heavy= -- rank 1 =fable=; rank 1 =gpt-astra="
            new "- =heavy= -- rank 1 =gpt-astra=; rank 2 =fable="
            before (slurp config)
            after (str/replace before "{:model \"fable\"  :rank 1 :quota-scope" "{:model \"fable\"  :rank 2 :quota-scope")]
        (is (str/includes? first-run old))
        (run!)
        (is (= first-run (slurp readme)))
        (is (not= before after) "the fixture config change applies")
        (spit config after)
        (run!)
        (let [changed (slurp readme)]
          (is (str/includes? changed new))
          (is (not (str/includes? changed old)))
          (is (= 1 (count (re-seq #"Ranked aliases \(lowest rank is preferred\):" changed))))
          (run!)
          (is (= changed (slurp readme)))))
      (finally (fs/delete-tree dir)))))

(if (fs/which "emacs")
  (let [{:keys [fail error]} (run-tests)]
    (System/exit (if (zero? (+ fail error)) 0 1)))
  (println "SKIP readme-roster regeneration: emacs is unavailable"))
