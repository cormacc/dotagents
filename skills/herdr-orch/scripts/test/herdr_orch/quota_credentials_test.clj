(ns herdr-orch.quota-credentials-test
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.quota-credentials :as credentials])
  (:import [java.util Base64]))

(def now-ms 1800000000000)
(def later-ms 1800000060000)
(defn jwt [exp]
  (str "eyJhbGciOiJub25lIn0."
       (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                        (.getBytes (json/generate-string {:exp exp}) "UTF-8"))
       ".fixture"))
(defn oauth [token]
  {:type "oauth" :access token :refresh "fixture-refresh" :expires later-ms :accountId "fixture-account"})
(defn put-json! [path value]
  (fs/create-dirs (fs/parent path))
  (spit path (json/generate-string value)))
(defn with-context [f]
  (fs/create-dirs ".tmp")
  (let [dir (str (fs/absolutize (fs/create-temp-dir {:dir ".tmp" :prefix "quota-credentials-"})))
        context {:env {} :os "Linux" :home dir :cwd dir :now-ms now-ms}]
    (try (f context) (finally (fs/delete-tree dir)))))
(defn pi-path [context] (str (fs/path (:home context) ".pi/agent/auth.json")))
(defn native-claude-path [context] (str (fs/path (:home context) ".claude/.credentials.json")))
(defn native-codex-path [context] (str (fs/path (:home context) ".codex/auth.json")))
(defn seed! [context]
  (put-json! (pi-path context) {:anthropic (oauth "pi-claude") :openai-codex (oauth "pi-codex")})
  (put-json! (native-claude-path context) {:claudeAiOauth {:accessToken "native-claude" :expiresAt later-ms}})
  (put-json! (native-codex-path context) {:auth_mode "chatgpt" :tokens {:access_token (jwt 1800000060) :account_id "native-account"}}))

(deftest selected-harness-owns-the-credential
  (with-context
    (fn [context]
      (seed! context)
      (is (= "pi-claude" (:access (credentials/credentials :pi "claude" context))))
      (is (= "pi-codex" (:access (credentials/credentials :pi "codex" context))))
      (is (= "native-claude" (:access (credentials/credentials :claude "claude" context))))
      (let [r (credentials/credentials :codex "codex" context)]
        (is (= "ready" (:status r)))
        (is (= "native-account" (:account-id r)))
        (is (= later-ms (:expires r))))
      (doseq [[h p] [[:claude "codex"] [:codex "claude"] [:other "claude"]]]
        (is (= {:status "unknown" :reason "unsupported-auth-context"}
               (credentials/credentials h p context))))
      (is (= "unsupported-provider" (:reason (credentials/credentials :pi "kimi" context)))))))

(deftest named-pi-account-selection-is-validated-and-read-only
  (with-context
    (fn [context]
      (seed! context)
      (let [a "anthropic-account-a" b "anthropic-account-b"
            path (pi-path context)
            auth (assoc (json/parse-string (slurp path) true)
                        (keyword a) (oauth "same-token")
                        (keyword b) (oauth "same-token")
                        :openai-codex-account-no (oauth "ignored")
                        :anthropic-account-INVALID (oauth "ignored")
                        :anthropic-account- (oauth "ignored"))]
        (put-json! path auth)
        (let [before (slurp path)
              first-read (credentials/credentials :pi "claude" context a)]
          (is (= [a b] (credentials/pi-accounts context)))
          (is (= "same-token" (:access first-read)))
          (is (not= (:context-key first-read)
                    (:context-key (credentials/credentials :pi "claude" context b)))
              "identical tokens still have account-specific cache identities")
          (is (= before (slurp path)))
          (is (= "pi-claude" (:access (credentials/credentials :pi "claude" context))))
          (doseq [id ["anthropic-account-INVALID" "anthropic-account-" "openai-codex" "../secret"
                      (str "anthropic-account-" (apply str (repeat 33 "a")))]]
            (is (= "unsupported-auth-context"
                   (:reason (credentials/credentials :pi "claude" context id)))))
          (is (= "unsupported-auth-context" (:reason (credentials/credentials :claude "claude" context a))))
          (is (= "unsupported-auth-context" (:reason (credentials/credentials :pi "codex" context a))))
          (doseq [[entry reason] [[nil "missing-credentials"]
                                 [(assoc (oauth "same-token") :expires now-ms) "expired-credentials"]
                                 [{:type "api_key" :key "paid-canary"} "not-subscription-auth"]]]
            (put-json! path (if entry (assoc auth (keyword a) entry) (dissoc auth (keyword a))))
            (let [selected (assoc-in context [:env "ANTHROPIC_API_KEY"] "paid-canary")]
              (is (= "ready" (:status (credentials/credentials :pi "claude" selected b))) "sibling control")
              (is (= reason (:reason (credentials/credentials :pi "claude" selected a))))))
          (put-json! path (assoc-in auth [(keyword a) :refresh] "new-refresh"))
          (is (not= (:context-key first-read)
                    (:context-key (credentials/credentials :pi "claude" context a)))))))))

(deftest pi-supported-location-overrides
  (with-context
    (fn [context]
      (seed! context)
      (let [dir (str (fs/path (:home context) "custom"))]
        (put-json! (str (fs/path dir "auth.json")) {:anthropic (oauth "override")})
        (doseq [override [dir "~/custom" "custom" (str (.toUri (fs/path dir)))]]
          (is (= "override" (:access (credentials/credentials :pi "claude"
                                    (assoc-in context [:env "PI_CODING_AGENT_DIR"] override)))) override))
        (is (= "missing-credentials" (:reason (credentials/credentials :pi "codex"
                                              (assoc-in context [:env "PI_CODING_AGENT_DIR"] dir)))))))))

(deftest native-location-overrides
  (with-context
    (fn [context]
      (seed! context)
      (let [dir (str (fs/path (:home context) "override"))]
        (put-json! (str (fs/path dir ".credentials.json")) {:claudeAiOauth {:accessToken "override" :expiresAt later-ms}})
        (put-json! (str (fs/path dir "auth.json")) {:tokens {:access_token (jwt 1800000060) :account_id "override-account"}})
        (is (= "override" (:access (credentials/credentials :claude "claude"
                                    (assoc-in context [:env "CLAUDE_CONFIG_DIR"] dir)))))
        (is (= "native-claude" (:access (credentials/credentials :claude "claude"
                                          (update context :env assoc "CLAUDE_CONFIG_DIR" dir "CLAUDE_SECURESTORAGE_CONFIG_DIR" "")))))
        (is (= "override" (:access (credentials/credentials :claude "claude"
                                    (assoc-in context [:env "CLAUDE_SECURESTORAGE_CONFIG_DIR"] dir)))))
        (is (= "override-account" (:account-id (credentials/credentials :codex "codex"
                                                (assoc-in context [:env "CODEX_HOME"] dir)))))))))

(deftest missing-expired-or-invalid-never-falls-back
  (with-context
    (fn [context]
      (seed! context)
      (is (= "native-claude" (:access (credentials/credentials :claude "claude" context))) "fallback control is present")
      (doseq [[entry reason] [[nil "missing-credentials"]
                              [(assoc (oauth "expired") :expires now-ms) "expired-credentials"]
                              [(assoc (oauth "expired") :expires (dec now-ms)) "expired-credentials"]
                              [(assoc (oauth "bad") :expires "tomorrow") "invalid-credentials"]
                              [(assoc (oauth "bad") :expires 0) "invalid-credentials"]
                              [(assoc (oauth "bad") :refresh nil) "invalid-credentials"]
                              [{:type "api_key" :key "not-subscription"} "not-subscription-auth"]]]
        (put-json! (pi-path context) (if entry {:anthropic entry} {}))
        (is (= {:status "unknown" :reason reason} (credentials/credentials :pi "claude" context))))
      (fs/delete (pi-path context))
      (is (= "missing-credentials" (:reason (credentials/credentials :pi "claude" context))))
      (put-json! (pi-path context) {:anthropic (oauth "pi-valid")})
      (fs/delete (native-claude-path context))
      (is (= "pi-valid" (:access (credentials/credentials :pi "claude" context))) "Pi control is present")
      (is (= "missing-credentials" (:reason (credentials/credentials :claude "claude" context)))))))

(deftest harness-refresh-is-observed-without-writing-the-store
  (with-context
    (fn [context]
      (seed! context)
      (let [path (pi-path context) before (slurp path)
            first-read (credentials/credentials :pi "claude" context)]
        (is (= before (slurp path)))
        (is (= first-read (credentials/credentials :pi "claude" context)))
        (is (re-matches #"[a-f0-9]{64}" (:context-key first-read)))
        (put-json! path {:anthropic (oauth "harness-refreshed")})
        (let [refreshed-by-harness (slurp path) r (credentials/credentials :pi "claude" context)]
          (is (= "harness-refreshed" (:access r)))
          (is (not= (:context-key first-read) (:context-key r)))
          (is (= refreshed-by-harness (slurp path))))
        (put-json! path {:anthropic (assoc (oauth "harness-refreshed") :expires now-ms)})
        (is (= "expired-credentials" (:reason (credentials/credentials :pi "claude" context))))))))

(deftest context-keys-separate-provider-location-and-harness
  (with-context
    (fn [context]
      (seed! context)
      (let [dir (str (fs/path (:home context) "same-token"))
            original (credentials/credentials :pi "claude" context)]
        (put-json! (str (fs/path dir "auth.json")) {:anthropic (oauth "pi-claude")})
        (put-json! (native-claude-path context) {:claudeAiOauth {:accessToken "pi-claude" :expiresAt later-ms}})
        (is (= 4 (count (set (map :context-key
                                [original
                                 (credentials/credentials :pi "codex" context)
                                 (credentials/credentials :pi "claude" (assoc-in context [:env "PI_CODING_AGENT_DIR"] dir))
                                 (credentials/credentials :claude "claude" context)])))))))))

(deftest malformed-stores-have-fixed-reasons
  (with-context
    (fn [context]
      (seed! context)
      (doseq [body ["not JSON secret-canary" "[]" "null" (apply str (repeat 1048577 "x"))]]
        (spit (pi-path context) body)
        (let [r (credentials/credentials :pi "claude" context)]
          (is (= "unknown" (:status r)))
          (is (#{"unreadable-credentials" "invalid-credentials"} (:reason r)))
          (is (= #{:status :reason} (set (keys r)))))))))

(deftest unavailable-os-stores-never-read-a-file-fallback
  (with-context
    (fn [context]
      (seed! context)
      (is (= "ready" (:status (credentials/credentials :claude "claude" context))) "Linux file control")
      (is (= {:status "unknown" :reason "os-store-unavailable"}
             (credentials/credentials :claude "claude" (assoc context :os "Mac OS X"))))
      (let [config (str (fs/path (:home context) ".codex/config.toml"))]
        (doseq [mode ["keyring" "auto" "ephemeral"]]
          (spit config (str "cli_auth_credentials_store = \"" mode "\"\n"))
          (is (= {:status "unknown" :reason "os-store-unavailable"}
                 (credentials/credentials :codex "codex" context))))
        (doseq [body ["cli_auth_credentials_store = \"file\"\n"
                      "# cli_auth_credentials_store = \"keyring\"\nmodel = \"fixture\"\n"
                      "cli_auth_credentials_store = 'file' # comment\n[projects]\n"
                      "[projects]\nsetting = \"value\"\n"]]
          (spit config body)
          (is (= "ready" (:status (credentials/credentials :codex "codex" context))) body))
        (doseq [body ["cli_auth_credentials_store = \"file\"\ncli_auth_credentials_store = \"auto\""
                      "[profiles.work]\ncli_auth_credentials_store = \"keyring\""
                      "'cli_auth_credentials_store' = 'keyring'"
                      "unrelated = '''\nexample\n'''\ncli_auth_credentials_store = 'file'"
                      "cli_auth_credentials_store = 'invalid'"]]
          (spit config body)
          (is (= "unsupported-auth-context" (:reason (credentials/credentials :codex "codex" context))) body))))))

(deftest native-expiry-and-auth-mode-validation
  (with-context
    (fn [context]
      (seed! context)
      (doseq [[data reason] [[{:auth_mode "apikey" :tokens {:access_token (jwt 1800000060) :account_id "account"}} "not-subscription-auth"]
                             [{:OPENAI_API_KEY "key" :tokens {:access_token (jwt 1800000060) :account_id "account"}} "not-subscription-auth"]
                             [{:tokens {:access_token (jwt 1800000000) :account_id "account"}} "expired-credentials"]
                             [{:tokens {:access_token "invalid" :account_id "account"}} "invalid-credentials"]
                             [{:tokens {:access_token (jwt 1800000060)}} "invalid-credentials"]]]
        (put-json! (native-codex-path context) data)
        (is (= {:status "unknown" :reason reason} (credentials/credentials :codex "codex" context)))))))

(deftest reads-cannot-refresh-or-contact-the-network
  (with-context
    (fn [context]
      (seed! context)
      (let [calls (atom [])
            tripwire (fn [& _] (swap! calls conj :escape) (throw (ex-info "fixture tripwire" {})))]
        ;; Establish that the guard fails on known-bad operations before trusting it.
        (with-redefs [process/process tripwire http/get tripwire http/post tripwire]
          (is (thrown? Exception (process/process ["claude" "doctor"])))
          (is (thrown? Exception (http/get "http://127.0.0.1:1")))
          (is (thrown? Exception (http/post "http://127.0.0.1:1")))
          (is (= 3 (count @calls)))
          (doseq [[h p] [[:pi "claude"] [:pi "codex"] [:claude "claude"] [:codex "codex"]]]
            (is (= "ready" (:status (credentials/credentials h p context)))))
          (is (= 3 (count @calls))))))))
