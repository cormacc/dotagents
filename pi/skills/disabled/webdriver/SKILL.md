---
name: webdriver
description: Browser-based verification and testing for the ClojureScript SPA. Covers using the webdriver_screenshot, webdriver_inspect, and webdriver_eval extension tools for ad-hoc verification, plus writing persistent etaoin test scripts for formal verification.
---

# Browser WebDriver

> **See also:** The `chromium` extension provides lighter-weight browser automation
> via Chrome DevTools Protocol (puppeteer). It connects to an existing browser,
> evaluates JavaScript directly, and is better suited to interactive development.
> This `webdriver` extension is more suited to automated testing and CI workflows,
> as it manages its own browser lifecycle and uses the W3C WebDriver protocol.

## Overview

Two modes of browser-based verification are available:

1. **Ad-hoc verification** (extension tools) -- use `webdriver_screenshot`,
   `webdriver_inspect`, and `webdriver_eval` to check your work during development.
   These tools manage the browser lifecycle automatically.

2. **Formal verification scripts** (etaoin test files) -- write persistent
   Clojure test scripts under `test/` for repeatable browser-based checks.

## Ad-hoc Verification (Extension Tools)

The `webdriver` extension provides three tools. No setup is required --
the extension starts a babashka nREPL and headless Chrome automatically on
first use.

### webdriver_screenshot

Take a screenshot of the running app. Returns the image inline (vision models
can interpret it directly).

```
webdriver_screenshot
  url:       "http://localhost:8080/#/patients"  (optional, default :8080)
  waitFor:   ".patient-list"                     (optional CSS selector)
```

Use after making UI changes to verify the rendered result.

### webdriver_inspect

Extract text, attributes, or element state from the current page by CSS
selector. Lighter than a full screenshot when you just need to check content.

```
webdriver_inspect
  selector:  "h1"
  action:    "text" | "html" | "attr" | "count" | "visible" | "exists"
  attribute: "href"       (when action is "attr")
  url:       "..."        (optional, navigate first)
```

### webdriver_eval

Evaluate arbitrary etaoin expressions for complex interactions. The driver
is bound to `driver` and etaoin.api is aliased as `e`.

```
webdriver_eval
  expr: '(e/fill-multi driver [:email "user@example.com" :password "secret"])'
```

### Commands

- `/browser [url]` -- Open a headed (visible) browser window.
- `/browser-stop` -- Stop the browser session and clean up.

## Prerequisites

**chromedriver** must be on PATH. It is included in the project flake.nix
`buildInputs`. After pulling changes, run `direnv reload` or `nix develop`.

Verify: `chromedriver --version`

## Project-Specific Patterns

### SPA route verification

The app uses hash-based routing. Navigate to specific routes:

```
webdriver_screenshot  url: "http://localhost:8080/#/patients"
webdriver_screenshot  url: "http://localhost:8080/#/support/sessions"
```

### Waiting for async content

The SPA loads content asynchronously. Always use `waitFor` with a selector
that indicates the content has rendered:

```
webdriver_screenshot  url: "http://localhost:8080"  waitFor: "[data-testid='app-loaded']"
webdriver_inspect     selector: ".patient-row"  action: "count"  waitFor: true
```

### Auth-gated pages

If the page requires authentication, the browser will see the login screen.
Use `browser_eval` to set auth state via localStorage or cookies before
navigating:

```
webdriver_eval  expr: '(e/js-execute driver "localStorage.setItem(\"auth-token\", \"...\");")'
```

### Checking for error states

```
webdriver_inspect  selector: ".error-banner"  action: "exists"
webdriver_inspect  selector: ".error-banner"  action: "text"
```

### Table data extraction

```
webdriver_eval  expr: '(let [rows (e/query-all driver {:css "table tbody tr"})]
                       (mapv #(e/get-element-text-el driver %) rows))'
```

## Formal Verification Scripts

For repeatable browser-based checks (e.g., smoke tests, regression tests),
write etaoin scripts as `.clj` files. These run under babashka.

### Script structure

Place scripts in `.agents/skills/webdriver/scripts/` or in `test/browser/`:

```clojure
#!/usr/bin/env bb
(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {etaoin/etaoin {:mvn/version "1.1.42"}}})
(require '[etaoin.api :as e])

(def driver (e/chrome-headless {:args ["--window-size=1920,1080" "--no-sandbox"]}))

(try
  (e/go driver "http://localhost:8080")
  (e/wait-visible driver {:css "[data-testid='app-loaded']"} {:timeout 15})

  ;; Assertions
  (assert (e/visible? driver {:css ".dashboard"}))
  (assert (= "Lenire Connect" (e/get-title driver)))

  (println "PASS: Smoke test")
  (finally
    (e/quit driver)))
```

### Running verification scripts

```bash
bb test/browser/smoke-test.clj
```

### Etaoin quick reference

When writing scripts, the key etaoin functions are:

- Navigation: `e/go`, `e/refresh`, `e/back`, `e/get-url`, `e/get-title`
- Querying: `e/query`, `e/query-all` -- accept `:css`, `:tag`, keyword ID
- Text: `e/get-element-text`, `e/get-element-attr`, `e/get-element-value`
- Interaction: `e/click`, `e/fill`, `e/fill-multi`, `e/clear`
- Keyboard: `e/press` (`:enter`, `:escape`, `:tab`)
- Waiting: `e/wait-visible`, `e/wait-invisible`, `e/wait-exists`, `e/wait-has-text`
- State: `e/exists?`, `e/visible?`, `e/enabled?`, `e/has-text?`
- Screenshots: `e/screenshot`, `e/screenshot-element`
- JavaScript: `e/js-execute`

Full API: https://github.com/clj-commons/etaoin
