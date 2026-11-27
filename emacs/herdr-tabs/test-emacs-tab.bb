#!/usr/bin/env bb
;; Tests for emacs-tab against fake herdr, hc and herdr-app runtimes.

(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing run-tests]])

(load-file (str (fs/path (fs/parent (fs/absolutize *file*)) "emacs-tab")))

(defn ok [v] {:exit 0 :out (json/generate-string v) :err ""})

(defn fake
  "Return [calls-atom run-fn]. `responses` maps an argv prefix vector to a
  response, or to a fn of argv that returns one."
  [responses]
  (let [calls (atom [])]
    [calls
     (fn [argv]
       (swap! calls conj argv)
       (if-let [[_ r] (first (filter (fn [[prefix _]] (= prefix (vec (take (count prefix) argv))))
                                     (sort-by (comp - count key) responses)))]
         (if (fn? r) (r argv) r)
         {:exit 1 :out "" :err (str "unexpected call: " (str/join " " argv))}))]))

(defn env-of [m] (fn [k] (get m k)))

(defn open [env responses args]
  (let [[calls run] (fake responses)]
    (binding [emacs-tab/*run* run emacs-tab/*env* (env-of env)
              emacs-tab/*sleep* (fn [_]) emacs-tab/*uuid* (constantly "new-uuid")]
      [(try (emacs-tab/open! args) (catch clojure.lang.ExceptionInfo e (ex-data e))) @calls])))

(def hc-env {"HC_TERMINAL_ID" "t2"})
(def hc-caller (ok {:address "t2" :labels {:herdr.space "sp1"} :cwd "/repo"}))
(def app-ls-with-new (ok {:spaces [{:id "sp1" :tabs [{:id "new-uuid" :name "2" :terminals [{:terminal "t9"}]}]}]}))

(deftest hc-creates-a-labelled-tab
  (let [[result calls] (open hc-env
                             {["hc" "get"] hc-caller
                              ["hc" "ls"] (ok {:terminals []})
                              ["hc" "run"] (ok {:address "t9"})
                              ["herdr-app" "ls"] app-ls-with-new
                              ["herdr-app" "do"] (ok {:ok true})}
                             ["magit"])
        run (first (filter #(= ["hc" "run"] (take 2 %)) calls))]
    (is (= {:runtime "hc" :created true :terminal "t9" :tab "new-uuid" :warnings []}
           (select-keys result [:runtime :created :terminal :tab :warnings])))
    (testing "the terminal carries the space, a new tab id and the tab name"
      (is (some #{"herdr.space=sp1"} run))
      (is (some #{"herdr.tab=new-uuid"} run))
      (is (some #{"emacs-tab=magit"} run)))
    (testing "the terminal has no parent, so it is a tab, not a sub-agent of the caller"
      (is (some #{"--no-parent"} run)))
    (testing "the frame runs emacsclient with the magit entry point on the caller's folder"
      (is (= ["emacsclient" "-t" "-a" ""] (take 4 (drop-while #(not= "emacsclient" %) run))))
      (is (str/includes? (last run) "(herdr-tabs-magit \"/repo\")")))
    (testing "the tab is renamed and focused"
      (is (some #(and (= "tab.rename" (nth % 2 nil)) (str/includes? (nth % 3) "\"magit\"")) calls))
      (is (some #(and (= "pane.focus" (nth % 2 nil)) (str/includes? (nth % 3) "\"t9\"")) calls)))))

(deftest hc-reuses-a-live-tab
  (let [[result calls] (open hc-env
                             {["hc" "get"] hc-caller
                              ["hc" "ls"] (ok {:terminals [{:address "t9" :state "live"}]})
                              ["herdr-app" "ls"] app-ls-with-new
                              ["herdr-app" "do"] (ok {:ok true})}
                             ["magit"])]
    (is (false? (:created result)))
    (is (not-any? #(= ["hc" "run"] (take 2 %)) calls))
    (is (some #(= "pane.focus" (nth % 2 nil)) calls))))

(deftest hc-lookup-filters-by-name-and-space
  (let [[_ calls] (open hc-env {["hc" "get"] hc-caller
                                ["hc" "ls"] (ok {:terminals [{:address "t9" :state "live"}]})
                                ["herdr-app"] (ok {})}
                        ["plan"])]
    (is (some #(and (= ["hc" "ls"] (take 2 %)) (some #{"emacs-tab=plan"} %) (some #{"herdr.space=sp1"} %)) calls))))

(deftest hc-without-the-window-warns-but-succeeds
  (let [[result _] (open hc-env {["hc" "get"] hc-caller
                                 ["hc" "ls"] (ok {:terminals []})
                                 ["hc" "run"] (ok {:address "t9"})
                                 ["herdr-app"] {:exit 1 :out "" :err "no window"}}
                         ["magit"])]
    (is (= "t9" (:terminal result)))
    (is (seq (:warnings result)))))

(def tui-env {"HERDR_ENV" "1" "HERDR_PANE_ID" "w1:p1"})
(def tui-pane (ok {:result {:pane {:pane_id "w1:p1" :workspace_id "w1" :cwd "/repo"}}}))

(deftest tui-creates-a-tab-and-execs-the-frame
  (let [[result calls] (open tui-env
                             {["herdr" "pane" "get"] tui-pane
                              ["herdr" "tab" "list"] (ok {:result {:tabs [{:tab_id "w1:t1" :label "orch-0"}]}})
                              ["herdr" "tab" "create"] (ok {:result {:tab {:tab_id "w1:t2"} :root_pane {:pane_id "w1:p2"}}})
                              ["herdr" "pane" "run"] (ok {:result {}})}
                             ["plan"])
        create (first (filter #(= ["herdr" "tab" "create"] (take 3 %)) calls))
        run (first (filter #(= ["herdr" "pane" "run"] (take 3 %)) calls))]
    (is (= {:runtime "tui" :created true :tab "w1:t2" :pane "w1:p2"}
           (select-keys result [:runtime :created :tab :pane])))
    (is (= ["--workspace" "w1" "--cwd" "/repo" "--label" "plan" "--focus"] (drop 3 create)))
    (is (= "w1:p2" (nth run 3)))
    (is (str/starts-with? (nth run 4) "exec 'emacsclient' '-t' '-a' '' '--eval' '(progn"))
    (is (str/includes? (nth run 4) "(herdr-tabs-plan \"/repo\")"))))

(deftest tui-focuses-an-existing-tab-by-label
  (let [[result calls] (open tui-env
                             {["herdr" "pane" "get"] tui-pane
                              ["herdr" "tab" "list"] (ok {:result {:tabs [{:tab_id "w1:t5" :label "magit"}]}})
                              ["herdr" "tab" "focus"] (ok {:result {:tab {}}})}
                             ["magit"])]
    (is (false? (:created result)))
    (is (some #(= ["herdr" "tab" "focus" "w1:t5"] %) calls))
    (is (not-any? #(= ["herdr" "tab" "create"] (take 3 %)) calls))))

(deftest tui-no-focus-creates-unfocused
  (let [[_ calls] (open tui-env
                        {["herdr" "pane" "get"] tui-pane
                         ["herdr" "tab" "list"] (ok {:result {:tabs []}})
                         ["herdr" "tab" "create"] (ok {:result {:tab {:tab_id "w1:t2"} :root_pane {:pane_id "w1:p2"}}})
                         ["herdr" "pane" "run"] (ok {:result {}})}
                        ["magit" "--no-focus"])]
    (is (some #(and (= ["herdr" "tab" "create"] (take 3 %)) (= "--no-focus" (last %))) calls))))

(deftest tui-key-binding-call
  (testing "a custom key command passes workspace and cwd, and Herdr's binary path"
    (let [[result calls] (open {"HERDR_BIN_PATH" "/opt/herdr"}
                               {["/opt/herdr" "tab" "list"] (ok {:result {:tabs []}})
                                ["/opt/herdr" "tab" "create"] (ok {:result {:tab {:tab_id "w2:t1"} :root_pane {:pane_id "w2:p1"}}})
                                ["/opt/herdr" "pane" "run"] (ok {:result {}})}
                               ["magit" "--runtime" "tui" "--workspace" "w2" "--cwd" "/proj"])]
      (is (= "w2:t1" (:tab result)))
      (is (not-any? #(= "pane" (second %)) (filter #(#{"get" "current"} (nth % 2 nil)) calls)))
      (is (some #(= ["/opt/herdr" "tab" "create" "--workspace" "w2" "--cwd" "/proj"] (take 7 %)) calls))))
  (testing "an empty --cwd from an unset variable falls back to the focused pane"
    (let [[_ calls] (open {"HERDR_PANE_ID" "w1:p1"}
                          {["herdr" "pane" "get"] tui-pane
                           ["herdr" "tab" "list"] (ok {:result {:tabs []}})
                           ["herdr" "tab" "create"] (ok {:result {:tab {:tab_id "w1:t2"} :root_pane {:pane_id "w1:p2"}}})
                           ["herdr" "pane" "run"] (ok {:result {}})}
                          ["magit" "--runtime" "tui" "--workspace" "" "--cwd" ""])]
      (is (some #(= ["herdr" "tab" "create" "--workspace" "w1" "--cwd" "/repo"] (take 7 %)) calls)))))

(deftest refusals
  (testing "outside Herdr: usage error and no runtime call"
    (let [[result calls] (open {} {} ["magit"])]
      (is (= 2 (:code result)))
      (is (empty? calls))))
  (testing "both runtimes' variables without --runtime"
    (is (= 2 (:code (first (open {"HC_TERMINAL_ID" "t2" "HERDR_ENV" "1"} {} ["magit"]))))))
  (testing "an unknown name without elisp creates nothing"
    (let [[result calls] (open hc-env {["hc" "get"] hc-caller ["hc" "ls"] (ok {:terminals []})} ["notes"])]
      (is (= 2 (:code result)))
      (is (not-any? #(= ["hc" "run"] (take 2 %)) calls))))
  (testing "an explicit elisp form is used verbatim"
    (let [[_ calls] (open hc-env {["hc" "get"] hc-caller ["hc" "ls"] (ok {:terminals []})
                                  ["hc" "run"] (ok {:address "t9"}) ["herdr-app"] (ok {})}
                          ["notes" "--" "(find-file" "\"/x\")"])]
      (is (= "(find-file \"/x\")" (last (first (filter #(= ["hc" "run"] (take 2 %)) calls))))))))

(deftest json-output
  (let [[calls run] (fake {["hc" "get"] hc-caller ["hc" "ls"] (ok {:terminals [{:address "t9" :state "live"}]})
                           ["herdr-app"] (ok {})})
        out (with-out-str
              (binding [emacs-tab/*run* run emacs-tab/*env* (env-of hc-env)]
                (is (zero? (emacs-tab/-main "magit" "--format" "json")))))]
    (is (= {:ok true :terminal "t9"} (let [v (json/parse-string out true)] {:ok (:ok v) :terminal (get-in v [:result :terminal])}))))
  (let [out (with-out-str (binding [emacs-tab/*env* (env-of {})]
                            (is (= 2 (emacs-tab/-main "magit" "--format" "json")))))]
    (is (false? (:ok (json/parse-string out true))))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
