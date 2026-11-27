#!/usr/bin/env bb
;; Run the herdr-tabs ERT suite in a clean batch Emacs.

(require '[babashka.fs :as fs]
         '[babashka.process :as process])

(def here (str (fs/parent (fs/absolutize *file*))))

(System/exit
 (:exit @(process/process ["emacs" "--batch" "-Q" "-L" here
                           "-l" "herdr-tabs-tests.el"
                           "-f" "ert-run-tests-batch-and-exit"]
                          {:dir here :inherit true})))
