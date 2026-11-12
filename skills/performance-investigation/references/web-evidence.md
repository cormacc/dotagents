# Web performance evidence

Interpret only evidence actually available for the assigned page, route, workload and release. A screenshot or static source inspection cannot measure Core Web Vitals.

## Separate evidence types

| Source | What it supports | Limit |
|---|---|---|
| Source inspection | A potential cost and a measurement hypothesis | No measured latency, LCP, INP or CLS |
| Lab report | A synthetic run under its recorded conditions | Not real-user field performance or an isolated causal comparison by itself |
| Field report | User experience for the reported population and window | Aggregation and release overlap can delay or obscure attribution |
| Trace or profile | Observed work and timing in the captured run | Not a population percentile without suitable sampling |

Label each value with its metric, unit, source, statistic, population or test conditions, time window and version when known. Mark missing values `not measured`. Do not relabel a lab value as field data or a route aggregate as a single interaction.

## Interpret before recommending

- Identify the framework, rendering model, measured route and relevant device or network conditions. Do not recommend a framework-specific component or API to another stack.
- Follow the measured bottleneck: resource discovery, server wait, transfer, main-thread work or layout movement. Verify attribution before applying a familiar optimisation.
- Retain the project's agreed targets. Verify published metric definitions and thresholds against current authoritative documentation when using them; a Lighthouse score is not interchangeable with a Core Web Vital.
- Separate local benchmark improvement from a claim about user experience. Field evidence must support the latter; supplied field evidence does not by itself isolate one code change's causal effect.

## Tool availability

Use supplied reports when available. Otherwise verify what the current browser tools actually expose before planning a capture. A DOM inspector and screenshot tool do not imply access to a performance trace, Lighthouse or a field-data API. Report the missing capability and request an artefact or an authorised capture; do not fabricate tool names or install a new browser integration automatically.

A useful report may contain no scorecard values: list source-level hypotheses as `potential impact`, state what is unmeasured, and name the next discriminating measurement.
