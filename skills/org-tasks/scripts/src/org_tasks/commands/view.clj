(ns org-tasks.commands.view
  "View-model commands: data for hosts that render task views natively."
  (:require [org-tasks.compact :as compact]
            [org-tasks.output :as out]
            [org-tasks.tui.tasks :as tasks]))

(defn compact-cmd
  "Emit the compact selected-task view model. Text output prints the lines of
  the TUI compact view at `--width` (default 80)."
  [{:keys [opts]}]
  (let [list-result (tasks/load-tasks opts)
        state (assoc (tasks/initial-state list-result)
                     :watch-files (compact/watch-files list-result)
                     :width (or (:width opts) 80)
                     :height Integer/MAX_VALUE
                     :color? (out/color-enabled? opts))
        text? (= :text (keyword (or (:format opts) :text)))]
    (out/emit-result opts
                     (cond-> (compact/model state)
                       ;; The terminal renderer loads charm; machine callers
                       ;; such as a polling widget skip that cost.
                       text? (assoc :text/lines
                                    (vec ((requiring-resolve 'org-tasks.tui/compact-lines) state)))))))
