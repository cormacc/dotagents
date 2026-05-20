(ns org-tasks.styling
  "Bling-backed styling helpers for `ot --format text` output.

  This namespace is the only place command renderers should touch
  `bling.core`. Helpers accept the command opts map (or any map carrying
  `:color?`) and return plain strings when colour is disabled."
  (:require [bling.core :refer [bling]]
            [clojure.string :as str]
            [org-tasks.output :as out]))

(def status-palette
  {"TODO" :yellow
   "STARTED" :blue
   "WAITING" :orange
   "DONE" :green
   "CANCELLED" :gray})

(def priority-palette
  {"A" :red
   "B" :orange
   "C" :yellow
   "D" :blue})

(def palette-keywords
  "Every bling keyword intentionally used by this namespace. Tests assert
  that the installed bling version accepts each one."
  #{:yellow :blue :orange :green :gray :red :magenta :italic.magenta
    :purple})

;; Tree drawing characters reused by callers that render hierarchical
;; output (currently `ot list`). Centralised so future renderers stay
;; visually consistent. Each glyph is two columns wide; subtasks abut
;; the next column (typically STATUS) with no separating space.
(def tree-branch "├─")
(def tree-last "└─")
(def tree-pipe "│ ")
(def tree-gap "  ")

(def ansi-re
  #"\u001B\[[0-?]*[ -/]*[@-~]")

(defn strip-ansi [s]
  (str/replace (str s) ansi-re ""))

(defn styled
  "Style `text` with bling `style` unless colour is disabled by opts."
  [opts style text]
  (let [text (str text)]
    (if (out/color-enabled? opts)
      (bling [style text])
      text)))

(defn status
  ([opts status-token]
   (status opts status-token status-token))
  ([opts status-token text]
   (styled opts (get status-palette status-token :neutral) text)))

(defn priority
  ([opts priority-token]
   (priority opts priority-token (when priority-token (str "[#" priority-token "]"))))
  ([opts priority-token text]
   (if priority-token
     (styled opts (get priority-palette priority-token :neutral) text)
     "")))

(defn tag-cluster [opts tags]
  (if (seq tags)
    (styled opts :italic.magenta (str ":" (str/join ":" tags) ":"))
    ""))

(defn linked-issue-cluster [opts labels]
  (if (seq labels)
    (styled opts :purple (str/join " " (map #(str "⤴" %) labels)))
    ""))

(defn local-marker [opts text]
  (styled opts :magenta text))

(defn selected-marker [opts text]
  (styled opts :yellow text))

(defn gutter
  "Style a left-margin tree-drawing prefix (box-drawing characters).
  Rendered subtly so the structure reads without competing with the
  task metadata."
  [opts text]
  (styled opts :gray text))
