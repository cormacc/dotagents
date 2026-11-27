# Penpot

Status: notes for a future back end. Nothing below is verified as an extraction route; check each point and update this file before you rely on it.

## Observed

- Cloud instance: `https://design.penpot.app`. A workspace URL has the form `/?screen=workspace&team-id=<uuid>&file-id=<uuid>...`.
- A Figma file reaches Penpot through the Penpot Exporter Figma plugin: it writes a `.penpot` file, which the user imports through Projects > Import Penpot file. The plugin lists the custom fonts that the file uses and says that they must be uploaded to Penpot before use. See [figma.md#Plugins and copies](figma.md#plugins-and-copies).

## To verify

- The Inspect tab: whether it shows exact values and generated CSS for the selection to a viewer, and how to read it from the DOM.
- The access-token API: how to create a token, the endpoint that returns a file's tree, and any rate limits.
- The plugin API, and whether a plugin can export a file's values in one pass.
- Design tokens: whether a file's colours and type styles export as a token file.
- Whether an import from Figma keeps layer names, component names and auto layout values, so that values read in Penpot equal those in Figma. Compare three values with the Figma record before you trust Penpot as a source.

When the route is verified, update the back-end table in [../SKILL.md](../SKILL.md) and a script beside `figma-cdp.mjs` if the browser route is needed.
