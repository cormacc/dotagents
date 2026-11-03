# Test design

## Independent oracles

Expected results come from the accepted contract, not a second copy of the implementation.

For a sum of 10 and 5, assert the known result 15. Repeating the production reduction in the test can repeat its defect. For rounding, ordering or conversion, work an example from the specified rule and state that rule.

A snapshot is useful only when its expected content was independently checked. Recording the current output is not proof that it is correct.

## Observable interfaces

Pick the narrowest existing interface that reaches the real failure pattern. A single-caller unit test cannot lock down a bug that needs two callers sharing state. An integration test that only checks that nothing crashed cannot prove a required state transition.

Check public retrieval after a public write when retrieval is the promised behaviour. Inspect the stored representation directly when that representation, migration or persistence guarantee is itself the contract. Do not create a new public method merely to avoid an internal assertion.

A behaviour-preserving restructuring should not require rewriting a behavioural test. If it does, identify whether the test observes an incidental implementation detail or whether the supposedly preserved contract actually changed.

## Dependencies and interactions

Prefer real, local dependencies when they are cheap, deterministic and safe. Substitute external services, time, randomness and other unstable inputs at the boundary that owns them. Verify that a fake honours the relevant failure behaviour; a permissive fake can make a broken contract pass.

Mocking an internal collaborator is not automatically wrong. Use it when the interaction is the promised behaviour or when a focused test needs isolation that the repository already supports. Avoid asserting incidental helper calls just because they are easy to count.

For example, if a durable queue promises to acknowledge only after persistence succeeds, assert persist-before-ack and no acknowledgement on persistence failure. A final-state assertion alone can miss that ordering guarantee.

## Coverage-preserving changes

Before replacing lower-level tests with an interface-level test, list the assertions and failure paths each existing test covers. Point to their replacements or keep the tests. An interface test that covers one happy path does not replace validation, retry, ordering or cleanup assertions.

## Review questions

- Can the proposed test disagree with an implementation that violates the required behaviour?
- Does it exercise the same caller count, state lifetime and boundary as the reported failure?
- Is the expected value independent of the implementation?
- Does a deliberately broken condition make the focused check fail?
- Does the same test remain valid after an internal restructuring that preserves the contract?

This reference is part of the adaptation described in [../SKILL.md#Provenance](../SKILL.md#provenance), under [../LICENSE](../LICENSE).
