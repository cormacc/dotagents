/**
 * OpenAI Codex (ChatGPT OAuth) provider for pi
 *
 * Lets you use your ChatGPT Plus / Pro subscription for model access instead
 * of an OpenAI Platform API key. Leverages pi-ai's built-in Codex OAuth flow
 * and `openai-codex-responses` streaming implementation.
 *
 * Prior art:
 *   - https://github.com/numman-ali/opencode-openai-codex-auth
 *   - https://docs.openclaw.ai/plugins/codex-harness
 *
 * COMPLIANCE NOTICE:
 * This plugin uses OpenAI's official OAuth authorization flow (the same one
 * used by OpenAI's Codex CLI). Intended for personal development use with
 * your own ChatGPT Plus/Pro subscription. Do not use for resale, multi-user
 * services, or anything that violates OpenAI's Terms of Service.
 *
 * Usage:
 *   1. Restart pi (or /reload) to pick up the extension.
 *   2. /login codex   -- completes the ChatGPT OAuth flow in your browser.
 *   3. /model codex/gpt-5.1-codex
 */

import {
	loginOpenAICodex,
	refreshOpenAICodexToken,
	streamSimpleOpenAICodexResponses,
	type OAuthCredentials,
	type OAuthLoginCallbacks,
} from "@mariozechner/pi-ai";
import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";

// Subscription-backed access: no per-token cost.
const ZERO_COST = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } as const;

// ChatGPT backend exposed by Codex CLI flow.
const CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex";

export default function (pi: ExtensionAPI) {
	pi.registerProvider("codex", {
		baseUrl: CODEX_BASE_URL,
		api: "openai-codex-responses",

		// Models available via ChatGPT Plus/Pro Codex backend.
		// Reasoning effort (none/minimal/low/medium/high/xhigh) is selected via
		// pi's thinking-level UI -- no need for per-variant model entries.
		models: [
			{
				id: "gpt-5.2",
				name: "GPT-5.2 (Codex)",
				reasoning: true,
				input: ["text", "image"],
				cost: ZERO_COST,
				contextWindow: 400_000,
				maxTokens: 128_000,
			},
			{
				id: "gpt-5.2-codex",
				name: "GPT-5.2 Codex",
				reasoning: true,
				input: ["text", "image"],
				cost: ZERO_COST,
				contextWindow: 400_000,
				maxTokens: 128_000,
			},
			{
				id: "gpt-5.1",
				name: "GPT-5.1 (Codex)",
				reasoning: true,
				input: ["text", "image"],
				cost: ZERO_COST,
				contextWindow: 272_000,
				maxTokens: 100_000,
			},
			{
				id: "gpt-5.1-codex",
				name: "GPT-5.1 Codex",
				reasoning: true,
				input: ["text", "image"],
				cost: ZERO_COST,
				contextWindow: 272_000,
				maxTokens: 100_000,
			},
			{
				id: "gpt-5.1-codex-max",
				name: "GPT-5.1 Codex Max",
				reasoning: true,
				input: ["text", "image"],
				cost: ZERO_COST,
				contextWindow: 272_000,
				maxTokens: 100_000,
			},
			{
				id: "gpt-5.1-codex-mini",
				name: "GPT-5.1 Codex Mini",
				reasoning: true,
				input: ["text", "image"],
				cost: ZERO_COST,
				contextWindow: 272_000,
				maxTokens: 100_000,
			},
		],

		// pi-ai ships a Codex-aware streamer that knows how to:
		//  - rewrite /responses -> /codex/responses as needed
		//  - inject chatgpt-account-id (derived from the JWT access token)
		//  - set originator=codex_cli_rs and OpenAI-Beta=responses=experimental
		streamSimple: streamSimpleOpenAICodexResponses,

		oauth: {
			name: "OpenAI Codex (ChatGPT Plus/Pro)",

			async login(callbacks: OAuthLoginCallbacks): Promise<OAuthCredentials> {
				return loginOpenAICodex({
					onAuth: callbacks.onAuth,
					onPrompt: callbacks.onPrompt,
					originator: "pi",
				});
			},

			async refreshToken(credentials: OAuthCredentials): Promise<OAuthCredentials> {
				return refreshOpenAICodexToken(credentials.refresh);
			},

			getApiKey(credentials: OAuthCredentials): string {
				// The Codex stream implementation decodes the JWT access token
				// to extract chatgpt_account_id -- so the access token itself
				// is what downstream code expects as the "api key".
				return credentials.access;
			},
		},
	});
}
