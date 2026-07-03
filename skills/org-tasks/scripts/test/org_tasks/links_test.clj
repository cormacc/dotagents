(ns org-tasks.links-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.links :as links]))

(deftest resolve-link-target-respects-base-and-sandbox
  (fs/with-temp-dir [dir {}]
    (let [root (str dir)
          source (str (fs/path root "design" "log" "record.org"))
          content "#+LINK: plan file:design/log/%s\n#+LINK: rel file:%s\n"]
      (fs/create-dirs (fs/parent source))
      (testing "template targets declared from project root resolve from project root"
        (is (= (str (fs/path root "design" "log" "child.org"))
               (links/resolve-link-target root source content "plan:child.org"))))
      (testing "plain relative targets resolve from the source file parent"
        (is (= (str (fs/path root "design" "log" "sibling.org"))
               (links/resolve-link-target root source content "sibling.org"))))
      (testing "sandbox escapes are rejected"
        (is (nil? (links/resolve-link-target root source content "../../../outside.org")))))))
