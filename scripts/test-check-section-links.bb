#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing run-tests]])

(def root
  (str/trim (:out @(process/process ["git" "rev-parse" "--show-toplevel"]
                                    {:out :string :err :string}))))
(def checker (str (fs/path root "scripts" "check-section-links.bb")))

(defn fixture-repo!
  "A disposable git repository holding `files`, a map of path -> content."
  [files]
  (let [dir (fs/create-temp-dir {:prefix "section-links-"})]
    (doseq [[p content] files]
      (fs/create-dirs (fs/parent (fs/path dir p)))
      (spit (str (fs/path dir p)) content))
    @(process/process ["git" "init" "-q"] {:dir (str dir)})
    @(process/process ["git" "add" "-A"] {:dir (str dir)})
    (str dir)))

(defn check! [dir & args]
  @(process/process (into ["bb" checker "--root" dir] args) {:out :string :err :string}))

(def targets
  {"docs/guide.org" (str "* TODO [#A] Install steps :setup:\n"
                         "** Configure\n:PROPERTIES:\n:CUSTOM_ID: configure-id\n:END:\n"
                         "* STARTED Rollout plan\n")
   "docs/ref.md" "# Reference\n\n## Model resolution\n\n## Harness `:extra-args`\n\n## Notes\n\n## Notes\n"})

(defn with-source [path content] (assoc targets path content))

(deftest valid-references-pass
  (let [dir (fixture-repo!
             (with-source "notes.org"
               (str "* Local heading\n"
                    "See [[file:docs/guide.org::*Install steps]] and [[file:docs/guide.org::*Configure]].\n"
                    "Also [[*Local heading]], [[file:docs/guide.org::#configure-id]], and [[file:docs/guide.org::#rollout-plan]].\n"
                    "From org to Markdown: [[file:docs/ref.md]] section 'Model resolution'.\n"
                    "TOC style: [[#local-heading][Local heading]].\n")))
        md (fixture-repo!
            (with-source "docs/index.md"
              (str "# Index\n\n"
                   "[ref.md#Harness `:extra-args`](ref.md#harness-extra-args), "
                   "[ref.md#Notes](ref.md#notes-1), [#Index](#index), "
                   "[guide.org#Install steps](guide.org#install-steps), "
                   "[guide.org#STARTED Rollout plan](guide.org#started-rollout-plan).\n")))]
    (doseq [d [dir md]]
      (let [proc (check! d)]
        (is (zero? (:exit proc)) (:out proc))
        (is (re-find #"; 0 problems" (:out proc)))))
    (testing "positive control: the valid fixtures were actually checked"
      (is (re-find #"checked 7 section references" (:out (check! dir))))
      (is (re-find #"checked 5 section references" (:out (check! md)))))))

(deftest each-broken-form-is-reported
  (doseq [[label content expected]
          [["missing org heading" "[[file:docs/guide.org::*Uninstall]]\n" #"no heading 'Uninstall' in docs/guide.org"]
           ["missing local heading" "[[*Nowhere]]\n" #"no heading 'Nowhere' in this file"]
           ["missing custom id" "[[#nowhere]]\n" #"no CUSTOM_ID or heading anchor 'nowhere'"]
           ["missing target file" "[[file:docs/gone.org::*Install steps]]\n" #"target file does not exist"]
           ["absolute path" "[[file:/etc/guide.org::*Install steps]]\n" #"not relative"]
           ["home path" "[[file:~/guide.org::*Install steps]]\n" #"not relative"]
           ["org heading search into Markdown" "[[file:docs/ref.md::*Notes]]\n" #"non-org file"]
           ["missing quoted Markdown heading" "[[file:docs/ref.md]] section 'Nope'\n" #"no heading 'Nope' in docs/ref.md"]
           ["missing Markdown anchor" "[x](docs/ref.md#nope)\n" #"no anchor '#nope' in docs/ref.md"]]]
    (testing label
      (let [proc (check! (fixture-repo! (with-source "notes.org" content)))]
        (is (= 1 (:exit proc)) (:out proc))
        (is (re-find expected (:out proc)) (:out proc))
        (is (re-find #"notes\.org:1: " (:out proc)) "problems name file and line")))))

(deftest examples-in-code-are-not-references
  (let [org (str "Verbatim =[[file:gone.org::*X]]= and ~[[*Nowhere]]~ in prose.\n"
                 "A long verbatim =#+PARENT: [[file:...::#<uuid>][summary]]= span.\n"
                 "#+begin_src org\n[[*Nowhere]]\n#+end_src\n"
                 ;; A one-line fence in an org file must not hide the rest of the file.
                 "```nix { x = 1; }```\n"
                 "[[*Still checked]]\n")
        md (str "Inline `[x](gone.md#nope)` code.\n\n```md\n[x](gone.md#nope)\n```\n\n[y](gone.md#nope)\n")
        org-proc (check! (fixture-repo! (with-source "notes.org" org)))
        md-proc (check! (fixture-repo! (with-source "notes.md" md)))]
    (is (re-find #"notes\.org:7: no heading 'Still checked'" (:out org-proc)) (:out org-proc))
    (is (re-find #"; 1 problem$" (str/trim (:out org-proc))) (:out org-proc))
    (is (re-find #"notes\.md:7: target file does not exist" (:out md-proc)) (:out md-proc))
    (is (re-find #"; 1 problem$" (str/trim (:out md-proc))) (:out md-proc))))

(deftest exclude-skips-a-path-prefix
  (let [dir (fixture-repo! (assoc targets "vendor/notes.org" "[[*Nowhere]]\n"))]
    (is (= 1 (:exit (check! dir))))
    (let [proc (check! dir "--exclude" "vendor/")]
      (is (zero? (:exit proc)) (:out proc)))))

(deftest unknown-argument-is-rejected
  (let [proc @(process/process ["bb" checker "--bogus"] {:out :string :err :string})]
    (is (= 2 (:exit proc)))
    (is (str/includes? (:err proc) "unknown argument"))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
