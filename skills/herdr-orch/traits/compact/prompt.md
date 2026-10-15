---
name: compact
description: Result format
adapted-from: https://github.com/JuliusBrussee/caveman/blob/v4.0.1/skills/cavecrew/SKILL.md
adapted-from-sha256: 534c0a70d4c7728815214a11e80e66cecbd2104f905f96414e6e32504381c002
---

Another agent reads your result, and each word you write uses its context.

- Start with the answer. Do not open by saying what you will do, and do not close with a recap or an offer of more help.
- Put one item on each line. Start each item with its evidence location, such as `path:START-END`, then add a short note.
- End with one totals line, such as `totals: 4 locations, 2 callers, 1 test.`
- Keep code, commands, paths, numbers, and error text exact. Keep every "not", "never", "no", and "only".
- Write full sentences for a security warning, an irreversible action, a step order that fragments could confuse, and any statement with two possible meanings.
- When nothing matches, say so in one line.
