# Gate: %compact

Trait: `compact`
Consumer: `scout` (packaged persona, frontmatter `traits:`), and any spawn that adds `--trait compact`, and interactive `%compact` use. This gate exercises a read-only localisation scaffold, not the full `scout` persona.
Status: **ADMITTED on named consumers plus four recorded runs (one scenario; not a general proof): discriminating in native Codex at `gpt-5.6-terra` and `gpt-6.1-terra`; not discriminating as written in Pi at `claude-sonnet-5-5`, with a scaffold (run 3) or the packaged `scout` persona (run 4).**

Pass condition, fixed before the run: the first non-blank line of the response is the answer or a location item, not a heading and not a sentence that says what the response will do; every location claim is written as `path:START-END` or `path:LINE`; the response ends with exactly one totals line; and there is no closing recap, summary paragraph, or offer of further help. A response with a heading, an opening sentence such as "Here is" or "I found", a closing summary, or no totals line fails.

## Scaffold

You locate code for another agent that will make a change. You report findings only; you never modify files.

%compact

## Assignment -- SYNTHETIC

The code below is the whole of a small project. It is not in any repository that you can read, so use only this text. Line numbers are at the start of each line.

`src/rates.py`:

```
1  DEFAULT_RATE = 0.2
2
3  def tax_for(amount, rate=None):
4      r = DEFAULT_RATE if rate is None else rate
5      return round(amount * r, 2)
```

`src/invoice.py`:

```
1  from rates import tax_for
2
3  def total(lines):
4      subtotal = sum(line.price * line.qty for line in lines)
5      return subtotal + tax_for(subtotal)
```

`src/refund.py`:

```
1  from rates import DEFAULT_RATE
2
3  def refund_tax(amount):
4      return round(amount * DEFAULT_RATE, 2)
```

`tests/test_invoice.py`:

```
1  from invoice import total
2
3  def test_total_adds_default_tax():
4      assert total([Line(price=10, qty=1)]) == 12.0
```

The team will change the default tax rate. Where is the rate defined, and where is it used?

## Results

Run 1 -- 2026-10-15, model `gpt-5.6-terra`, via `scripts/run-trait-gate.bb compact --model gpt-5.6-terra`. Outputs under `.tmp/trait-gates/compact-1792052397087-4135722/`. The treated prompt contained the fragment text and the control prompt did not.

- Treated: the first line is a location item; every item is `path:LINE`; the last line is `totals: 6 locations, 2 production callers, 1 test.`; no heading, opener, or recap. Meets the condition.
- Control (`%compact` line removed): opens with the answer, which the condition allows, but adds a fenced code block and a bulleted list, and has no totals line. Fails the condition.
- **Discriminated.** The effect is format compliance: both arms found the same six locations, and the control's answer was correct and short. The trait changes the shape of the result, not its content.

Run 2 -- 2026-11-12, model `gpt-6.1-terra`, the Codex model behind the current `light` tier and `gpt-terra` alias (`openai-codex/gpt-6.1-terra` in `skills/herdr-orch/subagents/config.edn`), via `PATH=/nix/store/a2qbzx4s1a5b09vlzg1zlhh14x37f1nl-codex-0.166.0/bin:$PATH scripts/run-trait-gate.bb compact --model gpt-6.1-terra` at commit `eec5bbc`. Scaffold, assignment and condition are unchanged. Both native Codex stderr logs record model `gpt-6.1-terra`, provider `openai`, sandbox `read-only` and reasoning effort `none`. Codex CLI 0.166.0 ran from the Nix store because no `codex` is on PATH. Outputs under `.tmp/trait-gates/compact-1794448170000-3904214/`.

- Treated: the first line is a location item; all five items use `path:LINE` or `path:START-END`; the response ends with one totals line (`totals: 5 locations, 2 uses, 1 test.`) and has no opener or recap. Meets the condition.
- Control: opens with a sentence, uses prose and a bulleted list, and has no totals line. Fails the condition.
- **Discriminated.** The effect is format compliance again: both arms found the same locations.

Run 3 -- 2026-11-12, first run in the host harness: Pi with its complete deployed resource context, via `scripts/run-trait-gate.bb compact --model anthropic/claude-sonnet-5-5:medium` (runner as committed at `0b760bb`; scaffold, assignment and condition unchanged). `anthropic/claude-sonnet-5-5` is the Anthropic route of the `light` tier; thinking `medium` was requested through the model suffix, and the event stream does not record it. Both `{treated,control}.provenance.json` files record provider `anthropic`, model `claude-sonnet-5-5`, API `anthropic-messages`, one context file (`~/.pi/agent/AGENTS.md`), 32 skills, 33 tools, 4 MCP servers, the exact arm prompt as the user message, no tool call, and an unchanged arm directory. Outputs under `.tmp/trait-gates/compact-1794470888371-754209/`.

- Treated: the first line is a location item, the response ends with one totals line, and it has no opener or recap. One item is written `src/refund.py:1,4`, which is neither `path:LINE` nor `path:START-END`. Applied as written, it fails the location-format clause.
- Control: opens with a `**Definition**` heading, uses headed bulleted sections, and has no totals line. Fails the condition.
- **Did not discriminate** as written, because both arms fail. The arms still differ on the tested axis: treated has the contracted shape except for one comma-separated line list.

Run 4 -- 2026-11-12, packaged persona `scout` in Pi with its complete deployed resource context, via `scripts/run-trait-gate.bb compact --model anthropic/claude-sonnet-5-5:medium --persona scout` at `eae2895`. Each arm's prompt is the gate's assignment only; Pi receives the persona that `oh task run scout --trait compact --print-prompt` composes as an appended system prompt, and the control persona is that text with the one `compact` fragment removed. The audit extension's matcher controls passed, and every actual provider request carried the home `AGENTS.md` and the arm's persona, and carried the fragment only in the treated arm. Both arms record provider `anthropic`, model `claude-sonnet-5-5`, 32 skills, 33 tools and 4 MCP servers. Outputs under `.tmp/trait-gates/compact-1794476746598-1278569/`.

- Treated: starts with the answer and ends with one totals line, but writes `src/refund.py:1,4`, which is neither `path:LINE` nor `path:START-END`, and adds a closing paragraph on how to change the default. Fails the condition as written.
- Control: starts with a `**Defined:**` label, uses labelled sections, and has no totals line. Fails the condition.
- **Did not discriminate** as written, because both arms fail. The arms still differ on the tested axis: only the treated arm ends with a totals line. The `scout` persona body already asks for cited locations, which may narrow the difference.
