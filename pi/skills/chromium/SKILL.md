---
name: chromium
description: Interactive browser automation via Chrome DevTools Protocol. Use when you need to interact with web pages, test frontends, or when user interaction with a visible browser is required.
---

# Chromium Browser Tools

Browser automation is provided by the `browser_nav`, `browser_eval`, `browser_tabs`,
`browser_screenshot`, `browser_inspect`, `browser_pick`, and `browser_cookies` tools.
These connect to Chromium/Chrome on `localhost:9222` — the browser must be running
with `--remote-debugging-port=9222`.

> **See also:** The `webdriver` extension provides an alternative browser automation
> approach using etaoin/WebDriver via babashka. It manages its own browser lifecycle
> and may be more suited to automated testing and CI workflows.

## Workflow

1. **Investigate first** — use `browser_eval` to understand the page before acting:
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

Take a screenshot. Optionally navigate first and wait for a CSS selector.
Returns an inline image for vision models.

```
browser_screenshot
  url:         "http://localhost:8080/#/patients"  (optional)
  waitFor:     ".patient-list"                     (optional CSS selector)
  waitTimeout: 10                                  (optional, seconds)
```

### browser_inspect

Extract text, attributes, or element state by CSS selector. Lighter than a
screenshot when you just need to check content.

```
browser_inspect
  selector:  "h1"
  action:    "text" | "html" | "attr" | "count" | "visible" | "exists"
  attribute: "href"       (when action is "attr")
  url:       "..."        (optional, navigate first)
  waitFor:   true         (default: true)
```

### browser_eval

Evaluate JavaScript in the active tab. Use for complex interactions.

```
browser_eval
  code: 'document.querySelector("#submit").click()'
```

### browser_nav / browser_tabs / browser_cookies / browser_pick

- `browser_nav` — Navigate to a URL (with optional `--new` tab)
- `browser_tabs` — List all open tabs grouped by window
- `browser_cookies` — Show cookies for the active tab
- `browser_pick` — Interactive element picker (user clicks elements in the browser)

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
