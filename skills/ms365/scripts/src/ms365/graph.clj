(ns ms365.graph
  "Write side of the ms365 skill: a Microsoft Graph client for editing SharePoint files.

  The ms365 MCP server stays read-only. This client signs in separately (same Entra app)
  and offers a fixed set of operations whose guardrails are in code:
  - check-out refuses a file that another user has checked out;
  - upload, check-in, discard and Excel writes need a check-out held by the signed-in user;
  - check-in publishes a major version only when `publish?` is exactly true;
  - a transaction discards its check-out when any step after the check-out fails.

  Errors are ex-info with ::code one of :usage :refused :graph :auth."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.net URLDecoder URLEncoder]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]))

(def graph-root "https://graph.microsoft.com/v1.0")
(def scope "openid profile offline_access https://graph.microsoft.com/Files.ReadWrite.All")
(def simple-upload-limit
  "Files up to this size go in one PUT; larger files use an upload session."
  (* 4 1024 1024))
(def chunk-size
  "Upload session chunk size; Graph requires a multiple of 320 KiB."
  (* 16 327680))

(def ^:dynamic *config-dir* (str (fs/expand-home "~/.config/ms365-graph")))
(def ^:dynamic *http*
  "Function from a babashka.http-client request map to a response map. Tests rebind it."
  (fn [req] (http/request (assoc req :throw false))))
(def ^:dynamic *now* (fn [] (quot (System/currentTimeMillis) 1000)))
(def ^:dynamic *open-browser*
  (fn [url] (p/process [(if (str/includes? (str/lower-case (System/getProperty "os.name")) "mac") "open" "xdg-open") url])))
(def ^:dynamic *log* (fn [& xs] (binding [*out* *err*] (apply println xs))))
(def ^:dynamic *sleep* (fn [ms] (Thread/sleep ms)))
(def save-wait-ms
  "How long to wait for the Excel service to save a closed session's changes to the file."
  60000)

(defn fail
  "Throw an ex-info with ::code (one of :usage :refused :graph :auth)."
  [code msg & [data]]
  (throw (ex-info msg (merge {::code code} data))))

;; ------------------------------------------------------------------ encoding helpers

(defn b64url [^bytes b] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))
(defn random-string [] (let [b (byte-array 32)] (.nextBytes (SecureRandom.) b) (b64url b)))
(defn pkce-challenge
  "S256 code challenge of a PKCE verifier (RFC 7636)."
  [^String verifier]
  (b64url (.digest (MessageDigest/getInstance "SHA-256") (.getBytes verifier "US-ASCII"))))
(defn url-encode [s] (str/replace (URLEncoder/encode (str s) "UTF-8") "+" "%20"))
(defn query-string [m] (str/join "&" (for [[k v] m] (str (url-encode k) "=" (url-encode v)))))
(defn parse-query [s]
  (into {} (for [kv (str/split (or s "") #"&") :when (seq kv)
                 :let [[k v] (str/split kv #"=" 2)]]
             [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")])))
(defn jwt-claims
  "Payload claims of a JWT, without signature verification (the token came from the token
  endpoint over TLS)."
  [jwt]
  (when-let [part (second (str/split (str jwt) #"\."))]
    (let [pad (apply str (repeat (mod (- 4 (mod (count part) 4)) 4) "="))]
      (json/parse-string (String. (.decode (Base64/getUrlDecoder) (str part pad)) "UTF-8") true))))
(defn share-id
  "Graph sharing id for a SharePoint or OneDrive URL (`/shares/{id}/driveItem`)."
  [url]
  (str "u!" (b64url (.getBytes (str url) "UTF-8"))))

;; ------------------------------------------------------------------ configuration and tokens

(defn config
  "Tenant and client ids: MS365_MCP_TENANT_ID / MS365_MCP_CLIENT_ID, else config.edn."
  []
  (let [f (str (fs/path *config-dir* "config.edn"))
        from-file (when (fs/exists? f) (edn/read-string (slurp f)))
        c {:tenant (or (System/getenv "MS365_MCP_TENANT_ID") (:tenant from-file))
           :client (or (System/getenv "MS365_MCP_CLIENT_ID") (:client from-file))}]
    (when-not (and (:tenant c) (:client c))
      (fail :auth (str "no tenant or client id: set MS365_MCP_TENANT_ID and MS365_MCP_CLIENT_ID, or run "
                       "`login --tenant ID --client ID` once")))
    c))

(defn write-private!
  "Write s to path with mode 600, creating the file with that mode before any content."
  [path s]
  (fs/create-dirs (fs/parent path))
  (fs/set-posix-file-permissions (fs/parent path) "rwx------")
  (when-not (fs/exists? path) (fs/create-file path {:posix-file-permissions "rw-------"}))
  (fs/set-posix-file-permissions path "rw-------")
  (spit path s))

(defn save-config! [tenant client]
  (write-private! (str (fs/path *config-dir* "config.edn")) (pr-str {:tenant tenant :client client})))

(defn token-path [] (str (fs/path *config-dir* "token.json")))

(defn read-token [] (when (fs/exists? (token-path)) (json/parse-string (slurp (token-path)) true)))

(defn store-token!
  "Store a token endpoint response. The refresh token replaces the previous one (Entra
  issues a new one on each use). The user comes from the ID token, else is kept."
  [resp]
  (let [claims (jwt-claims (:id_token resp))
        user (if claims
               {:id (:oid claims) :name (:name claims) :upn (or (:preferred_username claims) (:upn claims))}
               (:user (read-token)))
        t {:access_token (:access_token resp)
           :refresh_token (:refresh_token resp)
           :expires_at (+ (*now*) (or (:expires_in resp) 0) -120)
           :user user}]
    (write-private! (token-path) (json/generate-string t))
    t))

(defn- token-endpoint [{:keys [tenant]} kind]
  (str "https://login.microsoftonline.com/" tenant "/oauth2/v2.0/" kind))

(defn token-request
  "POST to the token endpoint; store and return the token. Throws :auth on an error."
  [params]
  (let [c (config)
        r (*http* {:method :post :uri (token-endpoint c "token")
                   :headers {"Content-Type" "application/x-www-form-urlencoded"}
                   :body (query-string (merge {"client_id" (:client c) "scope" scope} params))})
        body (try (json/parse-string (str (:body r)) true) (catch Exception _ {:error "invalid_response"}))]
    (when-not (= 200 (:status r))
      (fail :auth (str "token request failed: " (:error body) ": " (first (str/split-lines (str (:error_description body)))))
            {:error (:error body)}))
    (store-token! body)))

(defn login-browser
  "Interactive sign-in: authorization code with PKCE through a loopback redirect."
  []
  (require 'org.httpkit.server)
  (let [run-server (resolve 'org.httpkit.server/run-server)
        server-port (resolve 'org.httpkit.server/server-port)
        server-stop! (resolve 'org.httpkit.server/server-stop!)
        c (config) verifier (random-string) state (random-string) result (promise)
        server (run-server (fn [req]
                             (when (= "/callback" (:uri req)) (deliver result (parse-query (:query-string req))))
                             {:status 200 :headers {"Content-Type" "text/plain"}
                              :body "Signed in to the ms365 Graph client. You can close this tab."})
                           {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        redirect (str "http://localhost:" (server-port server) "/callback")
        url (str (token-endpoint c "authorize") "?"
                 (query-string {"client_id" (:client c) "response_type" "code" "redirect_uri" redirect
                                "response_mode" "query" "scope" scope "state" state
                                "code_challenge" (pkce-challenge verifier) "code_challenge_method" "S256"}))]
    (*log* "Opening the browser for sign-in. If it does not open, visit:\n" url)
    (*open-browser* url)
    (let [q (deref result 300000 nil)]
      (server-stop! server)
      (cond
        (nil? q) (fail :auth "sign-in timed out after 5 minutes")
        (not= state (get q "state")) (fail :auth "sign-in state mismatch")
        (get q "error") (fail :auth (str "sign-in failed: " (get q "error") ": " (get q "error_description")))
        :else (token-request {"grant_type" "authorization_code" "code" (get q "code")
                              "redirect_uri" redirect "code_verifier" verifier})))))

(defn login-device
  "Sign-in without a local browser: device code flow."
  []
  (let [c (config)
        r (*http* {:method :post :uri (token-endpoint c "devicecode")
                   :headers {"Content-Type" "application/x-www-form-urlencoded"}
                   :body (query-string {"client_id" (:client c) "scope" scope})})
        dc (json/parse-string (str (:body r)) true)]
    (when-not (= 200 (:status r)) (fail :auth (str "device code request failed: " (:error dc))))
    (*log* (:message dc))
    (loop [interval (or (:interval dc) 5) waited 0]
      (when (> waited (or (:expires_in dc) 900)) (fail :auth "device code expired"))
      (Thread/sleep (* 1000 interval))
      (let [t (try (token-request {"grant_type" "urn:ietf:params:oauth:grant-type:device_code"
                                   "device_code" (:device_code dc)})
                   (catch clojure.lang.ExceptionInfo e
                     (case (:error (ex-data e))
                       "authorization_pending" ::pending
                       "slow_down" ::slow
                       (throw e))))]
        (case t
          ::pending (recur interval (+ waited interval))
          ::slow (recur (+ interval 5) (+ waited interval))
          t)))))

(defn access-token
  "A valid access token, renewed silently with the refresh token when expired."
  []
  (let [t (or (read-token) (fail :auth "not signed in: run `login`"))]
    (if (< (*now*) (:expires_at t))
      (:access_token t)
      (:access_token (token-request {"grant_type" "refresh_token" "refresh_token" (:refresh_token t)})))))

(defn me "The signed-in user {:id :name :upn}." []
  (or (:user (read-token)) (fail :auth "not signed in: run `login`")))

;; ------------------------------------------------------------------ Graph requests

(defn graph-error-message [body]
  (let [e (:error body)] (str (:code e) ": " (:message e))))

(defn request
  "Graph request; returns the parsed JSON body (or nil). Throws :graph on a status >= 400.
  :bytes sends a raw body; :as :bytes returns raw bytes."
  [method path & {:keys [body bytes headers as]}]
  (let [r (*http* {:method method :uri (if (str/starts-with? path "http") path (str graph-root path))
                   :headers (merge {"Authorization" (str "Bearer " (access-token))}
                                   (when body {"Content-Type" "application/json"})
                                   headers)
                   :body (cond bytes bytes body (json/generate-string body))
                   :as (or as :string)})
        status (:status r)]
    (if (and status (< status 400))
      (if (= as :bytes) (:body r) (when (seq (:body r)) (json/parse-string (:body r) true)))
      (let [b (try (json/parse-string (str (:body r)) true) (catch Exception _ {}))]
        (fail :graph (str "Graph " (str/upper-case (name method)) " " path " -> " status " " (graph-error-message b))
              {:status status :graph-error (get-in b [:error :code])})))))

;; ------------------------------------------------------------------ items

(defn resolve-item
  "An item reference {:drive :item} from a SharePoint URL or \"DRIVE:ITEM\"."
  [ref]
  (cond
    (str/starts-with? (str ref) "http")
    (let [it (request :get (str "/shares/" (share-id ref) "/driveItem?$select=id,name,parentReference"))]
      {:drive (get-in it [:parentReference :driveId]) :item (:id it)})
    (re-matches #"[^:\s]+:[^:\s]+" (str ref))
    (let [[d i] (str/split ref #":")] {:drive d :item i})
    :else (fail :usage (str "item must be a SharePoint URL or DRIVE:ITEM, got " (pr-str ref)))))

(defn item-path [{:keys [drive item]}] (str "/drives/" drive "/items/" item))

(defn status
  "Name, version, publication level, check-out holder and version list of an item."
  [it]
  (let [i (request :get (str (item-path it) "?$select=name,size,webUrl,lastModifiedDateTime,publication"))
        vs (request :get (str (item-path it) "/versions?$select=id"))
        holder (get-in i [:publication :checkedOutBy :user])]
    {:name (:name i) :url (:webUrl i) :size (:size i)
     :version (get-in i [:publication :versionId])
     :level (get-in i [:publication :level])
     :checked_out_by (when holder {:id (:id holder) :name (:displayName holder) :email (:email holder)})
     :versions (mapv :id (:value vs))}))

(defn mine? [st user]
  (let [h (:checked_out_by st)]
    (boolean (and h (or (and (:id h) (= (:id h) (:id user)))
                        (and (:email h) (:upn user) (= (str/lower-case (:email h)) (str/lower-case (:upn user)))))))))

(defn require-mine! [st action]
  (cond
    (nil? (:checked_out_by st))
    (fail :refused (str action " refused: " (:name st) " is not checked out. Run `checkout` first."))
    (not (mine? st (me)))
    (fail :refused (str action " refused: " (:name st) " is checked out to " (get-in st [:checked_out_by :name]) "."))))

(defn checkout!
  "Check out the item. Refuses a check-out held by another user; a check-out already held
  by the signed-in user is reported, not repeated."
  [it]
  (let [st (status it)]
    (cond
      (mine? st (me)) {:already_checked_out true :status st}
      (:checked_out_by st) (fail :refused (str "checkout refused: " (:name st) " is checked out to "
                                               (get-in st [:checked_out_by :name]) "."))
      :else (do (request :post (str (item-path it) "/checkout")) {:status (status it)}))))

(defn- upload-session! [it ^bytes data]
  (let [s (request :post (str (item-path it) "/createUploadSession")
                   :body {:item {"@microsoft.graph.conflictBehavior" "replace"}})
        url (:uploadUrl s) n (alength data)]
    (loop [start 0 last-resp nil]
      (if (>= start n)
        last-resp
        (let [end (min n (+ start chunk-size))
              chunk (java.util.Arrays/copyOfRange data start end)
              ;; The upload URL is pre-authenticated: send no Authorization header.
              r (*http* {:method :put :uri url :body chunk :as :string
                         :headers {"Content-Range" (str "bytes " start "-" (dec end) "/" n)}})]
          (when-not (#{200 201 202} (:status r))
            (fail :graph (str "upload session chunk " start "-" (dec end) " -> " (:status r))))
          (recur end r))))))

(defn upload!
  "Replace the content of an item that the signed-in user has checked out."
  [it file]
  (let [st (status it)
        _ (require-mine! st "upload")
        data (fs/read-all-bytes file)]
    (if (<= (alength data) simple-upload-limit)
      (request :put (str (item-path it) "/content") :bytes data :headers {"Content-Type" "application/octet-stream"})
      (upload-session! it data))
    {:uploaded_bytes (alength data) :session (> (alength data) simple-upload-limit) :status (status it)}))

(defn download!
  "Write the current content (or :version) of the item to file."
  [it file & {:keys [version]}]
  (let [path (str (item-path it) (when version (str "/versions/" version)) "/content")
        data (request :get path :as :bytes)]
    (fs/create-dirs (fs/parent (fs/absolutize file)))
    (with-open [o (java.io.FileOutputStream. (str file))] (.write o ^bytes data))
    {:file (str file) :bytes (alength ^bytes data) :version (or version :current)}))

(defn checkin!
  "Check in a check-out held by the signed-in user. A comment is required. Publishes a
  major version only when publish? is exactly true."
  [it comment publish?]
  (when (str/blank? comment) (fail :usage "checkin needs a non-blank comment"))
  (let [st (status it)]
    (require-mine! st "checkin")
    (request :post (str (item-path it) "/checkin")
             :body (cond-> {:comment comment} (true? publish?) (assoc :checkInAs "published")))
    {:published (true? publish?) :status (status it)}))

(defn discard!
  "Discard a check-out held by the signed-in user, and the changes made during it."
  [it]
  (let [st (status it)]
    (require-mine! st "discard")
    (request :post (str (item-path it) "/discardCheckout"))
    {:status (status it)}))

;; ------------------------------------------------------------------ Excel

(defn- range-path [it sheet address]
  (str (item-path it) "/workbook/worksheets/" (url-encode sheet) "/range(address='" address "')"))

(defn- blank-cell? [v] (or (nil? v) (= "" v)))

(defn excel-write!
  "Write a 2-D vector of values to sheet!address in a workbook checked out to the signed-in
  user. Refuses non-empty target cells unless overwrite?. Runs in a workbook session
  (persistChanges), because requests without a session read back stale data. String cells
  get the text number format before the values are written, so Excel does not convert them
  (e.g. \"Oct 2026\" to a date serial); a format set after the value does not undo the
  conversion. Reads the range back in the session and throws :graph on a mismatch.
  The Excel service saves the changes to the file a few seconds after the session closes,
  so the function returns only when the item's cTag has changed (a check-in before that
  captures the file without the write); throws :graph after save-wait-ms."
  [it sheet address values & {:keys [overwrite?]}]
  (let [st (status it)
        _ (require-mine! st "excel-write")
        ctag #(:cTag (request :get (str (item-path it) "?$select=cTag")))
        ctag0 (ctag)
        changed? (atom false)
        wb (str (item-path it) "/workbook")
        sid (:id (request :post (str wb "/createSession") :body {:persistChanges true}))
        h {"workbook-session-id" sid}
        rp (range-path it sheet address)
        read-values #(:values (request :get (str rp "?$select=values") :headers h))]
    (try
      (let [current (read-values)
            _ (when (not= (map count current) (map count values))
                (fail :usage (str "values shape " (mapv count values) " does not match " address " " (mapv count current))))
            occupied (for [[r row] (map-indexed vector current) [c v] (map-indexed vector row)
                           :when (not (blank-cell? v))] [r c v])]
        (when (and (seq occupied) (not overwrite?))
          (fail :refused (str "excel-write refused: " address " has non-empty cells " (vec (take 5 occupied))
                              "; pass --overwrite to replace them")))
        (when (some string? (flatten values))
          (request :patch rp :headers h
                   :body {:numberFormat (mapv (fn [row] (mapv #(if (string? %) "@" nil) row)) values)}))
        (reset! changed? (not= current values))
        (request :patch rp :headers h :body {:values values})
        (let [back (read-values)]
          (when (not= back values)
            (fail :graph (str "read-back mismatch at " address ": wrote " (pr-str values) ", read " (pr-str back))))))
      (finally
        (try (request :post (str wb "/closeSession") :headers h) (catch Exception _ nil))))
    (when @changed?
      (loop [waited 0]
        (when (= ctag0 (ctag))
          (when (>= waited save-wait-ms)
            (fail :graph (str "the workbook changes were not saved to the file within " (quot save-wait-ms 1000) " s")))
          (*sleep* 2000)
          (recur (+ waited 2000)))))
    {:written values :status (status it)}))

;; ------------------------------------------------------------------ transaction

(defn transaction!
  "Check out, apply one operation, check in as a minor version (or published when publish?
  is exactly true). The item must not be checked out by anyone at the start. On any failure
  after the check-out, the check-out is discarded and the failure rethrown.
  op is {:exec \"cmd with {in} {out}\"} (download, run, upload) or
  {:excel [sheet address values overwrite?]}."
  [it comment op & {:keys [publish?]}]
  (when (str/blank? comment) (fail :usage "transaction needs a non-blank comment"))
  (let [st (status it)]
    (when (:checked_out_by st)
      (fail :refused (str "transaction refused: " (:name st) " is already checked out to "
                          (get-in st [:checked_out_by :name]) "; use the session commands")))
    (request :post (str (item-path it) "/checkout"))
    (try
      (let [result
            (cond
              (:exec op)
              (let [dir (str (fs/create-temp-dir {:prefix "ms365-graph-"}))
                    in (str (fs/path dir (str "in-" (:name st)))) out (str (fs/path dir (str "out-" (:name st))))
                    _ (download! it in)
                    cmd (-> (:exec op) (str/replace "{in}" (pr-str in)) (str/replace "{out}" (pr-str out)))
                    r (p/shell {:continue true :out :string :err :string} "bash" "-c" cmd)]
                (when-not (zero? (:exit r)) (fail :usage (str "exec failed (exit " (:exit r) "): " (str/trim (:err r)))))
                (when-not (fs/exists? out) (fail :usage "exec did not write {out}"))
                (let [u (upload! it out)] (fs/delete-tree dir) {:exec_output (str/trim (:out r)) :uploaded_bytes (:uploaded_bytes u)}))
              (:excel op)
              (let [[sheet address values overwrite?] (:excel op)]
                (excel-write! it sheet address values :overwrite? overwrite?))
              :else (fail :usage "transaction needs --exec or --excel"))]
        (assoc (checkin! it comment publish?) :operation result))
      (catch Exception e
        (let [discarded (try (request :post (str (item-path it) "/discardCheckout")) true (catch Exception _ false))]
          (throw (ex-info (str (ex-message e) (if discarded " (check-out discarded)" " (DISCARD FAILED: the item is still checked out)"))
                          (merge {::code :graph} (ex-data e) {:discarded discarded}))))))))
