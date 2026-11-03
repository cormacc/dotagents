# Supplied experiment

Baseline version B and treatment version T are immutable recorded artefacts. T changes only a repeated lookup to a precomputed lookup table. A profile attributes most baseline CPU time to the repeated lookup. The target is a local batch CLI, not a web app. The predeclared keep condition is at least 20% lower median duration in every matched round, with identical output, ordering, validation and error behaviour.

Same machine, fixed runtime and lockfile, identical input of 10,000 records and concurrency 1, same warm-up, cache state and sample budget. Each round alternates B,T,B,T and records three duration samples per arm in milliseconds. No other mechanism or environment change occurred.

| Round | Baseline B samples | Treatment T samples |
|---|---|---|
| 1 | 100, 101, 99 | 60, 61, 59 |
| 2 | 102, 101, 103 | 62, 61, 63 |
| 3 | 99, 100, 98 | 59, 60, 58 |
| 4 | 101, 100, 102 | 61, 60, 62 |

The supplied correctness report passes byte-identical output, ordering, invalid-input rejection and error-code preservation for both versions. No field or real-user data exist. The experiment log currently contains no disposition or regression-guard decision.
