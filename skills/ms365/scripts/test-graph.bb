#!/usr/bin/env bb
;; Tests for the ms365 Graph client against an in-memory fake of Graph and the token
;; endpoint (no network). GRAPH_TEST_SELFCHECK=1 adds one assertion that must fail.
(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing run-tests]])

(cp/add-classpath (str (fs/path (fs/parent (fs/absolutize *file*)) "src")))
(require '[ms365.graph :as g])

(def me {:id "user-me" :name "Me Myself" :upn "me@example.com"})
(def other {:id "user-other" :displayName "Other Person" :email "other@example.com"})

(defn fresh-state []
  {:item {:name "Test.docx" :version "1.0" :level "published" :holder nil
          :content (.getBytes "v1.0" "UTF-8") :versions ["1.0"]}
   :cells {"A1:C1" [["" "" ""]] "A2:B2" [["x" ""]]}
   :log []})

(def state (atom nil))

(defn- json-resp [status body] {:status status :body (if body (json/generate-string body) "")})

(defn- next-minor [v] (let [[a b] (str/split v #"\.")] (str a "." (inc (parse-long b)))))
(defn- next-major [v] (str (inc (parse-long (first (str/split v #"\.")))) ".0"))

(defn fake-graph
  "Handle one request against @state. Models check-out versioning as the spike observed it."
  [{:keys [method uri body headers] :as req}]
  (swap! state update :log conj {:method method :uri uri :headers headers
                                 :body (when (string? body) body)})
  (let [path (str/replace uri g/graph-root "")
        it (:item @state)
        holder (:holder it)]
    (cond
      (str/includes? uri "/oauth2/v2.0/token")
      (json-resp 200 {:access_token "new-access" :refresh_token "new-refresh" :expires_in 3600})

      (and (= :get method) (re-find #"/items/I1\?\$select" path))
      (json-resp 200 {:name (:name it) :size (count (:content it)) :webUrl "https://example/Test.docx"
                      :cTag (str "c:{X}," (:ctag @state 1))
                      :publication (cond-> {:level (:level it) :versionId (:version it)}
                                     holder (assoc :checkedOutBy {:user holder}))})

      (and (= :get method) (re-find #"/items/I1/versions" path))
      (json-resp 200 {:value (mapv (fn [v] {:id v}) (:versions it))})

      (and (= :post method) (str/ends-with? path "/items/I1/checkout"))
      (let [v (next-minor (:version it))]
        (swap! state update :item assoc :holder {:id (:id me) :displayName (:name me) :email (:upn me)}
               :level "checkout" :base (:version it) :version v :versions (into [v] (:versions it)))
        (json-resp 204 nil))

      (and (= :get method) (str/ends-with? path "/items/I1/content"))
      {:status 200 :body (:content it)}

      (and (= :put method) (str/ends-with? path "/items/I1/content"))
      (do (swap! state assoc-in [:item :content] body) (json-resp 200 {:name (:name it)}))

      (and (= :post method) (str/ends-with? path "/items/I1/createUploadSession"))
      (json-resp 200 {:uploadUrl "https://upload.example/session-1"})

      (str/starts-with? uri "https://upload.example/")
      (do (swap! state update :chunks (fnil conj []) (get headers "Content-Range"))
          (json-resp 202 {}))

      (and (= :post method) (str/ends-with? path "/items/I1/checkin"))
      (let [b (json/parse-string body true)
            v (if (= "published" (:checkInAs b)) (next-major (:base it)) (:version it))]
        (swap! state update :item assoc :holder nil :level (if (= "published" (:checkInAs b)) "published" "draft")
               :version v :versions (into [v] (rest (:versions it))))
        (json-resp 204 nil))

      (and (= :post method) (str/ends-with? path "/items/I1/discardCheckout"))
      (do (swap! state update :item (fn [i] (assoc i :holder nil :version (:base i) :versions (vec (rest (:versions i)))
                                                     :level (if (str/ends-with? (:base i) ".0") "published" "draft"))))
          (json-resp 204 nil))

      (str/ends-with? path "/workbook/createSession")
      (do (swap! state update :sessions (fnil inc 0)) (json-resp 201 {:id "session-1"}))

      (str/ends-with? path "/workbook/closeSession")
      (do (swap! state update :closed (fnil inc 0))
          ;; The Excel service saves a session's value changes to the file after it closes.
          (when (and (:dirty @state) (not (:no-save @state)))
            (swap! state #(-> % (update :ctag (fnil inc 1)) (assoc :dirty false))))
          (json-resp 204 nil))

      (re-find #"/workbook/worksheets/.+/range\(address='([^']+)'\)" path)
      (let [addr (second (re-find #"range\(address='([^']+)'\)" path))]
        (case method
          :get (json-resp 200 {:values (get-in @state [:cells addr])})
          :patch (let [b (json/parse-string body)]
                   (when (contains? b "values")
                     (swap! state #(-> % (assoc-in [:cells addr] (if (:corrupt %) [["wrong"]] (get b "values")))
                                       (assoc :dirty true))))
                   (json-resp 200 {}))))

      :else (json-resp 404 {:error {:code "itemNotFound" :message (str "no fake for " method " " path)}}))))

(defmacro with-fake [& body]
  `(let [dir# (str (fs/create-temp-dir {:prefix "graph-test-"}))]
     (reset! state (fresh-state))
     (binding [g/*config-dir* dir#
               g/*http* fake-graph
               g/*now* (constantly 1000)
               g/*log* (fn [& _#])
               g/*sleep* (fn [_#] nil)]
       (g/save-config! "tenant-x" "client-y")
       (g/write-private! (g/token-path) (json/generate-string {:access_token "a" :refresh_token "r"
                                                               :expires_at 999999 :user me}))
       (try ~@body (finally (fs/delete-tree dir#))))))

(def it1 {:drive "D1" :item "I1"})
(defn code-of [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (::g/code (ex-data e)))))
(defn requests [method suffix] (filter #(and (= method (:method %)) (str/includes? (:uri %) suffix)) (:log @state)))
(defn hold! [who] (swap! state assoc-in [:item :holder] who))

;; ------------------------------------------------------------------ pure helpers

(deftest pkce-matches-rfc-7636
  (is (= "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
         (g/pkce-challenge "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))))

(deftest share-id-is-unpadded-base64url
  (let [url "https://example.sharepoint.com/Sandbox/Lib/A file.docx?web=1"
        id (g/share-id url)]
    (is (str/starts-with? id "u!"))
    (is (not (re-find #"[+/=]" id)))
    (is (= url (String. (.decode (java.util.Base64/getUrlDecoder) (subs id 2)) "UTF-8")))))

(deftest jwt-claims-decode
  (let [payload (g/b64url (.getBytes (json/generate-string {:oid "abc" :name "N"}) "UTF-8"))]
    (is (= {:oid "abc" :name "N"} (g/jwt-claims (str "h." payload ".s"))))))

(deftest item-references
  (with-fake
    (is (= {:drive "b!x-y_z" :item "01ABC"} (g/resolve-item "b!x-y_z:01ABC")))
    (is (= :usage (code-of #(g/resolve-item "not an item"))))))

;; ------------------------------------------------------------------ sign-in

(deftest expired-token-is-refreshed-and-replaced
  (with-fake
    (binding [g/*now* (constantly 2000000)]
      (is (= "new-access" (g/access-token))))
    (let [sent (:body (first (requests :post "/oauth2/v2.0/token")))]
      (is (str/includes? sent "grant_type=refresh_token"))
      (is (str/includes? sent "refresh_token=r")))
    (is (= "new-refresh" (:refresh_token (g/read-token))) "the new refresh token replaces the old one")
    (is (= me (:user (g/read-token))) "the user is kept when the response has no ID token")
    (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (g/token-path)))))))

(deftest missing-token-needs-login
  (with-fake
    (fs/delete (g/token-path))
    (is (= :auth (code-of g/access-token)))))

;; ------------------------------------------------------------------ guardrails

(deftest checkout-guardrails
  (with-fake
    (testing "a check-out by another user is refused"
      (hold! other)
      (is (= :refused (code-of #(g/checkout! it1))))
      (is (empty? (requests :post "/checkout"))))
    (testing "a free item is checked out"
      (reset! state (fresh-state))
      (is (= "user-me" (get-in (g/checkout! it1) [:status :checked_out_by :id])))
      (is (= ["1.1" "1.0"] (get-in @state [:item :versions]))))
    (testing "an existing check-out by me is reported, not repeated"
      (is (true? (:already_checked_out (g/checkout! it1))))
      (is (= 1 (count (requests :post "/checkout")))))))

(deftest upload-guardrails
  (with-fake
    (let [f (str (fs/create-temp-file {:suffix ".docx"}))]
      (spit f "new content")
      (is (= :refused (code-of #(g/upload! it1 f))) "not checked out")
      (hold! other)
      (is (= :refused (code-of #(g/upload! it1 f))) "checked out to another user")
      (is (empty? (requests :put "/content")))
      (reset! state (fresh-state))
      (g/checkout! it1)
      (is (false? (:session (g/upload! it1 f))))
      (is (= "new content" (String. ^bytes (get-in @state [:item :content]) "UTF-8"))))))

(deftest large-upload-uses-a-session
  (with-fake
    (g/checkout! it1)
    (let [f (str (fs/create-temp-file {:suffix ".docx"}))
          n (+ g/chunk-size 10)]
      (is (> n g/simple-upload-limit) "control: the file is above the simple-upload limit")
      (fs/write-bytes f (byte-array n))
      (is (true? (:session (g/upload! it1 f))))
      (is (= (str "bytes 0-" (dec g/chunk-size) "/" n) (first (:chunks @state))))
      (is (= (str "bytes " g/chunk-size "-" (dec n) "/" n) (last (:chunks @state))))
      (is (every? #(nil? (get (:headers %) "Authorization")) (requests :put "upload.example"))
          "the pre-authenticated upload URL gets no bearer token")
      (is (empty? (requests :put "/content"))))))

(deftest checkin-publishes-only-on-explicit-true
  (with-fake
    (g/checkout! it1)
    (is (= :usage (code-of #(g/checkin! it1 "  " false))) "comment required")
    (g/checkin! it1 "minor" "true")
    (is (not (str/includes? (:body (last (requests :post "/checkin"))) "checkInAs")) "a string is not true")
    (is (= "1.1" (get-in @state [:item :version])))
    (g/checkout! it1)
    (g/checkin! it1 "major" true)
    (is (str/includes? (:body (last (requests :post "/checkin"))) "\"checkInAs\":\"published\""))
    (is (= "2.0" (get-in @state [:item :version])))))

(deftest discard-guardrails
  (with-fake
    (is (= :refused (code-of #(g/discard! it1))))
    (g/checkout! it1)
    (g/discard! it1)
    (is (= ["1.0"] (get-in @state [:item :versions])))
    (is (nil? (get-in @state [:item :holder])))))

(deftest excel-write-guardrails
  (with-fake
    (is (= :refused (code-of #(g/excel-write! it1 "Sheet 1" "A1:C1" [[1 "a" "b"]]))) "not checked out")
    (g/checkout! it1)
    (testing "string cells get the text number format before the values are written"
      (g/excel-write! it1 "Sheet 1" "A1:C1" [[99999 "Oct 2026" "x"]])
      (let [[fmt vals] (map #(json/parse-string (:body %)) (requests :patch "range"))]
        (is (= [[nil "@" "@"]] (get fmt "numberFormat")))
        (is (not (contains? fmt "values")))
        (is (= [[99999 "Oct 2026" "x"]] (get vals "values")))))
    (testing "every workbook request runs in the session, and the session is closed"
      (is (every? #(= "session-1" (get (:headers %) "workbook-session-id"))
                  (filter #(str/includes? (:uri %) "range(") (:log @state))))
      (is (= (:sessions @state) (:closed @state))))
    (testing "the sheet name is URL-encoded"
      (is (str/includes? (:uri (last (requests :patch "range"))) "/worksheets/Sheet%201/")))
    (testing "non-empty target cells are refused unless overwrite"
      (is (= :refused (code-of #(g/excel-write! it1 "S" "A2:B2" [["y" "z"]]))))
      (is (= [["y" "z"]] (:written (g/excel-write! it1 "S" "A2:B2" [["y" "z"]] :overwrite? true)))))
    (testing "a shape mismatch is a usage error"
      (is (= :usage (code-of #(g/excel-write! it1 "S" "A1:C1" [[1 2]])))))
    (testing "a read-back mismatch is reported"
      (swap! state assoc :corrupt true)
      (is (= :graph (code-of #(g/excel-write! it1 "S" "A1:C1" [[1 2 3]] :overwrite? true)))))
    (is (= 5 (:sessions @state)) "control: five writes opened sessions")
    (is (= (:sessions @state) (:closed @state)) "sessions are closed after refusals and errors too")))

(deftest excel-write-waits-for-the-save
  (with-fake
    (g/checkout! it1)
    (testing "a write returns after the cTag changes"
      (g/excel-write! it1 "S" "A1:C1" [[1 "a" "b"]])
      (is (= 2 (:ctag @state))))
    (testing "a write that changes nothing does not wait for a save"
      (swap! state assoc :no-save true)
      (is (= [[1 "a" "b"]] (:written (g/excel-write! it1 "S" "A1:C1" [[1 "a" "b"]] :overwrite? true)))))
    (testing "a save that never happens is an error"
      (is (= :graph (code-of #(g/excel-write! it1 "S" "A1:C1" [[2 "a" "b"]] :overwrite? true)))))
    (testing "in a transaction, an unsaved write discards the check-out"
      (reset! state (assoc (fresh-state) :no-save true))
      (let [e (try (g/transaction! it1 "row" {:excel ["S" "A1:C1" [[1 "a" "b"]] false]}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (true? (:discarded (ex-data e))))
        (is (empty? (requests :post "/checkin")))))))

(deftest transaction-rolls-back-on-failure
  (with-fake
    (testing "a check-out held by anyone is refused"
      (hold! other)
      (is (= :refused (code-of #(g/transaction! it1 "c" {:exec "true"}))))
      (is (empty? (requests :post "/checkout"))))
    (testing "a failing step discards the check-out"
      (reset! state (fresh-state))
      (let [e (try (g/transaction! it1 "c" {:exec "exit 3"}) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (true? (:discarded (ex-data e))))
        (is (str/includes? (ex-message e) "check-out discarded")))
      (is (= 1 (count (requests :post "/discardCheckout"))))
      (is (empty? (requests :post "/checkin")))
      (is (nil? (get-in @state [:item :holder]))))
    (testing "a successful exec uploads its output and checks in"
      (reset! state (fresh-state))
      (let [r (g/transaction! it1 "edit" {:exec "tr a-z A-Z < {in} > {out}"})]
        (is (= "V1.0" (String. ^bytes (get-in @state [:item :content]) "UTF-8")))
        (is (= "1.1" (get-in r [:status :version])))
        (is (false? (:published r)))))
    (testing "an Excel transaction writes and checks in"
      (reset! state (fresh-state))
      (g/transaction! it1 "row" {:excel ["S" "A1:C1" [[1 "a" "b"]] false]})
      (is (= [[1 "a" "b"]] (get-in @state [:cells "A1:C1"])))
      (is (= 1 (count (requests :post "/checkin")))))))

(deftest graph-errors-carry-the-code
  (with-fake
    (let [e (try (g/request :get "/drives/D1/items/NOPE") nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :graph (::g/code (ex-data e))))
      (is (= 404 (:status (ex-data e))))
      (is (= "itemNotFound" (:graph-error (ex-data e)))))))

(load-file (str (fs/path (fs/parent (fs/absolutize *file*)) "graph.clj")))

(deftest cli-values-parse-to-vectors
  (is (= [[1 "Oct 2026"] [2 "x"]] ((resolve 'user/values) {:values "[[1,\"Oct 2026\"],[2,\"x\"]]"})))
  (is (every? vector? ((resolve 'user/values) {:values "[[1,2]]"})))
  (is (= :usage (code-of #((resolve 'user/values) {:values "[1,2]"}))))
  (is (= :usage (code-of #((resolve 'user/values) {:values "not json"})))))

(deftest selfcheck
  (when (System/getenv "GRAPH_TEST_SELFCHECK")
    (is (= 1 2) "deliberate failure: GRAPH_TEST_SELFCHECK is set")))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
