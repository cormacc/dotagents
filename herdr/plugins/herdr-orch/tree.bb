#!/usr/bin/env bb
;; Sidebar tree entry point: `tree.bb reconcile` or `toggle`. See src/orch_tree.clj.
(ns tree (:require [orch-tree :as tree]))
(System/exit (apply tree/-main *command-line-args*))
