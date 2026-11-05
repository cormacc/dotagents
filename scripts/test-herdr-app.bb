#!/usr/bin/env bb
;; Run native-mod contracts offline. The real oh launcher uses only the owned HTTP fixture.
(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def root (str/trim (:out (process/shell {:out :string} "git" "rev-parse" "--show-toplevel"))))
(fs/create-dirs (fs/path root ".tmp"))
(def scratch (fs/create-temp-dir {:dir (str (fs/path root ".tmp")) :prefix "herdr-app-test-"}))
(try
  (let [home (fs/path scratch "home") bin (fs/path scratch "bin")
        agent (fs/path home ".pi/agent")
        skills (fs/path home ".agents/skills")
        report (str (fs/path scratch "report.json"))
        real-bb (str (fs/which "bb"))]
    (doseq [path [home bin agent skills]] (fs/create-dirs path))
    (fs/create-sym-link (fs/path skills "herdr-orch") (fs/path root "skills/herdr-orch"))
    (spit (str (fs/path bin "bb"))
          (str "#!/usr/bin/env bash\nset -euo pipefail\n"
               "[ \"${1:-}\" = --config ] && [ \"${5:-}\" = -m ] && [ \"${6:-}\" = herdr-orch.cli ] && [ \"${7:-}\" = -- ] || exit 96\n"
               "shift 7\nexec \"$ORCH_TEST_ROOT/skills/herdr-orch/scripts/test/fixtures/http-oh\" \"$@\"\n"))
    (fs/set-posix-file-permissions (fs/path bin "bb") "rwxr-xr-x")
    (spit report (json/generate-string {:claude {:body {:five_hour {:utilization 39}}}
                                       :codex {:body {:rate_limit {:primary_window {:used_percent 58 :limit_window_seconds 18000}}}}}))
    (spit (str (fs/path agent "auth.json"))
          (json/generate-string {:anthropic {:type "oauth" :refresh "fixture" :access "fixture-claude" :expires 4102444800000}
                                 :openai-codex {:type "oauth" :refresh "fixture" :access "fixture-codex" :expires 4102444800000 :accountId "fixture-account"}}))
    (let [env (cond-> {"HOME" (str home) "HERDR_APP_TEST_HOME" (str home)
                       "PATH" (str bin ":" (System/getenv "PATH"))
                       "PI_CODING_AGENT_DIR" (str agent)
                       "XDG_CACHE_HOME" (str (fs/path home ".cache"))
                       "ORCH_TEST_ROOT" root "ORCH_TEST_BB" real-bb
                       "FAKE_QUOTA_REPORT" report "FAKE_QUOTA_LOG" (str (fs/path scratch "calls"))}
                (some? (System/getenv "HERDR_APP_WIRING"))
                (assoc "HERDR_APP_WIRING" (System/getenv "HERDR_APP_WIRING")))
          tests (map str (sort (fs/glob (fs/path root "herdr-app/tests") "*.test.mjs")))
          result @(process/process (into ["node" "--experimental-vm-modules" "--test" "--test-timeout=45000"] tests)
                                   {:dir root :env env :out :inherit :err :inherit})]
      (when-not (zero? (:exit result))
        (throw (ex-info "herdr-app tests failed" {:exit (:exit result)})))))
  (finally (fs/delete-tree scratch)))
