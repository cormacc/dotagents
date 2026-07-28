(ns org-tasks.spec-test
  "Tests for `org-tasks.spec/discover`, the pure traversal engine
  backing `ot spec list`. Uses an in-memory `fs` fixture map instead of
  real disk access."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.spec :as spec]))

(defn- fixture-fs
  "Build an `fs` map (see `org-tasks.spec/discover`) from a
  `{repo-relative-path content}` map. Directories are inferred as any
  path prefix of a file path."
  [files]
  (let [paths (set (keys files))
        dirs (set (mapcat (fn [p]
                             (let [segs (str/split p #"/")]
                               (for [n (range 1 (count segs))]
                                 (str/join "/" (take n segs)))))
                           paths))]
    {:exists? (fn [p] (contains? paths p))
     :dir?    (fn [p] (contains? dirs p))
     :eligible? (fn [p]
                  (and (not (some #{".git" ".direnv" ".devenv" ".cache" "node_modules" "target" "build" "dist" ".next"}
                                  (str/split p #"/")))
                       (not (str/includes? (get files p "") "\u0000"))))
     :read    (fn [p] (get files p))
     :list-files
     (fn [dir]
       (if (= dir "")
         (vec (filter #(not (str/includes? % "/")) paths))
         (vec (filter #(str/starts-with? % (str dir "/")) paths))))}))

(defn- permissive-fs
  "An `fs` stub with no eligibility guard at all — no excluded-segment,
  `safe-relative-path?`, or NUL check. Regression coverage for the pure
  core's own malformed-target defence must not be able to pass because an
  adapter filtered the input first."
  [files]
  (dissoc (fixture-fs files) :eligible?))

(def ^:private skills-candidates ["skills" ".agents/skills"])

(deftest declared-spec-roots-enumerated
  (let [fs (fixture-fs {"design/SPEC.org" "spec body"})
        tasks-content "#+SPEC: [[proj:design/SPEC.org]]\n"
        entries (spec/discover fs tasks-content skills-candidates)]
    (is (some #(= "design/SPEC.org" (:path %)) entries))
    (is (str/includes? (:provenance (first (filter #(= "design/SPEC.org" (:path %)) entries)))
                       "#+SPEC:"))))

(deftest default-root-used-when-no-spec-declared
  (let [fs (fixture-fs {"design/SPEC.org" "spec body"})
        entries (spec/discover fs nil skills-candidates)]
    (is (some #(= "design/SPEC.org" (:path %)) entries))
    (is (str/includes? (:provenance (first (filter #(= "design/SPEC.org" (:path %)) entries)))
                       "default root"))))

(deftest implicit-specs-always-included
  (let [fs (fixture-fs {"README.md" "readme"
                        "AGENTS.md" "agents"
                        "skills/foo/SKILL.md" "skill body"})
        entries (spec/discover fs nil skills-candidates)
        paths (set (map :path entries))]
    (is (contains? paths "README.md"))
    (is (contains? paths "AGENTS.md"))
    (is (contains? paths "skills/foo/SKILL.md"))))

(deftest no-spec-and-no-default-root-yields-implicit-only
  (let [fs (fixture-fs {"README.md" "readme"
                        "AGENTS.md" "agents"})
        entries (spec/discover fs nil skills-candidates)]
    (is (= #{"README.md" "AGENTS.md"} (set (map :path entries))))))

(deftest folder-spec-root-expands-recursively
  (let [fs (fixture-fs {"design/specs/a.org" "a"
                        "design/specs/nested/b.org" "b"})
        tasks-content "#+SPEC: [[proj:design/specs]]\n"
        entries (spec/discover fs tasks-content skills-candidates)
        paths (set (map :path entries))]
    (is (contains? paths "design/specs/a.org"))
    (is (contains? paths "design/specs/nested/b.org"))))

(deftest excluded-and-binary-candidates-are-omitted
  (let [fs (fixture-fs {"design/SPEC.org" "[[file:node_modules/esbuild]] [[file:binary.org]] [[file:good.org]]"
                        "design/node_modules/esbuild" "\u0000binary"
                        "design/binary.org" "\u0000binary"
                        "design/good.org" "text"
                        "design/dist/also.org" "text"})
        report (spec/discover-report fs nil skills-candidates)
        paths (set (map :path (:entries report)))]
    (is (contains? paths "design/good.org"))
    (is (not (contains? paths "design/node_modules/esbuild")))
    (is (not (contains? paths "design/binary.org")))
    (is (not (contains? paths "design/dist/also.org")))))

(deftest malformed-link-is-a-non-fatal-warning
  (let [fs (assoc (fixture-fs {"design/SPEC.org" "[[file:bad\u0000target.org]] [[file:good.org]]"
                               "design/good.org" "text"})
                  :eligible? (constantly true))
        report (spec/discover-report fs nil skills-candidates)]
    (is (= ["spec-link-invalid"] (mapv :code (:warnings report))))
    (is (= "design/SPEC.org" (get-in report [:warnings 0 :location :file])))
    (is (= "bad\u0000target.org" (get-in report [:warnings 0 :location :target])))
    (is (some #(= "design/good.org" (:path %)) (:entries report)))))

(deftest transitive-org-links-are-followed
  (let [fs (fixture-fs {"design/SPEC.org" "See [[file:sub.org]] and [[proj:docs/other.org]]."
                        "design/sub.org" "sub content"
                        "docs/other.org" "other content"})
        entries (spec/discover fs nil skills-candidates)
        paths (set (map :path entries))]
    (is (contains? paths "design/sub.org"))
    (is (contains? paths "docs/other.org"))))

(deftest link-cycles-terminate
  (let [fs (fixture-fs {"design/SPEC.org" "See [[file:a.org]]."
                        "design/a.org" "See [[file:b.org]]."
                        "design/b.org" "See [[file:a.org]]."})
        entries (spec/discover fs nil skills-candidates)
        paths (map :path entries)]
    (is (= (count paths) (count (distinct paths))))
    (is (some #{"design/a.org"} paths))
    (is (some #{"design/b.org"} paths))))

(deftest linked-paths-from-returns-transitive-closure-excluding-root
  (let [fs (fixture-fs {"design/SPEC.org" "See [[file:a.org]]."
                        "design/a.org" "See [[file:b.org]]."
                        "design/b.org" "leaf"})]
    (is (= #{"design/a.org" "design/b.org"}
           (spec/linked-paths-from fs "design/SPEC.org")))))

(deftest resolve-link-target-rejects-malformed-control-data-targets
  (testing "the pure core is the second defence layer: malformed targets never resolve"
    (is (nil? (spec/resolve-link-target "design/SPEC.org"
                                       {:kind :file :target "bad\u0000target.org"})))
    (is (nil? (spec/resolve-link-target "design/SPEC.org"
                                       {:kind :proj :target "docs/bad\u0001target.org"})))
    (is (= "design/good.org"
           (spec/resolve-link-target "design/SPEC.org" {:kind :file :target "good.org"})))))

(deftest linked-paths-from-excludes-malformed-targets-without-adapter-guards
  (let [fs (permissive-fs {"design/SPEC.org" "[[file:bad\u0000target.org]] [[file:good.org]]"
                           "design/bad\u0000target.org" "text"
                           "design/good.org" "text"})]
    (is (= #{"design/good.org"} (spec/linked-paths-from fs "design/SPEC.org")))))

(deftest markdown-links-are-traversed
  (let [fs (fixture-fs {"design/SPEC.org" "See [markdown](md-target.org)."
                        "design/md-target.org" "target"})
        entries (spec/discover fs nil skills-candidates)]
    (is (some #(= "design/md-target.org" (:path %)) entries))))

(deftest org-include-directives-are-traversed
  (let [fs (fixture-fs {"design/SPEC.org" "#+INCLUDE: \"included.org\"\n"
                        "design/included.org" "included body"})
        entries (spec/discover fs nil skills-candidates)]
    (is (some #(= "design/included.org" (:path %)) entries))))

(deftest external-links-are-not-followed
  (let [fs (fixture-fs {"design/SPEC.org" "See [ext](https://example.com/spec.org)."})
        entries (spec/discover fs nil skills-candidates)]
    (is (not (some #(str/includes? (:path %) "example.com") entries)))))

(deftest mixed-org-markdown-cycle-terminates
  (let [fs (fixture-fs {"design/SPEC.org" "[[file:a.md]]"
                        "design/a.md" "[b.org](b.org)"
                        "design/b.org" "[[file:a.md]]"})
        entries (spec/discover fs nil skills-candidates)
        paths (map :path entries)]
    (is (= (count paths) (count (distinct paths))))
    (is (some #{"design/a.md"} paths))
    (is (some #{"design/b.org"} paths))))
