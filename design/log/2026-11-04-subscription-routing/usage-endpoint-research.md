# Research: subscription usage endpoints (Anthropic OAuth, ChatGPT/Codex)

Date: 2026-11-04. Read-only: no provider endpoint was called; no credential value was read or printed. Source clones are in `.tmp/src/` (codex `021fccb`, agent-router `fb24d06`, ccusage `f674ef6`, ccstatusline `66e9a08`). pi is 1.4.0 at `/nix/store/ny7g744b6cr4mnhnjbk3p8ly2078nm9k-pi-1.4.0/lib/pi/node_modules/@earendil-works/pi-ai/dist` (abbreviated PIAI below).

## Answer
- Both providers have a dedicated GET endpoint that returns percent used per window plus a reset time. Both accept the cached OAuth access token as a Bearer token. Neither needs a token refresh if the cached token is unexpired.
- Anthropic: `GET https://api.anthropic.com/api/oauth/usage`. It is undocumented, and it is aggressively rate limited (429) for third-party polling. Windows: `five_hour`, `seven_day`, per-model weekly. Unit: percent 0-100. Reset: RFC 3339 string.
- Codex: `GET https://chatgpt.com/backend-api/wham/usage`. Verified in Codex source. Windows: `primary_window`, `secondary_window`. Unit: integer percent 0-100. Reset: Unix epoch seconds.
- Normalisation: `used-fraction = percent / 100` for both. `resets-at` = epoch seconds for Codex, parsed from RFC 3339 for Anthropic.
- A 60 s cache is fine for Codex (no rate-limit reports found, unverified). For Anthropic, 60 s is too short: use 300 s, back off on 429, and serve the last good payload.

## Provider 1: Anthropic Claude subscription (OAuth)

### Endpoint, method, headers
- `GET https://api.anthropic.com/api/oauth/usage`. Verified in code: ccstatusline `src/utils/usage-fetch.ts` lines 914-915 and 937-950 (https://github.com/sirmalloc/ccstatusline, commit 66e9a08f). Also in cship, maki PR 456 and claudeops-tui docs (secondary).
- Required headers: `Authorization: Bearer <access token>` and `anthropic-beta: oauth-2025-04-20`. ccstatusline sends only these two (verified). claudeops-tui also sends a `User-Agent` (secondary, not shown to be required).
- Not usable with API keys. claudeops-tui doc: with no `claudeAiOauth` block the endpoint returns 401 (secondary).
- Scope: the token needs `user:profile`. Reports: claude-code issues 22450 and 11985 show `permission_error` "OAuth token does not meet scope requirement user:profile" for tokens that only have `user:inference` (`claude setup-token`). Verified in pi: `PIAI/auth/oauth/anthropic.js:23` requests scopes including `user:profile`, so a token from pi login should qualify (unverified against the live endpoint).

### Token source
- Claude Code `~/.claude/.credentials.json` -> `claudeAiOauth.accessToken` (ccstatusline `parseUsageCredentials`, lines 237-247, verified). It also has `expiresAt`, `scopes`, `subscriptionType` (claudeops-tui doc; secondary). On macOS Claude Code uses the Keychain; not relevant here.
- pi `~/.pi/agent/auth.json` -> `anthropic.access` (+ `expires`). pi's OAuth login produces tokens for the same Claude Code client flow (`PIAI/auth/oauth/anthropic.js`; scopes at line 23). pi sends `anthropic-beta: claude-code-20250219,oauth-2025-04-20` for OAuth tokens (`PIAI/api/anthropic-messages.js:872`), so the beta value is known to be accepted on inference; for the usage endpoint only `oauth-2025-04-20` is evidenced.
- Which store to prefer: either works in principle. Rate limits are reported per access token (issue 31637 comment, unverified), so reading a different token from a different store gives a separate limit bucket. Refreshing a token outside its owner breaks the owner's refresh-token rotation (issue 31637 comment; claudeops-tui doc) - do not refresh.

### Response (redacted example; shape from ccstatusline tests lines 380-398 and 2889-2929, and claudeops-tui doc)
```json
{
  "five_hour":        {"utilization": 42.0, "resets_at": "2030-01-01T00:00:00.000Z"},
  "seven_day":        {"utilization": 17.0, "resets_at": "2030-01-07T00:00:00.000Z"},
  "seven_day_opus":   null,
  "seven_day_sonnet": {"utilization": 1.0,  "resets_at": "2026-03-16T20:59:59.000000+00:00"},
  "extra_usage": {"is_enabled": true, "monthly_limit": 100.0, "used_credits": 12.5, "utilization": 12.5},
  "limits": [
    {"kind": "session",       "percent": 48, "resets_at": "2026-07-17T13:50:00Z", "scope": null},
    {"kind": "weekly_all",    "percent": 18, "resets_at": "2026-07-23T04:00:00Z", "scope": null},
    {"kind": "weekly_scoped", "percent": 3,  "resets_at": "2026-07-23T04:00:00Z", "scope": {"model": {"display_name": "Sonnet"}}}
  ]
}
```
- Unit: percent used, 0-100 (a float). Verified: ccstatusline test fixtures use `utilization: 42`; claude-meter article says `/usage` divides the number by 100.
- Reset: ISO 8601 / RFC 3339 string. Fractional seconds and timezone offset vary (`Z` or `+00:00`, 0-6 fractional digits, seen in the examples above), so the parser must accept both.
- Any bucket may be `null` (plans without that quota; Enterprise has none, ccstatusline comment lines 82-84). A bucket with 0% and `resets_at: null` is a placeholder for an unused window, not a real window (ccstatusline lines 192-206).
- Newer accounts: the same windows may appear only in a `limits[]` array with `kind` in `session`, `weekly_all`, `weekly_scoped`, and the field name `percent` (ccstatusline issue note #503; `parseUsageApiResponse` falls back from flat buckets to `limits[]`, verified in code). The adapter should read both shapes.
- Remaining fraction = 1 - max(percent)/100 over the real windows (my design suggestion, not from a source).

### Expiry detection (without refreshing)
- Claude Code file: `claudeAiOauth.expiresAt` is epoch milliseconds in current versions; older tools wrote seconds, so detect the unit (>= 1e11 means ms). Source: claudeops-tui doc (secondary).
- pi: `anthropic.expires` is epoch milliseconds, set to `Date.now() + expires_in*1000 - 5 min` (`PIAI/auth/oauth/anthropic.js:111` and `:229`, verified). So pi's value is 5 minutes earlier than the true expiry; treating it as expired when `now >= expires` is safe and slightly conservative.
- Unverified: whether the Claude Code access token is also a JWT (`sk-ant-oat01-...` is opaque as far as seen). Use the `expiresAt` field.

### Error responses
- 429: body `{"error":{"message":"Rate limited. Please try again later.","type":"rate_limit_error"}}`. Reports in anthropics/claude-code issues 30930, 31021, 31637 (all closed as stale/not planned; reports, not Anthropic statements). `retry-after` is absent or `0`. ccstatusline treats 429 as rate-limited with a default 300 s backoff (code lines 43, 980-985, verified).
- 401: expired or rotated token (Nimbalyst issue 617 says a transient 401 can occur during token-rotation races). claudeops-tui: no OAuth block -> 401.
- 403 / `permission_error`: missing `user:profile` scope (claude-code issues 22450, 11985).
- Sources disagree on nothing material here, but all 429 evidence is anecdotal.

### Rate limiting and cache
- Issue 31637: poll every 30-60 s led to persistent 429; 10-30 min polling still got 429 for some users. One commenter claims the limit is per access token with about 5 requests before 429 (unverified).
- Reference implementations: ccstatusline cache 180 s (`CACHE_MAX_AGE`, line 41); claudeops-tui 300 s default with negative caching on 429/5xx.
- Verdict: a 60 s cache is too aggressive for this endpoint. Recommend at least 300 s, negative-cache 429 for `max(Retry-After, 300)` s, and return the last good payload with a staleness mark. (Opinion, based on the evidence above.)

### Response headers on normal completions
- Yes, but only on inference responses for OAuth/subscription requests. Verified only through secondary sources: claude-meter article (2026-04-01) lists `anthropic-ratelimit-unified-5h-utilization`, `-5h-reset`, `-5h-status`, `-7d-utilization`, `-7d-reset`, `-7d-status`, `-7d_sonnet` variants, `-representative-claim`, `-fallback-percentage`.
- Unit differs from the endpoint: header utilization is a 0.0-1.0 fraction (examples `0.07`, `0.53`); reset is Unix epoch seconds (example `1774933200`). The endpoint uses 0-100 and RFC 3339. The two disagree in unit, so do not share a parser.
- Claude Code also exposes `rate_limits.five_hour.used_percentage` (0-100) and `resets_at` (epoch seconds) to a statusline command via stdin (official docs https://code.claude.com/docs/en/statusline). The per-model weekly windows are not included (issue 79022). These appear only inside a Claude Code session, so `oh` cannot use them.
- Cost: a header read needs an inference call, which spends quota; not recommended for polling.

### Uncertainties (Anthropic)
- The endpoint is undocumented; the shape has already changed once (flat buckets -> `limits[]`).
- `Retry-After` behaviour and the real rate-limit policy are unknown.
- Whether pi-issued tokens get the same bucket or a separate one is unverified.
- No live call was made, so the example JSON is from tests and docs, not a captured response.

## Provider 2: OpenAI ChatGPT / Codex subscription (OAuth)

### Endpoint, method, headers
- `GET https://chatgpt.com/backend-api/wham/usage`. Verified: `codex-rs/backend-client/src/client/rate_limit_resets.rs:131` (`PathStyle::ChatGptApi => format!("{}/wham/usage", ...)`), with a base URL normalised to `https://chatgpt.com/backend-api` (`client.rs:246-249`). Test asserts the full URL (`rate_limit_resets_tests.rs:61`). Repo https://github.com/openai/codex commit 021fccb0.
- Alternative path style `{base}/api/codex/usage` is used when the base URL has no `/backend-api` (`client.rs:163-170`). Use the `wham` one for chatgpt.com.
- Headers (verified, `client.rs:342-365`): `User-Agent` (default `codex-cli`), `Authorization: Bearer <access_token>` (set by the auth provider; the Bearer format is verified at `login/src/auth/manager/workspace_switching.rs:292`; the general `apply_auth_headers` path is trait-based and I did not trace every branch), and `chatgpt-account-id: <account id>` (`http-client/src/openai_headers.rs:3`). `X-OpenAI-Fedramp: true` only for FedRAMP accounts. pi also sends `originator: pi` on inference (`PIAI/api/openai-codex-responses.js:181`); not shown to be needed for usage.
- Optional header `x-openai-codex-luna-reserve: 1` is for clients that can apply "Reserve" credits; not needed for passive reads (`rate_limit_resets.rs:89-91`).

### Token source
- Codex CLI `~/.codex/auth.json`: `tokens.access_token` (struct `AuthDotJson` with `auth_mode`, `tokens`, `last_refresh`: `login/src/auth/storage.rs:53-64`, verified). Account id is a claim inside the token (`chatgpt_account_id`) and Codex also stores `account_id` under `tokens` (`storage.rs:109`).
- pi `~/.pi/agent/auth.json`: `openai-codex.access`, `.expires`, `.accountId`. pi derives `accountId` from the access token's `chatgpt_account_id` claim (`PIAI/auth/oauth/openai-codex.js:244-257`, verified) and sends it as `chatgpt-account-id` (`openai-codex-responses.js:1303`). pi default base URL is `https://chatgpt.com/backend-api` (line 20).
- Either store works. The `chatgpt-account-id` header is required in pi's own requests; for usage it is sent by Codex, but whether the server rejects its absence is unverified.

### Response (shape verified from `codex-backend-openapi-models` and `rate_limit_resets_tests.rs:20-40`; values are test fixtures)
```json
{
  "plan_type": "plus",
  "rate_limit": {
    "allowed": true,
    "limit_reached": false,
    "primary_window":   {"used_percent": 42, "limit_window_seconds": 300,   "reset_after_seconds": 60,   "reset_at": 2000000000},
    "secondary_window": {"used_percent": 84, "limit_window_seconds": 3600,  "reset_after_seconds": 0,    "reset_at": 456}
  },
  "credits": {"has_credits": true, "unlimited": false, "balance": "0", "approx_local_messages": [], "approx_cloud_messages": []},
  "additional_rate_limits": [{"...": "per-model limit, same window shape, plus limit name and normal_model_slug"}],
  "spend_control": {},
  "rate_limit_reached_type": null,
  "rate_limit_reset_credits": {},
  "account_id": "<redacted>",
  "user_id": "<redacted>"
}
```
- Windows: `primary_window` and `secondary_window` (each may be null: double-option in `rate_limit_status_details.rs`). Real windows are about 5 hours (primary) and 7 days (secondary) in practice, but the response carries `limit_window_seconds`; do not hard-code durations. The client converts to minutes with `(seconds+59)/60` (`client.rs:909-916`).
- Unit: `used_percent` is an integer 0-100 (`rate_limit_window_snapshot.rs:15-16`, `i32`). Percent used, not remaining.
- Reset: `reset_at` is an integer; Codex treats it as Unix epoch seconds (`resets_at: Some(i64::from(snapshot.reset_at))`, `client.rs:843`). `reset_after_seconds` is a relative alternative. Note the generated type is `i32`, which overflows in 2038; not a concern now.
- `additional_rate_limits` carries per-model limits (for example a model-specific limit) with the same window shape; Codex TUI treats ordinary and additional limits separately (`tui/src/chatwidget/rate_limits.rs:377`). Decide in the adapter whether to include them.
- `credits.balance` is a string.

### Expiry detection (without refreshing)
- `tokens.access_token` is a JWT; Codex reads its `exp` claim (seconds) with `parse_jwt_expiration` (`login/src/token_data.rs:157-162`, verified) and treats the token as due for refresh 5 minutes before `exp` (`manager.rs:232`, `:3555-3562`). Fall back: if no `exp`, Codex refreshes when `last_refresh` is older than 8 days (`TOKEN_REFRESH_INTERVAL = 8`, `manager.rs:231`).
- A Babashka reader can base64url-decode the middle JWT segment and read `exp`; no signature check is needed to detect expiry.
- pi: `openai-codex.expires` is epoch milliseconds, `Date.now() + expires_in*1000` with no safety margin (`PIAI/auth/oauth/openai-codex.js:105`, verified).

### Error responses
- Not verified. The client maps any non-success to `RequestError::UnexpectedStatus {status, ...}` (`client.rs:63-110`, `:412`). On HTTP 401 Codex refreshes the token and retries on the inference path (`codex-api/src/auth.rs:63-68`). I found no source text for 403 or 429 bodies on `/wham/usage`.

### Rate limiting and cache
- No documentation or issue found on a rate limit for `/wham/usage`. Codex calls it from `/status` and the TUI, and the repo's own `statusline-quota-cache.json` convention (agent-router `docs/provider-support.md`) uses a 15-minute max age (documented there; the router does not write it).
- Verdict: a 60 s cache is reasonable (opinion; no contrary evidence found). Negative-cache any non-200 for a few minutes anyway.

### Response headers on normal completions
- Yes, verified. `codex-api/src/rate_limits.rs:57-93` parses, per limit family with prefix `x-<limit>` (default `codex`; underscores become hyphens): `x-codex-primary-used-percent`, `x-codex-primary-window-minutes`, `x-codex-primary-reset-at`, and the same three for `secondary`. Also `x-codex-credits-has-credits`, `x-codex-credits-unlimited`, `x-codex-credits-balance`, `x-codex-promo-message`, `x-codex-rate-limit-reached-type`, and `x-<limit>-limit-name`.
- Units: used-percent is a float 0-100 (`parse_header_f64`), window in minutes (i64), reset-at is epoch seconds (i64). Same semantics as the endpoint.
- Streaming events also carry them: an event of type with `rate_limits.primary/secondary` having `used_percent`, `window_minutes`, `reset_at` (`rate_limits.rs:99-130`).
- Tests use fixtures `x-codex-primary-used-percent: 12.5` (`core/tests/suite/client.rs:3520`).
- Headers are only on inference responses (spend quota); the endpoint is the right tool for polling.

### Uncertainties (Codex)
- Error bodies for 401/403/429 on `/wham/usage` unknown.
- Whether `chatgpt-account-id` is mandatory for usage.
- The endpoint is internal to ChatGPT, not a public API; shape may change. The JSON field list above comes from generated client types, not a captured response.
- `/status` implementation not read in detail; the TUI usage path goes through the backend client above.

## Other references checked
- `nidhi-singh02/agent-router` (commit fb24d06a) exists, but it makes no call to either endpoint (grep for `oauth/usage`, `wham` found nothing in code). It documents fixture-only Claude parsing and a local Codex cache file. Not a reference implementation for this task.
- `ryoppippi/ccusage` (commit f674ef69): grep for `oauth/usage` in the clone returned no match, but that grep had no positive control in the same invocation, so absence is unconfirmed. The claude-meter article says ccusage reads local JSONL logs only.
- `Maciek-roboblog/Claude-Code-Usage-Monitor` was not examined.

## Suggested normalised record (design input, not a source claim)
- Anthropic: `{:name "five_hour"|"seven_day"|"seven_day_<model>" :used-fraction (/ utilization 100.0) :resets-at <instant parsed from RFC 3339>}`; prefer `limits[]` when flat buckets are null; skip placeholders (0% and null `resets_at`).
- Codex: `{:name "primary"|"secondary" :used-fraction (/ used_percent 100.0) :resets-at (java.time.Instant/ofEpochSecond reset_at)}`; keep `limit_window_seconds` as a label.
- `:remaining-fraction` = 1 - max used-fraction.

## Sources
- https://github.com/openai/codex @ 021fccb0 (`codex-rs/backend-client`, `codex-api/src/rate_limits.rs`, `login/src/token_data.rs`, `login/src/auth/manager.rs`, `codex-backend-openapi-models`)
- https://github.com/sirmalloc/ccstatusline @ 66e9a08f `src/utils/usage-fetch.ts`
- https://code.claude.com/docs/en/statusline (search excerpt only; page not fetched in full)
- https://github.com/anthropics/claude-code/issues/31637, /30930, /31021, /22450, /11985, /42108, /79022
- https://raw.githubusercontent.com/FullFran/claudeops-tui/main/docs/oauth-usage-endpoint.md (community, secondary)
- https://www.claudecodecamp.com/p/i-tried-to-reverse-engineer-claude-code-s-usage-limits (2026-04-01, community; header units)
- https://tzk.ar/blog/claude-statusline/ (2026-03-27, community)
- pi-ai 1.4.0 files under PIAI (paths above)
- https://github.com/nidhi-singh02/agent-router @ fb24d06a `docs/provider-support.md`
