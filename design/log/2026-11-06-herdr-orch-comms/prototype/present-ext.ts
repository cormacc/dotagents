/**
 * Presentation bench extension (disposable; loaded explicitly with -e).
 * Logs every lifecycle event with a ms timestamp to BENCH_EVENTS, forces a tiny system
 * prompt so turns are cheap, and, while the file named by BENCH_WATCH_ENABLE exists, watches
 * BENCH_WATCH_DIR for new herdr-orch result items and steers them into the session.
 */
import * as fs from "node:fs";
import * as path from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const EVENTS = process.env.BENCH_EVENTS ?? "/tmp/bench-events.jsonl";
const WATCH_DIR = process.env.BENCH_WATCH_DIR ?? "";
const WATCH_ENABLE = process.env.BENCH_WATCH_ENABLE ?? "";
const WATCH_MODE = (process.env.BENCH_WATCH_MODE ?? "steer") as "steer" | "followUp";

function log(kind: string, data: Record<string, unknown> = {}) {
	fs.appendFileSync(EVENTS, JSON.stringify({ t: Date.now(), kind, ...data }) + "\n");
}
function snippet(x: unknown): string {
	const s = typeof x === "string" ? x : JSON.stringify(x ?? "");
	return s.slice(0, 300);
}
function textOf(message: any): string {
	const c = message?.content;
	if (typeof c === "string") return c;
	if (Array.isArray(c)) return c.map((p: any) => (typeof p?.text === "string" ? p.text : "")).join(" ");
	return "";
}

export default function (pi: ExtensionAPI) {
	const seen = new Set<string>();

	pi.on("session_start", async () => {
		log("session_start", { pid: process.pid });
		if (WATCH_DIR) {
			fs.watch(WATCH_DIR, (_ev, name) => {
				if (!name || !/\.result(\.\d+)?$/.test(String(name))) return;
				const p = path.join(WATCH_DIR, String(name));
				if (seen.has(p) || !fs.existsSync(p)) return;
				if (!WATCH_ENABLE || !fs.existsSync(WATCH_ENABLE)) return;
				seen.add(p);
				let task = "";
				try {
					const body = fs.readFileSync(p, "utf-8");
					task = /^TASK: (\S+)/m.exec(body)?.[1] ?? "";
				} catch {}
				log("watch-fire", { path: p, task, mode: WATCH_MODE });
				pi.sendMessage(
					{ customType: "bench-mail", content: `[bench-mail] item ${p} TASK: ${task}`, display: true },
					{ triggerTurn: true, deliverAs: WATCH_MODE },
				);
			});
			log("watching", { dir: WATCH_DIR });
		}
	});

	pi.on("input", async (event) => {
		log("input", { source: event.source, streaming: event.streamingBehavior, text: snippet(event.text) });
	});
	pi.on("before_agent_start", async (event) => {
		log("before_agent_start", { messageType: event.messageType, prompt: snippet(event.prompt ?? textOf(event.message)) });
		return {
			systemPrompt:
				"You are a benchmark target. If the user message starts with 'sleep:', call the bash tool once with exactly the command that follows and then reply 'done'. For any other message, reply with the single word 'ack' and call no tools.",
		};
	});
	pi.on("turn_start", async () => log("turn_start"));
	pi.on("agent_start", async () => log("agent_start"));
	pi.on("agent_end", async () => log("agent_end"));
	pi.on("agent_settled", async () => log("agent_settled"));
	pi.on("tool_execution_start", async (event) => log("tool_start", { tool: event.toolName, args: snippet(event.args) }));
	pi.on("tool_execution_end", async (event) => log("tool_end", { tool: event.toolName }));
	pi.on("message_end", async (event) => {
		const m: any = event.message;
		log("message_end", { role: m?.role, customType: m?.customType, text: snippet(textOf(m)) });
	});
}
