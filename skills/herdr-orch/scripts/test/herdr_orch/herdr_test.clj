(ns herdr-orch.herdr-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [herdr-orch.herdr :as herdr]))

(def expected-operations
  #{["workspace" "list"] ["workspace" "create"] ["workspace" "focus"]
    ["tab" "list"] ["tab" "create"] ["tab" "focus"]
    ["pane" "list"] ["pane" "current"] ["pane" "layout"] ["pane" "split"]
    ["pane" "rename"] ["pane" "get"] ["pane" "run"] ["pane" "read"]
    ["pane" "send-text"] ["pane" "send-keys"] ["pane" "wait-output"] ["pane" "close"]
    ["agent" "list"] ["agent" "get"] ["agent" "start"] ["agent" "prompt"]
    ["agent" "wait"] ["agent" "read"] ["agent" "send-keys"] ["agent" "focus"]
    ["agent" "rename"]})

(defn- success [argv]
  (let [command (vec (take 2 argv))]
    {:ok true :out "output" :value
     (case command
       ["pane" "layout"] {:result {:layout {:panes [{:pane_id (System/getenv "HERDR_PANE_ID") :rect {:width 160 :height 80}}]}}}
       ["pane" "current"] {:result {:pane {:pane_id "caller"}}}
       ["pane" "wait-output"] {:result {:matched_line "matched"}}
       ["agent" "get"] {:result {:agent {:agent_status "idle"}}}
       ["agent" "start"] {:result {:agent {:name "agent"}}}
       ["tab" "create"] {:result {:tab {:tab_id "tab"} :root_pane {:pane_id "root"}}}
       {:result {}})}))

(deftest every-herdr-operation-has-a-wrapper
  (is (= expected-operations herdr/operations))
  (let [calls (atom [])]
    (with-redefs [herdr/invoke (fn [argv] (swap! calls conj argv) (success argv))]
      (herdr/workspace-list!)
      (herdr/workspace-create! {:cwd "/tmp" :label "workspace" :env {"A" "B"} :focus true})
      (herdr/workspace-focus! "workspace")
      (herdr/tab-list! "workspace")
      (herdr/tab-create! {:cwd "/tmp" :label "tab" :env {}})
      (herdr/tab-focus! "tab")
      (herdr/pane-list! "workspace")
      (herdr/current-pane!)
      (herdr/caller-rect!)
      (herdr/split! {:direction "right" :cwd "/tmp" :env {}})
      (herdr/rename! "pane" "renamed")
      (herdr/pane! "pane")
      (herdr/pane-run! "pane" "true")
      (herdr/pane-read! "pane" {})
      (herdr/pane-send-text! "pane" "text")
      (herdr/pane-send-keys! "pane" ["enter"])
      (herdr/pane-wait-output! "pane" {:match "matched"})
      (herdr/close! "other")
      (herdr/agents)
      (herdr/agent! "agent")
      (herdr/start! "agent" "pi" "pane" [])
      (herdr/prompt! "agent" "prompt")
      (herdr/wait! "agent" 1)
      (herdr/agent-read! "agent" {})
      (herdr/agent-send-keys! "agent" ["enter"])
      (herdr/agent-focus! "agent")
      (herdr/agent-rename! "agent" "renamed" false)
      (is (= expected-operations
             (set/intersection expected-operations (set (map #(vec (take 2 %)) @calls))))))))

(deftest self-close-is-refused
  (with-redefs [herdr/current-pane! (constantly {:pane_id "self"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Refusing to close the pane the caller is running in"
                          (herdr/close! "self")))))

(deftest no-output-mutations-succeed-on-exit-zero
  (let [calls (atom [])]
    (with-redefs [herdr/invoke (fn [argv] (swap! calls conj argv) {:ok true :value nil :out ""})]
      (is (nil? (herdr/pane-run! "pane" "echo ok")))
      (is (nil? (herdr/pane-send-text! "pane" "literal")))
      (is (nil? (herdr/pane-send-keys! "pane" ["enter"])))
      (is (nil? (herdr/agent-send-keys! "agent" ["esc"])))
      (is (= [["pane" "run" "pane" "echo ok"]
              ["pane" "send-text" "pane" "literal"]
              ["pane" "send-keys" "pane" "enter"]
              ["agent" "send-keys" "agent" "esc"]]
             @calls)))))

(deftest reads-fall-back-to-visible-and-truncate-tail
  (let [calls (atom [])
        output (str/join "\n" (concat (repeat 2000 "old") ["tail"]))]
    (with-redefs [herdr/invoke (fn [argv]
                                 (swap! calls conj argv)
                                 {:ok true :value nil :out (if (= "visible" (last argv)) output "")})]
      (doseq [source ["recent" "recent-unwrapped"]]
        (reset! calls [])
        (let [rendered (herdr/pane-read! "pane" {:source source})]
          (is (= [["pane" "read" "pane" "--source" source]
                  ["pane" "read" "pane" "--source" "visible"]]
                 @calls))
          (is (str/starts-with? rendered "[Showing last 2000 of 2001 lines]"))
          (is (str/ends-with? rendered "tail"))))))
  (let [output (str/join "\n" (concat (repeat 2000 "old") ["tail"]))]
    (with-redefs [herdr/invoke (constantly {:ok true :out "" :value {:result {:matched_line "matched" :read {:text output}}}})]
      (let [result (herdr/pane-wait-output! "pane" {:match "matched"})]
        (is (str/starts-with? (:output result) "[Showing last 2000 of 2001 lines]"))
        (is (str/ends-with? (:output result) "tail")))))
  (let [output (str (apply str (repeat herdr/max-output-bytes "x")) "tail")
        rendered (herdr/truncate-output output)
        content (second (str/split rendered #"\n" 2))]
    (is (str/ends-with? content "tail"))
    (is (<= (alength (.getBytes content "UTF-8")) herdr/max-output-bytes))))

(deftest errors-and-list-output-are-actionable
  (let [error (herdr/herdr-error ["pane" "get" "missing"]
                                 "{\"error\":{\"code\":\"pane_not_found\",\"message\":\"no such pane\"}}")]
    (is (= "herdr pane get missing failed: no such pane" (:message error))))
  (is (= "build: [p1] (current, pi, working) /tmp/project\nshell: [p2] (idle) /tmp/shell"
         (herdr/render-panes [{:pane_id "p1" :label "build" :agent "pi" :agent_status "working" :cwd "/tmp/project"}
                              {:pane_id "p2" :label "shell" :agent_status "idle" :cwd "/tmp/shell"}]
                             "p1")))
  (is (= "worker: [p1] (idle, focused) /tmp/project\nmanual: [p2] (working)"
         (herdr/render-agents [{:name "worker" :pane_id "p1" :agent_status "idle" :focused true :cwd "/tmp/project"}
                               {:agent "manual" :pane_id "p2" :agent_status "working"}]))))

;; Regression: the CLI stores options as a multimap, so a `--lines 6` flag arrives as
;; ["6"]. Passing that slice straight through produced the literal argv value `["6"]`,
;; which Herdr rejected. The fixtures could not catch it because they emit JSON for every
;; subcommand, so the resulting plain-text `Error: ...` on stderr never appeared.
(deftest read-options-reach-herdr-as-scalars
  (let [calls (atom [])]
    (with-redefs [herdr/invoke (fn [argv] (swap! calls conj argv) {:ok true :out "text" :value nil})]
      (herdr/pane-read! "w:p" {:source "visible" :lines "6"})
      (herdr/agent-read! "child" {:source "visible" :lines "6"})
      (herdr/pane-wait-output! "w:p" {:match "ready" :source "visible" :lines "6" :timeout "3000"}))
    (doseq [argv @calls]
      (is (not-any? sequential? argv) (str "argv carries a collection: " (pr-str argv)))
      (when-let [tail (second (drop-while #(not= "--lines" %) argv))]
        (is (= "6" tail))))))

;; Regression: Herdr writes a JSON envelope for most failures but plain text for argument
;; errors, and the read family emits raw terminal text on success. Neither may be parsed
;; as JSON: a hard parse failure masked the actionable message with a Jackson token error.
(deftest non-json-output-is-tolerated
  (with-redefs [herdr/invoke (fn [_] {:ok true :out "\u276f echo hi\nhi" :value nil})]
    (is (str/includes? (herdr/pane-read! "w:p" {}) "hi")))
  (with-redefs [herdr/invoke (fn [argv]
                               {:ok false :error (assoc (herdr/herdr-error argv "Error: invalid value for --lines: bogus")
                                                        :exit 2)})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid value for --lines: bogus"
                          (herdr/pane-read! "w:p" {:lines "bogus"})))))
