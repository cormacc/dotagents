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

Runtime dependency: `puppeteer-core`. The extension uses Pi-hosted
`@earendil-works/pi-coding-agent`, `@earendil-works/pi-ai`,
`@earendil-works/pi-tui`, and TypeBox 1.x (`typebox`) APIs.
