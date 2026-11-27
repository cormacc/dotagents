# Icons from a design file

Designers usually draw icons from a public library. Identify the library and the icon names, then use the library's own files at a pinned version. A copy of an exported glyph has no name, no licence record and no upstream for new icons.

## Find the icons

- **Component instances in the mockups** are the best source: they carry names (in Figma, layer names such as `icon/help`) and sit on the square canvas that the code will use (18, 20, 24 or 30 px).
- **An icon sheet** (a frame of loose icons) often has unnamed groups ("Group 223", "Vector"), cropped to the glyph. Use it to see the set, not to identify names.
- **Icon files sent by the designer** may be minified copies of the sheet: unnamed, cropped and incomplete. Do not treat them as the source.

In Figma: `search "icon/"`, `select-results`, `export --format svg --out <dir>`. See [figma.md#Recipes](figma.md#recipes).

## Identify the library

1. Read the exported SVG: canvas size (`width`, `height`, `viewBox`), `stroke-width`, `stroke-linecap`, `stroke-linejoin`, and whether shapes are strokes or fills. Round caps and joins with a 2 px stroke suggest Lucide or Feather; fills suggest a filled set.
2. Guess the icon name from the layer name, and fetch the candidate from the library at a pinned version, e.g. `https://unpkg.com/lucide-static@<version>/icons/<name>.svg`.
3. Find the scale: the exported canvas divided by the library's canvas (18 / 24 = 0.75). A designer who scales an instance usually keeps the stroke width, so the stroke does not tell you the scale.
4. Divide each coordinate in the exported path data by the scale, and compare with the library's path data. An exact match on two or three icons identifies the library. A near match on one icon can mean an older release of the same library: check one or two older versions before you choose a different library.
5. Record the library, the version that matched, the scale, and the icons that matched, in the values record.

Observed on 2026-11-27: the Lenire Connect mockups used Lucide at 0.75 scale with the 2 px stroke kept; `megaphone` and `circle-help` matched `lucide-static` exactly, and `zap` matched an older Lucide release.

## Choose names

Use the library's upstream names in code. Where the design's layer name differs from the upstream name (`help` for `circle-help`), map it once in the values record.
