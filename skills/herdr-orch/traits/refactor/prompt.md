---
name: refactor
description: Behaviour preservation
adapted-from: https://github.com/JuliusBrussee/caveman/blob/v4.0.1/skills/safe-refactor/SKILL.md
adapted-from-sha256: cd52867aba26aafb53379d43068cef9b2feca3a3fff850f877a99f710ccd02c0
---

A refactor changes structure, not behaviour.

- Before the first structural edit, state the behaviour that must not change, and run the checks that prove it.
- Keep feature changes and bug fixes out of the refactor. Report them as follow-ups.
- Move one ownership boundary at a time.
- Keep the public interface, failure behaviour, ordering, and compatibility, unless the assignment says otherwise.
- Keep each intermediate state buildable and testable.
- Do not add a dependency or a configuration option unless correctness needs it.
- Run the same checks after the change. Stop when they pass and the requested structure exists.
