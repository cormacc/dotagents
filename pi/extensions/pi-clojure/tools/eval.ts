// SPDX-License-Identifier: EPL-2.0
// Copyright © 2026-present Marko Kocic <marko@euptera.com>

import {
  DEFAULT_MAX_BYTES,
  DEFAULT_MAX_LINES,
  defineTool,
  truncateHead,
} from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import type { SessionEvalOptions, SessionEvalResult } from "../nrepl-client";

const MAX_TIMEOUT_MS = 2_147_483_647;

export type SessionEval = (opts: SessionEvalOptions) => Promise<SessionEvalResult>;

export function createEvalTool(runEval: SessionEval) {
  return defineTool({
    name: "clojure_eval",
    label: "Clojure Eval",
    description:
      "Evaluate Clojure code via nREPL in a persistent session. Each host and port keeps one session for this pi session, so the current namespace, *1, *e, set! bindings, and a shadow-cljs CLJS REPL selection persist between calls. Requires an existing nREPL server (see clojure_find_nrepl_port to find one). Output is limited to pi's standard 2000 lines or 50KB.",
    promptSnippet: "Evaluate Clojure code in a persistent nREPL session",
    parameters: Type.Object({
      code: Type.String({ description: "Clojure code to evaluate" }),
      port: Type.Integer({ minimum: 1, maximum: 65_535, description: "nREPL TCP port" }),
      host: Type.Optional(
        Type.String({ description: "nREPL host", default: "localhost" }),
      ),
      ns: Type.Optional(
        Type.String({
          description: "Namespace for this evaluation only. The session's current namespace does not change; use in-ns to change it.",
        }),
      ),
      reset: Type.Optional(
        Type.Boolean({ description: "Close the persistent session for this host and port, and evaluate in a new one" }),
      ),
      timeout: Type.Optional(
        Type.Integer({
          minimum: 1,
          maximum: MAX_TIMEOUT_MS,
          description: "Timeout in milliseconds, including connection setup (default: 30000). A timed-out evaluation is interrupted.",
        }),
      ),
    }),

    async execute(_toolCallId, params, signal, _onUpdate, _ctx) {
      try {
        const result = await runEval({
          host: params.host ?? "localhost",
          port: params.port,
          code: params.code,
          ns: params.ns,
          reset: params.reset,
          timeout: params.timeout,
          signal,
        });

        const lines: string[] = [];
        if (result.sessionStatus === "replaced") {
          lines.push("[The previous nREPL session no longer exists, for example after a server restart. This evaluation used a new session, so earlier session state is lost.]");
        }
        if (result.vals.length > 0) lines.push(`=> ${result.vals.join("\n=> ")}`);
        if (result.out) lines.push(`stdout: ${result.out}`);
        if (result.err) lines.push(`stderr: ${result.err}`);
        if (lines.length === 0) lines.push("No output (nil or empty)");

        const truncation = truncateHead(lines.join("\n"), {
          maxLines: DEFAULT_MAX_LINES,
          maxBytes: DEFAULT_MAX_BYTES,
        });

        return {
          content: [{ type: "text", text: truncation.content }],
          details: { truncation, session: result.session, sessionStatus: result.sessionStatus },
        };
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        throw new Error(`Clojure eval failed: ${message}`);
      }
    },
  });
}
