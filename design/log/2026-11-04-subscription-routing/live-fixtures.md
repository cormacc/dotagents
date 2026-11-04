# Approved usage fixtures

Captured on 2026-11-04 through the actual `oh usage --provider NAME --fresh --format json` CLI execution path, with a private cache and an HTTP wrapper that permitted at most one request per provider. The user explicitly approved these two read-only requests. Both returned HTTP 200. No retry was made.

- [anthropic-live-redacted.json](anthropic-live-redacted.json): account-wide remaining allowance 0.37; five-hour usage 63%, seven-day usage 40%.
- [codex-live-redacted.json](codex-live-redacted.json): account-wide remaining allowance 0.98; primary-window usage 2%, secondary window null.

These JSON files retain only allowlisted usage fields. Request headers, tokens, account/user IDs, credits and arbitrary error text are excluded. All three credential-store paths had the same presence and mtime before and after capture. This is metadata evidence, not a content hash comparison. Offline replay of each retained body matched the CLI's account-wide windows and remaining fraction.

The live responses did not exercise Codex 401, 403 or 429 bodies. Synthetic tests retain those status paths and the response shapes not present in this capture. No further live probe is authorised by this capture approval.
