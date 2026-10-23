#!/usr/bin/env bb
;; ms365 Graph client: edit SharePoint files in a check-out session. See ../SKILL.md.
;;
;; ITEM is a SharePoint file URL or DRIVE:ITEM.
;;   graph.clj login [--device] [--tenant ID --client ID]
;;   graph.clj logout | whoami
;;   graph.clj status ITEM
;;   graph.clj checkout ITEM
;;   graph.clj upload ITEM FILE
;;   graph.clj download ITEM FILE [--version V]
;;   graph.clj checkin ITEM --comment TEXT [--publish]
;;   graph.clj discard ITEM
;;   graph.clj excel-write ITEM --sheet NAME --range A1:C1 --values JSON [--overwrite]
;;   graph.clj transaction ITEM --comment TEXT (--exec 'CMD {in} {out}' | --sheet --range --values [--overwrite]) [--publish]
;;
;; Prints {"ok": true, "result": ...} or {"ok": false, "error": {"code": ..., "message": ...}}.
;; Exit codes: 0 ok, 1 usage, 2 refused by a guardrail, 3 Graph error, 4 sign-in needed.
(require '[babashka.classpath :as cp] '[babashka.fs :as fs])
(cp/add-classpath (str (fs/path (fs/parent (fs/absolutize *file*)) "src")))
(require '[babashka.cli :as cli] '[cheshire.core :as json] '[ms365.graph :as g])

(def exit-codes {:usage 1 :refused 2 :graph 3 :auth 4})

(defn- out! [x] (println (json/generate-string x {:pretty true})))

(defn- need [opts k] (or (get opts k) (g/fail :usage (str "missing --" (name k)))))

(defn values
  "Parse --values as a JSON array of arrays into vectors (cheshire returns sequences)."
  [opts]
  (let [v (try (json/parse-string (need opts :values)) (catch Exception _ (g/fail :usage "--values must be JSON")))]
    (when-not (and (sequential? v) (seq v) (every? sequential? v)) (g/fail :usage "--values must be a JSON array of arrays"))
    (mapv vec v)))

(defn- run [[cmd & args] opts]
  (let [item #(g/resolve-item (or (first args) (g/fail :usage (str cmd " needs ITEM"))))]
    (case cmd
      "login" (do (when (and (:tenant opts) (:client opts)) (g/save-config! (:tenant opts) (:client opts)))
                  (let [t (if (:device opts) (g/login-device) (g/login-browser))]
                    {:user (:user t) :expires_at (:expires_at t)}))
      "logout" (do (fs/delete-if-exists (g/token-path)) {:signed_out true})
      "whoami" (let [t (g/read-token)] {:user (g/me) :access_token_valid (and t (< (g/*now*) (:expires_at t)))})
      "status" (g/status (item))
      "checkout" (g/checkout! (item))
      "upload" (g/upload! (item) (or (second args) (g/fail :usage "upload needs FILE")))
      "download" (g/download! (item) (or (second args) (g/fail :usage "download needs FILE")) :version (:version opts))
      "checkin" (g/checkin! (item) (need opts :comment) (true? (:publish opts)))
      "discard" (g/discard! (item))
      "excel-write" (g/excel-write! (item) (need opts :sheet) (need opts :range) (values opts) :overwrite? (true? (:overwrite opts)))
      "transaction" (g/transaction! (item) (need opts :comment)
                                    (if (:exec opts)
                                      {:exec (:exec opts)}
                                      {:excel [(need opts :sheet) (need opts :range) (values opts) (true? (:overwrite opts))]})
                                    :publish? (true? (:publish opts)))
      (g/fail :usage "usage: login | logout | whoami | status | checkout | upload | download | checkin | discard | excel-write | transaction"))))

;; Run only as the main script, so that tests can load-file this file for its functions.
(when (some-> (System/getProperty "babashka.file") fs/canonicalize str (= (str (fs/canonicalize *file*))))
(let [{:keys [args opts]} (cli/parse-args *command-line-args*
                                          {:coerce {:publish :boolean :overwrite :boolean :device :boolean
                                                    ;; Keep these as text: "1.10" must not become 1.1.
                                                    :version :string :comment :string :sheet :string :range :string
                                                    :values :string :exec :string :tenant :string :client :string}})]
  (try
    (out! {:ok true :result (run args opts)})
    (catch clojure.lang.ExceptionInfo e
      (let [code (or (::g/code (ex-data e)) :graph)]
        (out! {:ok false :error {:code (name code) :message (ex-message e)}})
        (System/exit (exit-codes code 3)))))))
