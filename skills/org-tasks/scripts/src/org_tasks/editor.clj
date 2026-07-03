(ns org-tasks.editor
  "Editor/launcher abstraction for the org-tasks TUI.

  Resolves *which* editor to open task sources in and builds the right argv for
  it, so the launcher can be made configurable later (emacsclient, Vim, VS
  Code, …) without touching call sites. Emacs is the default and gets
  daemon-ensure parity with the pi tasks extension.

  Selection order (first non-blank wins):
    1. explicit `:editor` opt
    2. `OT_EDITOR` env var
    3. `EDITOR` env var
    4. `emacsclient` (default)"
  (:require [babashka.process :as process]
            [clojure.string :as str]))

(def ^:dynamic *getenv*
  "Indirection for environment lookup so tests can validate editor resolution
  without mutating the process environment."
  (fn [k] (System/getenv k)))

(defn detect-kind
  "Classify an editor binary into a launch convention keyword."
  [binary]
  (let [base (-> (str binary) (str/split #"[/\\]") last str/lower-case)]
    (cond
      (re-find #"emacs" base)         :emacs
      (re-find #"code|codium|cursor" base) :vscode
      (re-find #"n?vim|^vi$" base)    :vim
      :else                           :generic)))

(defn resolve-editor
  "Return `{:binary :kind}` for the configured editor."
  [opts]
  (let [binary (or (not-empty (:editor opts))
                   (not-empty (*getenv* "OT_EDITOR"))
                   (not-empty (*getenv* "EDITOR"))
                   "emacsclient")]
    {:binary binary :kind (detect-kind binary)}))

(defn argv
  "Build the argv that opens PATH at LINE for the given editor spec."
  [{:keys [binary kind]} path line]
  (let [line (or line 1)]
    (case kind
      ;; emacsclient against a daemon: `-n` returns immediately so the TUI is
      ;; not blocked waiting for the buffer to be closed.
      :emacs  [binary "-n" (str "+" line) path]
      :vscode [binary "--goto" (str path ":" line)]
      ;; vim/nvim and most $EDITORs honour the `+LINE file` convention.
      [binary (str "+" line) path])))

(defn- emacs-server-up? [client]
  (try
    (zero? (:exit (process/shell {:continue true :out :string :err :string}
                                 client "-e" "t")))
    (catch Throwable _ false)))

(defn ensure-emacs-server!
  "Probe the Emacs server; start `emacs --daemon` and poll if it is not yet
  reachable. Returns true when the server answers. Mirrors the pi tasks
  extension's `ensureEmacsServer`."
  ([]
   (ensure-emacs-server!
    (or (not-empty (*getenv* "EMACSCLIENT_BINARY")) "emacsclient")
    (or (not-empty (*getenv* "EMACS_BINARY")) "emacs")))
  ([client daemon]
   (or (emacs-server-up? client)
       (do
         (try
           (process/shell {:continue true :out :string :err :string} daemon "--daemon")
           (catch Throwable _ nil))
         (loop [i 0]
           (cond
             (emacs-server-up? client) true
             (< i 10) (do (Thread/sleep 300) (recur (inc i)))
             :else false))))))

(defn open!
  "Open PATH at LINE in the resolved editor. For the Emacs kind, ensure a
  server/daemon is reachable first. Returns nil on success or an error message
  string on failure."
  [opts path line]
  (let [{:keys [kind] :as spec} (resolve-editor opts)]
    (try
      (when (= :emacs kind)
        (when-not (ensure-emacs-server!)
          (throw (ex-info "Could not reach or start Emacs server" {}))))
      (let [[cmd & args] (argv spec path line)]
        (apply process/shell {:continue true :out :inherit :err :inherit} cmd args))
      nil
      (catch Throwable e (.getMessage e)))))
