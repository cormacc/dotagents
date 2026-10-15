# Gate: %compact

Trait: `compact`
Consumer: `scout` (packaged persona, frontmatter `traits:`), and any spawn that adds `--trait compact`, and interactive `%compact` use. This gate exercises a read-only localisation scaffold, not the full `scout` persona.
Status: **ADMITTED on named consumers plus one discriminating run (one tier, one scenario; not a general proof).**

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
