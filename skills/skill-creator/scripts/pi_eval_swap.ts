// Loaded by run_eval.py with `pi -e` for one trigger-eval run.
//
// The eval measures triggering in the context of use, so the run keeps every
// discovered skill, extension, MCP server and tool. Only the description of
// the skill under test changes, and only in this process's system prompt: the
// skill keeps its place in the list and its real location, and nothing on disk
// changes, so parallel runs and other sessions are unaffected. A skill that is
// not installed yet is appended at its draft location.
//
// Every tool call is blocked. The model still sees the real tool declarations,
// but no eval run can change state.
//
// The "skill-eval-swap" entry tells run_eval.py which SKILL.md path counts as
// a trigger. A run without it fails, so a change to this pi API cannot fail
// silently.
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function (pi: ExtensionAPI) {
	const name = process.env.SKILL_EVAL_NAME;
	const description = process.env.SKILL_EVAL_DESCRIPTION;
	const draftPath = process.env.SKILL_EVAL_DRAFT_PATH;
	if (!name || !description || !draftPath) {
		throw new Error("pi_eval_swap needs SKILL_EVAL_NAME, SKILL_EVAL_DESCRIPTION and SKILL_EVAL_DRAFT_PATH");
	}

	pi.on("before_agent_start", (event) => {
		const skills = event.systemPromptOptions.skills ?? [];
		const installed = skills.find((skill) => skill.name === name);
		let filePath: string;
		if (installed) {
			installed.description = description;
			filePath = installed.filePath;
		} else {
			filePath = draftPath;
			skills.push({
				name,
				description,
				filePath,
				baseDir: filePath.replace(/\/SKILL\.md$/, ""),
				disableModelInvocation: false,
			} as (typeof skills)[number]);
		}
		event.systemPromptOptions.skills = skills;
		pi.appendEntry("skill-eval-swap", {
			filePath,
			installed: Boolean(installed),
			// disable-model-invocation keeps a skill out of the prompt, so it cannot trigger.
			hidden: installed?.disableModelInvocation ?? false,
		});
	});

	pi.on("tool_call", () => ({
		block: true,
		reason: "Tools are disabled during a skill trigger eval.",
		terminate: true,
	}));
}
