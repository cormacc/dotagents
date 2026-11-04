#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests]])

(def root (str/trim (:out (process/shell {:out :string} "git" "rev-parse" "--show-toplevel"))))
(def checker (str (fs/path root "scripts" "check-record-identifiers.bb")))
(def real-git (str (fs/which "git")))

(defn fixture-repo!
  "A disposable git repository holding tracked `files`, a map of path -> content."
  [files]
  (let [dir (fs/create-temp-dir {:prefix "record-identifiers-"})]
    (doseq [[p content] files]
      (fs/create-dirs (fs/parent (fs/path dir p)))
      (spit (str (fs/path dir p)) content))
    (process/check (process/process ["git" "init" "-q"] {:dir (str dir)}))
    (process/check (process/process ["git" "add" "-A"] {:dir (str dir)}))
    (str dir)))

(defn check! [dir & args]
  @(process/process (into ["bb" checker "--root" dir] args) {:out :string :err :string}))

(def source-files
  {"src/acme/core.clj" "(ns acme.core)\n(defn present [] :ready)\n(def qualified acme/core)\n"
   "config.edn" "{:setting :ready}\n"})

(defn with-record [content]
  (assoc source-files "design/log/record.org" content))

(deftest resolved-identifiers-and-advisories
  (let [dir (fixture-repo!
             (with-record
              "`acme.core` `present` `:ready` `acme/core` `acme/core.clj`\n`removed-helper` `rejected-plan` `::local`\n"))
        {:keys [exit out err]} (check! dir "--control" ":ready" "design/log/record.org")]
    (is (zero? exit) (str out err))
    (is (str/includes? out "record.org:2: unresolved `removed-helper`") out)
    (is (str/includes? out "record.org:2: unresolved `rejected-plan`") out)
    (is (str/includes? out "record.org:2: uncertain `::local`") out)
    (doseq [identifier ["acme.core" "present" ":ready" "acme/core"]]
      (is (not (str/includes? out (str "unresolved `" identifier "`"))) out))
    (is (not (str/includes? out "acme/core.clj")) out)
    (is (str/includes? out "2 unresolved, 1 uncertain") out)
    (is (str/includes? out "control `:ready` matched") out)))

(deftest token-boundaries-prevent-substring-matches
  (let [dir (fixture-repo!
             (with-record "`present` `foo`\n"))
        source-path (fs/path dir "src/acme/core.clj")]
    (spit (str source-path) "(ns acme.core)\n(def presentable :present)\n(def foo-bar :foo)\n")
    (let [{:keys [exit out err]} (check! dir "--control" "present" "design/log/record.org")]
      (is (= 2 exit) (str out err))
      (is (str/includes? err "control identifier `present` is absent") err))
    (spit (str source-path) "(ns acme.core)\n(def present :ready)\n(def foo-bar :foo)\n")
    (let [{:keys [exit out err]} (check! dir "--control" "present" "design/log/record.org")]
      (is (zero? exit) (str out err))
      (is (str/includes? out "record.org:1: unresolved `foo`") out)
      (is (not (str/includes? out "unresolved `present`")) out))))

(deftest missing-control-is-an-operational-failure
  (let [dir (fixture-repo! (with-record "`removed-helper`\n"))
        {:keys [exit err]} (check! dir "--control" "missing-control" "design/log/record.org")]
    (is (= 2 exit) err)
    (is (str/includes? err "control identifier `missing-control` is absent") err)))

(deftest tracked-source-excludes-untracked-and-ignored-evidence
  (let [dir (fixture-repo! (assoc (with-record "`untracked-only` `ignored-only`\n") ".gitignore" "ignored/\n"))]
    (spit (str (fs/path dir "scratch.clj")) "(def untracked-only true)\n")
    (fs/create-dirs (fs/path dir "ignored"))
    (spit (str (fs/path dir "ignored" "source.clj")) "(def ignored-only true)\n")
    (let [{:keys [exit out err]} (check! dir "--control" ":ready" "design/log/record.org")]
      (is (zero? exit) (str out err))
      (is (str/includes? out "unresolved `untracked-only`") out)
      (is (str/includes? out "unresolved `ignored-only`") out))))

(deftest missing-source-and-record-are-operational-failures
  (let [empty-source (fixture-repo! {"design/log/record.org" "`present`\n"})
        missing-record (fixture-repo! source-files)
        no-source (check! empty-source "--control" "present" "design/log/record.org")
        absent-record (check! missing-record "--control" ":ready" "design/log/missing.org")]
    (is (= 2 (:exit no-source)) (:err no-source))
    (is (str/includes? (:err no-source) "no tracked Clojure, EDN or Babashka source files") (:err no-source))
    (is (= 2 (:exit absent-record)) (:err absent-record))
    (is (str/includes? (:err absent-record) "change-record is unreadable") (:err absent-record))))

(deftest git-subprocess-failure-is-an-operational-failure
  (let [dir (fixture-repo! (with-record "`present`\n"))
        bin (fs/path dir "bin")
        fake-git (fs/path bin "git")]
    (fs/create-dirs bin)
    (spit (str fake-git)
          (str "#!/bin/sh\n"
               "if [ \"$1\" = \"ls-files\" ]; then echo synthetic-git-failure >&2; exit 19; fi\n"
               "exec " real-git " \"$@\"\n"))
    (process/check (process/process ["chmod" "+x" (str fake-git)]))
    (let [{:keys [exit err]} @(process/process ["bb" checker "--root" dir "--control" ":ready" "design/log/record.org"]
                                                {:out :string :err :string
                                                 :extra-env {"PATH" (str bin ":" (System/getenv "PATH"))}})]
      (is (= 2 exit) err)
      (is (str/includes? err "git ls-files failed") err)
      (is (str/includes? err "synthetic-git-failure") err))))

(deftest every-tracked-source-must-be-readable-before-reporting
  (doseq [damage [:missing :unreadable]]
    (let [files (into {"a.clj" "(def present true)\n"
                       "z.clj" "(def sibling true)\n"
                       "record.org" "A record without code spans.\n"}
                      (for [n (range 40)]
                        [(str "m" n ".clj") "(def middle true)\n"]))
          dir (fixture-repo! files)
          late-source (str (fs/path dir "z.clj"))
          baseline (check! dir "--control" "present" "record.org")]
      (is (zero? (:exit baseline)) (str baseline))
      (try
        (case damage
          :missing (fs/delete late-source)
          :unreadable (process/check (process/process ["chmod" "000" late-source])))
        (let [{:keys [exit err]} (check! dir "--control" "present" "record.org")]
          (is (= 2 exit) (str damage ": " err))
          (is (str/includes? err "tracked source is unreadable: z.clj") err))
        (finally
          (when (fs/exists? late-source)
            (process/check (process/process ["chmod" "644" late-source]))))))))

(deftest documented-direct-invocation-is-executable
  (is (fs/executable? checker) "The README invokes the checker directly")
  (when (fs/executable? checker)
    (let [dir (fixture-repo! (with-record "`present`\n"))
          {:keys [exit out err]} @(process/process [checker "--root" dir "--control" "present" "design/log/record.org"]
                                                  {:out :string :err :string})]
      (is (zero? exit) (str out err))
      (is (str/includes? out "control `present` matched") out))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
