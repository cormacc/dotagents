# Figma

Verified on 2026-11-27 against the Figma web app in Chrome 157 on Linux, as a signed-in collaborator with view access and no Dev Mode seat. Figma changes its web app often: when a command fails, inspect the DOM anchors below before you change the workflow.

## Contents

- [#URLs](#urls)
- [#Access levels](#access-levels)
- [#The figma-cdp CLI](#the-figma-cdp-cli)
- [#Recipes](#recipes)
- [#Properties panel](#properties-panel)
- [#Export](#export)
- [#Comments](#comments)
- [#Gotchas](#gotchas)
- [#DOM anchors](#dom-anchors)
- [#REST API](#rest-api)
- [#Plugins and copies](#plugins-and-copies)

## URLs

`https://www.figma.com/design/<file-key>/<file-name>?node-id=<node-id>`

- The file key identifies the file for the REST API and for `figma-cdp status`.
- `node-id` in a URL uses a hyphen (`953-2528`); the API and the Layers panel use a colon (`953:2528`).
- A duplicate of a file has a new key.

## Access levels

| Access | What the agent can read |
|--------|-------------------------|
| Signed out, link access | Canvas only: screenshots and crops. Hex values only where a palette sheet prints them. |
| Signed in, view access (no Dev Mode) | Right panel tabs "Comments" and "Properties". Properties gives exact values for the selection, and an Export section. The panel shows a Dev Mode box with a "Request access" button; ignore it. |
| Signed in, edit access (own copy) | Right panel tabs "Design" and "Prototype"; plugins run. `figma-cdp` is not verified in this mode. |
| Dev Mode seat | Not verified. |

The page does not expose the plugin API (`window.figma`) to page scripts.

## The figma-cdp CLI

`scripts/figma-cdp.mjs` talks CDP directly to the Figma tab's WebSocket; it has no npm dependencies. It is host code only: every function that runs inside the Figma page is in `scripts/figma-page-snippets.mjs`, and the host calls a snippet by name. `scripts/test-figma-page-snippets.mjs` checks that each snippet runs without the module around it. Each invocation runs one command and prints one JSON object: check `ok` before you read other fields. On failure, `error` says what went wrong and `help` gives the next command to try. Exit status: 0 success, 1 error, 2 usage error.

```bash
F=<skill-dir>/scripts/figma-cdp.mjs
node $F tabs                                 # Figma tabs, with their ids
node $F status --tab <id>                    # file key, node id, panels, selection
node $F layers --tab <id>                    # rendered Layers panel rows
node $F select "Colour Palette" --zoom --tab <id>
node $F props --tab <id>                     # Properties panel lines
```

- `--tab` takes a tab id, or a substring of the URL or title. Without it, the CLI uses the only tab with a `figma.com/design` URL, and fails if there are several.
- Each command brings the tab to the front: a background tab gets no animation frames, so Figma does not update its panels after input.
- Coordinates are CSS pixels. A screenshot has `devicePixelRatio` image pixels for each CSS pixel; `status` and `shot` report the ratio. Divide image coordinates by it before `click`.
- `key` takes DOM `code` names joined by `+`: `Escape`, `Enter`, `Shift+Digit2`, `Alt+KeyL`. `Mod` is Control on Linux and Windows, Meta on macOS.

## Recipes

**Values of a named frame or layer.** `select <name>` (or `select <node-id>`), then `props`. If the row is not rendered, `expand <parent>` first, or use `search`.

**Values of an element inside a frame.** Zoom to the frame (`select <frame> --zoom`), take `shot`, find the element in the image, then `click <x> <y> --deep`. Deep select picks the innermost layer under the point and returns its Properties. Click again with `--deep` on a different point to compare siblings.

**Every instance of a component.** `search "icon/"` lists the total and the rendered results; `select-results` selects all of them (it fails if the selected count differs from the total); then `export`.

**All children of a frame.** `select <frame>`, then `key Enter` (selects the children), then `export`.

**Reference image.** `select <frame> --zoom`, then `shot <file.png> --clip x,y,w,h` with the frame's on-screen rectangle in CSS pixels. Convert for the record with `magick <file.png> -quality 85 <file.jpg>`.

**Clear the selection.** `key Escape`.

## Properties panel

For the selection, the panel shows, as text lines:

- Name and layer type.
- Layout: width and height (with "Fixed", "Hug" or "Fill"), position, flow direction, padding, gap, radius.
- Colours: hex value and opacity percentage, for fills and borders, with border width and sides.
- Typography: font family, weight and style, size, line height, letter spacing, decoration.
- Effects: drop shadow as X, Y, blur, spread, colour and opacity.
- Export settings.

With several layers selected, the panel shows "N Selected" and the colours and typography that the selection uses.

## Export

- The Export section is in the Properties panel. `export` adds an export setting if none exists, sets the format, presses the Export button ("Export <layer name>" or "Export N layers"), and waits for the file in `--out`.
- Formats: PNG, JPEG, SVG, PDF (`--format png|jpg|svg|pdf`). The first combobox in the panel is the colour format (Hex), not the export format; the CLI selects the one that shows a file type.
- One layer exports as `<layer name>.<ext>`. Several layers export as one zip named after the file, with folders from slash-separated layer names (`Connect/Tile/icon/help.svg`) and `-1`, `-2` suffixes on duplicate names.
- An export setting stays on the layer while the tab is open, so the next export of that layer reuses its format.

## Comments

`comments` switches to the Comments tab, reads every thread in the list, and switches back. Each thread gives `id` (`#78`), `page`, `author`, `age` and the top-level `text`; replies need the browser. The list is not limited to the current page (on 2026-11-27 it showed threads from an older page only): check `page` before you use a value from a comment.

## Gotchas

- **Escape** clears the selection, and closes the Export format list. Once, directly after an export, the first Escape did not clear the selection and a second one did: read `selection` in the output of `key`.
- **Click in the Layers panel**: a plain click on a row inside a multiple selection keeps the whole selection, so `select` presses Escape first. Shift+click (`--range`) selects a range; Mod+click (`--toggle`) adds or removes one row.
- **Virtualised lists**: the Layers panel, the search results and the comments list render only rows near the visible part. The CLI scrolls them through the DOM (`scrollTop`); wheel events over them were unreliable.
- **Shortcuts** (verified): Shift+2 zooms to the selection, Alt+L collapses all layers, Enter selects the children. Shift+1 (zoom to fit) and Shift+Enter (select the parent) are in the Figma shortcut list but not verified here.
- **Layer names are not unique**: `select` uses the first match and warns on stderr. Use the node id from `layers` to be exact.
- **Selection state**: `aria-selected` also marks the descendants of a selected layer. The CLI reads `data-fpl-selected-root`, which marks only the selected layers.

## DOM anchors

The page snippets in `scripts/figma-page-snippets.mjs` depend on these. Check them first when the CLI breaks, and change the snippet, not the host.

| Anchor | Element |
|--------|---------|
| `[data-testid="<node-id>-layers-panel-row"]` | Layers panel row; its `[role=row]` ancestor has `aria-level`, `aria-expanded`, `data-fpl-selected-root` |
| `[data-testid=layers-panel-expand-caret]` | Expand caret inside a row |
| `[data-testid=objects-panel]` | Layers panel |
| `[data-testid=properties-panel]` | Right panel content |
| `[role=tab]` | Right panel tabs ("Comments", "Properties") |
| `[aria-label="Add export settings"]`, `button[role=combobox]`, `[role=option]` | Export controls |
| `[data-testid=canvas-search-icon-button]`, `[data-testid=canvas-search-input]` | Layer search (a `textarea`) |
| `[data-testid=search-sidebar] [role=option]` | Search results; "N results" in the sidebar text |
| `[data-testid=comment-message]` | Comment text; the thread card is the ancestor with a class that starts with `comments_row_presentation--comment-` |

## REST API

Not verified in this skill: no token was available. From the Figma developer documentation (read 2026-11-27):

- Authenticate with a personal access token in the `X-Figma-Token` header. The user generates it under Settings > Security, with scopes such as `file_content:read`. A token gives only the access that the user has.
- `GET /v1/files/:key` returns the document tree as JSON; `GET /v1/files/:key/nodes?ids=<ids>` returns chosen nodes; `GET /v1/images/:key?ids=<ids>&format=svg|png|jpg|pdf&scale=<n>` returns URLs of rendered images.
- These three are Tier 1 endpoints. A View or Collab seat gets up to about 6 to 20 Tier 1 requests a month (the page gives both figures), and possibly fewer; Dev and Full seats get 10 to 20 a minute, depending on plan. https://developers.figma.com/docs/rest-api/rate-limits/

For a view-only collaborator, the browser route is the practical one. Before you use a token, confirm the user's seat, and spend the requests on whole-file reads, not one node per request.

## Plugins and copies

A view-only user can duplicate the file to their drafts; the copy is editable and runs plugins. Observed on 2026-11-27: the Penpot Exporter plugin (v0.26.3) ran on such a copy and produced a `.penpot` file. Its dialog said that the export held only the current page unless "All pages" was selected, listed the custom fonts that Penpot needs uploaded (here CircularXX, Proxima Nova, Circular Pro), and pointed to Projects > Import Penpot file in Penpot.

A copy does not track later changes to the original. Record which file you read.
