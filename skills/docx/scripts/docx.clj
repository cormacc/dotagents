#!/usr/bin/env bb
;; Review a Word .docx. See ../SKILL.md.
;;
;; Usage:
;;   docx.clj inspect DOCX          JSON: header and footer text, tracked-change counts,
;;                                  version history rows
;;   docx.clj changes DOCX          JSON: each paragraph with tracked changes, with its
;;                                  heading and its text before and after
;;   docx.clj compare OLD NEW OUT   Write OUT, a .docx with OLD -> NEW as tracked changes
;;                                  (needs LibreOffice)
;;
;; Exit codes: 0 success, 1 usage or input error.
(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs])
(cp/add-classpath (str (fs/path (fs/parent (fs/absolutize *file*)) "src")))
(require '[cheshire.core :as json]
         '[docx.review :as review])

(defn- out! [x] (println (json/generate-string x {:pretty true})))

(let [[cmd & args] *command-line-args*]
  (try
    (case [cmd (count args)]
      ["inspect" 1] (out! (review/inspect (first args)))
      ["changes" 1] (out! (review/changes (first args)))
      ["compare" 3] (out! {:ok true :out (apply review/compare-docx args)})
      (throw (ex-info "usage: docx.clj inspect DOCX | changes DOCX | compare OLD NEW OUT" {})))
    (catch Exception e
      (binding [*out* *err*] (println "docx:" (ex-message e)))
      (System/exit 1))))
