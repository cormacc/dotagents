(ns org-tasks.effective-test
  "Setupfile chain regression tests.

  Mirrors `pi/extensions/tasks/effective.test.ts`. Walks a real
  temp-dir chain so the file-keyword + link-template merging behaves
  the same way the loader's `parse-tasks` + `expand-org-link-target`
  flow expects."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.effective :as effective]
            [org-tasks.parser :as parser]))

(defn- with-temp-dir [f]
  (let [dir (str (fs/create-temp-dir {:prefix "ot-effective-"}))]
    (try (f dir)
         (finally (fs/delete-tree dir)))))

(deftest setupfile-chain-merges-and-expands-plan-link
  (with-temp-dir
    (fn [project]
      (fs/create-dirs (fs/path project "design" "log"))
      (let [tasks-path (str (fs/path project "TASKS.org"))
            local-path (str (fs/path project "TASKS.local.org"))
            setup-path (str (fs/path project "TASKS.setup.org"))
            content    (str/join "\n"
                                 ["#+SETUPFILE: ./TASKS.local.org"
                                  "#+SETUPFILE: ./TASKS.setup.org"
                                  ""
                                  "* Improvements"
                                  "** DONE Parent"
                                  ":PROPERTIES:"
                                  ":CUSTOM_ID: aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
                                  ":END:"
                                  "#+IMPORT: [[plan:2026-05-14-evaluate-vulpea-org-memory.org]]"
                                  ""])]
        (spit tasks-path content)
        (spit local-path "#+SELECTED:\n#+JIRA_PROJECT: LOCAL\n")
        (spit setup-path "#+LINK: plan file:design/log/%s\n#+JIRA_PROJECT: SHARED\n")

        (let [effective (effective/read-effective-org-content
                          project tasks-path content)]

          (testing "first setupfile keywords merged"
            (is (str/includes? effective "#+JIRA_PROJECT: LOCAL")))

          (testing "second setupfile keywords merged"
            (is (str/includes? effective "#+LINK: plan file:design/log/%s")))

          (testing "plan: typed link resolves through the merged template"
            (let [{:keys [tasks]}
                  (parser/parse-tasks content
                                      {:source-path tasks-path
                                       :effective-source-content effective})
                  parent (first tasks)
                  expanded (parser/expand-org-link-target
                             (:import-path parent)
                             (:effective-source-content parent))]
              (is (= "design/log/2026-05-14-evaluate-vulpea-org-memory.org"
                     (:target expanded)))
              (is (true? (:from-project-root expanded))))))))))
