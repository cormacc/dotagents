(ns herdr-orch.traits-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.cli :as cli]
            [herdr-orch.traits :as traits]))

(def root
  (if (fs/absolute? *file*)
    (str (fs/canonicalize (fs/path (fs/parent *file*) "../../../../..")))
    (str (fs/canonicalize "."))))
(def bin (str root "/skills/herdr-orch/scripts/oh"))
(def fake (str root "/skills/herdr-orch/scripts/test/fixtures/fake-herdr"))

(def shared-clj-cache
  (delay
    (let [dir (str (fs/create-temp-dir {:prefix "traits-test-clj-cache-"}))
          home (str (fs/create-temp-dir {:prefix "traits-test-warm-home-"}))
          proc @(process/process [bin "--help"]
                                 {:out :string :err :string
                                  :env {"PATH" (System/getenv "PATH") "HOME" home
                                        "CLJ_CACHE" dir "CLJ_CONFIG" dir}})]
      (when-not (zero? (:exit proc))
        (throw (ex-info "failed to warm traits test classpath cache" {:exit (:exit proc) :err (:err proc)})))
      dir)))

(defn- fixture-env []
  (let [dir (fs/canonicalize (fs/create-temp-dir {:prefix "traits-cli-"}))
        home (fs/path dir "home")
        log (str (fs/path dir "calls"))
        env-file (str (fs/path dir "env"))
        prompt-file (str (fs/path dir "prompt"))
        state (str (fs/path dir "state"))]
    (fs/create-dirs home)
    (fs/create-sym-link (fs/path dir "herdr") fake)
    {:dir (str dir) :log log :env-file env-file :prompt-file prompt-file :state state
     :env {"PATH" (str dir ":" (System/getenv "PATH"))
           "HOME" (str home)
           "HERDR_ENV" "1"
           "HERDR_PANE_ID" "w:p"
           "HERDR_ORCH_BIN" bin
           "ORCH_ASSIGNMENT_ROOT" (str dir)
           "FAKE_HERDR_LOG" log
           "FAKE_HERDR_ENV_FILE" env-file
           "FAKE_HERDR_PROMPT_FILE" prompt-file
           "FAKE_HERDR_STATE_DIR" state
           "CLJ_CACHE" @shared-clj-cache
           "CLJ_CONFIG" @shared-clj-cache}}))

(defn- write-persona! [dir name body]
  (let [path (fs/path dir ".agents" "subagents" (str name ".md"))]
    (fs/create-dirs (fs/parent path))
    (spit (str path) body)
    (str path)))

(defn- write-trait! [dir name shape body]
  (let [base (fs/path dir ".agents" "traits")
        path (case shape
               :flat (fs/path base (str name ".md"))
               :directory (fs/path base name "prompt.md"))]
    (fs/create-dirs (fs/parent path))
    (spit (str path) body)
    (str path)))

(defn- call! [env & argv]
  @(process/process (into [bin] argv) {:out :string :err :string :env env}))
(defn- output [proc] (json/parse-string (:out proc) true))
(defn- calls [log]
  (if (fs/exists? log)
    (mapv #(str/split % #"\037") (str/split-lines (slurp log)))
    []))
(defn- mutating? [argv]
  (contains? #{["pane" "split"] ["tab" "create"] ["pane" "rename"]
               ["pane" "close"] ["agent" "start"] ["agent" "prompt"]}
             (vec (take 2 argv))))
(defn- ledger-entry [dir task]
  (json/parse-string
   (slurp (str (fs/path dir ".tmp" "herdr-orch" "ledger" (str task ".json")))) true))
(defn- patch-entry! [dir entry & kvs]
  (let [updated (apply assoc entry kvs)]
    (spit (str (fs/path dir ".tmp" "herdr-orch" "ledger" (str (:task entry) ".json")))
          (json/generate-string updated))
    updated))

;; The complete shipped string, not a prefix of it: a prefix still occurs exactly once, so
;; a truncated copy pins nothing about the sentence that follows it. The relative-artifact
;; sentence is the only channel that tells a child the `ARTIFACTS` grammar and `WORK-ROOT`.
(def expected-publication-guidance
  "Published `SUMMARY` must be a single line. Write multi-line detail to a distinct `.tmp/*-report.md` file, pass the report with `--artifact`, and emit each key finding with `--finding`; do not hide findings only in `SUMMARY`, and never treat pane text as the result. Never use `HERDR_ORCH_RESULT` as a report file. Use `HERDR_ORCH_RESULT` only through `task publish`. Each `--artifact` value must be a path relative to your working directory (`$HERDR_ORCH_WORK_ROOT`); an absolute path, or one that escapes that root, is refused.")
(defn- occurrence-count [text needle]
  (loop [from 0 n 0]
    (let [at (.indexOf ^String text ^String needle from)]
      (if (neg? at) n (recur (+ at (count needle)) (inc n))))))

(deftest publication-guidance-is-exact-and-unconditional
  (let [{:keys [dir env]} (fixture-env)
        _ (write-persona! dir "leaf-fixture" "---\nname: leaf-fixture\nkind: pi\nretro: false\n---\n\nLeaf.\n")
        _ (write-persona! dir "spawner-fixture" "---\nname: spawner-fixture\nkind: pi\nretro: false\nspawns: leaf-fixture\n---\n\nSpawner.\n")
        leaf-proc (call! env "task" "run" "leaf-fixture" "--task" "blocking preview" "--print-prompt")
        spawner-proc (call! env "task" "start" "spawner-fixture" "--task" "non-blocking preview" "--print-prompt")
        leaf (get-in (output leaf-proc) [:result :preview])
        spawner (get-in (output spawner-proc) [:result :preview])
        continued (cli/continuation-prompt {:assignment "follow on" :task "next-task" :result "/tmp/next.result"
                                            :waiting-policy "non-blocking"})
        poked (cli/poke-prompt "poke-task" "/tmp/poke.result")]
    (is (zero? (:exit leaf-proc)) (:err leaf-proc))
    (is (zero? (:exit spawner-proc)) (:err spawner-proc))
    (doseq [[label prompt] [["leaf run preview" leaf]
                            ["spawning start preview" spawner]
                            ["continuation" continued]
                            ["poke" poked]]]
      (is (= 1 (occurrence-count prompt expected-publication-guidance)) label))
    (doseq [[label prompt] [["leaf run preview" leaf]
                            ["spawning start preview" spawner]
                            ["continuation" continued]]]
      (is (str/includes? prompt
                         (str "never stop silently or publish a second envelope after recovering. "
                              expected-publication-guidance))
          label))
    (is (str/includes? leaf "You are a leaf: do not spawn subagents."))
    (is (str/includes? spawner "You may spawn at most one blocking leaf-fixture"))
    (is (not (str/includes? leaf "--status WAITING")))
    (is (str/includes? spawner "--status WAITING"))
    (is (str/includes? continued "Follow-on round in the role you already hold."))))

(defn- interpolate [text files]
  (traits/interpolate {:text text
                       :directories (traits/trait-directories "/project" "/home/u" "/installed/herdr-orch")
                       :exists? #(contains? files %)
                       :read-text #(get files %)}))

(deftest trait-resolution-covers-both-shapes-and-all-layers
  (let [directories (traits/trait-directories "/project" "/home/u" "/installed/herdr-orch")
        candidates (fn [source shape]
                     (let [directory (:directory (first (filter #(= source (:source %)) directories)))]
                       (case shape
                         :flat (str directory "/focused.md")
                         :directory (str directory "/focused/prompt.md"))))]
    (is (= [{:source "project" :directory "/project/.agents/traits"}
            {:source "home" :directory "/home/u/.agents/traits"}
            {:source "packaged" :directory "/installed/herdr-orch/traits"}]
           directories))
    (doseq [source ["project" "home" "packaged"]
            shape [:flat :directory]]
      (let [path (candidates source shape)]
        (is (= {:trait "focused" :source source :path path}
               (traits/resolve-trait #{path} directories "focused"))
            (str source " " shape))))
    (testing "the flat shape wins within one layer"
      (let [flat (candidates "project" :flat)
            directory (candidates "project" :directory)]
        (is (= flat (:path (traits/resolve-trait #{flat directory} directories "focused"))))))
    (testing "layer precedence outranks shape"
      (let [project-directory (candidates "project" :directory)
            home-flat (candidates "home" :flat)
            packaged-flat (candidates "packaged" :flat)]
        (is (= project-directory
               (:path (traits/resolve-trait #{project-directory home-flat packaged-flat}
                                            directories "focused"))))))))

(deftest interpolation-grammar-exclusions-short-names-and-single-pass
  (let [focus "/project/.agents/traits/focused.md"
        ct "/project/.agents/traits/ct/prompt.md"
        nested "/project/.agents/traits/nested.md"
        input (str "Start %focused, page%focused %20 %PATH% %s %ct.\n"
                   "`%focused` and `` %focused ``\n"
                   "```md\n%focused\n```\n"
                   "~~~\n%focused\n~~~\n"
                   "    %focused\n\t%focused\n")
        result (interpolate input
                            {focus "---\nname: focused\ndescription: Axis\n---\nFocus %nested."
                             ct "Short whole-file fragment."
                             nested "MUST NOT BE RESCANNED"})]
    (is (= (str "Start Focus %nested., page%focused %20 %PATH% %s Short whole-file fragment..\n"
                "`%focused` and `` %focused ``\n"
                "```md\n%focused\n```\n"
                "~~~\n%focused\n~~~\n"
                "    %focused\n\t%focused\n")
           (:text result)))
    (is (= ["focused" "ct"] (mapv :trait (:resolved result))))
    (is (= ["s"] (:unknowns result)))
    (is (= [] (:repeats result)))
    (is (not (str/includes? (:text result) "MUST NOT BE RESCANNED")))
    (testing "start-of-line, hyphenated names, maximal names, and unresolved two-character names"
      (let [read-only "/project/.agents/traits/read-only.md"
            edge (interpolate "%read-only %focusedx %an"
                              {read-only "Read-only." focus "Focused."})]
        (is (= "Read-only. %focusedx %an" (:text edge)))
        (is (= ["read-only"] (mapv :trait (:resolved edge))))
        (is (= ["focusedx" "an"] (:unknowns edge)))))))

(deftest persona-frontmatter-is-byte-preserved-and-inert
  (let [focus "/project/.agents/traits/focused.md"
        frontmatter "---\r\nname: %focused\r\ndescription: %missing-trait\r\n---\r\n"
        result (interpolate (str frontmatter "Body %focused.\r\n")
                            {focus "---\nname: focused\ndescription: Axis\n---\nFocused"})]
    (is (= (str frontmatter "Body Focused.\r\n") (:text result)))
    (is (= ["focused"] (mapv :trait (:resolved result))))
    (is (= [] (:unknowns result)))))

(deftest unknowns-repeats-and-fragment-name-validation-are-reported
  (let [focus "/project/.agents/traits/focused.md"
        result (interpolate "%focsu %focused and %focused plus %llu"
                            {focus "---\nname: focused\n---\nFocused."})]
    (is (= ["focsu" "llu"] (:unknowns result)))
    (is (= ["focused"] (:repeats result))))
  (let [focus "/project/.agents/traits/focused.md"]
    (try
      (interpolate "%focused" {focus "---\nname: wrong\n---\nNo."})
      (is false "mismatched fragment name must fail")
      (catch Exception e
        (is (str/includes? (ex-message e) "fragment `name` mismatch"))
        (is (= "focused" (:expected (ex-data e))))
        (is (= "wrong" (:actual (ex-data e))))
        (is (= focus (:path (ex-data e))))))))

(deftest unknown-and-repeated-traits-fail-before-ledger-or-pane-mutation
  (doseq [[label body trait expected]
          [["unknown" "%missing-trait" nil "missing-trait"]
           ["repeat" "%focused and %focused" ["focused" "---\nname: focused\n---\nFocused."] "focused"]]]
    (let [{:keys [dir env log]} (fixture-env)
          _ (write-persona! dir "broken" (str "---\nname: broken\nkind: pi\n---\n\n" body "\n"))
          _ (when trait (write-trait! dir (first trait) :flat (second trait)))
          proc (call! env "task" "start" "broken" "--task" (str label " trait"))
          failure (output proc)]
      (is (= 1 (:exit proc)) label)
      (is (str/includes? (get-in failure [:error :message]) (str "trait `" expected "`")) label)
      (is (str/includes? (get-in failure [:error :message]) "write it as code") label)
      (is (= expected (get-in failure [:error :data :trait])) label)
      (is (= ["project" "home" "packaged"]
             (get-in failure [:error :data :searched-layers])) label)
      (is (= 6 (count (get-in failure [:error :data :searched-paths]))) label)
      (is (not (fs/exists? (fs/path dir ".tmp" "herdr-orch" "ledger"))) label)
      (is (not-any? mutating? (calls log)) label))))

(deftest preview-materialization-pass-through-and-continuation-contract
  (let [persona-body "---\nname: composed-fixture\nkind: pi\n---\n\n# Fixture\n\nFirst %alpha then %beta.\n"
        alpha "---\nname: alpha\ndescription: First\n---\nAlpha directive."
        beta "Beta directive."]
    (testing "print-prompt reports occurrence order and sources, uses a placeholder, and writes no file"
      (let [{:keys [dir env]} (fixture-env)
            _ (write-persona! dir "composed-fixture" persona-body)
            alpha-path (write-trait! dir "alpha" :flat alpha)
            beta-path (write-trait! (get env "HOME") "beta" :directory beta)
            proc (call! env "task" "run" "composed-fixture" "--task" "preview traits" "--print-prompt")
            preview (:result (output proc))]
        (is (zero? (:exit proc)) (:err proc))
        (is (= ["alpha" "beta"] (:traits preview)))
        (is (= [{:trait "alpha" :source "project" :path alpha-path}
                {:trait "beta" :source "home" :path beta-path}]
               (:trait-sources preview)))
        (is (str/includes? (:preview preview) "Read <composed-persona-path>, adopt that role."))
        (is (not (fs/exists? (fs/path dir ".tmp" "herdr-orch" "composed"))))))
    (testing "spawn substitutes in place, routes the path everywhere, and continue only inherits it"
      (let [{:keys [dir env log prompt-file state]} (fixture-env)
            _ (write-persona! dir "composed-fixture" persona-body)
            _ (write-trait! dir "alpha" :flat alpha)
            _ (write-trait! dir "beta" :directory beta)
            start (call! env "task" "start" "composed-fixture" "--task" "materialize traits")
            task (get-in (output start) [:result :task])
            entry (ledger-entry dir task)
            composed (str (fs/path dir ".tmp" "herdr-orch" "composed"
                                   (str task "-composed-fixture.md")))
            expected (str/replace persona-body "%alpha then %beta" "Alpha directive. then Beta directive.")
            start-argv (first (filter #(= ["agent" "start"] (vec (take 2 %))) (calls log)))]
        (is (zero? (:exit start)) (:err start))
        (is (= composed (:persona-path entry)))
        (is (= expected (slurp composed)))
        (is (not (str/includes? (slurp composed) "## Shared directives")))
        (is (some #{composed} start-argv))
        (is (str/starts-with? (slurp prompt-file) (str "Read " composed ", adopt that role.")))
        (let [captured (patch-entry! dir entry :captured-at "2026-08-10T00:00:00Z" :status "COMPLETE")
              _ (spit (str (fs/path state "children" (:child captured) "status")) "idle")
              continued (call! env "task" "continue" task "--task" "continued round")
              next-entry (ledger-entry dir (get-in (output continued) [:result :task]))
              prompt (slurp prompt-file)]
          (is (zero? (:exit continued)) (:err continued))
          (is (= composed (:persona-path next-entry)))
          (is (fs/exists? composed))
          (is (not (str/includes? prompt "Read ")))
          (is (not (str/includes? prompt composed)))
          (is (= 1 (occurrence-count prompt expected-publication-guidance))))))
    (testing "token-free, retired-frontmatter-only, and unresolved-short-only personas pass through"
      (doseq [[name body]
              [["plain-fixture" "Plain body."]
               ["retired-fixture" "No body token."]
               ["short-fixture" "Git format %h stays literal."]]]
        (let [{:keys [dir env log prompt-file]} (fixture-env)
              traits-line (when (= name "retired-fixture") "traits: missing-trait\n")
              source (write-persona! dir name (str "---\nname: " name "\nkind: pi\n" traits-line "---\n\n" body "\n"))
              proc (call! env "task" "start" name "--task" (str name " spawn"))
              task (get-in (output proc) [:result :task])
              entry (ledger-entry dir task)
              start-argv (first (filter #(= ["agent" "start"] (vec (take 2 %))) (calls log)))]
          (is (zero? (:exit proc)) (:err proc))
          (is (= source (:persona-path entry)))
          (is (not (contains? entry :traits)))
          (is (not (contains? entry :trait-sources)))
          (is (some #{source} start-argv))
          (is (str/starts-with? (slurp prompt-file) (str "Read " source ", adopt that role.")))
          (is (not (fs/exists? (fs/path dir ".tmp" "herdr-orch" "composed")))))))))
