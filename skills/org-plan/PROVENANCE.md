# Provenance

Attribution and upstream sources for adapted content in the `org-plan` skill. An agent does not need this file to use the skill, so it is kept out of `SKILL.md` and its references.

## SKILL.md

The acceptance-criteria evidence rules (`test:` and `cmd:`) adapt the `acceptance` skill from [rpiv-mono](https://github.com/juicesharp/rpiv-mono/blob/46dbdfd78ab631bbf3ce41c2618bb173a5ec2725/packages/rpiv-pi/skills/acceptance/SKILL.md) at that revision (MIT License).

## references/discovery.md

Adapted from Matt Pocock's `triage`, `prototype`, `domain-modeling`, `wayfinder` and `codebase-design` (including `DESIGN-IT-TWICE.md`) under [skills/engineering](https://github.com/mattpocock/skills/tree/4ae2317ddb22b046e26455e4f9c9b3aaaf62212d/skills/engineering) at that revision (MIT; [matt-pocock-LICENSE](references/matt-pocock-LICENSE)). Retains active terminology checks, decision-first discovery and alternative interfaces; uses existing Org memory and role permissions rather than tracker maps, compulsory document trees or fixed agent/session quotas.

## Prior-record lookup, Gotcha durability test and settled-decision labels

Adapted from the [compound-engineering-plugin](https://github.com/EveryInc/compound-engineering-plugin/tree/ee3695a) `ce-compound` skill, the `learnings-researcher` agent and `ce-plan`'s `settled-decisions.md` at that revision (MIT). Retains searching earlier lessons before planning, a keep-or-delete test for recorded lessons, and the two provenance classes with no self-settling; uses `design/log/` Gotchas and Decisions as the lesson store rather than a `solutions/` directory.
