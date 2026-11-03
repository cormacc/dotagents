---
name: to-questionnaire
description: Draft a discovery questionnaire for someone who holds knowledge the user lacks. Use when preparing asynchronous requirements questions or a meeting questionnaire for another person. Not for interviewing the current user about their own design, sending messages, or creating a teaching workspace.
---

# Discovery questionnaire

Produce a document that helps one recipient supply the facts or decisions the user cannot supply alone. Interview the sender about the recipient and the desired outcome, not about knowledge only the recipient holds.

## Establish the send

1. Identify the recipient's role, expertise and relationship to the sender from supplied context. Ask one unresolved decision at a time; do not re-ask information already given.
2. Establish what the sender must be able to decide or do after the reply. Separate missing facts from decisions that belong to the recipient.
3. Establish the document's destination and format using the project's existing convention and the requested delivery channel. Ask when a durable output location is unclear; use repository `.tmp/` for an explicitly temporary draft. Do not invent a top-level file or a new memory tree.

If the recipient or outcome is missing, ask the next necessary question and stop. Do not make the sender answer the subject-matter questions intended for the recipient.

## Draft

Use the following shape, adjusted to the requested format:

- **Purpose:** the decision the answers support.
- **From / To / Use:** sender, intended recipient and how the answers will be used.
- **Context:** only the background the recipient needs to answer.
- **How to answer:** any agreed deadline or effort expectation; partial answers and explicit uncertainty are useful. Do not invent a deadline.
- **Questions:** most important first, grouped by theme when needed. Each question asks one thing and has an answer space immediately beneath it. Add a short reason only where the question could otherwise be misread.
- **Anything missing:** one closing invitation for relevant information the questions did not cover.

For example, separate “What load is expected at launch?” from “Who can approve the capacity budget?”: one is a forecast, the other is decision authority.

## Verify and hand back

Map each required outcome to at least one question. Remove compound, leading, duplicate and already-answered questions. Check that the recipient can understand the context without reading this conversation, and distinguish an unknown from an assumed fact.

Save only to the agreed path, report that path and flag unresolved coverage. Drafting does not authorise sending mail, uploading a document, contacting the recipient, or changing task state. Feed returned answers into the existing plan or contract rather than treating the questionnaire as a second specification.

## Provenance

Adapted from Matt Pocock's [to-questionnaire](https://github.com/mattpocock/skills/blob/4ae2317ddb22b046e26455e4f9c9b3aaaf62212d/skills/productivity/to-questionnaire/SKILL.md) at that revision (MIT; [LICENSE](LICENSE)). Retains sender/recipient separation, outcome coverage and single-idea questions; uses local interaction, output-location and approval rules instead of a fixed current-directory Markdown target.
