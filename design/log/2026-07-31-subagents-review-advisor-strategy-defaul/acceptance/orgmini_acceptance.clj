(ns orgmini-acceptance
  "Hidden acceptance suite for the mini org-outline benchmark.

  Never seeded into an executor cell and never named in ASSIGNMENT.md. Run via
  the sibling `score` wrapper, which puts a cell's src/ on the classpath:

    ./score /path/to/cell-a cell-a [--edn out.edn]

  Scoring is per-case, not per-assertion: a case either passes, fails, or errors
  (a thrown exception counts as errored, never aborting the run)."
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [orgmini :as om]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private combined
  (str "#+TITLE: Combined\n"
       "\n"
       "* TODO Parent task :proj:urgent:\n"
       ":PROPERTIES:\n"
       ":CUSTOM_ID: p-1\n"
       ":CREATED: [2026-07-31 Fri]\n"
       ":END:\n"
       "Parent body line.\n"
       "\n"
       "** DONE Child one\n"
       "*** WAITING Grandchild :blocked:\n"
       ":PROPERTIES:\n"
       ":BLOCKED-BY: task:xyz\n"
       ":END:\n"
       "** CANCELLED Child two\n"
       "Trailing body.\n"))

(def ^:private blank-line-body
  (str "* One\n"
       "\n"
       "body after a blank\n"
       "\n"
       "\n"
       "two blanks above\n"
       "\n"
       "* Two\n"))

;; ---------------------------------------------------------------------------
;; Cases
;;
;; :kind :round-trip -> (serialize (parse in)) must equal in, byte for byte
;; :kind :normalize  -> (serialize (parse in)) must equal :out
;; :kind :parse      -> (:get (parse in)) must equal :expect

(def cases
  [;; --- byte-identical round-trip of canonical text ------------------------
   {:id :rt-01-simple :kind :round-trip :cat :round-trip
    :in "* Hello\n"}
   {:id :rt-02-nested :kind :round-trip :cat :round-trip
    :in "* One\n** Two\n*** Three\n"}
   {:id :rt-03-todo-states :kind :round-trip :cat :round-trip
    :in "* TODO a\n* STARTED b\n* WAITING c\n* DONE d\n* CANCELLED e\n"}
   {:id :rt-04-tags :kind :round-trip :cat :round-trip
    :in "* Fix it :bug:urgent:\n"}
   {:id :rt-05-props :kind :round-trip :cat :round-trip
    :in "* Task\n:PROPERTIES:\n:CUSTOM_ID: x1\n:CREATED: [2026-07-31 Fri]\n:END:\n"}
   {:id :rt-06-preamble :kind :round-trip :cat :round-trip
    :in "#+TITLE: Doc\n#+DATE: today\n\n* First\n"}
   {:id :rt-07-blank-lines :kind :round-trip :cat :round-trip
    :in blank-line-body}
   {:id :rt-08-combined :kind :round-trip :cat :round-trip
    :in combined}
   {:id :rt-09-malformed-drawer :kind :round-trip :cat :round-trip
    :in "* Task\n:PROPERTIES:\n:CUSTOM_ID: x\n* Next\n"}
   ;; NB: deliberately different literals from ASSIGNMENT.md's illustrative
   ;; examples, so a cell must implement the stated rule rather than
   ;; special-case the three strings it was shown.
   {:id :rt-10-colon-titles :kind :round-trip :cat :round-trip
    :in "* Scale 16:9\n* Deploy at 09:45\n* Heading :two words:\n"}

   ;; --- TODO keyword recognition ------------------------------------------
   {:id :ps-01-todo-recognized :kind :parse :cat :todo
    :in "* TODO Thing\n"
    :get #(let [n (first (:nodes %))] [(:todo n) (:title n)])
    :expect ["TODO" "Thing"]}
   {:id :ps-02-todo-unrecognized :kind :parse :cat :todo
    :in "* FIXME Thing\n"
    :get #(let [n (first (:nodes %))] [(:todo n) (:title n)])
    :expect [nil "FIXME Thing"]}
   {:id :ps-03-todo-case-sensitive :kind :parse :cat :todo
    :in "* todo Thing\n"
    :get #(let [n (first (:nodes %))] [(:todo n) (:title n)])
    :expect [nil "todo Thing"]}
   {:id :ps-04-todo-with-tags :kind :parse :cat :todo
    :in "* DONE Ship it :rel:\n"
    :get #(let [n (first (:nodes %))] [(:todo n) (:title n) (vec (:tags n))])
    :expect ["DONE" "Ship it" ["rel"]]}

   ;; --- tag recognition ----------------------------------------------------
   {:id :ps-05-tags-basic :kind :parse :cat :tags
    :in "* Fix it :bug:urgent:\n"
    :get #(let [n (first (:nodes %))] [(vec (:tags n)) (:title n)])
    :expect [["bug" "urgent"] "Fix it"]}
   {:id :ps-06-tags-ratio :kind :parse :cat :tags
    :in "* Scale 16:9\n"
    :get #(let [n (first (:nodes %))] [(vec (:tags n)) (:title n)])
    :expect [[] "Scale 16:9"]}
   {:id :ps-07-tags-time :kind :parse :cat :tags
    :in "* Deploy at 09:45\n"
    :get #(let [n (first (:nodes %))] [(vec (:tags n)) (:title n)])
    :expect [[] "Deploy at 09:45"]}
   {:id :ps-08-tags-space-inside :kind :parse :cat :tags
    :in "* Heading :two words:\n"
    :get #(let [n (first (:nodes %))] [(vec (:tags n)) (:title n)])
    :expect [[] "Heading :two words:"]}
   {:id :ps-09-tags-special-chars :kind :parse :cat :tags
    :in "* Review :code_review:@alice:p%:\n"
    :get #(vec (:tags (first (:nodes %))))
    :expect ["code_review" "@alice" "p%"]}

   ;; --- property drawers ---------------------------------------------------
   {:id :ps-10-props-ordered :kind :parse :cat :drawer
    :in "* T\n:PROPERTIES:\n:B: 2\n:A: 1\n:C: 3\n:END:\n"
    :get #(mapv vec (:properties (first (:nodes %))))
    :expect [["B" "2"] ["A" "1"] ["C" "3"]]}
   {:id :ps-11-props-empty-value :kind :parse :cat :drawer
    :in "* T\n:PROPERTIES:\n:EMPTY:\n:END:\n"
    :get #(mapv vec (:properties (first (:nodes %))))
    :expect [["EMPTY" ""]]}
   {:id :ps-12-drawer-not-adjacent :kind :parse :cat :drawer
    :in "* Task\n\n:PROPERTIES:\n:K: v\n:END:\n"
    :get #(let [n (first (:nodes %))] [(vec (:properties n)) (:body n)])
    :expect [[] "\n:PROPERTIES:\n:K: v\n:END:\n"]}
   {:id :ps-13-drawer-in-body :kind :parse :cat :drawer
    :in "* Task\nsome text\n:PROPERTIES:\n:K: v\n:END:\n"
    :get #(let [n (first (:nodes %))] [(vec (:properties n)) (:body n)])
    :expect [[] "some text\n:PROPERTIES:\n:K: v\n:END:\n"]}
   {:id :ps-14-drawer-no-end :kind :parse :cat :drawer
    :in "* Task\n:PROPERTIES:\n:K: v\n* Next\n"
    :get #(let [n (first (:nodes %))] [(vec (:properties n)) (:body n)])
    :expect [[] ":PROPERTIES:\n:K: v\n"]}
   {:id :ps-15-drawer-value-with-colons :kind :parse :cat :drawer
    :in "* T\n:PROPERTIES:\n:BLOCKED-BY: task:abc-123\n:END:\n"
    :get #(mapv vec (:properties (first (:nodes %))))
    :expect [["BLOCKED-BY" "task:abc-123"]]}

   ;; --- body, preamble, structure -----------------------------------------
   {:id :ps-16-body-verbatim :kind :parse :cat :structure
    :in blank-line-body
    :get #(:body (first (:nodes %)))
    :expect "\nbody after a blank\n\n\ntwo blanks above\n\n"}
   {:id :ps-17-body-empty :kind :parse :cat :structure
    :in "* One\n* Two\n"
    :get #(:body (first (:nodes %)))
    :expect ""}
   {:id :ps-18-preamble-captured :kind :parse :cat :structure
    :in "#+TITLE: T\n\n* H\n"
    :get :preamble
    :expect "#+TITLE: T\n\n"}
   {:id :ps-19-no-preamble :kind :parse :cat :structure
    :in "* H\n"
    :get :preamble
    :expect ""}
   {:id :ps-20-levels :kind :parse :cat :structure
    :in "* a\n*** deep\n** mid\n"
    :get #(mapv :level (:nodes %))
    :expect [1 3 2]}
   {:id :ps-21-flat-nodes :kind :parse :cat :structure
    :in combined
    :get #(count (:nodes %))
    :expect 4}
   {:id :ps-22-not-a-heading :kind :parse :cat :structure
    :in "*bold* not a heading\n* Real\n"
    :get #(let [n (:nodes %)] [(count n) (:title (first n))])
    :expect [1 "Real"]}

   ;; --- normalization of non-canonical input -------------------------------
   {:id :nm-01-tag-padding :kind :normalize :cat :normalize
    :in "* Fix it    :bug:\n"
    :out "* Fix it :bug:\n"}
   {:id :nm-02-drawer-indent :kind :normalize :cat :normalize
    :in "* T\n  :PROPERTIES:\n  :K: v\n  :END:\n"
    :out "* T\n:PROPERTIES:\n:K: v\n:END:\n"}
   {:id :nm-03-prop-value-spacing :kind :normalize :cat :normalize
    :in "* T\n:PROPERTIES:\n:K:     v\n:END:\n"
    :out "* T\n:PROPERTIES:\n:K: v\n:END:\n"}])

;; ---------------------------------------------------------------------------
;; Runner

(defn- truncate [s]
  (let [s (pr-str s)]
    (if (> (count s) 220) (str (subs s 0 220) "…") s)))

(defn- run-case [{:keys [id kind cat in out get expect] :as c}]
  (try
    (let [[ok? exp act]
          (case kind
            :round-trip (let [a (om/serialize-outline (om/parse-outline in))]
                          [(= in a) in a])
            :normalize  (let [a (om/serialize-outline (om/parse-outline in))]
                          [(= out a) out a])
            :parse      (let [a (get (om/parse-outline in))]
                          [(= expect a) expect a]))]
      (cond-> {:id id :cat cat :status (if ok? :pass :fail)}
        (not ok?) (assoc :expected (truncate exp) :actual (truncate act))))
    (catch Throwable t
      {:id id :cat cat :status :error
       :error (str (.getSimpleName (class t)) ": " (ex-message t))})))

(defn- summarize [label results]
  (let [by-status (frequencies (map :status results))
        total (count results)
        passed (get by-status :pass 0)]
    {:label label
     :total total
     :passed passed
     :failed (get by-status :fail 0)
     :errored (get by-status :error 0)
     :pass-rate (/ (Math/round (* 1000.0 (/ passed (double total)))) 1000.0)
     :by-category (into (sorted-map)
                        (for [[cat rs] (group-by :cat results)]
                          [cat {:passed (count (filter #(= :pass (:status %)) rs))
                                :total (count rs)}]))
     :not-passed (mapv :id (remove #(= :pass (:status %)) results))}))

(defn -main [& args]
  (let [flags (set (filter #(str/starts-with? % "--") args))
        positional (remove #(str/starts-with? % "--") args)
        label (or (first positional) "unlabelled")
        edn-path (second (drop-while #(not= "--edn" %) args))
        results (mapv run-case cases)
        summary (summarize label results)]
    (println (str "=== " label " ==="))
    (doseq [{:keys [id status expected actual error]} results]
      (println (format "%-7s %s" (str/upper-case (name status)) (name id)))
      (when expected (println "        expected:" expected))
      (when actual   (println "        actual:  " actual))
      (when error    (println "        error:   " error)))
    (println)
    (println (format "%d/%d passed (%.1f%%)  failed=%d errored=%d"
                     (:passed summary) (:total summary)
                     (* 100.0 (:pass-rate summary))
                     (:failed summary) (:errored summary)))
    (doseq [[cat {:keys [passed total]}] (:by-category summary)]
      (println (format "  %-11s %d/%d" (name cat) passed total)))
    (when edn-path
      (spit edn-path (with-out-str (pp/pprint summary)))
      (println "\nwrote" edn-path))
    (when (contains? flags "--edn-stdout")
      (println "\n" (pr-str summary)))
    (System/exit (if (= (:passed summary) (:total summary)) 0 1))))
