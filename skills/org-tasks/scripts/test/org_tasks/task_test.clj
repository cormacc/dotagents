(ns org-tasks.task-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
      (is (= "Imported A" (:summary (:match r)))))))

(deftest find-by-id-or-prefix-reports-none-and-ambiguity
  (testing "short prefixes never match"
    (is (= {:none true} (task/find-by-id-or-prefix roots "abc"))))
  (testing "unknown prefixes report none"
    (is (= {:none true} (task/find-by-id-or-prefix roots "deadbeef"))))
  (testing "shared prefixes report every ambiguous match"
    (let [r (task/find-by-id-or-prefix roots "abcd")]
      (is (= ["Root A" "Root B"] (mapv :summary (:ambiguous r)))))))

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

;; ── compact short-ids ────────────────────────────

(def ^:private sequential-prefix-roots
  ;; Mirrors the patterned IDs that exposed the original short-id collision.
  [(t "1c5f0b32-9b62-4f8e-9b8c-3a6b2c4d0001" "Alpha")
   (t "1c5f0b32-9b62-4f8e-9b8c-3a6b2c4d0002" "Beta")
   (t "1c5f0b32-9b62-4f8e-9b8c-3a6b2c4d0003" "Gamma")
   (t "abcdef01-2222-4333-8444-555555555551" "Delta")])

(def ^:private compact-collision-roots
  ;; Two tasks share both leading and trailing chars; only middle
  ;; characters differ.
  [(t "1c5f0b32-9b62-4f8e-8aaa-3a6b2c4d0001" "Same edges A")
   (t "1c5f0b32-9b62-4f8e-8bbb-3a6b2c4d0001" "Same edges B")])

(deftest compact-id-builds-first-and-last-edges
  (is (= "abcd…9999" (task/compact-id "abcd0000-1111-2222-3333-444444449999" 4)))
  (testing "returns raw id when length cannot accommodate edges"
    (is (= "short" (task/compact-id "short" 4)))))

(deftest build-short-ids-disambiguates-shared-prefixes
  (let [m (task/build-short-ids sequential-prefix-roots)]
    (testing "every full id is present in the map"
      (is (= 4 (count m))))
    (testing "sibling IDs with shared prefix are uniquely represented"
      (is (= 4 (count (set (vals m)))))
      (doseq [[id short] m]
        (is (str/starts-with? id (first (str/split short #"…"))))
        (is (str/ends-with? id (last (str/split short #"…"))))))))

(deftest find-by-id-or-prefix-resolves-compact-input
  (testing "compact form with unicode ellipsis"
    (let [r (task/find-by-id-or-prefix sequential-prefix-roots "1c5f…0001")]
      (is (= "Alpha" (:summary (:match r))))))
  (testing "compact form with ASCII ellipsis"
    (let [r (task/find-by-id-or-prefix sequential-prefix-roots "1c5f...0002")]
      (is (= "Beta" (:summary (:match r))))))
  (testing "compact form that matches multiple tasks remains ambiguous"
    (let [r (task/find-by-id-or-prefix compact-collision-roots "1c5f…0001")]
      (is (= 2 (count (:ambiguous r))))
      (is (= ["Same edges A" "Same edges B"]
             (mapv :summary (:ambiguous r))))))
  (testing "compact form that matches nothing reports none"
    (is (= {:none true}
           (task/find-by-id-or-prefix sequential-prefix-roots "abcd…dead")))))
