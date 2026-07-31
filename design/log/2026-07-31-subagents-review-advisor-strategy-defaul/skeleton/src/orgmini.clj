(ns orgmini
  "Mini org-outline parser and serializer.

  Implement `parse-outline` and `serialize-outline` per the contract in
  ASSIGNMENT.md. Both function names, arities, and the data shape below are
  fixed: do not rename them, do not change the namespace, and do not move this
  file. Helper functions may be added freely.")

(defn parse-outline
  "Parse org outline text `s` into a document map.

  Returns:

    {:preamble <string>   ; text before the first heading, verbatim; \"\" if none
     :nodes    [<node>]}  ; in document order; [] if there are no headings

  Each node is:

    {:level      <int>              ; number of leading stars, >= 1
     :todo       <string-or-nil>    ; recognized keyword only, else nil
     :title      <string>          ; heading text, no todo keyword, no tags
     :tags       [<string>]        ; tag names without colons; [] if none
     :properties [[<key> <value>]] ; ordered pairs, keys/values sans colons; [] if none
     :body       <string>}         ; verbatim text after heading/drawer; \"\" if none

  See ASSIGNMENT.md for the full recognition rules."
  [s]
  (throw (ex-info "parse-outline not implemented" {:input-length (count s)})))

(defn serialize-outline
  "Render a document map (as returned by `parse-outline`) back to org text.

  Emits the canonical form described in ASSIGNMENT.md. For canonical input,
  (serialize-outline (parse-outline s)) must equal s byte for byte."
  [doc]
  (throw (ex-info "serialize-outline not implemented" {:node-count (count (:nodes doc))})))
