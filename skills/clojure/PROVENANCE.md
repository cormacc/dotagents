# Provenance

Attribution and upstream sources for adapted content in the `clojure` skill. An agent does not need this file to use the skill, so it is kept out of `SKILL.md` and its references.

## references/review.md

The hazard and candidate lists adapt the `clojure-reviewer` subagent of the `clojure-review` plugin in https://github.com/editor-code-assistant/eca-plugins (Apache License 2.0, commit `0defb6d`). This adaptation keeps defect, readability, and simplification items, each under the bar of the workflow that owns it. The plugin's Boy Scout rule is now [../code-review/SKILL.md#Cleanup in modified code](../code-review/SKILL.md#cleanup-in-modified-code), limited to the functions and forms that the change modifies. The destructuring preference is also in [idioms.md#Local preferences](references/idioms.md#local-preferences). It omits these plugin items:

- "Suggest 2-3 critical tests" -- `code-review` already requires tests for changed failure paths.
- Praise for good design, the emoji priority scale, and the "LGTM" reply -- `code-review` sets the output format.
- The `eca__task` step and the book list -- ECA-specific, or not testable.

The **Mechanism and policy** item adapts the `clj-refactor` skill in https://github.com/humorless/clj-native-agent (MIT License, commit `de8b9b3`), including its rule that a pattern needs at least two uses before extraction.
