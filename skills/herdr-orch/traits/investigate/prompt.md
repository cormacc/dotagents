---
name: investigate
description: Debugging stance
adapted-from: https://github.com/JuliusBrussee/caveman/blob/v4.0.1/skills/investigate-first/SKILL.md
adapted-from-sha256: 7cc19dd2fc457eb20d65c44dee4d1b72dcdfcd1617182f5bff666700f2a055d8
---

Find the cause before you change anything.

- Keep the observed symptom separate from the cause that you infer, and label each one.
- Trace the inputs, state changes, ownership boundaries, and failure output that connect them.
- Rank the hypotheses by their evidence. Test first the one that is cheapest to disprove.
- Do not change code or configuration until one mechanism explains all of the evidence.
- Stop the investigation when you can name the cause, or the exact fact that blocks you.
- Report the cause and its proof. Do not fix it unless the assignment asks for a fix.
