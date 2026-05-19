(ns org-tasks.effective
  "Setupfile chain expansion.

  Walks `#+SETUPFILE:` declarations in declaration order (recursive,
  depth-capped, cycle-broken) and prepends their content so file-keyword
  lookups and `#+LINK:` template resolution see the merged view.

  Unresolved or out-of-tree setupfiles are silently ignored so fresh
  checkouts continue to work as they did when only one setupfile was
  followed."
  (:require [babashka.fs :as fs]
            [org-tasks.parser :as parser]
            [org-tasks.paths :as paths]))

(def ^:private max-setupfile-depth 8)

(defn read-effective-org-content
  "Return CONTENT preceded by all readable in-project `#+SETUPFILE:`
  contents.

  Setupfiles are expanded recursively, in declaration order, with a
  small depth guard and a visited set to break cycles."
  ([project-root file-path content]
   (read-effective-org-content project-root file-path content #{} 0))
  ([project-root file-path content visited depth]
   (if (>= depth max-setupfile-depth)
     content
     (let [base-dir (str (fs/parent file-path))
           setup-targets (parser/get-file-keywords content "SETUPFILE")
           parts
           (reduce
             (fn [{:keys [acc seen]} raw]
               (let [target (or (parser/extract-org-link-target raw)
                                (when raw (clojure.string/trim raw)))]
                 (if (or (nil? target) (empty? target))
                   {:acc acc :seen seen}
                   (let [setup-path (paths/resolve-project-path
                                      project-root base-dir target)]
                     (cond
                       (nil? setup-path)        {:acc acc :seen seen}
                       (contains? seen setup-path) {:acc acc :seen seen}
                       :else
                       (let [seen' (conj seen setup-path)]
                         (try
                           (let [setup-content (slurp setup-path)
                                 expanded (read-effective-org-content
                                            project-root setup-path setup-content
                                            seen' (inc depth))]
                             {:acc (conj acc expanded) :seen seen'})
                           (catch Throwable _
                             ;; Best effort: unresolved setupfiles must
                             ;; not make task loading fail.
                             {:acc acc :seen seen'}))))))))
             {:acc [] :seen visited}
             setup-targets)]
       (if (empty? (:acc parts))
         content
         (str (clojure.string/join "\n" (:acc parts)) "\n" content))))))
