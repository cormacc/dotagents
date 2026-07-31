# Assignment: cellform — a spreadsheet formula engine (Babashka)

Implement `evaluate` in `src/cellform.clj`. The namespace, the function name, its
arity, and the data shapes below are fixed by the skeleton — do not rename or
relocate them. Add helpers freely.

```clojure
(cellform/evaluate sheet) ; => resolved sheet
```

`sheet` is a map of **cell reference string → source string**. The return value is
a map with **exactly the same keys**, each mapped to a resolved value.

```clojure
(evaluate {"A1" "2" "A2" "3" "B1" "=A1+A2"})
;; => {"A1" 2 "A2" 3 "B1" 5}
```

The rules below are the whole specification. Read them; they deliberately differ
from real spreadsheet behaviour in several places, and those differences are load
bearing.

## 1. Cell sources

A source string is either a **formula** (first character `=`) or a **literal**.

A literal is a **number** if the entire string matches an optional leading `-`,
digits, and an optional single `.` with digits (`12`, `-4`, `3.5`). Otherwise it
is a **string**, taken verbatim. The empty string `""` makes the cell *empty*.

## 2. Values

A resolved value is a number, a string, or one of exactly four error keywords:

| Error | Meaning |
|---|---|
| `:err/parse` | the cell's own formula is malformed |
| `:err/cycle` | the cell is in a dependency cycle, or depends on one |
| `:err/type` | an operation received a value of the wrong type |
| `:err/div0` | division by zero |

Numbers are compared numerically when scoring: returning `3` or `3.0` are
equally acceptable. `/` performs exact division, so `=7/2` is `3.5`.

## 3. Grammar

```
formula := '=' expr
expr    := term (('+' | '-') term)*
term    := factor (('*' | '/') factor)*
factor  := '-'? primary
primary := number | string | funcall | range | ref | '(' expr ')'
funcall := NAME '(' [arg (',' arg)*] ')'
ref     := [A-Z] [0-9]+
range   := ref ':' ref
```

- `*` and `/` bind tighter than `+` and `-`; parentheses override.
- Whitespace between tokens is insignificant.
- String literals inside formulas use double quotes: `="ab"`.
- Column references are a **single** uppercase letter `A`–`Z`; rows are `1`–`999`.
- Anything not matching this grammar is a malformed formula.

## 4. Functions

Exactly four, name-matched case-sensitively:

| Function | Arity | Behaviour |
|---|---|---|
| `SUM` | 1+ | Adds all numeric values among its arguments. **Silently skips strings and empty cells.** |
| `COUNT` | 1+ | Counts arguments that resolve to numbers. Strings and empty cells count 0. |
| `IF` | exactly 3 | `IF(cond, then, else)` — see §7. |
| `CONCAT` | 1+ | Joins arguments as text. Numbers render integrally when integral (`3`, not `3.0`), otherwise as their decimal form (`3.5`). Empty cells render as `""`. |

Wrong arity for any of these is a malformed formula. An unknown function name is
a malformed formula.

**Ranges are accepted only by `SUM` and `COUNT`.** A range anywhere else —
including as an `IF` or `CONCAT` argument, or as an operand of `+ - * /` — yields
`:err/type`. A range `X<m>:Y<n>` denotes the whole rectangle between the two
corners inclusive, regardless of which corner is given first.

## 5. Empty cells and missing references

A reference to a cell that is **absent from the sheet, or present but empty**,
coerces by context:

- arithmetic (`+ - * /`, `SUM`) → `0`
- `CONCAT` → `""`
- `COUNT` → not counted
- `IF` condition → `0` (falsey)

## 6. Strict typing — strings never coerce to numbers

A string operand in arithmetic yields `:err/type`, **even when the string looks
numeric**. `="1"+1` is `:err/type`, not `2`. This applies to string literals and
to references to string-valued cells alike.

`CONCAT` is the only function that accepts strings.

## 7. Strict `IF` — both branches always evaluate

`IF(cond, then, else)` evaluates **all three** arguments, always.

- If `cond` is not a number, the result is `:err/type`.
- `cond` of `0` selects `else`; any non-zero number selects `then`.
- **An error in either branch propagates even when that branch is not selected.**
  `=IF(1,10,1/0)` is `:err/div0`, not `10`.

## 8. Dependency evaluation

Cells may be declared in any order; evaluation is dependency driven, not
positional. A cell referencing a cell that references a third resolves correctly
however the map is ordered.

## 9. Cycles

If a set of cells forms a reference cycle, **every participant** resolves to
`:err/cycle`. Additionally, **every cell that transitively depends on a cycle
participant** resolves to `:err/cycle`, however far downstream.

## 10. Error precedence

Resolution order for a single cell:

1. If the cell's own formula is malformed → `:err/parse`. Its operands are never
   consulted, so a malformed formula referencing a cycle is still `:err/parse`.
2. Otherwise, if the cell is a cycle participant or depends on one → `:err/cycle`.
3. Otherwise, if any operand resolves to an error, the result is the
   **highest-precedence** error among all operands, by this total order:

   ```
   :err/cycle  >  :err/parse  >  :err/div0  >  :err/type
   ```

   Note this ranks `:err/parse` *below* `:err/cycle` during propagation even
   though step 1 ranks a cell's *own* parse error first.
4. Otherwise compute. Division by zero → `:err/div0`; a type violation →
   `:err/type`.

`SUM` and `COUNT` skip strings and empties but **never** skip errors: an error
anywhere in their arguments or ranges propagates by the order above.

## Constraints

- Babashka only, no dependencies beyond the Clojure/bb standard library.
- `evaluate` must be pure: no I/O, no global mutable state, no `println`.
- Do not add a `deps.edn`, a build step, or extra source paths.
- Keep it readable and well factored — a parser, a dependency pass, and an
  evaluator are naturally separate concerns.

## Verifying

`bb test` runs the visible smoke tests in `test/cellform_smoke_test.clj`. They are
a thin happy-path sample, **not** the standard you will be judged against.
Correctness is judged by a broader suite you do not have, exercising the rules
above with inputs other than the examples shown here. Implement the rules
themselves rather than the specific examples; extend the smoke tests freely.

## Definition of done

- `src/cellform.clj` implements `evaluate` per the contract.
- `bb test` passes.
- No stray files beyond your implementation and any tests you add.
