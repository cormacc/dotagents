import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export { buildChangeRecordPrompt, buildSummaryRefreshPrompt, handlePlanEdit } from "./prompts.ts";

/**
 * Load prompt helpers without registering a visual task surface.
 * Agent task operations use ot through Pi's existing shell access.
 */
export default function (_pi: ExtensionAPI): void {}
