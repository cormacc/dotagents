# Org-tasks protocol reference

This is the field-level reference for the `TASKS.org` memory protocol. Agents should normally mutate durable state through `ot`; use this file when reviewing or repairing on-disk org by hand.

## Protocol files

`TASKS.org`, `TASKS.local.org`, `TASKS.archive.org`, `TASKS.setup.org`, and imported change-records are anchored at the project root resolved from CWD. Do not climb into a parent project to find task memory.

Repositories declare shared options in `TASKS.setup.org` and reference it from task files and change-records:

```org
#+TODO: TODO(t) STARTED(s!) WAITING(w@/!) | DONE(d!) CANCELLED(c!)
#+STARTUP: logdone logdrawer
#+LINK: proj file:../../%s
#+LINK: task file:../../TASKS.org::#%s
#+LINK: archive file:../../TASKS.archive.org::#%s
```

Setup links resolve from a change-record's own location (`design/log/`), since records pull this file in via `#+SETUPFILE: ../../TASKS.setup.org`. `TASKS.org` and `TASKS.archive.org` declare repo-root-relative overrides before setupfiles (first declaration wins). `plan` is referenced only from the task files, so it lives there, not in setup; `proj` is a generic repo-root path link (`./` from the task file, `../../` from a record):

```org
#+LINK: task file:TASKS.org::#%s
#+LINK: archive file:TASKS.archive.org::#%s
#+LINK: plan file:design/log/%s
#+LINK: proj file:%s
#+SETUPFILE: ./TASKS.local.org
#+SETUPFILE: ./TASKS.setup.org
```

Org's first-declared-wins setupfile semantics let gitignored keywords in `TASKS.local.org` override shared defaults while task headings in `TASKS.local.org` remain local drafts.

## Task headings

```org
** TODO [#A] Implement feature :area:
:PROPERTIES:
:CUSTOM_ID: 01234567-89ab-4def-8123-456789abcdef
:CREATED: [2026-04-25 Sat 09:00]
:END:
:LOGBOOK:
- Created [2026-04-25 Sat 09:00]
:END:
#+IMPORT: [[plan:2026-04-25-feature.org]]
Optional description text.
```

- States: `TODO`, `STARTED`, `WAITING`, `DONE`, `CANCELLED`.
- Priorities: `[#A]` critical, `[#B]` high, `[#C]` medium, `[#D]` low.
- Tags are semantic categories; there are no reserved operational tags.
- Sibling task subtrees are separated by one blank line for readability.
- `:CUSTOM_ID:` is a UUID v4 and required on every task/subtask. Use `ot create` or `ot uuid`; never invent sequential/shared-prefix IDs.
- `:CREATED:` is set on creation and is not backfilled on existing tasks.
- `:STARTED:` caches the first STARTED transition; source of truth is the first matching LOGBOOK state entry.
- `CLOSED:` is the current close timestamp for `DONE`/`CANCELLED`, on its own line between heading and `:PROPERTIES:`. It is cleared on reopen and rewritten on the next close.
- `:LOGBOOK:` is append-only lifecycle history: one created entry plus one state entry per transition.
- `#+IMPORT:` links a change-record/imported task file. Canonical plan imports use `[[plan:file.org]]`; file links remain valid for non-plan imports.
- `design/specs/` is the default home for prose-only living contracts; there is no dedicated `spec` link type. Reference specs (and any repo file) from records with `proj` (`[[proj:design/specs/foo.org]]`), and declare expected impact with bare repo-relative `#+SPEC_IMPACT:` paths.

## Blockers, handoff, and linked issues

`ot blocker`, `ot ready`, `ot handoff`, and `ot issue` are the canonical writers for these fields.

Blockers live in drawer properties:

```org
:BLOCKED-BY: task:01234567-89ab-4def-8123-456789abcdef
:BLOCKED-BY+: url:https://github.com/example/project/pull/123
:BLOCKED-BY+: human: waiting on Alice's review
```

Ready-task checks treat `task:<UUID>` blockers as resolved only when the referenced task is `DONE` or `CANCELLED`; all non-task blockers are opaque until removed.

`:HANDOFF:` is a short free-form next-session note. It is valid on top-level tasks and plan subtasks and is surfaced during resume.

`:LINKED_ISSUES:` is tracker-agnostic, whitespace-separated org-link tokens, usually typed links such as `[[jira:ABC-1]]` plus `#+LINK:` templates. Bare keys are not protocol syntax; tracker-specific semantics live in companion skills.

## Selection and local tasks

The active task is stored in gitignored `TASKS.local.org`:

```org
#+SELECTED: 01234567-89ab-4def-8123-456789abcdef
```

Absent file or empty value means no selection. `TASKS.local.org` may also contain task headings and imports that remain local until `ot publish` moves them to `TASKS.org`.

## Line wrapping

Do not hard-wrap prose in generated or edited org task files. Each paragraph is one logical line; editors should soft-wrap visually. Keep natural line breaks in code/quote/example blocks, tables, drawers, `#+KEYWORD:` lines, and one-list-item-per-line lists. When touching old hard-wrapped prose, unwrap only the paragraph you are already editing.

## Extension points and non-goals

Unknown `#+` keywords and drawer properties round-trip untouched. Protocol keywords used by change-record/spec coupling include repeated `#+SPEC_IMPACT: path/to/contract.org` declarations and `#+NO_SPEC_IMPACT: true` opt-outs; third-party metadata should use an `UPPERCASE_NAMESPACE_` prefix.

The core protocol deliberately does not own vendor-specific workflow state, bidirectional external-tracker sync, transcript retention, human-readable aliases replacing UUIDs, or per-task attribution properties. Add those as companion layers when a project needs them.
