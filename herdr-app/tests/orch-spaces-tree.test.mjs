import test from "node:test";
import assert from "node:assert/strict";
import { agentTree, agentName, agentStatus } from "../mods/orch-spaces/tree.js";

const term = (address, extra = {}) => ({ address, state: "live", parent: null, labels: {}, ...extra });
const agent = (address, space, extra = {}) => ({ address, space, pane: `p-${address}`, status: "idle", ...extra });
const shape = (rows) => rows.map(r => [r.terminal.address, r.lines.join(""), r.pane]);

test("root agents sit under their space and sub-agents under their parent", () => {
  const agents = [agent("t1", "s1"), agent("t2", "s1"), agent("t5", "s2")];
  const terminals = [term("t1"), term("t2"), term("t3", { parent: "t1" }), term("t4", { parent: "t3" }),
                     term("t5"), term("t6", { parent: "t5", state: "finished" })];
  const tree = agentTree(agents, terminals);
  assert.deepEqual(shape(tree.get("s1")), [
    ["t1", "├", "p-t1"], ["t3", "│└", null], ["t4", "│ └", null], ["t2", "└", "p-t2"]]);
  assert.deepEqual(shape(tree.get("s2")), [["t5", "└", "p-t5"]], "a finished sub-agent is not shown");
  assert.equal(tree.get("s3"), undefined);
});

test("an agent with a live parent is shown once, under that parent, with its pane", () => {
  // `oh task start --tab` gives a child a pane, so it is listed by agents() too.
  const agents = [agent("t1", "s1"), agent("t2", "s1")];
  const terminals = [term("t1"), term("t2", { parent: "t1" })];
  assert.deepEqual(shape(agentTree(agents, terminals).get("s1")), [["t1", "└", "p-t1"], ["t2", " └", "p-t2"]]);
  // Its parent ended (agents() no longer lists it): it becomes a root of its space.
  const orphan = [term("t1", { state: "finished" }), term("t2", { parent: "t1" })];
  assert.deepEqual(shape(agentTree([agents[1]], orphan).get("s1")), [["t2", "└", "p-t2"]]);
});

test("a listed agent that the core has not described yet still shows", () => {
  const rows = agentTree([agent("t9", "s1", { program: { name: "pi" } })], []).get("s1");
  assert.deepEqual(shape(rows), [["t9", "└", "p-t9"]]);
  assert.equal(agentName(rows[0].terminal), "pi");
});

test("the name is the oh label, else what the terminal is called", () => {
  assert.equal(agentName(term("t1", { labels: { "oh.label": "planner-1/reviewer-9-astra" }, name: "x" })), "planner-1/reviewer-9-astra");
  assert.equal(agentName(term("t1", { name: "reviewer-ab12" })), "reviewer-ab12");
  assert.equal(agentName(term("t1", { template: "claude" })), "claude");
  assert.equal(agentName(term("t1", { program: { name: "Pi" } })), "Pi");
  assert.equal(agentName(term("t1")), "t1");
});

test("status: the app's word for a listed agent; an unlisted one reports to its parent", () => {
  const listed = new Map([["t1", "done"]]);
  assert.equal(agentStatus(term("t1", { status: "idle" }), listed), "done");
  assert.equal(agentStatus(term("t2", { status: "done" }), listed), "idle");
  assert.equal(agentStatus(term("t2", { status: "error" }), listed), "blocked");
  assert.equal(agentStatus(term("t2", { status: "working" }), listed), "working");
});
