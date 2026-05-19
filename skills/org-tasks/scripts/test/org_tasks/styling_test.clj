(ns org-tasks.styling-test
  (:require [bling.core :refer [bling]]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.output :as out]
            [org-tasks.styling :as styling]))

(defn- ansi? [s]
  (boolean (re-find styling/ansi-re s)))

(deftest palette-keywords-are-accepted-by-bling
  (doseq [k styling/palette-keywords]
    (testing (str k)
      (is (string? (bling [k "sample"]))))))

(deftest styling-can-emit-and-suppress-ansi
  (is (ansi? (styling/status {:color? true} "TODO")))
  (is (not (ansi? (styling/status {:color? false} "TODO"))))
  (is (not (ansi? (styling/priority {:no-color true} "A"))))
  (with-redefs [out/*getenv* (fn [k] (when (= k "NO_COLOR") "1"))]
    (is (not (ansi? (styling/tag-cluster {} ["org-tasks"]))))))

(deftest known-statuses-and-priorities-render-plain-text-when-disabled
  (is (= "TODO" (styling/status {:color? false} "TODO")))
  (is (= "[#A]" (styling/priority {:color? false} "A")))
  (is (= ":org-tasks:skills:"
         (styling/tag-cluster {:color? false} ["org-tasks" "skills"])))
  (is (= "⤴ABC-1 ⤴XYZ-2"
         (styling/linked-issue-cluster {:color? false} ["ABC-1" "XYZ-2"]))))
