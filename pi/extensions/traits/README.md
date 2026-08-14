# Interactive trait expansion

Expands trait tokens in pi input before skill and prompt-template expansion. The concrete workflow is interactive plan review in the parent session, for example `review the plan for the selected task %prune`.

## Syntax and resolution

A token is `%<name>`, where the name follows `[a-z][a-z0-9-]*` and `%` is at the start of a line or preceded by whitespace. Tokens adjacent to non-whitespace and digit-first values such as `page%focused` and `%20` are inert. Unknown tokens stay unchanged and the input continues normally.

Resolution is first-match-wins from `<ctx.cwd>/.agents/traits/`, when `ctx.isProjectTrusted()` is true, then `~/.agents/traits/`, then the packaged store beside the resolved CLI binary (`<skill>/traits/`, derived from `resolveTraitsBinary()` the same way `skill-directory` in `cli.clj` derives it). An untrusted project's layer is never passed to the interpolator; the packaged layer is always passed, trusted or not. Each layer checks `<name>.md` before `<name>/prompt.md`; siblings such as `<name>/gate.md` are not candidates. Fragment frontmatter is stripped and the body is substituted in place by the shared `herdr-orch.traits` implementation.

The canonical trait format, admission rules, and gate protocol live in `skills/herdr-orch/references/traits.md`. Mechanical interpolation semantics live in `skills/herdr-orch/scripts/docs/contract.md` under Trait composition.

## Runtime

The extension invokes the co-installed `herdr-orch` trait launcher. Its path, symlink resolution, and cwd behaviour are owned by `skills/herdr-orch/scripts/docs/contract.md` § Standalone trait interpolator CLI. It requires Babashka and the co-installed dotagents skills tree. There is no availability probe or alternate implementation.

On an interpolation error, the extension shows a warning and sends the original input unchanged. Unknown tokens are not errors: they remain unchanged without a warning.

The extension provides no slash commands and has no suggested or default keybindings.
