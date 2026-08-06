(ns org-tasks.parser.issues
  "Linked-issue helpers for org task maps."
  (:require [clojure.string :as str]
            [org-tasks.parser.links :as links]
            [org-tasks.parser.properties :as properties]))

(defn- split-linked-issue-tokens
  "Split a `:LINKED_ISSUES:` value into `[[..]]` blobs and bare tokens
  on whitespace."
  [^String value]
  (loop [i 0
         tokens []]
    (let [n (count value)]
      (cond
        (>= i n) tokens

        (Character/isWhitespace (.charAt value i))
        (recur (inc i) tokens)

        (and (< (+ i 1) n)
             (= \[ (.charAt value i))
             (= \[ (.charAt value (inc i))))
        (let [end (str/index-of value "]]" (+ i 2))]
          (if end
            (recur (+ end 2) (conj tokens (subs value i (+ end 2))))
            (let [j (loop [j i]
                      (if (or (>= j n) (Character/isWhitespace (.charAt value j)))
                        j (recur (inc j))))]
              (recur j (conj tokens (subs value i j))))))

        :else
        (let [j (loop [j i]
                  (if (or (>= j n) (Character/isWhitespace (.charAt value j)))
                    j (recur (inc j))))]
          (recur j (conj tokens (subs value i j))))))))

(defn get-linked-issues
  "Resolve `:LINKED_ISSUES:` for a task. Returns a vector of
  `{:url, :label, :raw-token, :error?}` maps."
  [task content-or-templates]
  (let [value (properties/get-drawer-property task "LINKED_ISSUES")]
    (if-not (and value (seq value))
      []
      (let [templates (if (string? content-or-templates)
                        (links/parse-link-templates content-or-templates)
                        (or content-or-templates {}))]
        (mapv
          (fn [raw-token]
            (let [link (links/extract-org-link raw-token)]
              (if-not link
                {:url nil :label raw-token :raw-token raw-token
                 :error "LINKED_ISSUES token is not an org link"}
                (let [typed (links/typed-link-parts (:target link))]
                  (if typed
                    (let [template (get templates (:prefix typed))]
                      (if-not template
                        {:url nil
                         :label (or (:description link) (:key typed))
                         :raw-token raw-token
                         :error (str "Missing #+LINK declaration for prefix "
                                     (:prefix typed))}
                        {:url (links/resolve-link-template template (:key typed))
                         :label (or (:description link) (:key typed))
                         :raw-token raw-token}))
                    {:url (:target link)
                     :label (or (:description link) (:target link))
                     :raw-token raw-token})))))
          (split-linked-issue-tokens value))))))

(defn set-linked-issues
  "Replace `:LINKED_ISSUES:` with whitespace-joined tokens. Empty
  collection clears the property."
  [task tokens]
  (if (empty? tokens)
    (properties/set-drawer-property task "LINKED_ISSUES" nil)
    (properties/set-drawer-property task "LINKED_ISSUES" (str/join " " tokens))))
