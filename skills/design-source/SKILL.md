---
name: design-source
description: Extract exact values and assets from a design file (Figma now; Penpot and others later) into a values record that an implementation can use. Read colours, typography, sizes, radii, spacing, shadows and designer comments from the design tool, export icons and images, identify the icon library, and crop reference images. Use whenever the user shares a Figma or Penpot link, asks to match, apply or check a design, mockup, frame, palette or style guide, or needs icons or measurements from a design tool, even if they do not say "extract".
compatibility: The Figma CLI needs Node 22 or later and a Chromium-based browser started with --remote-debugging-port=9222, signed in to Figma. Crops need ImageMagick (magick).
---

# Design source

Get the designer's values from the design tool itself, record them once, then implement from the record. A value read from the tool is exact; a value sampled from a screenshot is not (colour management, anti-aliasing, scaling). This skill covers extraction only. Mapping the values onto a code base's design system belongs to that project, for example a `design-handoff` reference in its UI skill.

## Back ends

| Tool | Reference | Status |
|------|-----------|--------|
| Figma (web) | [references/figma.md](references/figma.md) | Browser route verified; REST API route from documentation only |
| Penpot | [references/penpot.md](references/penpot.md) | Notes only; not verified |

Read the reference for the tool in use before you touch the file.

## Choose a route

Use the first route that the user's access permits. Each step down loses precision.

1. **API with a token.** Structured node data and server-side renders. Check the rate limits for the user's seat before you plan on it: a Figma View or Collab seat gets only a few file requests each month.
2. **Inspection panel in the signed-in web app, driven over CDP.** The panel shows exact values for the selection: size, layout, radius, padding, gap, fills, borders, type, shadows. Export works from the same panel. This is the main route for a read-only collaborator.
3. **Plugin export to another tool.** Needs edit rights; for Figma, the user can duplicate the file to their drafts. Useful to move a design into a tool with an open API (e.g. Figma to Penpot).
4. **Canvas screenshots only** (signed out, or no panel). Read hex values from a palette sheet if one exists; treat sampled colours and measured sizes as approximate, and say so in the record.

Ask the user to sign in or to grant access before you fall back to a lower route. Do not use their credentials yourself.

## Workflow

1. **Orient.** Find the file, the page and the frames that are in scope. Ask which page is current when several pages hold versions of the same screens: older pages and comments often give superseded values.
2. **Check access.** Confirm which route works (for Figma: `figma-cdp status`).
3. **Read the palette and type styles first.** A palette or style sheet gives the intended set; values in frames that are not on the sheet (page background, borders) are worth a separate note.
4. **Read each component.** Select it by layer name, or deep-select it on the canvas, and record the panel values. Read one instance of each repeated element, and a second one to confirm.
5. **Export assets.** Export icons as SVG and identify the library before you copy any glyph: see [references/icons.md](references/icons.md).
6. **Read the comments.** They hold behaviour (hover, animation, states) that the frames do not show. Note the page and age of each comment you use.
7. **Crop reference images** of the frames in scope, as JPEG, for the record.
8. **Write the record** from [assets/specs-template.org](assets/specs-template.org), beside the change record or design note that uses it: `<record-stem>/<tool>-specs.org` with the images. State the source file, page, frames, access level and date.
9. **Check the record.** Re-read three values from the tool and compare them with the record: a transcription error otherwise reaches the code unnoticed.

## Rules

- Record the designer's value, not the value you will implement. Where the implementation must differ (contrast, a missing font, a size rounded to a scale), the project records the compromise next to its own code and cites the design value.
- Keep units as the tool reports them (Figma reports px). Convert in the implementation, not in the record.
- Name frames, layers and pages exactly as the tool shows them, so that a reader can find them.
- Put scratch exports and screenshots in the repository's `.tmp/`; commit only the record and its reference images.
- A design file changes without notice. Record the date, and re-extract rather than trust an old record when the user says the design has changed.

## Scripts

| Script | Use |
|--------|-----|
| `scripts/figma-cdp.mjs` | Drive a Figma tab: list and select layers, read the Properties panel, deep-select on the canvas, search layers, export the selection, screenshot, read comments. Run `node scripts/figma-cdp.mjs --help`. |
| `scripts/figma-page-snippets.mjs` | The functions that `figma-cdp.mjs` runs inside the Figma page; all DOM queries are here. |
