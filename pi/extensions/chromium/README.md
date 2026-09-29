# Chromium extension

Controls an existing Chromium instance through Chrome DevTools Protocol (CDP).
Chromium must be reachable at `http://localhost:9222`, normally by starting it
with `--remote-debugging-port=9222`.

## Tools

- `browser_nav` — navigate the active tab or open a URL in a new tab.
- `browser_eval` — evaluate asynchronous JavaScript in the active tab.
- `browser_tabs` — list tabs grouped by browser window.
- `browser_screenshot` — capture the viewport, optionally after navigation or a selector wait.
- `browser_inspect` — query rendered DOM text, HTML, attributes, counts, visibility, or existence.
- `browser_cookies` — list cookies for the active tab.
- `browser_pick` — show a browser-page overlay so the user can select one or more DOM elements.

There are no slash commands or default keybindings. The extension has no custom
Pi TUI component; `browser_pick` instead needs a visible browser page and a
user who can interact with its in-page overlay.

## Dependencies

Runtime dependency: `puppeteer-core` (`^25.12.0`). The extension uses Pi-hosted
`@earendil-works/pi-coding-agent`, `@earendil-works/pi-ai`,
`@earendil-works/pi-tui`, and TypeBox 1.x (`typebox`) APIs.

### Keep puppeteer-core in step with Chrome

`puppeteer-core` must understand the running Chrome's target model. When Chrome
is much newer than the pinned puppeteer, `puppeteer.connect()` hangs and every
`browser_*` tool fails with `Connection timeout` (the 5 s race in
`ensureBrowser`), even though `curl http://localhost:9222/json/version` and raw
CDP still work.

Symptom-to-fix: if the CDP HTTP endpoint responds but the tools time out, the
puppeteer-core version is too old for this Chrome. Bump it:

```sh
npm install puppeteer-core@latest   # then reload the extension / restart pi
```

Observed once: `puppeteer-core@23.11.1` could not connect to `Chrome/153`
(connect never resolved); `@25.12.0` connected in ~34 ms. The dependency is a
`^25` range so patch/minor Chrome updates stay covered; a major Chrome jump may
still need a manual bump.
