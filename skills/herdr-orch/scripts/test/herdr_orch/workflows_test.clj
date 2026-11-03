(ns herdr-orch.workflows-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.test-support :as support]
            [herdr-orch.workflows :as workflows]))

(defn- write! [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text)
  (str path))

(defn- skill! [directory skill-name marker]
  (write! (fs/path directory "SKILL.md")
          (str "---\nname: " skill-name "\ndescription: " skill-name " instructions\n"
               marker "\n---\nBody is not YAML: [\n")))

(deftest declarations-use-yaml-not-text-matching-test
  (support/call-with-temp-dir
   "workflow-yaml-"
   (fn [dir]
     (let [root {:source "project" :directory dir}]
       (skill! (fs/path dir "block") "block"
               "metadata:\n  herdr-orch: workflow")
       (skill! (fs/path dir "flow") "flow"
               "metadata: {herdr-orch: 'workflow'}")
       (write! (fs/path dir "folded" "SKILL.md")
               "---\r\nname: folded\r\ndescription: >-\r\n  A folded\r\n  description.\r\nmetadata:\r\n  herdr-orch: \"workflow\"\r\n---\r\nIgnored body.\r\n")
       (doseq [[skill-name marker]
               [["oh-prefix-only" ""]
                ["prose-only" "description: mentions herdr-orch: workflow"]
                ["top-level" "herdr-orch: workflow"]
                ["wrong-value" "metadata: {herdr-orch: workflows}"]
                ["boolean-value" "metadata: {herdr-orch: true}"]
                ["wrong-key" "metadata: {other-orch: workflow}"]
                ["quoted-prose" "metadata: 'herdr-orch: workflow'"]
                ["broken" "metadata: ["]
                ["duplicate-keys" "metadata: {herdr-orch: workflow}\nmetadata: {herdr-orch: workflow}"]]]
         (skill! (fs/path dir skill-name) skill-name marker))
       (write! (fs/path dir "scalar-header" "SKILL.md") "---\nnot-a-map\n---\n")
       (write! (fs/path dir "no-description" "SKILL.md")
               "---\nname: no-description\nmetadata: {herdr-orch: workflow}\n---\n")
       (write! (fs/path dir "no-header" "SKILL.md")
               "metadata: {herdr-orch: workflow}\n")
       (write! (fs/path dir "unclosed" "SKILL.md")
               "---\nname: unclosed\ndescription: incomplete\nmetadata: {herdr-orch: workflow}\n")
       (let [listed (workflows/discover [root])]
         (is (= ["block" "flow" "folded"] (mapv :name listed)))
         (is (= "A folded description." (:description (last listed))))
         (is (= #{:name :description :source :path} (set (keys (first listed)))))
         (is (= "project" (:source (first listed)))))))))

(deftest names-resolve-before-marker-filtering-test
  (support/call-with-temp-dir
   "workflow-layers-"
   (fn [dir]
     (let [project (str (fs/path dir "project"))
           home (str (fs/path dir "home"))
           packaged (str (fs/path dir "packaged"))
           roots (workflows/directories project home packaged)
           [project-agent project-skills home-skills packaged-skills]
           (map :directory roots)
           marked "metadata: {herdr-orch: workflow}"]
       (is (= ["project" "project" "home" "packaged"] (mapv :source roots)))
       (doseq [[directory skill-name marker]
               [[packaged-skills "unmarked-override" marked]
                [home-skills "unmarked-override" ""]
                [packaged-skills "home-wins" marked]
                [home-skills "home-wins" marked]
                [home-skills "project-wins" marked]
                [project-skills "project-wins" marked]
                [project-agent "project-wins" marked]
                [packaged-skills "packaged-only" marked]]]
         (skill! (fs/path directory skill-name) skill-name marker))
       (testing "the declared name, not the directory name, controls shadowing"
         (skill! (fs/path project-agent "different-directory")
                 "home-wins" ""))
       (let [listed (workflows/discover roots)]
         (is (= ["packaged-only" "project-wins"] (mapv :name listed)))
         (is (= ["packaged" "project"] (mapv :source listed)))
         (is (= (str (fs/path project-agent "project-wins" "SKILL.md"))
                (:path (last listed)))))
       (testing "within one root the first lexical path wins"
         (skill! (fs/path project-agent "a-first") "same-root" "")
         (skill! (fs/path project-agent "z-last") "same-root" marked)
         (is (= ["packaged-only" "project-wins"]
                (mapv :name (workflows/discover roots)))))
       (testing "removing a higher declaration exposes the lower marked skill"
         (fs/delete (fs/path project-agent "different-directory" "SKILL.md"))
         (is (= ["home-wins" "packaged-only" "project-wins"]
                (mapv :name (workflows/discover roots)))))))))

(deftest discovery-follows-symlinks-and-stops-at-skill-roots-test
  (support/call-with-temp-dir
   "workflow-walk-"
   (fn [dir]
     (let [store (fs/path dir "store")
           root (fs/path dir "skills")
           marked "metadata: {herdr-orch: workflow}"]
       (skill! (fs/path store "collection" "nested") "nested" marked)
       (skill! (fs/path store "parent") "parent" "")
       (skill! (fs/path store "parent" "bundled") "bundled" marked)
       (skill! (fs/path store ".hidden") "hidden" marked)
       (skill! (fs/path store "node_modules" "dependency") "dependency" marked)
       (fs/create-sym-link root store)
       (fs/create-sym-link (fs/path store "collection" "cycle") store)
       (fs/create-sym-link (fs/path store "broken-link") (fs/path dir "absent"))
       (let [listed (workflows/discover [{:source "home" :directory (str root)}])]
         (is (= ["nested"] (mapv :name listed)))
         (is (= (str (fs/path root "collection" "nested" "SKILL.md"))
                (:path (first listed)))))))))

(deftest missing-roots-and-empty-catalogue-test
  (support/call-with-temp-dir
   "workflow-empty-"
   (fn [dir]
     (skill! (fs/path dir "ordinary") "ordinary" "")
     (is (= [] (workflows/discover [{:source "home" :directory dir}
                                   {:source "project" :directory (str dir "/absent")}]))))))
