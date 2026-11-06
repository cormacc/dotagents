#!/usr/bin/env bb
(ns adapter (:require [org-tasks-host :as host]))
(apply host/-main *command-line-args*)
