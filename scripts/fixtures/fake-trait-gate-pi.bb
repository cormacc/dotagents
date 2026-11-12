#!/usr/bin/env bb
;; A fake `pi` for scripts/test-run-trait-gate.bb. It logs its argv, cwd and selected
;; environment to FAKE_PI_LOG, emits the JSON-mode messages that the runner reads, and stands
;; in for the audit extension: it writes the TRAIT_GATE_AUDIT_OUT lines for one request whose
;; payload is the home AGENTS.md, the appended system prompt and the user prompt.
;; FAKE_PI_MODE=silent emits no assistant message. FAKE_PI_MODE=no-baseline leaves the home
;; AGENTS.md out of the request. FAKE_PI_WRITE=<file> appends a line to that file in the cwd.
(require '[cheshire.core :as json]
         '[clojure.string :as str])

(defn contains-text? [parts text] (boolean (some #(str/includes? % text) parts)))

(let [args (vec *command-line-args*)
      prompt (last args)
      system-path (second (drop-while #(not= "--append-system-prompt" %) args))
      system (when system-path (slurp system-path))
      cwd (System/getProperty "user.dir")
      mode (System/getenv "FAKE_PI_MODE")
      agent-dir (or (System/getenv "PI_CODING_AGENT_DIR") (str (System/getenv "HOME") "/.pi/agent"))
      baseline (slurp (str agent-dir "/AGENTS.md"))
      emit (fn [m] (println (json/generate-string {:type "message_end" :message m})))]
  (spit (System/getenv "FAKE_PI_LOG")
        (str (pr-str {:args args :cwd cwd :system system
                      :herdr (System/getenv "HERDR_ENV")
                      :session (System/getenv "PI_SESSION_ID")
                      :keep (System/getenv "TRAIT_GATE_KEEP")}) "\n")
        :append true)
  (when-let [out (System/getenv "TRAIT_GATE_AUDIT_OUT")]
    (let [expected (json/parse-string (slurp (System/getenv "TRAIT_GATE_AUDIT_EXPECT")))
          parts (cond-> [prompt]
                  system (conj system)
                  (not= "no-baseline" mode) (conj baseline))]
      (spit out (str (json/generate-string {:controls {:positive true :missing false :knownBad false}}) "\n"
                     (json/generate-string {:request 1
                                            :present (into {} (map (fn [[k v]] [k (contains-text? parts v)]) expected))})
                     "\n"))))
  (when-let [file (System/getenv "FAKE_PI_WRITE")]
    (spit (str cwd "/" file) "fake write\n" :append true))
  (emit {:role "system" :toolsAdded [{:name "read"} {:name "bash"}]
         :sections {:project_context "<project_instructions path=\"/home/AGENTS.md\">x"
                    :skills "<skill>a</skill><skill>b</skill>"
                    :mcp_servers "- mcp__one (codemode)\n- mcp__two"}})
  (emit {:role "user" :content [{:type "text" :text prompt}]})
  (when-not (= "silent" mode)
    (emit {:role "assistant" :provider "fake" :model "fake-model" :api "fake-api"
           :stopReason "stop" :content [{:type "text" :text (str "FAKE RESPONSE\n" prompt)}]})))
