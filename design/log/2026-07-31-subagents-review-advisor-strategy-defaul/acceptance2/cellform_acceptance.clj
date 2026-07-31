(ns cellform-acceptance
  "Hidden acceptance suite for the cellform benchmark (round 2).

  Never seeded into an executor cell and never named in ASSIGNMENT.md. Run via
  the sibling `score2` wrapper, which puts a cell's src/ on the classpath:

    ./score2 /path/to/r2-cell-i cell-i [--edn out.edn]

  Scoring is per-case: a case passes, fails, or errors (a thrown exception is one
  errored case and never aborts the run). Numbers compare numerically, so 3 and
  3.0 are equivalent.

  Case inputs deliberately avoid every literal used as an illustrative example in
  ASSIGNMENT.md or in the visible smoke tests, so a cell must generalize the
  stated rules rather than special-case what it was shown."
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [cellform :as cf]))

;; ---------------------------------------------------------------------------
;; Cases: {:id :cat :sheet {...} :expect {cell value ...}}

(def cases
  [;; --- parsing and grammar ------------------------------------------------
   {:id :p-01-mul-binds-tighter :cat :parsing
    :sheet {"C1" "=5+2*6"} :expect {"C1" 17}}
   {:id :p-02-parens-override :cat :parsing
    :sheet {"C1" "=(5+2)*6"} :expect {"C1" 42}}
   {:id :p-03-unary-minus :cat :parsing
    :sheet {"C1" "=-7+2"} :expect {"C1" -5}}
   {:id :p-04-nested-parens :cat :parsing
    :sheet {"C1" "=((4+1)*(2+1))"} :expect {"C1" 15}}
   {:id :p-05-exact-division :cat :parsing
    :sheet {"C1" "=9/4"} :expect {"C1" 2.25}}
   {:id :p-06-whitespace-insignificant :cat :parsing
    :sheet {"C1" "=  5  +  2 * 3 "} :expect {"C1" 11}}
   {:id :p-07-trailing-operator :cat :parsing
    :sheet {"C1" "=4+"} :expect {"C1" :err/parse}}
   {:id :p-08-unknown-function :cat :parsing
    :sheet {"C1" "=TOTAL(1)"} :expect {"C1" :err/parse}}
   {:id :p-09-if-wrong-arity :cat :parsing
    :sheet {"C1" "=IF(1,2,3,4)"} :expect {"C1" :err/parse}}
   {:id :p-10-sum-zero-args :cat :parsing
    :sheet {"C1" "=SUM()"} :expect {"C1" :err/parse}}
   {:id :p-11-multi-letter-column :cat :parsing
    :sheet {"C1" "=BB2+1"} :expect {"C1" :err/parse}}
   {:id :p-12-row-out-of-range :cat :parsing
    :sheet {"C1" "=D0+1"} :expect {"C1" :err/parse}}
   {:id :p-13-unterminated-string :cat :parsing
    :sheet {"C1" "=CONCAT(\"oops)"} :expect {"C1" :err/parse}}
   {:id :p-14-lowercase-function :cat :parsing
    :sheet {"C1" "=sum(D1)"} :expect {"C1" :err/parse}}

   ;; --- references and evaluation order ------------------------------------
   {:id :r-01-two-refs :cat :refs
    :sheet {"D1" "5" "D2" "7" "E1" "=D1+D2"} :expect {"E1" 12}}
   {:id :r-02-deep-chain-declared-backwards :cat :refs
    :sheet {"E1" "=D1*2" "D1" "=C1*2" "C1" "=B1*2" "B1" "3"} :expect {"E1" 24}}
   {:id :r-03-diamond :cat :refs
    :sheet {"B1" "4" "C1" "=B1+1" "D1" "=B1+2" "E1" "=C1+D1"} :expect {"E1" 11}}
   {:id :r-04-refy-string-literal :cat :refs
    :sheet {"C1" "D1" "D1" "9"} :expect {"C1" "D1"}}
   {:id :r-05-same-ref-twice :cat :refs
    :sheet {"B1" "6" "C1" "=B1+B1"} :expect {"C1" 12}}

   ;; --- ranges -------------------------------------------------------------
   {:id :g-01-sum-column-range :cat :ranges
    :sheet {"B1" "2" "B2" "4" "B3" "6" "C1" "=SUM(B1:B3)"} :expect {"C1" 12}}
   {:id :g-02-sum-rectangle :cat :ranges
    :sheet {"B1" "1" "B2" "2" "C1" "3" "C2" "4" "D1" "=SUM(B1:C2)"} :expect {"D1" 10}}
   {:id :g-03-reversed-corners :cat :ranges
    :sheet {"B1" "3" "B2" "5" "C1" "=SUM(B2:B1)"} :expect {"C1" 8}}
   {:id :g-04-count-rectangle-with-gaps :cat :ranges
    :sheet {"B1" "1" "C2" "2" "D1" "=COUNT(B1:C2)"} :expect {"D1" 2}}
   {:id :g-05-range-in-arithmetic :cat :ranges
    :sheet {"B1" "1" "B2" "2" "C1" "=B1:B2*2"} :expect {"C1" :err/type}}
   {:id :g-06-range-in-if :cat :ranges
    :sheet {"B1" "1" "B2" "2" "C1" "=IF(1,B1:B2,0)"} :expect {"C1" :err/type}}
   {:id :g-07-range-in-concat :cat :ranges
    :sheet {"B1" "1" "B2" "2" "C1" "=CONCAT(B1:B2)"} :expect {"C1" :err/type}}

   ;; --- empty-cell coercion ------------------------------------------------
   {:id :c-01-empty-is-zero-in-arithmetic :cat :coercion
    :sheet {"C1" "=Y7+9"} :expect {"C1" 9}}
   {:id :c-02-empty-is-blank-in-concat :cat :coercion
    :sheet {"C1" "=CONCAT(\"x\",Y7,\"y\")"} :expect {"C1" "xy"}}
   {:id :c-03-empty-not-counted :cat :coercion
    :sheet {"B1" "5" "C1" "=COUNT(B1,Y7)"} :expect {"C1" 1}}
   {:id :c-04-declared-empty-in-sum :cat :coercion
    :sheet {"B1" "" "B2" "8" "C1" "=SUM(B1:B2)"} :expect {"C1" 8}}
   {:id :c-05-empty-condition-is-falsey :cat :coercion
    :sheet {"C1" "=IF(Y7,4,6)"} :expect {"C1" 6}}

   ;; --- strictness (deliberately counter-spreadsheet) ----------------------
   {:id :s-01-numeric-string-literal-rejected :cat :strictness
    :sheet {"C1" "=\"5\"*2"} :expect {"C1" :err/type}}
   ;; A string that genuinely looks like a number can only be produced by a
   ;; formula, since the literal "5" would parse as a number.
   {:id :s-02-formula-made-numeric-string-rejected :cat :strictness
    :sheet {"B1" "=CONCAT(5)" "C1" "=B1+1"} :expect {"C1" :err/type}}
   {:id :s-10-numeric-string-condition-rejected :cat :strictness
    :sheet {"B1" "=CONCAT(1)" "C1" "=IF(B1,4,6)"} :expect {"C1" :err/type}}
   {:id :s-11-trailing-text-cell-rejected :cat :strictness
    :sheet {"B1" "5 apples" "C1" "=B1+1"} :expect {"C1" :err/type}}
   {:id :s-03-sum-skips-strings :cat :strictness
    :sheet {"B1" "3" "B2" "note" "B3" "4" "C1" "=SUM(B1:B3)"} :expect {"C1" 7}}
   {:id :s-04-if-unselected-then-errors :cat :strictness
    :sheet {"C1" "=IF(0,9/0,4)"} :expect {"C1" :err/div0}}
   {:id :s-05-if-unselected-else-errors :cat :strictness
    :sheet {"C1" "=IF(3,4,9/0)"} :expect {"C1" :err/div0}}
   {:id :s-06-if-nonzero-selects-then :cat :strictness
    :sheet {"C1" "=IF(5,3,8)"} :expect {"C1" 3}}
   {:id :s-07-if-string-condition :cat :strictness
    :sheet {"B1" "yes" "C1" "=IF(B1,1,2)"} :expect {"C1" :err/type}}
   {:id :s-08-concat-mixes-types :cat :strictness
    :sheet {"C1" "=CONCAT(\"a\",\"b\",7)"} :expect {"C1" "ab7"}}
   {:id :s-09-concat-non-integral :cat :strictness
    :sheet {"C1" "=CONCAT(2.5)"} :expect {"C1" "2.5"}}

   ;; --- error precedence lattice -------------------------------------------
   {:id :e-01-div-by-zero :cat :errors
    :sheet {"C1" "=8/0"} :expect {"C1" :err/div0}}
   {:id :e-02-string-in-subtraction :cat :errors
    :sheet {"B1" "txt" "C1" "=B1-1"} :expect {"C1" :err/type}}
   ;; Operands are deliberately ordered so that the higher-precedence error is
   ;; NOT the first one encountered: taking the first error rather than the
   ;; highest-ranked one gives the wrong answer.
   {:id :e-03-parse-outranks-div0 :cat :errors
    :sheet {"B1" "=%%" "C2" "=6/0" "D1" "=C2+B1"} :expect {"D1" :err/parse}}
   {:id :e-04-div0-outranks-type :cat :errors
    :sheet {"B1" "=\"x\"+1" "C1" "=6/0" "D1" "=B1+C1"} :expect {"D1" :err/div0}}
   {:id :e-07-parse-outranks-type :cat :errors
    :sheet {"B1" "=\"x\"+1" "C1" "=@@" "D1" "=B1+C1"} :expect {"D1" :err/parse}}
   {:id :e-05-sum-propagates-error :cat :errors
    :sheet {"B1" "2" "B2" "=3/0" "C1" "=SUM(B1:B2)"} :expect {"C1" :err/div0}}
   {:id :e-08-sum-error-after-skipped-string :cat :errors
    :sheet {"B1" "note" "B2" "=4/0" "B3" "2" "C1" "=SUM(B1:B3)"}
    :expect {"C1" :err/div0}}
   {:id :e-06-count-propagates-error :cat :errors
    :sheet {"B1" "=3/0" "C1" "=COUNT(B1,4)"} :expect {"C1" :err/div0}}

   ;; --- cycles -------------------------------------------------------------
   {:id :y-01-self-cycle :cat :cycles
    :sheet {"C1" "=C1"} :expect {"C1" :err/cycle}}
   {:id :y-02-two-cycle-marks-both :cat :cycles
    :sheet {"C1" "=D1" "D1" "=C1"} :expect {"C1" :err/cycle "D1" :err/cycle}}
   {:id :y-03-three-cycle-marks-all :cat :cycles
    :sheet {"C1" "=D1" "D1" "=E1" "E1" "=C1"}
    :expect {"C1" :err/cycle "D1" :err/cycle "E1" :err/cycle}}
   {:id :y-04-dependent-of-cycle :cat :cycles
    :sheet {"C1" "=D1" "D1" "=C1" "E1" "=C1+1"} :expect {"E1" :err/cycle}}
   {:id :y-05-far-dependent-of-cycle :cat :cycles
    :sheet {"C1" "=D1" "D1" "=C1" "E1" "=C1+1" "F1" "=E1+1" "G1" "=F1+1"}
    :expect {"G1" :err/cycle}}
   {:id :y-06-unrelated-cell-unaffected :cat :cycles
    :sheet {"C1" "=D1" "D1" "=C1" "E1" "9" "F1" "=E1+1"} :expect {"F1" 10}}
   {:id :y-07-cycle-through-range :cat :cycles
    :sheet {"B1" "=SUM(B1:B2)" "B2" "1"} :expect {"B1" :err/cycle}}
   ;; div0 operand listed first, so first-error-wins would return :err/div0.
   {:id :y-08-cycle-outranks-div0 :cat :cycles
    :sheet {"C1" "=D1" "D1" "=C1" "E1" "=8/0" "F1" "=E1+C1"} :expect {"F1" :err/cycle}}
   {:id :y-10-cycle-outranks-type :cat :cycles
    :sheet {"C1" "=D1" "D1" "=C1" "E1" "=\"s\"+1" "F1" "=E1+C1"}
    :expect {"F1" :err/cycle}}
   {:id :y-09-own-parse-outranks-cycle-operand :cat :cycles
    :sheet {"C1" "=D1" "D1" "=C1" "E1" "=C1 *"} :expect {"E1" :err/parse}}])

;; ---------------------------------------------------------------------------
;; Runner

(defn- vals-eq? [a b]
  (if (and (number? a) (number? b)) (== a b) (= a b)))

(defn- truncate [x]
  (let [s (pr-str x)]
    (if (> (count s) 200) (str (subs s 0 200) "…") s)))

(defn- run-case [{:keys [id cat sheet expect]}]
  (try
    (let [got (cf/evaluate sheet)
          bad (for [[cell want] expect
                    :let [actual (get got cell)]
                    :when (not (vals-eq? want actual))]
                [cell want actual])]
      (if (empty? bad)
        {:id id :cat cat :status :pass}
        {:id id :cat cat :status :fail
         :mismatches (vec (for [[cell want actual] bad]
                            {:cell cell :expected (truncate want) :actual (truncate actual)}))}))
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
  (let [positional (remove #(str/starts-with? % "--") args)
        label (or (first positional) "unlabelled")
        edn-path (second (drop-while #(not= "--edn" %) args))
        results (mapv run-case cases)
        summary (summarize label results)]
    (println (str "=== " label " ==="))
    (doseq [{:keys [id status mismatches error]} results]
      (println (format "%-7s %s" (str/upper-case (name status)) (name id)))
      (doseq [{:keys [cell expected actual]} mismatches]
        (println (format "        %s: expected %s, got %s" cell expected actual)))
      (when error (println "        error:  " error)))
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
    (System/exit (if (= (:passed summary) (:total summary)) 0 1))))
