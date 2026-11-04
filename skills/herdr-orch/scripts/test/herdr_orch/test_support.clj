(ns herdr-orch.test-support
  "Shared helpers for herdr-orch tests."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]))

(defn quota-env!
  "Install a fake quota executable and vendor tripwires in DIR; return its isolated environment."
  [dir fixture]
  (let [bin (fs/path dir "quota-bin") home (fs/path dir "home")
        report (str (fs/path dir "usage-report.json"))]
    (fs/create-dirs bin)
    (fs/create-dirs home)
    (fs/copy fixture (fs/path bin "quota-axi"))
    (fs/set-posix-file-permissions (fs/path bin "quota-axi") "rwxr-xr-x")
    (doseq [name ["claude" "codex" "kimi" "zai" "cswap" "security" "npm"]]
      (let [path (fs/path bin name)]
        (spit (str path) "#!/usr/bin/env bash\nprintf 'vendor escape\\n' >>\"$FAKE_VENDOR_LOG\"\nexit 97\n")
        (fs/set-posix-file-permissions path "rwxr-xr-x")))
    (spit report (json/generate-string {:schemaVersion 5 :providers []}))
    {"PATH" (str bin ":" (System/getenv "PATH")) "HOME" (str home)
     "CODEX_HOME" (str (fs/path home ".codex")) "CLAUDE_CONFIG_DIR" (str (fs/path home ".claude"))
     "XDG_DATA_HOME" (str (fs/path home ".local/share"))
     "XDG_CONFIG_HOME" (str (fs/path home ".config")) "XDG_CACHE_HOME" (str (fs/path home ".cache"))
     "FAKE_QUOTA_REPORT" report "FAKE_QUOTA_LOG" (str (fs/path dir "quota-calls"))
     "FAKE_VENDOR_LOG" (str (fs/path dir "vendor-escapes"))}))

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
