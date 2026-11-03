# Chromium extension

Controls an existing Chromium instance through Chrome DevTools Protocol (CDP).
Chromium must be reachable at `http://localhost:9222`, normally by starting it
with `--remote-debugging-port=9222`.

## Tools

- `browser_nav`: navigate the selected tab/frame, or open and select a new tab with `newTab: true`.
- `browser_eval`: evaluate asynchronous JavaScript in the selected frame, including cross-origin frames.
- `browser_tabs`: list stable tab IDs grouped by browser window; supply `tabId` to select its main frame and enumerate its frames.
- `browser_screenshot`: capture the selected tab viewport or iframe element, optionally after navigation or a selector wait in that frame.
- `browser_inspect`: query rendered text, explicit raw text, HTML, attributes, counts, visibility, or existence in the selected frame.
- `browser_cookies`: list cookies for the selected frame's URL.
- `browser_pick`: show an overlay inside the selected frame so the user can select DOM elements.

There are no slash commands or default keybindings. The extension has no custom
Pi TUI component; `browser_pick` instead needs a visible browser page and a
user who can interact with its in-page overlay.

## Inspection

`browser_inspect` queries the first CSS match in the selected frame; `count` counts all matches. The default `action: "text"` returns trimmed HTML `innerText`, with rendered line breaks rather than DOM source text. It excludes normal script/style bodies and hidden descendants. With `waitFor: false`, hidden roots and descendants of `display: none` or `content-visibility: hidden` ancestors return an empty string instead of raw text. Non-HTML elements without `innerText` return an empty string; there is no automatic raw-text fallback. This follows rendered-text semantics, not screenshot pixel visibility (for example, opacity and off-screen position do not remove text).

Use `action: "raw"` explicitly for trimmed `textContent`, including script/style bodies and hidden data. Use `waitFor: false` when inspecting a hidden element: the default visible-selector wait also applies to raw extraction. `html`, `attr`, `count`, `visible` and `exists` retain their existing meanings; `attr` requires `attribute`.

Every inspection result string is limited to 12,000 UTF-16 code units, including the appended marker `\n[truncated: result limited to 12000 characters]` when shortened. This cap also applies to `html` and `attr`. Narrow the selector to obtain a smaller result. The target header is outside this limit. Other browser tools are not covered by this cap.

The result has one text content block containing the target header followed by the bounded result. `details` contains `{target, selector, action, result, truncated}`: `result` is always a string (also for counts and booleans), `action` is the resolved action, and `truncated` is a boolean. No unbounded copy is retained in `details`.

```json
{"tool":"browser_inspect","arguments":{"selector":"main"}}
{"tool":"browser_inspect","arguments":{"selector":"script[type='application/json']","action":"raw","waitFor":false}}
```

## Targeting

The implementation in [index.ts](index.ts) and [targets.ts](targets.ts) owns the targeting contract. Every action accepts optional `tabId` and `frameId` parameters and returns the chosen IDs in its text and `details.target`.

- `tabId` is the CDP page target ID, stable for the lifetime of that tab. It is not a tab index or a window ID.
- `frameId` is an opaque handle to a live Puppeteer frame. It is not a CSS selector, URL, name or CDP iframe target ID. Handles stay valid while that frame object lives, including navigation; detached or replaced frames and connection replacement invalidate them. Re-enumerate after reconnecting or reloading the extension.
- Omit both IDs to reuse the exact selection. Tab ordering, focus changes and new tabs do not change it. On the first action, one open tab can be selected implicitly; multiple tabs require an explicit choice.
- An explicit `tabId` without `frameId` selects that tab's main frame. A `frameId` alone uses the selected tab. A frame handle from another tab is rejected.
- Closed tabs and missing frames fail without fallback. Select a live target explicitly to recover. `newTab: true` cannot be combined with target IDs.
- Calls use sequential execution because they share selection state. Use explicit IDs when switching between targets.

`browser_tabs({})` lists tab IDs and titles without URL fields; it does not change the selection. `browser_tabs({tabId})` selects that tab's main frame and returns only that tab's URLs and frames. Each frame entry has `frameId`, `parentFrameId`, `url` and `main`. Target metadata contains only IDs, never a snapshot of other tabs.

Example call sequence (replace placeholders with IDs returned by the tools):

```json
{"tool":"browser_nav","arguments":{"url":"http://localhost:8080/","newTab":true}}
{"tool":"browser_tabs","arguments":{"tabId":"<returned tabId>"}}
{"tool":"browser_inspect","arguments":{"tabId":"<returned tabId>","frameId":"<child frameId>","selector":"h1"}}
{"tool":"browser_eval","arguments":{"code":"document.title"}}
```

The last call runs in the child frame. Optional `url` parameters on inspection and screenshot calls navigate that frame, not its parent tab. Screenshots capture the whole iframe element for a child target; cookies are scoped to that frame's URL.

## Tests

```bash
bash pi/extensions/chromium/test.sh
CHROMIUM=$(command -v chromium) bash pi/extensions/chromium/test.sh
```

Run from the repository root after installing the locked Node dependencies. The runner type-checks the extension and executes routing regressions plus an installed-Pi RPC loading check. `scripts/check.sh` includes this runner. Set `CHROMIUM` to enable the live test, which starts a disposable headless browser and local HTTP server, verifies an out-of-process cross-origin iframe, and exercises frame actions and stale-target rejection. It also checks rendered versus raw inspection beside script/style/hidden siblings, hidden roots, result-string contracts, and output boundaries below, at and above the limit. It does not use the browser on port 9222. Screenshots remain under repository-root `.tmp/` for visual inspection; the disposable profile is removed.

## Dependencies

Runtime dependency: `puppeteer-core`, pinned to an exact version. The extension uses Pi-hosted `@earendil-works/pi-coding-agent`, `@earendil-works/pi-ai`, `@earendil-works/pi-tui`, and TypeBox 1.x (`typebox`) APIs.

The pin is exact because a range does not track new releases here. Home Manager activation reinstalls only when `package.json` changes, and it ignores the lockfile, so a range resolves once on each host when the manifest changes. Hosts then differ, and none of them follows later releases. Bump the pin at each compatibility review, and when the symptom below appears.

### Keep puppeteer-core in step with Chrome

`puppeteer-core` must understand the running Chrome's target model. When Chrome
is much newer than the pinned puppeteer, `puppeteer.connect()` hangs and every
`browser_*` tool fails with `Connection timeout` (the 5 s race in
`ensureBrowser`), even though `curl http://localhost:9222/json/version` and raw
CDP still work.

Symptom-to-fix: if the CDP HTTP endpoint responds but the tools time out, the
puppeteer-core version is too old for this Chrome. Bump it:

```sh
npm install --save-exact puppeteer-core@latest   # then reload the extension / restart pi
```

Observed once: `puppeteer-core@23.11.1` could not connect to `Chrome/153` (connect never resolved); `@25.12.0` connected in ~34 ms. Each puppeteer-core release names the Chrome version it targets in `PUPPETEER_REVISIONS`.

## Compatibility review

Last review: 2026-10-29, against pi 1.4.0.

- `puppeteer-core` 25.16.0. It targets Chrome 156.0.8078.47.
- Chromium 155.0.8059.84 (Nix, running on port 9222): `puppeteer.connect()` succeeded and listed the open pages. The installed `chromium` 156.0.8078.47 was not running, so the review did not exercise it.
- Checks: type-check against the pi 1.4.0 declarations, and `pi --mode rpc` load with empty stderr.
