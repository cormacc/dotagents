#!/usr/bin/env bb
;; Locate and extract the repository's pi sessions for the wide-scope retro.
;;
;;   sessions.bb list    --root <repo> [--since D] [--until D] [--sessions-dir P]
;;   sessions.bb extract --root <repo> [--since D] [--until D] [--run NAME] [--sessions-dir P]
;;   sessions.bb due     --root <repo> [--days 7] [--today D] [--exclude-id SESSION_ID] [--sessions-dir P]
;;
;; Selection is by the `cwd` in each session's header line, never by the encoded
;; directory name. Only the header line is read for selection; `extract` reads the
;; bodies of selected sessions only. The sessions directory defaults to
;; $RETRO_SESSIONS_DIR, then ~/.pi/agent/sessions. Dates are YYYY-MM-DD (UTC).
;; Output is JSON on stdout; errors are `error: <name>: <detail>` on stderr, exit 1.
(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str])
(import '(java.time LocalDate ZoneOffset Instant))

(def supported-version 3)
(def max-skeleton-lines 400)
(def user-limit 400)
(def arg-limit 120)
(def result-limit 200)

(defn fail! [name detail]
  (throw (ex-info (str name ": " detail) {:fail name})))

(defn- sh [dir & args]
  (let [{:keys [exit out]} @(process/process (into ["git" "-C" (str dir)] args)
                                             {:out :string :err :string})]
    (when (zero? exit) (str/trim out))))

(defn- norm [p] (str (fs/normalize (fs/absolutize p))))

(defn repo-roots
  "Main checkout and every registered worktree of the repository at `root`
  (this covers Herdr managed checkouts). A submodule's shared git directory is
  listed as a worktree but is not a checkout; its `core.worktree` names the
  main checkout, so list entries that are git directories are replaced by it."
  [root]
  (let [out (or (sh root "worktree" "list" "--porcelain")
                (fail! "not-a-git-repository" (str root)))
        listed (keep #(when (str/starts-with? % "worktree ") (subs % 9))
                     (str/split-lines out))
        git-dir? #(fs/directory? (fs/path % "objects"))
        common (some-> (sh root "rev-parse" "--path-format=absolute" "--git-common-dir"))
        main (when common
               (if-let [wt (sh common "config" "core.worktree")]
                 (str (fs/path common wt))
                 (when (= ".git" (str (fs/file-name common))) (str (fs/parent common)))))]
    (->> (concat [root main] (remove git-dir? listed))
         (remove nil?)
         (map norm)
         distinct
         vec)))

(defn inside?
  "Component-wise containment: /x/agents-other is not inside /x/agents."
  [root cwd]
  (let [parts #(vec (map str (fs/components (norm %))))
        r (parts root) c (parts cwd)]
    (and (<= (count r) (count c)) (= r (subvec c 0 (count r))))))

(defn read-header [file]
  (with-open [rdr (clojure.java.io/reader (str file))]
    (when-let [line (first (line-seq rdr))]
      (let [h (try (json/parse-string line true)
                   (catch Exception _ (fail! "bad-session-header" (str file))))]
        (when-not (= "session" (:type h)) (fail! "bad-session-header" (str file)))
        (when-not (= supported-version (:version h))
          (fail! "unsupported-session-version"
                 (str file " has version " (pr-str (:version h)) "; supported: " supported-version)))
        h))))

(defn sessions-dir [opts]
  (or (:sessions-dir opts) (System/getenv "RETRO_SESSIONS_DIR")
      (str (fs/path (System/getProperty "user.home") ".pi" "agent" "sessions"))))

(defn- day [s] (LocalDate/parse (subs s 0 10)))

(defn scan
  "All headers under the sessions directory, with the in-repo ones selected by
  `cwd`. Returns {:scanned n :sessions [...]}, oldest first."
  [opts]
  (let [dir (sessions-dir opts)
        roots (repo-roots (:root opts))
        since (some-> (:since opts) LocalDate/parse)
        until (some-> (:until opts) LocalDate/parse)
        files (when (fs/directory? dir) (sort (map str (fs/glob dir "*/*.jsonl"))))
        headed (keep (fn [f] (when-let [h (read-header f)] (assoc h :path f))) files)
        in-repo (filter (fn [h] (and (string? (:cwd h))
                                     (some #(inside? % (:cwd h)) roots)
                                     (or (nil? since) (not (.isBefore (day (:timestamp h)) since)))
                                     (or (nil? until) (not (.isAfter (day (:timestamp h)) until)))))
                        headed)]
    {:scanned (count files)
     :roots roots
     :sessions (->> in-repo
                    (map #(select-keys % [:id :timestamp :cwd :path]))
                    (sort-by :timestamp)
                    vec)}))

;; ---- extract ------------------------------------------------------------

(defn- clip [s n]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) n) (str (subs s 0 n) "...") s)))

(defn- text-of [content]
  (if (string? content)
    content
    (str/join " " (keep #(when (= "text" (:type %)) (:text %)) content))))

(defn skeleton-lines [file]
  (with-open [rdr (clojure.java.io/reader file)]
    (doall
     (mapcat
      (fn [line]
        (let [r (try (json/parse-string line true) (catch Exception _ nil))
              m (:message r) ts (:timestamp r)]
          (when (= "message" (:type r))
            (case (:role m)
              "user" [(str ts " USER " (clip (text-of (:content m)) user-limit))]
              "assistant" (for [c (:content m) :when (= "toolCall" (:type c))]
                            (str ts " CALL " (:name c) " " (clip (json/generate-string (:arguments c)) arg-limit)))
              "toolResult" (when (:isError m)
                             [(str ts " FAIL " (:toolName m) " " (clip (text-of (:content m)) result-limit))])
              nil))))
      (line-seq rdr)))))

(defn extract [opts]
  (let [run (or (:run opts) (str "wide-" (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
                                                 (java.time.LocalDateTime/now))))
        _ (when-not (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" run) (fail! "bad-run-name" run))
        out (fs/path (norm (:root opts)) ".tmp" "retro" run)
        {:keys [scanned sessions]} (scan opts)]
    (fs/create-dirs out)
    {:run run
     :dir (str out)
     :scanned scanned
     :files (vec (for [s sessions
                       :let [lines (skeleton-lines (:path s))
                             cut (> (count lines) max-skeleton-lines)
                             f (str (fs/path out (str (:id s) ".txt")))]]
                   (do (spit f (str "# session " (:id s) " " (:timestamp s) " cwd " (:cwd s) "\n"
                                    (str/join "\n" (take max-skeleton-lines lines)) "\n"
                                    (when cut (str "# truncated: " (count lines) " lines\n"))))
                       {:id (:id s) :file f :lines (count lines) :truncated cut})))}))

;; ---- due ----------------------------------------------------------------

(defn wide-run-dates
  "Dates of `:KIND: wide-run` entries: the date of the nearest preceding
  top-level `* YYYY-MM-DD` heading."
  [log-text]
  (loop [[l & more] (str/split-lines log-text) date nil acc []]
    (cond
      (nil? l) acc
      (re-find #"^\* \d{4}-\d{2}-\d{2}" l) (recur more (LocalDate/parse (subs l 2 12)) acc)
      (and date (re-find #"^\s*:KIND:\s+wide-run\s*$" l)) (recur more date (conj acc date))
      :else (recur more date acc))))

(defn due [opts]
  (let [days (or (:days opts) 7)
        today (if (:today opts) (LocalDate/parse (:today opts)) (LocalDate/now ZoneOffset/UTC))
        ;; The marker may sit in any checkout's RETROLOG.org: an unmerged worktree
        ;; does not see the main checkout's newer entry otherwise.
        logs (map #(fs/path % "RETROLOG.org") (repo-roots (:root opts)))
        last-run (->> logs (filter fs/exists?) (mapcat #(wide-run-dates (slurp (str %)))) sort last)
        recent? (and last-run (.isAfter last-run (.minusDays today days)))
        since (or last-run (.minusDays today days))
        {:keys [scanned sessions]} (scan (assoc opts :since (str since)))
        sessions (remove #(= (:exclude-id opts) (:id %)) sessions)
        n (count sessions)]
    {:due (boolean (and (not recent?) (pos? n)))
     :last_wide_run (some-> last-run str)
     :since (str since)
     :days days
     :sessions_since n
     :scanned scanned}))

;; ---- main ---------------------------------------------------------------

(def spec {:root {:coerce :string} :since {:coerce :string} :until {:coerce :string}
           :run {:coerce :string} :days {:coerce :long} :today {:coerce :string}
           :sessions-dir {:coerce :string} :exclude-id {:coerce :string}})

(defn -main [[cmd & args]]
  (try
    (let [opts (cli/parse-opts args {:spec spec})
          opts (update opts :root #(or % "."))
          result (case cmd
                   "list" (scan opts)
                   "extract" (extract opts)
                   "due" (due opts)
                   (fail! "usage" "sessions.bb list|extract|due --root <repo> ..."))]
      (println (json/generate-string result {:pretty true})))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println (str "error: " (ex-message e))))
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
