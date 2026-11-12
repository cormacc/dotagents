// Pi extension that `run-trait-gate.bb` loads with `-e` for each arm. It records, for every
// actual provider request, whether each expected text is present in the decoded payload.
// It stores booleans only, never payload text, and never changes the request.
import { appendFileSync, readFileSync } from "node:fs";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

/** True when `expected` occurs inside any decoded string of `value`. */
export function containsDecodedText(value: unknown, expected: string): boolean {
  if (typeof value === "string") return value.includes(expected);
  if (Array.isArray(value)) return value.some((item) => containsDecodedText(item, expected));
  if (value && typeof value === "object") return Object.values(value).some((item) => containsDecodedText(item, expected));
  return false;
}

/**
 * Matcher controls, run before any request. A serialised-JSON search escapes newlines, so it
 * misses multiline text that is present: that known-bad matcher must report false.
 */
export function matcherControls(sample: string) {
  const multiline = `${sample}\nsecond line`;
  const payload = { system: [{ type: "text", text: `prefix ${multiline} suffix` }] };
  return {
    positive: containsDecodedText(payload, multiline),
    missing: containsDecodedText({ system: [{ type: "text", text: "unrelated" }] }, multiline),
    knownBad: JSON.stringify(payload).includes(multiline),
  };
}

export default function (pi: ExtensionAPI) {
  const expectPath = process.env.TRAIT_GATE_AUDIT_EXPECT;
  const outPath = process.env.TRAIT_GATE_AUDIT_OUT;
  if (!expectPath || !outPath) return;
  const expected: Record<string, string> = JSON.parse(readFileSync(expectPath, "utf8"));
  appendFileSync(outPath, JSON.stringify({ controls: matcherControls("trait gate audit control") }) + "\n");
  let request = 0;
  pi.on("before_provider_request", (event) => {
    request += 1;
    const present = Object.fromEntries(
      Object.entries(expected).map(([name, text]) => [name, containsDecodedText(event.payload, text)]),
    );
    appendFileSync(outPath, JSON.stringify({ request, present }) + "\n");
    return undefined;
  });
}
