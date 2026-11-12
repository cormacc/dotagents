# Bounded discovery

Use when unresolved design decisions prevent an actionable implementation plan, especially across sessions. A well-scoped change needs no discovery stage. Keep the existing task/change-record canonical and retain its acceptance and approval rules.

## Incoming requests

Before turning an external request into an implementation brief, read its body, relevant discussion and existing decisions. Check whether the requested behaviour already exists and whether a prior rejection applies; verify the current claim from code or a bounded reproduction before treating it as settled. Record where you checked and any missing evidence. An empty search needs a positive control before it can support an absence claim.

A brief states current and desired behaviour, relevant interfaces, observable acceptance and explicit non-goals. Preserve the originating contract and unresolved stakeholder decisions; an agent-written brief does not silently replace them. Use the existing tracker skill for authorised updates, without adding labels, a rejection directory or a second state machine.

## Clarify the domain

Challenge an overloaded term against the existing glossary, contract and code before using it in acceptance criteria. Offer a precise distinction, then ask the user which meaning is intended. Use concrete scenarios to test relationships, cardinality, ordering and ownership; check claims against source and surface contradictions rather than silently choosing a side.

Capture agreed terminology in its existing contract or glossary. Create a glossary only when a recurring vocabulary actually needs one. Record rationale in the change-record; offer a separate ADR only when the decision is hard to reverse, surprising without context and the result of real alternatives. Do not create a parallel glossary or ADR tree by default.

## Resolve decisions before scheduling their consequences

1. State the destination and scope. Separate decisions needed to reach it from implementation deliverables.
2. Put precise unresolved questions in `Open questions`. When an investigation is independently actionable, use an ordinary task with its question and observable completion criterion; record real dependencies with `:BLOCKED-BY:`.
3. Keep in-scope areas that cannot yet be phrased as precise questions as a coarse note in the same record. Do not invent downstream tasks or acceptance criteria to fill the unknowns.
4. Resolve an unblocked question: facts from source or the existing scout/researcher roles, preferences from the user, experiments from permitted scratch work or a scoped worker assignment. Resolve one user decision per question. A parent-owned analyst comparison follows the recipe below; it does not expand a planner's spawn allow-list.
5. Record the answer and its evidence in the existing decisions/contract, link supporting artefacts and revise dependent questions. Promote a coarse note into a precise question only when evidence makes that possible.
6. Stop discovery when the implementation plan is actionable. Apply the main planning skill's atomic acceptance and task-splitting rules; do not keep interviewing merely to visit every possible branch.

On resume, load the task and Summary, then the relevant open question and linked evidence. A decision index points to its detail rather than copying it. Recheck mutable code claims before using a prior answer as a premise. A portable handoff may name the next relevant skills, but it supplements the existing record and excludes secrets and duplicated decisions.

## Question-first experiments

Before a prototype, state the one design question and the observation that would answer it. Choose the smallest runnable artefact: a state/logic exercise with visible transitions, or contrasting UI variants whose differences answer the question. Use repository `.tmp/`, disposable state and existing runtime tools; do not connect to live resources or skip safety controls merely because the artefact is temporary.

Let the user inspect the relevant state or variants. Record the answer and its evidence in the change-record, retaining a supporting artefact only when it is needed. A prototype neither authorises production implementation nor an automatic branch, commit or dependency install.

## Compare interfaces when the decision warrants it

For a costly interface choice, describe the required behaviour, caller examples, constraints and current friction before proposing an abstraction. An interface includes invariants, ordering, failure modes and configuration, not just its type signature.

Apply the deletion test: would removing the proposed layer remove unnecessary complexity, or merely spread required behaviour back across callers? Prefer the smallest interface that hides required complexity; do not merge modules or invent extension points solely to make them look deeper.

Compare two materially different designs against the same contract. If independent exploration is worth its cost and delegation is authorised, the root may give fresh `base-analyst` children different bounded objectives, such as minimal interface versus simplest common caller. The planner must not spawn beyond its existing allow-list. Use the existing orchestration concurrency, evidence, capture and close rules; do not launch a mandatory panel.

Each design supplies an interface contract, a caller example, hidden implementation responsibility, dependency strategy and trade-offs. Compare where required complexity and future changes live, then recommend an option for user approval. Keep safety and failure-path tests; an interface-level test does not justify deleting lower-level coverage without accounting for its assertions.
