(ns org-tasks.commands.view-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [babashka.process]
            [org-tasks.commands.test-util :refer :all]))

(defn- compact-json [root]
  (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "compact")]
    (is (zero? exit))
    (parse-json-result out)))

(deftest compact-model-follows-the-selected-plan
  (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (testing "without a selection"
        (let [r (compact-json root)]
          (is (= "none" (:selection r)))
          (is (= [] (:rows r)))
          (is (= 4 (:total r)))
          (is (= [{:status "TODO" :count 4}] (:counts r)))
          (is (str/starts-with? (:title r) "Tasks :: "))
          (is (nil? (:plan r)))
          (is (= (set (map str [(fs/path root "TASKS.org") (fs/path root "TASKS.local.org")
                                (fs/path root "TASKS.setup.org")
                                (fs/path root "design" "log" "linked-plan.org")]))
                 (set (:files r)))
              "the protocol files, setup and the imported plan are watched; the archive is not")))
      (run-cli! "--root" root "status" linked-plan-child-id "STARTED" "--no-promote")
      (run-cli! "--root" root "select" linked-plan-parent-id)
      (testing "with the plan owner selected"
        (let [r (compact-json root)]
          (is (= "selected" (:selection r)))
          (is (= "linked-plan.org" (:plan r)))
          (is (str/ends-with? (:title r) " :: linked-plan.org"))
          (is (= [["task" "Parent with linked plan" false nil]
                  ["started" "Plan child" true "0/3"]
                  ["next" "Second plan child" false "..."]]
                 (map (juxt :role :summary :current :token) (:rows r)))
              "the started row is current and carries progress; hidden pending work adds the ellipsis")
          (is (= (str "Plan child [" (subs linked-plan-child-id 0 8) "]")
                 (:label (second (:rows r))))))))))

(deftest compact-text-prints-the-tui-compact-lines
  (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (run-cli! "--root" root "select" linked-plan-parent-id)
      (let [{:keys [out exit]} (run-cli! "--root" root "--no-color" "compact" "--width" "60")
            lines (str/split-lines out)]
        (is (zero? exit))
        (is (= 60 (count (first lines))) "the progress token is right-justified at --width")
        (is (str/starts-with? (first lines) "TODO Parent with linked plan ["))
        (is (str/ends-with? (first lines) " 0/3"))
        (is (= (str "... TODO Plan child [" (subs linked-plan-child-id 0 8) "]")
               (str/trimr (str/replace (second lines) #"\s+\.\.\.$" "")))))))
  (testing "without a selection, status counts replace the rows"
   (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (let [{:keys [out]} (run-cli! "--root" root "--no-color" "compact")]
        (is (= "4 tasks: 4 TODO" (str/trim out))))))))

(deftest machine-output-stays-clean-without-terminal-hints
  ;; A GUI app's subprocess has no TERM or COLORTERM. bling's colour
  ;; detection then prints to stdout while loading (fireworks 0.20.0), which
  ;; would precede the JSON envelope.
  (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (let [env {"PATH" (System/getenv "PATH") "HOME" (System/getProperty "user.home")}
            bb (str (fs/which "bb"))
            control (babashka.process/shell {:out :string :err :string :env env :continue true}
                                            bb "-e" "(require 'bling.core)")
            r (babashka.process/shell {:out :string :err :string :env env :continue true :dir root}
                                      (str (fs/absolutize "skills/org-tasks/scripts/ot")) "compact" "--format" "json")]
        (is (str/includes? (:out control) "color level could not be detected")
            "control: this environment still triggers the upstream stdout print")
        (is (zero? (:exit r)) (:err r))
        (is (str/starts-with? (:out r) "{") "stdout is only the JSON envelope")))))
