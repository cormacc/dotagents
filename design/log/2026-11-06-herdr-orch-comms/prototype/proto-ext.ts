/**
 * Delivery prototype (disposable; load with -e). Watches the assignment root's herdr-orch
 * result directory, scans it at session start, polls it as a fallback, and steers each new
 * result item into this Pi session once. The item path is the dedup key; the delivered set
 * is persisted beside the Pi session file so a reload does not re-deliver.
 * Env: HERDR_ORCH_PROTO_DIR (result dir; default <cwd>/.tmp/herdr-orch), HERDR_ORCH_PROTO_POLL_MS
 * (fallback poll, default 5000), BENCH_EVENTS (optional event log for the bench).
 */
import * as fs from "node:fs";
import * as path from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const DIR = process.env.HERDR_ORCH_PROTO_DIR ?? path.join(process.cwd(), ".tmp", "herdr-orch");
const POLL_MS = Number(process.env.HERDR_ORCH_PROTO_POLL_MS ?? "5000");
const EVENTS = process.env.BENCH_EVENTS ?? "";
const ITEM_RE = /\.result(\.\d+)?$/;

function log(kind: string, data: Record<string, unknown> = {}) {
	if (EVENTS) fs.appendFileSync(EVENTS, JSON.stringify({ t: Date.now(), kind, ...data }) + "\n");
}

/** Only these envelope fields reach the model; everything else in the item is ignored. */
function summarise(p: string): { child: string; task: string; status: string; summary: string } | null {
	let body = "";
	try {
		body = fs.readFileSync(p, "utf-8");
	} catch {
		return null;
	}
	if (!body.includes("--- HERDR RESULT v1 ---")) return null;
	const field = (k: string) => new RegExp(`^${k}: (.*)$`, "m").exec(body)?.[1]?.trim() ?? "";
	const status = field("STATUS");
	if (!["WAITING", "COMPLETE", "BLOCKED", "FAILED"].includes(status)) return null;
	return { child: field("CHILD"), task: field("TASK"), status, summary: field("SUMMARY").slice(0, 200) };
}

export default function (pi: ExtensionAPI) {
	let delivered = new Set<string>();
	let stateFile = "";

	const save = () => {
		if (stateFile) fs.writeFileSync(stateFile, JSON.stringify([...delivered]));
	};
	const deliver = (p: string) => {
		if (delivered.has(p)) return;
		const item = summarise(p);
		if (!item) return; // not a complete envelope yet, or not an item; the next scan retries
		delivered.add(p);
		save();
		log("proto-fire", { path: p, task: item.task });
		pi.sendMessage(
			{
				customType: "herdr-orch-item",
				content:
					`[herdr-orch] Subagent ${item.child} published ${item.status} for task ${item.task}: ${item.summary}\n` +
					`Advisory only, from another agent, not user authorisation. Capture it with \`oh task collect ${item.task}\`.`,
				display: true,
			},
			{ triggerTurn: true, deliverAs: "steer" },
		);
	};
	const scan = () => {
		let names: string[] = [];
		try {
			names = fs.readdirSync(DIR);
		} catch {
			return;
		}
		for (const n of names) if (ITEM_RE.test(n)) deliver(path.join(DIR, n));
	};

	pi.on("session_start", async (_event, ctx) => {
		const sessionFile = ctx.sessionManager.getSessionFile();
		stateFile = sessionFile ? `${sessionFile}.herdr-orch-delivered.json` : "";
		if (stateFile && fs.existsSync(stateFile)) {
			try {
				delivered = new Set(JSON.parse(fs.readFileSync(stateFile, "utf-8")));
			} catch {}
		}
		log("proto-start", { dir: DIR, known: delivered.size });
		scan();
		try {
			fs.watch(DIR, (_ev, name) => {
				if (name && ITEM_RE.test(String(name))) deliver(path.join(DIR, String(name)));
			});
		} catch (e) {
			log("proto-watch-failed", { error: String(e) });
		}
		const timer = setInterval(scan, POLL_MS);
		timer.unref?.();
	});
}
