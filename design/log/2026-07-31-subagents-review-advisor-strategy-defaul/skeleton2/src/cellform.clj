(ns cellform
  "A small spreadsheet formula engine.

  Implement `evaluate` per the contract in ASSIGNMENT.md. The namespace, the
  function name, its arity, and the data shapes are fixed: do not rename them, do
  not change the namespace, and do not move this file. Helper functions may be
  added freely.")

(defn evaluate
  "Resolve every cell in `sheet`.

  `sheet` is a map of cell reference string (e.g. \"A1\") to source string, where
  a source beginning with `=` is a formula and anything else is a literal.

  Returns a map with exactly the same keys, each mapped to a resolved value: a
  number, a string, or one of `:err/parse`, `:err/cycle`, `:err/type`,
  `:err/div0`.

  Evaluation is dependency driven, so declaration order is irrelevant. See
  ASSIGNMENT.md for the grammar, the four functions, empty-cell coercion, strict
  string typing, strict `IF`, cycle marking, and the error-precedence order."
  [sheet]
  (throw (ex-info "evaluate not implemented" {:cell-count (count sheet)})))
