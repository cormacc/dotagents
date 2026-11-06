(ns herdr-orch.quota-credentials
  "Read-only, harness-specific subscription credentials.

  Results are private collector inputs, never public evidence or cache payloads.
  No read starts a process, performs HTTP, refreshes or writes credentials."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.text Normalizer Normalizer$Form]
           [java.util Base64]))

(def credential-size-limit 1048576)

(defn- text? [s] (and (string? s) (not (str/blank? s))))
(defn- finite? [n] (and (number? n) (Double/isFinite (double n))))
(defn- digest [s]
  (apply str (map #(format "%02x" (bit-and % 255))
                  (.digest (MessageDigest/getInstance "SHA-256")
                           (.getBytes s StandardCharsets/UTF_8)))))
(defn- unknown [reason] {:status "unknown" :reason reason})

(defn environment
  "Return the current process environment and host platform for credential selection."
  []
  {:env (into {} (System/getenv)) :os (System/getProperty "os.name")
   :home (or (not-empty (System/getenv "HOME")) (System/getProperty "user.home"))
   :cwd (str (fs/cwd)) :now-ms (System/currentTimeMillis)})

(defn- absolute [path cwd]
  (str (fs/normalize (if (fs/absolute? path) (fs/path path) (fs/path cwd path)))))

(defn- pi-directory [{:keys [env home cwd]}]
  (let [path (or (not-empty (get env "PI_CODING_AGENT_DIR")) (str (fs/path home ".pi/agent")))
        path (cond
               (= path "~") home
               (str/starts-with? path "~/") (str (fs/path home (subs path 2)))
               (str/starts-with? path "file://") (str (fs/path (URI. path)))
               :else path)]
    (absolute path cwd)))

(defn- read-json [path]
  (try
    (if-not (fs/exists? path)
      (unknown "missing-credentials")
      (if (or (not (fs/regular-file? path)) (> (fs/size path) credential-size-limit))
        (unknown "unreadable-credentials")
        (let [s (slurp path)
              data (json/parse-string (str/replace-first s "\uFEFF" "") true)]
          (if (map? data) {:data data} (unknown "invalid-credentials")))))
    (catch Exception _ (unknown "unreadable-credentials"))))

(defn- jwt-expiry [token]
  (try
    (let [payload (second (str/split token #"\."))
          claims (json/parse-string (String. (.decode (Base64/getUrlDecoder) payload)
                                            StandardCharsets/UTF_8) true)
          exp (:exp claims)]
      (when (and (finite? exp) (pos? exp)) (* exp 1000)))
    (catch Exception _ nil)))

(defn- ready [harness provider path access expires account now-ms]
  (cond
    (or (not (text? access)) (not (finite? expires)) (not (pos? expires))) (unknown "invalid-credentials")
    (<= expires now-ms) (unknown "expired-credentials")
    (and (= provider "codex") (not (text? account))) (unknown "invalid-credentials")
    :else (cond-> {:status "ready" :access access :expires expires
                   :context-key (digest (pr-str [harness provider path access expires account]))}
            (text? account) (assoc :account-id account))))

(defn pi-account?
  "True only for the supported named Pi Anthropic account namespace."
  [id]
  (and (string? id)
       (boolean (re-matches #"anthropic-account-[a-z][a-z0-9_-]{0,31}" id))))

(defn pi-accounts
  "Return sorted supported account identities, without credentials."
  [context]
  (->> (read-json (str (fs/path (pi-directory context) "auth.json")))
       :data keys (map name) (filter pi-account?) sort vec))

(defn- pi-credentials [provider context account]
  (let [path (str (fs/path (pi-directory context) "auth.json"))
        result (read-json path)
        identity (or account (case provider "claude" "anthropic" "codex" "openai-codex"))
        entry (get (:data result) (keyword identity))]
    (cond
      (:reason result) result
      (nil? entry) (unknown "missing-credentials")
      (not= "oauth" (:type entry)) (unknown "not-subscription-auth")
      (not (string? (:refresh entry))) (unknown "invalid-credentials")
      :else (let [result (ready :pi provider path (:access entry) (:expires entry)
                               (:accountId entry) (:now-ms context))]
              (if (= "ready" (:status result))
                (assoc result :context-key
                       (digest (pr-str [(:context-key result) identity
                                        (select-keys entry [:access :refresh :expires :accountId])])))
                result)))))

(defn- claude-credentials [{:keys [env os home cwd now-ms]}]
  (cond
    ;; Reading Keychain through `security` can prompt even with a process timeout.
    (= os "Mac OS X") (unknown "os-store-unavailable")
    (some #(text? (get env %)) ["CLAUDE_CODE_OAUTH_TOKEN" "CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR"
                               "ANTHROPIC_API_KEY" "ANTHROPIC_AUTH_TOKEN"])
    (unknown "unsupported-auth-context")
    :else
    (let [secure (get env "CLAUDE_SECURESTORAGE_CONFIG_DIR")
          dir (if (some? secure) (or (not-empty secure) (str (fs/path home ".claude")))
                  (or (get env "CLAUDE_CONFIG_DIR") (str (fs/path home ".claude"))))
          dir (Normalizer/normalize dir Normalizer$Form/NFC)
          path (str (fs/path (absolute dir cwd) ".credentials.json"))
          result (read-json path)
          entry (:claudeAiOauth (:data result))]
      (if (:reason result) result
          (ready :claude "claude" path (:accessToken entry) (:expiresAt entry) nil now-ms)))))

(defn- codex-storage-mode [dir]
  ;; Fail closed on richer configuration instead of guessing which store won.
  ;; known-limit: CLI/profile/managed overrides need an effective-config reader.
  (let [path (str (fs/path dir "config.toml"))]
    (try
      (if-not (fs/exists? path) "file"
        (if (> (fs/size path) credential-size-limit) nil
          (let [lines (str/split-lines (slurp path))
                root (take-while #(not (str/starts-with? (str/trim %) "[")) lines)
                mentions (filter #(and (not (str/starts-with? (str/trim %) "#"))
                                       (str/includes? % "cli_auth_credentials_store")) lines)
                modes (keep #(second (re-matches #"\s*cli_auth_credentials_store\s*=\s*[\"'](file|keyring|auto|ephemeral)[\"']\s*(?:#.*)?" %)) root)]
            (cond
              (and (empty? mentions) (not-any? #(re-find #"\"\"\"|'''" %) lines)) "file"
              (and (= 1 (count mentions)) (= 1 (count modes))
                   (not-any? #(re-find #"\"\"\"|'''" %) lines)) (first modes)
              :else nil))))
      (catch Exception _ nil))))

(defn- codex-credentials [{:keys [env home cwd now-ms]}]
  (let [dir (absolute (or (not-empty (get env "CODEX_HOME")) (str (fs/path home ".codex"))) cwd)
        mode (codex-storage-mode dir)]
    (cond
      (nil? mode) (unknown "unsupported-auth-context")
      (not= mode "file") (unknown "os-store-unavailable")
      (some #(text? (get env %)) ["CODEX_API_KEY" "OPENAI_API_KEY"])
      (unknown "unsupported-auth-context")
      :else
      (let [path (str (fs/path dir "auth.json")) result (read-json path)
            data (:data result) entry (:tokens data)]
        (cond
          (:reason result) result
          (or (text? (:OPENAI_API_KEY data))
              (and (:auth_mode data) (not= "chatgpt" (:auth_mode data)))) (unknown "not-subscription-auth")
          :else (ready :codex "codex" path (:access_token entry) (jwt-expiry (:access_token entry))
                       (:account_id entry) now-ms))))))

(defn credentials
  "Read PROVIDER (claude or codex) from HARNESS (:pi, :claude or :codex).
  Default context comes from `environment`; an explicit context must supply all
  its fields. Return private ready inputs with a credential-generation digest,
  or a fixed unknown reason. Optional ACCOUNT is a validated named Pi Anthropic
  identity; nil selects the base credential. Never fall back or prompt."
  ([harness provider] (credentials harness provider (environment)))
  ([harness provider context] (credentials harness provider context nil))
  ([harness provider context account]
   (try
     (cond
       (not (#{"claude" "codex"} provider)) (unknown "unsupported-provider")
       (and account (not (and (= harness :pi) (= provider "claude")
                              (pi-account? account)))) (unknown "unsupported-auth-context")
       (= harness :pi) (pi-credentials provider context account)
       (and (= harness :claude) (= provider "claude")) (claude-credentials context)
       (and (= harness :codex) (= provider "codex")) (codex-credentials context)
       :else (unknown "unsupported-auth-context"))
     (catch Exception _ (unknown "unreadable-credentials")))))
