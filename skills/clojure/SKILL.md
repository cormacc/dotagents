---
name: clojure
description: REPL-driven Clojure, ClojureScript, EDN, and Babashka development. Use whenever the user mentions Clojure code, .clj/.cljs/.cljc/.edn/.bb files, deps.edn, project.clj, bb.edn, shadow-cljs, lein, nREPL, namespaces, vars, or clojure-lsp / clj-kondo workflows. Covers writing, editing, debugging, paren-repair, and REPL-first validation.
---

# Clojure Development

REPL-first development against `.clj` / `.cljs` / `.cljc` / `.edn` / `.bb` files.
Most tasks land entirely in source files — prefer extending existing namespaces
to creating new files, and don't introduce sibling `*.md` notes unless asked.

Defer to the project's `AGENTS.md` for architecture, commands, and test
infrastructure. These rules govern Clojure work only; they don't override
planning, documentation, git, or non-Clojure tasks.

Default style reference: <https://guide.clojure.style/>. When reporting changes,
cite code as `file_path:line_number`.

## Core workflow

**Never write Clojure without REPL validation.** Every task is a
gather-context → take-action → verify-output loop:

1. **Read the target file.** Use `lsp` (`definition`, `references`,
   `workspace_symbol`) for related symbols, call sites, and dependencies.
   Review namespace imports and match local patterns.
2. **Connect to nREPL.** Use `clojure_find_nrepl_port` + `clojure_eval` if the
   pi-clojure extension is loaded; otherwise `clj-nrepl-eval --discover-ports`
   to find a port, then `-p PORT "code"` to evaluate.
   **Reuse an existing nREPL — never spawn a second.** A discovered port file
   (`.nrepl-port`, `.shadow-cljs/nrepl.port`, `.cider-nrepl.port`) means a
   server is up.
   If no nREPL is reachable and the project is Babashka (`bb.edn`, or a bare
   bb script), start one yourself: `bb nrepl-server` (writes `.nrepl-port`)
   in a background terminal — under Herdr (`HERDR_ENV=1`), a new tab in the
   current workspace (`herdr_layout` `tab_create`, then `herdr_pane` `run`).
   If `bb nrepl-server` fails with address-in-use, an nREPL is already
   listening on the default port without a discoverable port file — probe
   that port with an eval and reuse it instead of retrying startup.
   Ephemeral bb scripts have no persistent build watch, so spawning the
   server is the normal bb workflow, not an exception — the never-spawn-a-second
   rule still applies once it is up. For JVM/ClojureScript projects, ask the
   user to start one (`lein repl :headless`, `clj -M:repl`, shadow-cljs
   watch, etc.) — their build/watch setup is session-owned.
3. **Explore unfamiliar code in the REPL.** `(clojure.repl/doc x)`,
   `(clojure.repl/source x)`, `(clojure.repl/dir ns)`. For an unfamiliar Java
   interop call, evaluate it on a real input before relying on its documented
   behavior — some `java.nio`/`java.io` methods (e.g. `Path.toUri`) perform
   filesystem I/O and are not pure. Never assert purity, totality, or
   determinism of a function in code comments or docs without having checked.
4. **Define and validate in the REPL before saving** — happy path, nil, empty
   collections, edge cases.
5. **Save with `edit` or `write`.**
6. **Reload and re-verify** — `(require '[project.ns] :reload)` for `.clj`;
   shadow-cljs/figwheel watch loops typically auto-reload `.cljs`. Re-run the
   changed function and any directly-relevant tests.
7. **Do not report success before verification passes.**

Ask for clarification when requirements are ambiguous, when materially
different approaches exist, or when an architectural decision is needed.

### ClojureScript runtime

A ClojureScript nREPL evaluates against a connected JS runtime — usually a
browser tab (shadow-cljs `:browser` / `:browser-repl`, figwheel), occasionally
Node or a self-hosted runtime. Before evaluating `.cljs`, confirm a runtime is
attached. For the browser case use the `chromium` skill to open a tab against
the dev server URL. A `.cljs` eval that hangs or returns `nil` with no effect
is the classic disconnected-runtime symptom — check the runtime before
debugging the form.

## Tools

| Capability     | pi-clojure (preferred)    | CLI fallback                                              |
| -------------- | ------------------------- | --------------------------------------------------------- |
| Find port      | `clojure_find_nrepl_port` | `clj-nrepl-eval --discover-ports`                         |
| Eval           | `clojure_eval`            | `clj-nrepl-eval -p PORT "code"`                           |
| Paren — file   | n/a                       | `clj-paren-repair file.clj`                               |
| Paren — string | `clojure_paren_repair`    | `echo '…' \| clj-paren-repair`                            |

Detect availability by tool list (`clojure_eval` appears when pi-clojure is
loaded) and `which clj-nrepl-eval` / `which clj-paren-repair` for the CLI.
`clojure_eval` accepts an optional namespace and 1–2147483647 ms timeout that
covers connection and response processing; cancellation closes its socket and
settles the operation once. Native eval and string-repair results (including
details) are bounded to pi's standard 2000 lines or 50KB. See the tool guide
for discovery probe limits and troubleshooting.

**Use `clj-paren-repair` for file repair even when pi-clojure is loaded** — it
uses a real Clojure reader (edamame), parinfer-rust, and cljfmt, so the output
is also formatted. `clojure_paren_repair` (JS parinfer) is for string repair
before writing a new file. Never hand-fix delimiter errors.

After structural edits that move or delete forms, run a balance check
(`clj-paren-repair` on the file, or `clojure_paren_repair` with `check`)
*before* running the test suite — a dropped delimiter otherwise surfaces as a
confusing whole-suite parse failure.

See [references/tool-guide.md](references/tool-guide.md) for parameters,
session persistence, the `clj-nrepl-eval --connected-ports` listing, and
troubleshooting.

## Idioms

Match codebase conventions; default to community idiom otherwise. The
short list:

- Prefer threading macros (`->`, `->>`, `some->`, `cond->`) over deep nesting.
- `kebab-case` names; predicates end in `?`; conversions use `->`;
  `!` suffix marks unsafe mutation (`swap!`, `save-user!`), `!` prefix marks
  mutable refs (`!conn`).
- Docstrings on public functions describing args, return, and at least one
  example.
- `(set! *warn-on-reflection* true)` in JVM namespaces that interop with Java.
- Babashka resolves `user.home` from the OS user database, not `$HOME`: in bb
  subprocess tests, never rely on a `$HOME` override to isolate
  home-directory probes — inject an explicit root/path override instead.
- Conversely, bb's classpath cache *does* follow `$HOME`, so overriding it for
  isolation silently forces cold classpath resolution (~0.7s vs ~70ms per
  call) and bootstraps a `.clojure/` into the fake home. `CLJ_CACHE`
  (classpath cache) and `CLJ_CONFIG` (user config/`tools` dir) are
  independent knobs — set both to one shared warm dir, not just `CLJ_CACHE`.
- Measure per-test timing inside a real namespace or suite run. A test timed
  as the first subprocess-spawning call in a fresh `bb` process over-reads by
  ~3× on one-time JIT/class-load warm-up.
- `fs/glob` does not traverse a directory symlink — it returns `[]` unless called with `{:follow-links true}`. Home Manager installs managed trees as symlinks, so any discovery walk over them needs the flag, and its test needs a real `fs/create-sym-link` fixture rather than a plain directory.

See [references/idioms.md](references/idioms.md) for threading-macro,
control-flow, data-structure, error-handling, testing, and anti-pattern
detail.

## Failure recovery

If REPL eval, namespace load, or a test fails:

1. Read the exact error message.
2. Isolate the failing expression.
3. Fix the root cause.
4. Reload affected namespaces.
5. Re-run verification.

After a large multi-line replacement via a tool that does its own text
escaping, run a cheap compile-check (`bb -e '(require (quote project.ns)
:reload)'` or equivalent) immediately, before the full test suite — an
extra layer of string-escaping can produce a syntactically balanced but
semantically bogus top-level form that a delimiter check alone won't catch.

Keep `bb -e` to a single short form. Multi-line code carrying `$`, regexes,
or nested quotes misevaluates silently inside a shell heredoc; write it to
`.agents/tmp/*.clj` and run `bb <file>` instead.

For unbalanced-delimiter / EOF errors, run `clj-paren-repair` (file) or
`clojure_paren_repair` (string) instead of editing by hand.
