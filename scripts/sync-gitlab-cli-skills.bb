#!/usr/bin/env bb

;; Re-sync the vendored gitlab-cli-skills tree from upstream.
;;
;; Upstream ships one flat collection: a `gitlab-cli-skills/` router directory
;; beside 40+ standalone `glab-*/` skill directories, all at the repository
;; root. This repository nests them instead -- router at the vendor root, the
;; `glab-*` directories as its children -- so a harness discovers one skill
;; description rather than 40. `npx skills add/update` cannot express that
;; layout, so this script performs the re-nesting deterministically.
;;
;; Layout invariants this relies on:
;;   - child-to-child links (`../glab-x/SKILL.md`) hold in both layouts,
;;     because siblings stay adjacent;
;;   - only the router changes level, so only its links are rewritten.

(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str]
         '[clojure.pprint :as pp])

(def upstream-default "vince-winkintel/gitlab-cli-skills")
(def vendor-rel "skills/gitlab-cli-skills")
(def router-dir "gitlab-cli-skills")
(def skill-glob #"^glab-")

(defn die [& msg]
  (binding [*out* *err*] (apply println "error:" msg))
  (System/exit 1))

(defn sh-out [& args]
  (let [{:keys [exit out err]} (apply shell {:out :string :err :string :continue true} args)]
    (when-not (zero? exit) (die (str/join " " args) "failed:" (str/trim err)))
    (str/trim out)))

(defn parse-args [args]
  (loop [a args m {:ref "main" :repo upstream-default :apply false}]
    (case (first a)
      nil m
      "--ref" (recur (drop 2 a) (assoc m :ref (second a)))
      "--repo" (recur (drop 2 a) (assoc m :repo (second a)))
      "--apply" (recur (rest a) (assoc m :apply true))
      (die "unknown argument:" (first a)))))

(def opts (parse-args *command-line-args*))
(def repo-root (sh-out "git" "rev-parse" "--show-toplevel"))
(def vendor (fs/path repo-root vendor-rel))
(def scratch (fs/path repo-root ".tmp" "sync-gitlab-cli-skills"))

;; Scratch-target containment: assert before the first destructive operation.
(let [s (str scratch)
      root (str (fs/path repo-root ".tmp"))]
  (when (str/blank? s) (die "scratch path is empty"))
  (when-not (fs/absolute? scratch) (die "scratch path is not absolute:" s))
  (when-not (str/starts-with? s (str root "/")) (die "scratch path escapes" root ":" s))
  (when (= s root) (die "scratch path is the temporary root itself")))

(def clone (fs/path scratch "upstream"))
(def candidate (fs/path scratch "candidate"))

(println "repo-root:" repo-root)
(println "vendor:   " (str vendor))
(println "scratch:  " (str scratch))

(fs/delete-tree scratch)
(fs/create-dirs scratch)

(println (format "\ncloning %s @ %s ..." (:repo opts) (:ref opts)))
(shell "git" "clone" "--depth" "1" "--quiet"
       "--branch" (:ref opts)
       (format "https://github.com/%s.git" (:repo opts))
       (str clone))

(def commit (sh-out "git" "-C" (str clone) "rev-parse" "HEAD"))

;; Upstream layout assertions. If upstream repackages, stop rather than
;; silently produce a wrong tree.
(let [router-skill (fs/path clone router-dir "SKILL.md")
      children (->> (fs/list-dir clone)
                    (filter fs/directory?)
                    (map (comp str fs/file-name))
                    (filter #(re-find skill-glob %)))]
  (when-not (fs/exists? router-skill)
    (die "upstream has no" (str router-dir "/SKILL.md") "-- layout changed"))
  (when (empty? children)
    (die "upstream has no root-level glab-* directories -- layout changed"))
  (println (format "upstream commit %s, %d glab-* skills" (subs commit 0 12) (count children))))

;; Build the candidate tree: upstream root (minus git metadata and the router
;; directory) plus the router directory's own contents hoisted to the top.
(fs/create-dirs candidate)
(def skip-at-root #{".git" ".github" router-dir})

(defn copy-entry [src dst]
  (if (fs/directory? src)
    (fs/copy-tree src dst {:replace-existing true})
    (fs/copy src dst {:replace-existing true})))

(doseq [p (fs/list-dir clone)
        :let [n (str (fs/file-name p))]
        :when (not (skip-at-root n))]
  (copy-entry p (fs/path candidate n)))

;; The router directory's `scripts/` is a subset of the root `scripts/`.
;; Keep the root copy, but only after proving the shared files are identical --
;; a divergence means upstream forked them and the merge is no longer safe.
(defn assert-identical-subset [src dst n]
  (doseq [f (fs/glob src "**" {:hidden true})
          :when (fs/regular-file? f)
          :let [rel (str (fs/relativize src f))
                other (fs/path dst rel)]]
    (when-not (fs/exists? other)
      (die "router" n "contains" rel "which the root copy lacks -- resolve by hand"))
    (when-not (java.util.Arrays/equals (fs/read-all-bytes f) (fs/read-all-bytes other))
      (die "router" n "file" rel "differs from the root copy -- resolve by hand")))
  (println (format "router %s is an identical subset of the root copy; kept the root copy" n)))

(doseq [p (fs/list-dir (fs/path clone router-dir))
        :let [n (str (fs/file-name p))
              target (fs/path candidate n)]]
  (if (fs/exists? target)
    (if (and (fs/directory? p) (fs/directory? target))
      (assert-identical-subset p target n)
      (die "router entry" n "collides with an upstream root entry -- resolve by hand"))
    (copy-entry p target)))

;; The router moved up one level, so its sibling links lose one `../`.
(let [f (fs/file (fs/path candidate "SKILL.md"))
      before (slurp f)
      after (str/replace before "](../glab-" "](glab-")
      n (- (count (re-seq #"\]\(\.\./glab-" before))
           (count (re-seq #"\]\(\.\./glab-" after)))]
  (when (zero? n) (die "rewrote no router links -- upstream link style changed"))
  (when (re-find #"\]\(\.\./" after) (die "router still contains parent-relative links"))
  (spit f after)
  (println (format "rewrote %d router links to child-relative" n)))

;; Local override: this repository tightens the router's discovery triggers.
(def overrides
  (let [current (fs/path vendor "SKILL.md")]
    (if-not (fs/exists? current)
      (do (println "no existing router SKILL.md -- keeping upstream description") [])
      (let [ours (->> (str/split-lines (slurp (fs/file current)))
                      (filter #(str/starts-with? % "description:"))
                      first)
            f (fs/file (fs/path candidate "SKILL.md"))
            lines (str/split-lines (slurp f))
            idx (first (keep-indexed #(when (str/starts-with? %2 "description:") %1) lines))]
        (cond
          (nil? ours) (do (println "warning: current router has no description line") [])
          (nil? idx) (die "candidate router has no description line")
          (= ours (nth lines idx)) (do (println "description already matches upstream") [])
          :else (do (spit f (str (str/join "\n" (assoc (vec lines) idx ours)) "\n"))
                    (println "re-applied local router description")
                    [:router-description]))))))

(spit (fs/file (fs/path candidate ".vendor.edn"))
      (with-out-str
        (pp/pprint {:source (format "https://github.com/%s" (:repo opts))
                    :ref (:ref opts)
                    :commit commit
                    :synced (str (java.time.LocalDate/now))
                    :layout :nested-router
                    :local-overrides overrides
                    :sync-command "scripts/sync-gitlab-cli-skills.bb --apply"})))

;; Report the candidate diff before replacing anything.
(defn skill-names [dir]
  (->> (fs/list-dir dir)
       (filter fs/directory?)
       (map (comp str fs/file-name))
       (filter #(re-find skill-glob %))
       set))

(let [old (skill-names vendor)
      new (skill-names candidate)
      added (sort (remove old new))
      removed (sort (remove new old))]
  (println (format "\nskills: %d -> %d" (count old) (count new)))
  (when (seq added) (println "added:  " (str/join ", " added)))
  (when (seq removed) (println "removed:" (str/join ", " removed)))
  (let [{:keys [exit out]} (shell {:out :string :continue true}
                                  "git" "diff" "--no-index" "--stat"
                                  (str vendor) (str candidate))]
    (when (> exit 1) (die "git diff failed"))
    (println "\ndiff against the current vendor tree:")
    (println (if (str/blank? out) "  (identical)" out))))

(if (:apply opts)
  (do (fs/delete-tree vendor)
      (fs/move candidate vendor)
      (println "applied. Review with: git -C" repo-root "diff --stat" vendor-rel))
  (println (format "\ndry run. Candidate is at %s\nRe-run with --apply to replace the vendor tree."
                   (str candidate))))
