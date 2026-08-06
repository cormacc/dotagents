(ns herdr-orch.launcher-test
  "Launcher matrix: the four supported invocation forms must select the right
  Babashka branch, keep `HERDR_ORCH_BIN` absolute, and preserve the caller's
  working directory (which becomes the child pane's `--cwd`)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- git-toplevel []
  (let [proc @(process/process ["git" "rev-parse" "--show-toplevel"] {:out :string :err :string})]
    (when (zero? (:exit proc)) (str/trim (:out proc)))))
(def root
  (if (fs/absolute? *file*)
    (str (fs/canonicalize (fs/path (fs/parent *file*) "../../../../..")))
    (or (git-toplevel) (throw (ex-info "cannot resolve repo root" {})))))
(def scripts-dir (str root "/skills/herdr-orch/scripts"))
(def repo-bin (str scripts-dir "/oh"))
(def fake (str scripts-dir "/test/fixtures/fake-herdr"))
(def real-bb (str (fs/which "bb")))

(defn- calls [log] (if (fs/exists? log) (mapv #(str/split % #"\037") (str/split-lines (slurp log))) []))
(defn- flag-value [argv flag] (second (drop-while #(not= flag %) argv)))
(defn- split-argv [log]
  (first (filter #(= ["pane" "split"] (vec (take 2 %))) (calls log))))
(defn- injected [env-file]
  (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file)))))

(defn- harness
  "Temp assignment root + a PATH shim providing the fake `herdr` and a logging `bb`."
  []
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:prefix "subagent-launcher-"})))
        shim (fs/path dir "shim")
        bb-shim (fs/path shim "bb")
        caller (fs/path dir "caller")]
    (fs/create-dirs shim)
    (fs/create-dirs caller)
    ;; The bare-subtree form must launch from a caller cwd that owns a bb.edn:
    ;; that is exactly the state in which `-Sdeps` rejects absolute :paths.
    (spit (str (fs/path caller "bb.edn")) "{}")
    (fs/create-sym-link (fs/path shim "herdr") fake)
    (spit (str bb-shim) "#!/usr/bin/env bash\nprintf '%s\\037' \"$@\" >>\"$BB_ARGV_LOG\"\nprintf '\\n' >>\"$BB_ARGV_LOG\"\nexec \"$REAL_BB\" \"$@\"\n")
    (fs/set-posix-file-permissions bb-shim "rwxr-xr-x")
    (fs/create-dirs (fs/path dir "empty-home"))
    (let [log (str (fs/path dir "calls")) env-file (str (fs/path dir "env")) bb-log (str (fs/path dir "bb-argv"))]
      {:dir dir :caller (str caller) :log log :env-file env-file :bb-log bb-log
       :env {"PATH" (str shim ":" (System/getenv "PATH"))
             "HOME" (System/getenv "HOME")
             "HERDR_ENV" "1" "HERDR_PANE_ID" "w:p"
             "FAKE_HERDR_LOG" log "FAKE_HERDR_ENV_FILE" env-file
             "FAKE_HERDR_PROMPT_FILE" (str (fs/path dir "prompt"))
             "ORCH_ASSIGNMENT_ROOT" dir
             "BB_ARGV_LOG" bb-log "REAL_BB" real-bb}})))

(defn- launch!
  "Invoke `bin` (possibly relative) with `cwd` as the process working directory."
  [h bin cwd]
  @(process/process (into ["/bin/sh" "-c" "exec \"$0\" \"$@\"" bin] ["task" "start" "worker" "--task" "launcher matrix"])
                    {:out :string :err :string :env (:env h) :dir cwd}))

(defn- bb-argv [h] (first (calls (:bb-log h))))

;; Without the override, `assignment-root` probes `git rev-parse` in the CLI's cwd. That
;; only lands in the caller's project because the launcher never `cd`s away from it.
(deftest assignment-root-follows-the-caller-project
  (let [h (harness)
        project (str (fs/path (:dir h) "project"))
        _ @(process/process ["git" "init" "-q" project] {:out :string :err :string})
        ;; Empty HOME and no project persona directory: the shipped definition must
        ;; resolve from the deployed launcher's skill subtree.
        env (-> (:env h) (dissoc "ORCH_ASSIGNMENT_ROOT") (assoc "HOME" (str (fs/path (:dir h) "empty-home"))))
        ;; Through the *deployed* directory-symlinked path — the documented invocation,
        ;; and the one the pre-fix launcher resolved into its own scripts directory.
        deploy (str (fs/path (:dir h) "deploy-root"))
        _ (fs/create-sym-link deploy (fs/path root "skills"))
        bin (str deploy "/herdr-orch/scripts/oh")
        proc @(process/process ["/bin/sh" "-c" "exec \"$0\" \"$@\"" bin "task" "start" "worker" "--task" "foreign project"]
                               {:out :string :err :string :env env :dir project})
        env-map (injected (:env-file h))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= (:dir h) (str (fs/canonicalize (:dir h)))))
    (is (= project (flag-value (split-argv (:log h)) "--cwd")))
    (is (str/starts-with? (get env-map "HERDR_ORCH_RESULT")
                          (str (fs/path project ".tmp" "herdr-orch"))))
    ;; The override is absent, so it must not be injected either.
    (is (nil? (get env-map "ORCH_ASSIGNMENT_ROOT")))))

(deftest launcher-matrix-selects-branch-and-preserves-cwd
  (testing "repo-relative invocation"
    (let [h (harness) proc (launch! h "./skills/herdr-orch/scripts/oh" root)]
      (is (zero? (:exit proc)) (:err proc))
      (is (some #{"--config"} (bb-argv h)))
      (is (= (str root "/bb.edn") (flag-value (bb-argv h) "--config")))
      (is (= root (flag-value (split-argv (:log h)) "--cwd")))
      ;; A relative argv0 must still inject an absolute launcher path.
      (is (= repo-bin (get (injected (:env-file h)) "HERDR_ORCH_BIN")))))
  (testing "repo-absolute invocation"
    (let [h (harness) proc (launch! h repo-bin (:caller h))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= (str root "/bb.edn") (flag-value (bb-argv h) "--config")))
      (is (= (:caller h) (flag-value (split-argv (:log h)) "--cwd")))
      (is (= repo-bin (get (injected (:env-file h)) "HERDR_ORCH_BIN")))))
  (testing "deployed directory-symlink invocation"
    (let [h (harness)
          deploy (str (fs/path (:dir h) "deploy"))
          _ (fs/create-sym-link deploy (fs/path root "skills"))
          bin (str deploy "/herdr-orch/scripts/oh")
          proc (launch! h bin (:caller h))]
      (is (zero? (:exit proc)) (:err proc))
      ;; `~/.agents/skills` is a *directory* symlink; only `cd -P` reaches the repo bb.edn.
      (is (= (str root "/bb.edn") (flag-value (bb-argv h) "--config")))
      ;; Fails if the launcher ever `cd`s to its own script directory again.
      (is (= (:caller h) (flag-value (split-argv (:log h)) "--cwd")))
      ;; The deployed path itself is injected (absolute and stable for the child).
      (is (= bin (get (injected (:env-file h)) "HERDR_ORCH_BIN")))))
  (testing "bare skill-subtree invocation from a caller cwd with its own bb.edn"
    (let [h (harness)
          bare (str (fs/path (:dir h) "bare" "x" "y" "scripts"))
          _ (fs/create-dirs (fs/parent bare))
          _ (fs/copy-tree scripts-dir bare)
          ;; A bare-subtree install ships the complete sibling `subagents/` tree. This
          ;; fixture deliberately has no project or home persona directory, so both the
          ;; worker definition and config.edn must resolve from beside the launcher.
          _ (fs/copy-tree (fs/path root "skills" "herdr-orch" "subagents")
                          (fs/path (fs/parent bare) "subagents"))
          bin (str bare "/oh")
          proc (launch! h bin (:caller h))
          argv (bb-argv h)]
      (is (zero? (:exit proc)) (:err proc))
      (is (not-any? #{"--config"} argv))
      (is (some #{"-Sdeps"} argv))
      ;; Relative :paths anchored by --deps-root: an absolute :paths value throws
      ;; `is not a relative path` from a cwd that contains a bb.edn.
      (is (= bare (flag-value argv "--deps-root")))
      (is (= "{:paths [\"src\"]}" (flag-value argv "-Sdeps")))
      (is (= (:caller h) (flag-value (split-argv (:log h)) "--cwd")))
      (is (= bin (get (injected (:env-file h)) "HERDR_ORCH_BIN"))))))
