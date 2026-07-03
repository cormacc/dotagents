(ns org-tasks.editor-test
  (:require [clojure.test :refer [deftest is testing]]
            [org-tasks.editor :as editor]))

(defn- no-env [_] nil)

(deftest resolve-editor-selection-order
  (testing "defaults to emacsclient when nothing is configured"
    (binding [editor/*getenv* no-env]
      (is (= {:binary "emacsclient" :kind :emacs} (editor/resolve-editor {})))))
  (testing "explicit :editor opt wins over the environment"
    (binding [editor/*getenv* (fn [_] "vim")]
      (is (= "code" (:binary (editor/resolve-editor {:editor "code"}))))))
  (testing "OT_EDITOR beats EDITOR"
    (binding [editor/*getenv* (fn [k] (get {"OT_EDITOR" "code" "EDITOR" "vim"} k))]
      (is (= {:binary "code" :kind :vscode} (editor/resolve-editor {})))))
  (testing "EDITOR is the final fallback before the default"
    (binding [editor/*getenv* (fn [k] (get {"EDITOR" "nvim"} k))]
      (is (= {:binary "nvim" :kind :vim} (editor/resolve-editor {}))))))

(deftest detect-kind-classifies-known-editors
  (is (= :emacs (editor/detect-kind "emacsclient")))
  (is (= :emacs (editor/detect-kind "/usr/bin/emacs")))
  (is (= :vscode (editor/detect-kind "code")))
  (is (= :vscode (editor/detect-kind "vscodium")))
  (is (= :vim (editor/detect-kind "nvim")))
  (is (= :vim (editor/detect-kind "vi")))
  (is (= :generic (editor/detect-kind "nano"))))

(deftest argv-uses-per-editor-conventions
  (testing "emacsclient opens non-blocking at +LINE"
    (is (= ["emacsclient" "-n" "+12" "/x/TASKS.org"]
           (editor/argv {:binary "emacsclient" :kind :emacs} "/x/TASKS.org" 12))))
  (testing "VS Code uses --goto file:line"
    (is (= ["code" "--goto" "/x/f.org:7"]
           (editor/argv {:binary "code" :kind :vscode} "/x/f.org" 7))))
  (testing "vim and generic editors use +LINE file, defaulting LINE to 1"
    (is (= ["nvim" "+3" "/x/f.org"]
           (editor/argv {:binary "nvim" :kind :vim} "/x/f.org" 3)))
    (is (= ["nano" "+1" "/x/f.org"]
           (editor/argv {:binary "nano" :kind :generic} "/x/f.org" nil)))))
