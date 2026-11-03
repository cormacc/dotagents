---
name: chromium
description: Interactive browser automation via Chrome DevTools Protocol. Use when you need to interact with web pages, test frontends, or when user interaction with a visible browser is required.
---

# Chromium Browser Tools

Browser automation is provided by the `browser_nav`, `browser_eval`, `browser_tabs`, `browser_screenshot`, `browser_inspect`, `browser_pick`, and `browser_cookies` tools. These connect to Chromium/Chrome on `localhost:9222`; the browser must be running with `--remote-debugging-port=9222`.

## Stable targets

Choose a tab before acting: use `browser_nav` with `newTab: true` to open a new one, or use `browser_tabs` to obtain a stable `tabId`. Unscoped enumeration lists IDs and titles without URL fields. Supply `tabId` to enumerate only that tab's frames and URLs; this also selects its main frame.

All action tools accept `tabId` and `frameId` and return the chosen target. For a cross-origin iframe, pass its opaque `frameId` from `browser_tabs({tabId})` to `browser_inspect` or `browser_eval`; do not traverse it through the parent DOM. Omit both IDs to reuse the exact selected frame. An explicit `tabId` without `frameId` resets to its main frame. With multiple tabs and no selection, choose explicitly rather than relying on focus or ordering.

Closed tabs and missing frames fail without fallback. Select a live tab and re-enumerate frames to recover. Frame handles must be re-enumerated after a reconnect or extension reload. `newTab: true` cannot be combined with target IDs. Full contract: [../../extensions/chromium/README.md#Targeting](../../extensions/chromium/README.md#targeting).

## Workflow

1. **Investigate first**: use `browser_eval` on the chosen target to understand the page before acting:
   ```javascript
   (function() {
     return {
       title: document.title,
       forms: document.forms.length,
       buttons: document.querySelectorAll('button').length,
       inputs: document.querySelectorAll('input').length,
       mainContent: document.body.innerHTML.slice(0, 3000)
     };
   })()
   ```

2. **Target specific elements** based on what you find, then interact.

3. **Use `browser_inspect`** for quick element queries (text, attributes, visibility)
   without writing JavaScript.

4. **Use `browser_pick`** when the user wants to select elements visually, or when
   the page structure is complex/ambiguous.

## Tools

### browser_screenshot

Capture the selected tab viewport or iframe element. Optionally navigate the selected frame first and wait for a CSS selector in that frame. Returns an inline image for vision models.

```
browser_screenshot
  url:         "http://localhost:8080/#/patients"  (optional)
  waitFor:     ".patient-list"                     (optional CSS selector)
  waitTimeout: 10                                  (optional, seconds)
```

### browser_inspect

Extract rendered text, attributes, or element state by CSS selector in the selected frame. Lighter than a screenshot when you just need to check content. Text extraction excludes normal script/style bodies and hidden data; use `action: "raw"` explicitly for DOM source text. Results are bounded and report truncation. Full contract: [../../extensions/chromium/README.md#Inspection](../../extensions/chromium/README.md#inspection).

```
browser_inspect
  selector:  "h1"
  action:    "text" | "raw" | "html" | "attr" | "count" | "visible" | "exists"
  attribute: "href"       (when action is "attr")
  url:       "..."        (optional, navigate first)
  waitFor:   true         (default: true)
```

### browser_eval

Evaluate JavaScript in the selected tab/frame, including cross-origin frames. Use for complex interactions.

```
browser_eval
  code: 'document.querySelector("#submit").click()'
```

### browser_nav / browser_tabs / browser_cookies / browser_pick

- `browser_nav`: navigate the selected tab/frame, or open and select a tab with `newTab: true`.
- `browser_tabs`: list tab IDs grouped by window; supply `tabId` to select its main frame and enumerate its frames.
- `browser_cookies`: show cookies for the selected frame's URL.
- `browser_pick`: interactive element picker inside the selected frame.

## Starting the Browser

The browser must be running with `--remote-debugging-port=9222`. Use the
included start script (works on Linux, macOS, and Windows):

```bash
node browser-start.js             # Start with a clean profile
node browser-start.js --profile   # Copy your default profile (cookies, logins)
```

Or start manually:

```bash
# Linux
google-chrome-stable --remote-debugging-port=9222 --user-data-dir=$HOME/.cache/chromium-debug
# macOS
/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --remote-debugging-port=9222 --user-data-dir=$HOME/.cache/chromium-debug
```

## Efficiency Guide

### DOM Inspection Over Screenshots

**Don't** take screenshots to see page state. **Do** use `browser_inspect` or
parse the DOM with `browser_eval`:

```javascript
Array.from(document.querySelectorAll('button, input, [role="button"]')).map(e => ({
  id: e.id, text: e.textContent.trim(), class: e.className
}))
```

### Use IIFEs for Complex Scripts

Wrap multi-statement code in an IIFE for `browser_eval`:

```javascript
(function() {
  const data = document.querySelector('#target').textContent;
  document.querySelector('#submit').click();
  return JSON.stringify({ data });
})()
```

### Batch Interactions

**Don't** make separate `browser_eval` calls for each click. **Do** batch them:

```javascript
(function() {
  ["btn1", "btn2", "btn3"].forEach(id => document.getElementById(id).click());
  return "Done";
})()
```

### Waiting for Updates

If the DOM updates after actions, make a second `browser_eval` call after a brief
pause rather than trying to add delays in JS. The persistent connection makes
sequential tool calls cheap.
