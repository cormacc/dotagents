(ns herdr-orch.owned-usage-http-test
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [herdr-orch.owned-usage-test :as owned]
            [herdr-orch.quota-credentials :as credentials]
            [herdr-orch.quota-credentials-test :as stores]
            [herdr-orch.usage :as usage]))

(use-fixtures :each
  (fn [f]
    (let [get-http http/get]
      (with-redefs [credentials/environment (fn [] (throw (ex-info "ambient credentials forbidden" {})))
                    http/get (fn [url opts]
                               (when-not (= "127.0.0.1" (.getHost (java.net.URI. url)))
                                 (throw (ex-info "external network forbidden" {})))
                               (get-http url opts))]
        (f)))))

(defn with-server [handler f]
  (let [server (java.net.ServerSocket. 0 20 (java.net.InetAddress/getByName "127.0.0.1"))
        sockets (atom []) calls (atom 0) closed (promise)
        worker (future
                 (try
                   (while (not (.isClosed server))
                     (let [socket (.accept server)]
                       (swap! sockets conj socket)
                       (future
                         (with-open [socket socket]
                           (try
                             (.setSoTimeout socket 5000)
                             (let [input (.getInputStream socket)
                                   reader (java.io.BufferedReader. (java.io.InputStreamReader. input))
                                   _ (loop [] (when-let [line (.readLine reader)] (when (seq line) (recur))))
                                   response (handler (swap! calls inc))
                                   body (or (:body response) "{}")
                                   headers (str "HTTP/1.1 " (or (:status response) "200 OK") "\r\nConnection: close\r\nContent-Length: "
                                                (or (:length response) (alength (.getBytes body "UTF-8"))) "\r\n"
                                                (or (:headers response) "") "\r\n")
                                   output (.getOutputStream socket)]
                               (when-not (:silent response)
                                 (.write output (.getBytes (str headers body) "UTF-8")) (.flush output))
                               (when (:stall response)
                                 (deliver closed (neg? (.read input)))))
                             (catch Exception _ (deliver closed false)))))))
                   (catch Exception _ nil)))]
    (try (f {:url (str "http://127.0.0.1:" (.getLocalPort server) "/usage") :calls calls :closed closed})
         (finally
           (.close server)
           (doseq [socket @sockets] (try (.close socket) (catch Exception _ nil)))
           (future-cancel worker)))))

(deftest whole-response-timeout-and-body-bound
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"external network forbidden"
                       (http/get "https://example.invalid/" {})) "known-bad network control")
  (with-server (fn [_] {:body "{}"})
    (fn [{:keys [url calls]}]
      (is (= "{}" (:body (usage/default-http-fn {:url url :headers {} :timeout-ms 2000}))))
      (is (= 1 @calls))))
  (doseq [mode [:silent :stall]]
    (with-server (fn [_] {:body "{" :length 100 :stall true :silent (= mode :silent)})
      (fn [{:keys [url closed calls]}]
        (is (thrown? java.net.http.HttpTimeoutException
                     (usage/default-http-fn {:url url :headers {} :timeout-ms 500})))
        (is (= 1 @calls))
        (is (= true (deref closed 2000 :timeout)) "the body reader closes its socket"))))
  (with-server (fn [_] {:body (apply str (repeat (inc usage/body-limit) "x"))})
    (fn [{:keys [url]}]
      (let [r (usage/fetch "codex" {:access "test" :account-id "test"}
                          (fn [req] (usage/default-http-fn (assoc req :url url))) stores/now-ms)]
        (is (= "oversized-response" (:reason r))))))
  (with-server (fn [_] {:status "302 Found" :headers "Location: https://example.invalid/\r\n"})
    (fn [{:keys [url calls]}]
      (is (= 302 (:status (usage/default-http-fn {:url url :headers {} :timeout-ms 2000}))))
      (is (= 1 @calls)))))

(deftest concurrent-processes-coalesce-and-release-the-lock
  (stores/with-context
    (fn [context]
      (stores/seed! context)
      (with-server (fn [_] (Thread/sleep 500) {:body (json/generate-string (owned/codex-body 2 2592000))})
        (fn [{:keys [url calls]}]
          (let [root (str (fs/canonicalize "."))
                bb (str (fs/which "bb"))
                opts {:credential-context context :cache-dir (str (fs/path (:home context) "cache"))
                      :providers ["openai-codex"] :fresh true}
                gate (str (fs/path (:home context) "gate"))
                ready (fn [index] (str (fs/path (:home context) (str "ready-" index))))
                argv (fn [index]
                       [bb "-cp" (str root "/skills/herdr-orch/scripts/src") "-e"
                        (str "(require '[herdr-orch.usage :as u] '[cheshire.core :as j]) "
                             "(spit " (pr-str (ready index)) " \"ready\") "
                             "(loop [n 0] (when-not (.exists (java.io.File. " (pr-str gate) ")) "
                             "(when (> n 1000) (throw (ex-info \"gate timeout\" {}))) (Thread/sleep 10) (recur (inc n)))) "
                             "(with-redefs [u/endpoints {\"codex\" " (pr-str url) "}] "
                             "(println (j/generate-string (u/usage-for " (pr-str owned/config) " " (pr-str opts) "))))")])
                procs (mapv #(process/process (argv %) {:out :string :err :string}) [1 2])]
            (try
              (loop [n 0]
                (when-not (every? fs/exists? [(ready 1) (ready 2)])
                  (when (> n 1000) (throw (ex-info "children did not reach gate" {})))
                  (Thread/sleep 10) (recur (inc n))))
              (spit gate "go")
              (doseq [p procs]
                (let [result (deref p 10000 ::timeout)]
                  (is (not= ::timeout result))
                  (when (= ::timeout result) (throw (ex-info "child timeout" {})))
                  (is (= 0 (:exit result)) (:err result))
                  (when-not (= 0 (:exit result)) (throw (ex-info "child failed" {})))
                  (is (= 0.98 (owned/remaining (json/parse-string (:out result) true) "openai-codex")))))
              (is (= 1 @calls) "two fresh callers share one in-flight fetch")
              (let [r (usage/usage-for owned/config
                                      (assoc opts :http-fn (fn [_] {:status 200 :body (owned/codex-body 30 18000)})))]
                (is (= 0.7 (owned/remaining r "openai-codex"))) "a later fresh caller can fetch again")
              (finally (doseq [p procs] (when (.isAlive (:proc p)) (process/destroy-tree p)))))))))))
