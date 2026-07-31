# Assignment: mini org-outline parser (Babashka)

Implement a small org-outline parser and serializer in `src/orgmini.clj`. The
namespace, the two public function names, their arities, and the document data
shape are fixed by the skeleton — do not rename or relocate them. Add helpers
freely.

```clojure
(orgmini/parse-outline     s)   ; org text  -> document map
(orgmini/serialize-outline doc) ; document map -> org text
```

## Document shape

```clojure
{:preamble "text before the first heading, verbatim; \"\" if none"
 :nodes [{:level      1
          :todo       "TODO"                 ; or nil
          :title      "Heading text"
          :tags       ["bug" "urgent"]       ; [] if none
          :properties [["CUSTOM_ID" "abc"]]  ; [] if none
          :body       "verbatim body text"}]}
```

`:nodes` is a flat vector in document order — do **not** nest children inside
parents. `:level` carries the depth.

## Recognition rules

### Input guarantees

- Input is UTF-8 text using `\n` line endings and always ends with exactly one
  trailing newline.
- A heading line is any line starting with one or more `*` followed by a space.
  Lines that do not match are ordinary text.

### TODO keyword

- Recognized keywords, and only these: `TODO`, `STARTED`, `WAITING`, `DONE`,
  `CANCELLED`.
- A keyword counts only as the first whitespace-delimited word after the stars,
  matched case-sensitively. Anything else — `FIXME`, `todo`, `Done` — is part of
  the title, and `:todo` is `nil`.

### Tags

- Tags are a trailing run matching `:(name:)+` at the very end of the heading
  line, where each `name` is one or more of `A-Z a-z 0-9 _ @ % #`.
- The run must be preceded by at least one space. Everything before that space
  (after the optional keyword) is the title.
- Consequently these are **not** tags, and the colons stay in the title:
  `Aspect ratio 3:4`, `Standup at 10:30`, `Title :not tags:` (space inside the
  run breaks it).
- `:tags` holds bare names without colons.

### Property drawer

- A drawer is recognized only when the line **immediately following** the
  heading line is `:PROPERTIES:` (surrounding whitespace ignored), and a later
  line is `:END:` (likewise) before the next heading or end of input.
- Between them, each line of the form `:KEY: value` contributes one pair. The
  key is the text between the first two colons; the value is the remainder with
  surrounding whitespace trimmed. `:KEY:` alone yields value `""`.
- If `:PROPERTIES:` is not immediately after the heading, or no `:END:` closes
  it, there is no drawer: `:properties` is `[]` and those lines are ordinary
  body text, preserved verbatim.

### Body

- `:body` is everything after the heading line — and after the drawer's `:END:`
  line when a drawer was recognized — up to but excluding the next heading line,
  captured verbatim including blank lines and their newlines.
- `""` when there is no body.

## Canonical output form

`serialize-outline` emits canonical text: `:preamble`, then each node as

1. Heading line: stars, one space, then the keyword plus one space when present,
   then the title, then — when tags are present — one space and `:a:b:`.
2. When `:properties` is non-empty: `:PROPERTIES:`, one `:KEY: value` line per
   pair in order (`:KEY:` with no trailing space when the value is `""`), then
   `:END:`. Never indented.
3. `:body`, verbatim.

Round-trip requirement: for text already in canonical form,
`(serialize-outline (parse-outline s))` must equal `s` byte for byte.
Non-canonical input is normalized — extra padding before a tag run collapses to
one space, an indented drawer is emitted flush left, and property values are
trimmed.

## Constraints

- Babashka only, no dependencies beyond the Clojure/bb standard library. It must
  run under the pinned `bb` on this machine.
- Pure functions: no I/O, no global mutable state, no `println` in the two public
  functions.
- Keep it readable — this is a small, self-contained parser, not a framework.
- Do not add a `deps.edn`, a build step, or extra source paths.

## Verifying

`bb test` runs the visible smoke tests in `test/orgmini_smoke_test.clj`. They are
deliberately a thin happy-path sample, **not** the standard you will be judged
against: passing them does not mean the contract above is satisfied. Correctness
will be judged by a broader suite you do not have, exercising the recognition
rules and edge cases spelled out above with inputs other than the examples shown
here. Implement the rules themselves rather than the specific examples; extend
the smoke tests with your own cases if useful.

## Definition of done

- `src/orgmini.clj` implements both functions per the contract.
- `bb test` passes.
- No stray files beyond your implementation and any tests you add.
