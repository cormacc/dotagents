(ns org-tasks.parser.lines
  "Lossless line-classification helpers shared by Org scanners.

  `next-block-kind` owns the protocol's lexical source-block boundary rule.
  Callers keep the returned kind as scanner state. A non-nil input means the
  current line is shielded from structural interpretation."
  (:require [clojure.string :as str]))

(def ^:private block-open-re #"(?i)^\s*#\+BEGIN_(\w+)\b")
(def ^:private block-close-re #"(?i)^\s*#\+END_(\w+)\s*$")

(defn block-boundary?
  "True when `line` is a recognised source-block boundary."
  [^String line]
  (boolean (or (re-find block-open-re line)
               (re-find block-close-re line))))

(defn next-block-kind
  "Return the block kind after scanning `line`.

  `block-kind` is nil outside a block. Marker kinds match without regard to
  case. A close marker only ends a block when its kind matches the active
  kind. An unmatched close marker and nested-looking open marker remain
  opaque block content."
  [block-kind ^String line]
  (if block-kind
    (if-let [[_ close-kind] (re-find block-close-re line)]
      (if (= (str/lower-case close-kind) block-kind)
        nil
        block-kind)
      block-kind)
    (some-> (re-find block-open-re line) second str/lower-case)))
