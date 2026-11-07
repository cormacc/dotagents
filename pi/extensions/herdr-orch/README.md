# herdr-orch pi adapter

Exposes the `herdr-orch` skill's `oh` CLI to pi. The extension invokes `$HERDR_ORCH_BIN` when set, else the co-installed launcher at `~/.agents/skills/herdr-orch/scripts/oh`, else the launcher of the skill packaged with the extension (`../../../skills/herdr-orch/scripts/oh`). It has no alternate implementation: each call runs `oh` and reads its `herdr-orch/v1` JSON envelope. The CLI owns backend selection and refusal. With `HC_TERMINAL_ID` present, each listing passes through that same CLI gate.

## Tools

- `herdr_layout` -- workspace, tab, and pane topology (`oh ws|tab|pane ...`).
- `herdr_pane` -- raw pane control for ordinary commands (`oh pane ...`).
- `herdr_agent` -- coding-agent lifecycle (`oh agent ...`). `prompt` waits on the same call by default.

The tools proxy Herdr control only. Persona delegation uses `oh task ...` as the skill describes.

Always-loaded prompt guidelines retain the explicit Herdr-request boundary and pane ownership protection. Direct control loads [../../../skills/herdr/SKILL.md](../../../skills/herdr/SKILL.md) for procedures; persona delegation loads [../../../skills/herdr-orch/SKILL.md](../../../skills/herdr-orch/SKILL.md) for its placement contract. Tool descriptions retain their API contracts instead of repeating procedures as prompt guidelines.

## Delivery watcher

In an orchestrator session (`HERDR_ENV=1` or `HC_TERMINAL_ID`, not a delegated child, with a resolvable assignment root) the extension watches `<assignment-root>/.tmp/herdr-orch/` and steers each new result item into the session once as a `herdr-orch-item` message: child, task, status, first summary line and the `oh task collect <task>` command. It scans at session start, uses `fs.watch` with a 5 s poll fallback, and keeps the delivered set in `<session-file>.herdr-orch-delivered.json` so a reload re-delivers nothing. It is advisory: it never collects, closes or runs `oh`, and the validated result item remains the only completion evidence. `oh task publish` skips its push for a Pi parent because of it. Contract: [../../../skills/herdr-orch/scripts/docs/contract.md#Delivery watcher](../../../skills/herdr-orch/scripts/docs/contract.md#delivery-watcher).

## Slash command

`/oh [help|personas|traits|models|usage|workflows]` shows a user-only discovery entry. `/oh usage` runs `oh usage --format markdown`; expanded output adds `--detail`, which adds safe scope bounds and reset times. Without an argument, `/oh` shows every roster section, the Herdr status of the pane, the spawn defaults, the same subscription-usage block, and short usage notes. An unknown provider includes its fixed CLI reason. Usage and roster reads each have a 30 s Pi budget. The notes route natural language to a context: "spawn a subagent" is a fresh context, and "fork a subagent" is a forked context (`oh task run|start --context fork`). A measurement runs headless (`--context measurement`), with a read-only monitor pane only on `--tab` or `--split`.

- `oh roster` and `oh usage` own their renderings. The roster renderer also generates the org listings in [../../../skills/herdr-orch/README.org](../../../skills/herdr-orch/README.org), so this extension adds only the Herdr status line, placement of the usage block, and the usage notes (`help.ts`).

- The output is a session entry (`appendEntry` with the `herdr-orch-help` type), so it never enters model context. The skill already gives the model this information.
- In the TUI, `/oh usage` runs `oh usage` twice, with and without `--detail`; bare `/oh` also runs `oh roster` in both forms. The entry stores both renderings. The tool-output expand key (default `ctrl+o`) switches to the detail rendering: roster detail adds file paths, timeouts, retro gating, every model alias, and the resolution layers; usage detail adds safe scope bounds and reset times.
- RPC mode shows the detail rendering as a notification. JSON and print modes do not render listings.
- Usage uses the owned Babashka collector and Pi's active file-store location, including `PI_CODING_AGENT_DIR`. Authentication failures appear as fixed reasons. No readiness check, installer, login prompt or refresh process runs. `/oh setup` is not a supported section. Credential selection, cache and HTTP bounds are in [../../../skills/herdr-orch/scripts/docs/contract.md#Usage](../../../skills/herdr-orch/scripts/docs/contract.md#usage).
- `oh roster` runs in the session cwd, so the project layer is the git root of that cwd. Persona, trait, and model listings therefore show what a spawn would use. The `traits` extension expands a project-layer `%name` only in a trusted project, so an untrusted project's trait can appear in `/oh traits` and still not expand interactively.
- `/oh workflows` lists ordinary skills whose YAML frontmatter contains `metadata: {herdr-orch: workflow}`. It reuses each skill's name and description; expanded output adds its source and path. This is catalogue membership, not execution or permission to delegate. The CLI owns skill-root discovery and shadowing ([../../../skills/herdr-orch/scripts/docs/contract.md#Workflow skill discovery](../../../skills/herdr-orch/scripts/docs/contract.md#workflow-skill-discovery)).
- The output contract is in [../../../skills/herdr-orch/scripts/docs/contract.md#Roster](../../../skills/herdr-orch/scripts/docs/contract.md#roster).
- Root-owned staging, repeated measurements and the approval-gated context smoke are in [../../../skills/herdr-orch/references/workflow-recipes.md](../../../skills/herdr-orch/references/workflow-recipes.md).

## Named Anthropic subscription accounts

Account registration lives in [accounts.ts](accounts.ts). An additional account is `<provider>-<N>`, where N is a positive integer without leading zeros: `anthropic-1`, `anthropic-2`, and so on. The provider is the text before the final `-<N>` and must be a provider that the extension can register; only `anthropic` is supported. The selectable provider, auth-store key and display name are all this full key. The existing `anthropic` credential remains the default account; `anthropic-1` is the first additional account. Keys are stable identities, not cosmetic labels; there is no rename operation, display alias or separate account database.

1. Run `/oh-account add` to register the lowest free number, or `/oh-account add <N>` to choose the number. For example, the first `/oh-account add` registers `anthropic-1` in this Pi process without changing credentials. The command refuses any other argument. It also works outside Herdr.
2. Run `/login anthropic-1` in the Pi TUI. Complete Pi's native Anthropic subscription login. A cancelled login does not create an account credential. Successful login stores OAuth credentials under that key in the existing Pi `auth.json`.
3. Use `/model` to choose `anthropic-1/<model-id>`. This changes only the current session, and Pi restores the model/account when that session resumes. Another running session keeps its own selection. Use an `anthropic/<model-id>` entry to return this session to the default account. Do not save startup defaults in the model selector unless you intend to change them.
4. To remove an account credential, run Pi's native `/logout` and select its display name, for example `anthropic-1`, not `Anthropic`. Pi serialises logout with in-progress refresh transactions and deletes only that credential. Registration can remain visible until reload, but requests cannot authenticate after removal. `/reload` discovers the remaining stored account keys. Other processes discover newly added accounts at startup or reload; they never swap the shared default credential.

Named-account browser login opens Chromium on Linux (`chromium --incognito <url>`), Google Chrome on macOS (`"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --incognito <url>`), or Google Chrome on Windows (`chrome.exe --incognito <url>`, with `chrome.exe` on PATH). These use separate executable and argument entries, not a shell. The [Chromium command-line guide](https://www.chromium.org/developers/how-tos/run-chromium-with-flags/) documents the Chrome executable forms and switches. A private window separates login cookies from the primary browser session, so you do not need to log that session out. An existing incognito session can still share cookies with other incognito windows; close those windows first if they contain the wrong account.

The native login method selector remains available. `Copy code login (headless)` does not launch a browser. The exact native URL and instructions appear in Pi's login dialog as an informational link, rather than an `auth_url` event that would also open the default browser. Copy the link into a private window manually if the command is absent, fails or cannot open a window. Login does not wait for the browser process to exit and browser failure does not cancel native code entry. Pi's dedicated auth-URL copy shortcut is not available for this informational link; use terminal link copying. Native prompts, cancellation, token exchange and refresh are retained. Registration and refresh do not launch a browser, and default `anthropic` login is unchanged. No browser is installed and no global browser settings are changed. The wrapper is [private-browser.ts](private-browser.ts).

The extension registers stored keys only from the validated `anthropic-<N>` namespace before startup model resolution. Registration without a successful login lasts only until reload. Login, refresh and logout belong to Pi's native provider/credential APIs, not extension-owned file writes. These commands use Pi's normal file store, including `PI_CODING_AGENT_DIR`; an SDK host with a custom credential store must register its account providers itself.

A credential stored under a retired `anthropic-account-<name>` key is not discovered. To keep it, stop every Pi process and rename that key once in `auth.json` to a free `anthropic-<N>` key. Alternatively, run `/oh-account add` and `/login` again for a fresh login, then remove the retired key.

Aliases expose native Anthropic OAuth only: no API-key login method, ambient API-key fallback, or default-account fallback. Missing, removed, expired-with-failed-refresh and wrong-type credentials fail rather than use another account or a paid key. Explicit operator endpoint/auth overrides in `models.json` are Pi configuration, not account switching. Primary tier selection and quota recovery use the shared account-aware routing policy described below.

## Primary tiers and quota recovery

1. Use `/oh models` to list the merged configured weight aliases.
2. While idle, run `/oh-tier light` (or another configured vector alias). The shared `oh route --request PATH` policy selects an authenticated registered account/model with the current reasoning level. Tier selection also works outside Herdr and does not change startup defaults.
3. Use `/model` for a direct concrete selection, or `/oh-tier clear` to retain the concrete selection and clear the tier. A direct model/account change reported by Pi clears tier intent. Pi persists tier intent in session metadata and restores it on resume; recovery-generated changes retain it.

On an explicit quota/rate-limit refusal, the controller attributes the error to the failed request's captured account/model. It first tries eligible same-model sibling accounts, then only candidates in the retained tier, including other providers. Without a tier it cannot change models. The shared policy preserves exact reasoning capability, rank, reserve and quota-scope rules; unavailable credentials supply no eligibility. Authentication failures, permission denials and generic server failures do not trigger recovery.

Each chain uses a fixed initial inventory, tries each pair at most once and stops with a visible exhaustion or failure reason. It is capped at 4096 attempted pairs; an inventory above 4096 pairs is refused. Availability is checked again before switching. Successful recovery uses Pi's native continuation with completed tool results intact; the controller does not replay tools. Delegated children remain pinned to their launch selection, even when fork history contains tier metadata. Account registration still works in children.

### Pending native selection limit

Before an automatic switch, the extension shows a status, widget and notification warning: avoid manual selection while authentication is pending. Abort stops automatic continuation, but an already-pending native setter can still change the selected account/model after abort or overwrite a newer manual selection. The warning remains until that setter settles in the active session, not merely until cancellation or a timeout. A cancelled switch reports the actual selected account/model and makes no automatic retry. A failed setter clears its warning and reports failure. Session replacement prevents old callbacks from updating the replacement UI.

Explicit abort, new input and an external model/account change reported by Pi invalidate recovery. Pending native selection requests and silent same-target/equal selections are not reliably observable and may not cancel recovery. Use Pi's explicit abort control when cancellation is required. The extension does not intercept selection requests, poll transcripts, lock native controls or roll back selection. While a controller setter is unresolved, another `/oh-tier` command is refused rather than queued.

Warnings and switch reports are user-only output, never model context. Hosts without UI receive them on stderr. TUI uses native status/widget/notification methods; RPC emits the supported extension UI requests, which its client must display. Automated TUI API checks are not rendered visual evidence. Native visual and authorised real-account checks remain separate acceptance gates.

Routing requires Babashka, the shared CLI and writable system temporary storage. Private request files contain routing identities and capabilities, not credentials; they are removed after the routing call. No assignment ledger or terminal operation is used for selection. The controller is [recovery.ts](recovery.ts); the policy and request schema are in [../../../skills/herdr-orch/scripts/docs/contract.md#Shared selection operation](../../../skills/herdr-orch/scripts/docs/contract.md#shared-selection-operation).

## Dependencies

Pi 1.5.0 or newer, Babashka and the `herdr-orch` skill (co-installed or packaged). Subscription usage requires valid credentials in the selected Pi file store. Native and SDK-only stores are not silently substituted. There is no npm collector dependency. `/oh` does not need a terminal host for offline listings. When `HC_TERMINAL_ID` is present, the CLI probes host ancestry and refuses an unsupported or mismatched runtime.

## Keybindings

None.

## Tests

`./test.sh` runs `accounts.test.ts` (onboarding, native OAuth login and credential transactions, refresh cancellation/removal, and no API-key fallback), `private-browser.test.ts` (platform argv, trusted URLs, installed native login/UI and installed Pi executable login with stub browsers and a fake endpoint, missing/failing launch fallback, headless/cancel/default-login isolation), `accounts-integration.test.ts` (installed Pi RPC alias refresh and streamed tool round-trip, two session-local selections outside Herdr, and command registration), `index.test.ts` (pi-to-`oh` argv, `/oh` behaviour, and rendering), `pi-integration.test.ts` (installed Pi RPC commands through the real CLI, including fixture-backed usage and workflow discovery without a model turn), `recovery.test.ts` (classification, captured pairs, continuation staging, cancellation, warning lifecycle, credential revalidation, pinned children and the 4096-attempt bound), `recovery-integration.test.ts` (installed Pi startup discovery and RPC recovery, native tool-result retention, shared tier/direct/reasoning constraints, delayed native/public-store selection, pending requests, credential removal, resume/fork and idle command/replacement/inventory boundaries), and `../test/herdr-agent-state.test.ts` with the repository's locked `tsx`. Account tests use disposable auth stores, synthetic credentials and bounded fake endpoints, never live provider calls. `account-fake-only.cjs` is test-only: it redirects native OAuth token requests to the loopback fixture and loads the socket egress guard. `ACCOUNT_TEST_GATE_ONLY=1` selects the provider-only runtime gate; `ACCOUNT_TEST_WRONG_ACCOUNT=1` supplies a deliberate wrong-account negative control that must fail.

Private-browser executable tests require Linux and refuse other platforms before any launch; macOS and Windows command construction is checked without opening a browser. They use disposable `chromium` and `xdg-open` executables and synthetic OAuth only. They run with `node --import tsx` to avoid a `tsx` CLI IPC socket in guarded subprocess tests.

The runner clears inherited `HERDR_ORCH_*` and `HC_TERMINAL_ID` and creates isolated HOME, auth and assignment roots under repository `.tmp/`. Runtime fixtures use synthetic credentials, loopback endpoints and an external-traffic guard. `RECOVERY_TEST_DELAYED_ONLY=1`, `RECOVERY_TEST_SESSION_ONLY=1` and `RECOVERY_TEST_BOUNDARIES_ONLY=1` select focused installed suites. `RECOVERY_TEST_WRONG_ACCOUNT=1` and `RECOVERY_TEST_DUPLICATE_CONTROL=1` are deliberate negative inputs: the integration suite must fail its account-sequence assertion. The fixture files are not extension entry points.

Test tooling: [../test/README.md](../test/README.md).

## Compatibility review

Last review: 2026-10-29, against pi 1.4.0.

- Herdr 0.9.5: the `herdr_agent` and `herdr_layout` tools worked live in a pi 1.4.0 session.
- Babashka 1.13.228 runs `oh`.
- Checks: `test.sh`, type-check against the pi 1.4.0 declarations, and `pi --mode rpc` load with empty stderr and `/oh` registered.
