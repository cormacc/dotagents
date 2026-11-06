(ns herdr-orch.test-support
  "Shared helpers for herdr-orch tests."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]))

(def ^:private repository-root
  (str (fs/canonicalize (fs/path (fs/parent *file*) "../../../../.."))))

(defn quota-env!
  "Create an isolated auth/cache environment and vendor-process tripwires in DIR."
  [dir]
  (let [bin (fs/path dir "quota-bin") home (fs/path dir "home")
        report (str (fs/path dir "usage-report.json"))]
    (fs/create-dirs bin)
    (fs/create-dirs home)
    (doseq [name ["claude" "codex" "kimi" "zai" "cswap" "security" "npm"]]
      (let [path (fs/path bin name)]
        (spit (str path) "#!/usr/bin/env bash\nprintf 'vendor escape\\n' >>\"$FAKE_VENDOR_LOG\"\nexit 97\n")
        (fs/set-posix-file-permissions path "rwxr-xr-x")))
    (let [root repository-root
          pi (fs/path bin "fixture-pi-inventory")]
      (spit (str pi) (str "#!/usr/bin/env bash\nexec bb --config '" root "/bb.edn' --deps-root '" root
                         "' '" root "/skills/herdr-orch/scripts/test/fixtures/model-inventory.clj'\n"))
      (fs/set-posix-file-permissions pi "rwxr-xr-x"))
    (spit report (json/generate-string {}))
    {"PATH" (str bin ":" (System/getenv "PATH")) "HOME" (str home)
     "HERDR_ORCH_PI_BIN" (str (fs/path bin "fixture-pi-inventory"))
     "PI_CODING_AGENT_DIR" (str (fs/path home ".pi/agent"))
     "CODEX_HOME" (str (fs/path home ".codex")) "CLAUDE_CONFIG_DIR" (str (fs/path home ".claude"))
     "XDG_DATA_HOME" (str (fs/path home ".local/share"))
     "XDG_CONFIG_HOME" (str (fs/path home ".config")) "XDG_CACHE_HOME" (str (fs/path home ".cache"))
     "FAKE_QUOTA_REPORT" report "FAKE_QUOTA_LOG" (str (fs/path dir "quota-calls"))
     "FAKE_VENDOR_LOG" (str (fs/path dir "vendor-escapes"))}))

(defn routing-auth!
  "Seed disposable subscription identities for launcher fixtures, never real credentials."
  [dir]
  (let [path (fs/path dir "home/.pi/agent/auth.json")
        oauth {:type "oauth" :access "fixture-launch-access" :refresh "fixture-launch-refresh" :expires 4102444800000}]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (json/generate-string {:anthropic oauth :openai-codex (assoc oauth :accountId "fixture-launch")}))))

(defn call-with-temp-dir
  "Call `f` with the canonical path string of a new temporary directory, and delete the
  directory afterwards, also when `f` throws. `fs/delete-tree` removes a symlink inside the
  directory without following it.

  Use this for each new test directory. The root `bb test` task also deletes its per-run
  temporary root, but a focused run outside that task does not."
  [prefix f]
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:prefix prefix})))]
    (try
      (f dir)
      (finally (fs/delete-tree dir)))))
