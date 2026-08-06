(ns org-tasks.task-test
  (:require [clojure.test :refer [deftest is testing]]
            [org-tasks.task :as task]))

(defn- t [id summary & {:as extra}]
  (merge {:summary summary
          :source-path "TASKS.org"
          :property-lines [(str ":CUSTOM_ID: " id)]}
         extra))

(def ^:private root-a
  (t "abcd1111-2222-4333-8444-555555555551" "Root A"
     :children [(t "beef1111-2222-4333-8444-555555555552" "Child A")]
     :import-children [(t "cafe1111-2222-4333-8444-555555555553" "Imported A")]))

(def ^:private root-b
  (t "abcd9999-2222-4333-8444-555555555559" "Root B"))

(def ^:private roots [root-a root-b])

(deftest find-by-id-or-prefix-resolves-exact-and-prefixes
  (testing "exact full UUID wins before prefix matching"
    (let [r (task/find-by-id-or-prefix roots "abcd1111-2222-4333-8444-555555555551")]
      (is (= "Root A" (:summary (:match r))))))
  (testing "unique prefixes can resolve nested children"
    (let [r (task/find-by-id-or-prefix roots "beef1111")]
      (is (= "Child A" (:summary (:match r))))))
  (testing "unique prefixes can resolve imported children"
    (let [r (task/find-by-id-or-prefix roots "cafe")]
      (is (= "Imported A" (:summary (:match r))))))
  (testing "a unique exact UUID beats a longer ID sharing its prefix"
    (let [id "abcd1111-2222-4333-8444-555555555551"
          r (task/find-by-id-or-prefix
             [(t (str id "-stale-copy") "Longer prefix match")
              (t id "Exact")]
             id)]
      (is (= "Exact" (:summary (:match r))))
      (is (nil? (:ambiguous r))))))

(deftest find-by-id-or-prefix-reports-none-and-ambiguity
  (testing "short prefixes never match"
    (is (= {:none true} (task/find-by-id-or-prefix roots "abc"))))
  (testing "unknown prefixes report none"
    (is (= {:none true} (task/find-by-id-or-prefix roots "deadbeef"))))
  (testing "shared prefixes report every ambiguous match"
    (let [r (task/find-by-id-or-prefix roots "abcd")]
      (is (= ["Root A" "Root B"] (mapv :summary (:ambiguous r))))))
  (testing "duplicate exact UUID matches are ambiguous"
    (let [id "dead1111-2222-4333-8444-555555555554"
          r (task/find-by-id-or-prefix [(t id "First") (t id "Second")] id)]
      (is (= ["First" "Second"] (mapv :summary (:ambiguous r))))
      (is (nil? (:match r))))))

(deftest find-top-level-by-id-or-prefix-only-considers-roots
  (testing "top-level resolver finds roots"
    (let [r (task/find-top-level-by-id-or-prefix roots "abcd9999")]
      (is (= "Root B" (:summary (:match r))))))
  (testing "top-level resolver ignores nested and imported children"
    (is (= {:none true} (task/find-top-level-by-id-or-prefix roots "beef1111")))
    (is (= {:none true} (task/find-top-level-by-id-or-prefix roots "cafe1111"))))
  (testing "top-level resolver reports ambiguity among roots only"
    (let [r (task/find-top-level-by-id-or-prefix roots "abcd")]
      (is (= ["Root A" "Root B"] (mapv :summary (:ambiguous r)))))))

