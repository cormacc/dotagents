import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const doc = readFileSync(new URL("../../../skills/org-jira/references/atlassian-exploration.md", import.meta.url), "utf8");
const blocks = new Map([...doc.matchAll(/<!-- recipe: (\w+) -->\n```js\n([\s\S]*?)\n```/g)].map(m => [m[1], m[2]]));
const fixture = JSON.parse(readFileSync(new URL("./exploration.fixtures.json", import.meta.url), "utf8"));
const prefix = "mcp__atlassian__";
const names = ["getJiraIssue", "getConfluencePage", "search", "getTeamworkGraphContext", "getTeamworkGraphObject"];
const cloudId = fixture.cloudId;
const issue = { objectType: "JiraWorkItem", objectIdentifier: "SAND-1" };
const page = { objectType: "ConfluencePage", objectIdentifier: "20001" };
const ari = fixture.hydration.data.data.objects[0].ari;
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const clone = <T>(value: T): T => structuredClone(value);
const mcp = (value: unknown, structured = false) => structured
  ? { isError: false, structuredContent: value, content: [] }
  : { isError: false, statusCode: 200, content: [{ type: "text", text: JSON.stringify(value) }] };

type Handler = (args: any) => any;
async function runCode(code: string, input: any, handlers: Record<string, Handler>, catalog = names) {
  const calls: Array<{ name: string; args: any }> = [];
  let active = 0, peak = 0;
  const tools = new Proxy({}, {
    get(_target, fullName: string) {
      const name = fullName.slice(prefix.length);
      return async (args: any) => {
        if (!names.includes(name)) throw new Error("write sentinel: " + name);
        calls.push({ name, args });
        active++;
        peak = Math.max(peak, active);
        try {
          await Promise.resolve();
          if (!handlers[name]) throw new Error("Unexpected fake call: " + name);
          return await handlers[name](args);
        } finally { active--; }
      };
    },
  });
  const result = await new AsyncFunction("tools", "ALL_TOOLS", "input", blocks.get("shared") + "\n" + code)(
    tools, catalog.map(name => ({ name: prefix + name })), input,
  );
  return { result, calls, peak };
}
function run(name: string, input: any, handlers: Record<string, Handler>, catalog = names) {
  assert.ok(blocks.has(name), "documented recipe exists: " + name);
  return runCode(blocks.get(name)!, { cloudId, ...input }, handlers, catalog);
}
const good = {
  getJiraIssue: () => mcp(fixture.issue),
  getConfluencePage: () => mcp(fixture.page, true),
  getTeamworkGraphContext: (args: any) => {
    const graph = clone(fixture.graph);
    if (args.objectType === "JiraWorkItem") {
      graph.data.data.object = { ari: `ari:cloud:jira:${cloudId}:issue/10001`, type: "JiraWorkItem", key: "SAND-1" };
      graph.data.data.relationships = [];
    }
    return mcp(graph);
  },
  getTeamworkGraphObject: () => mcp(fixture.hydration),
  search: () => mcp(fixture.search),
};

test("the shared prelude and four documented recipes are exercised", () => {
  assert.deepEqual([...blocks.keys()], ["shared", "sources", "context", "search", "hydrate"]);
});

test("source reads project full descriptions and page bodies, not search previews", async () => {
  const long = { ...fixture.issue, fields: { ...fixture.issue.fields, description: "x".repeat(500) } };
  const r = await run("sources", { sources: [issue, page] }, { ...good, getJiraIssue: () => mcp(long) });
  assert.deepEqual(r.result.map((x: any) => x.ok), [true, true]);
  assert.equal(r.result[0].value.body.text.length, 500);
  assert.equal(r.result[1].value.body.text, fixture.page.body);
  assert.equal(r.peak, 2);
  assert.equal(r.calls[0].args.updateHistory, false);
  assert.equal(r.calls[0].args.responseContentFormat, "markdown");
  assert.equal(r.calls[1].args.contentFormat, "markdown");
  assert.ok(!r.calls[0].args.fields.includes("*all"));
});

test("each recipe uses the observed JSON-in-content envelope or structured content", async () => {
  for (const structured of [false, true]) {
    const r = await run("context", { seeds: [page] }, { getTeamworkGraphContext: () => mcp(fixture.graph, structured) });
    assert.equal(r.result[0].value.candidates[0].ari, ari);
    assert.deepEqual(r.result[0].value.warnings, fixture.graph.data.warnings);
  }
});

test("ADF and null descriptions are not misreported as missing source reads", async () => {
  for (const description of [null, { type: "doc", version: 1, content: [] }]) {
    const r = await run("sources", { sources: [issue] }, {
      getJiraIssue: () => mcp({ ...fixture.issue, fields: { ...fixture.issue.fields, description } }),
    });
    assert.equal(r.result[0].ok, true);
    assert.equal(r.result[0].value.body.format, description === null ? "markdown" : "adf");
  }
});

test("body slices carry explicit truncation, continuation and source version", async () => {
  const body = "x".repeat(12001);
  const handlers = { getConfluencePage: () => mcp({ ...fixture.page, body }) };
  const first = await run("sources", { sources: [page] }, handlers);
  assert.equal(first.result[0].value.body.text.length, 12000);
  assert.equal(first.result[0].value.body.nextOffset, 12000);
  assert.equal(first.result[0].value.body.truncated, true);
  assert.equal(first.result[0].value.version, 1);
  const last = await run("sources", { sources: [page], bodyOffset: 12000 }, handlers);
  assert.equal(last.result[0].value.body.text, "x");
  assert.equal(last.result[0].value.body.nextOffset, null);
  for (const size of [11999, 12000]) {
    const r = await run("sources", { sources: [page] }, { getConfluencePage: () => mcp({ ...fixture.page, body: "x".repeat(size) }) });
    assert.equal(r.result[0].value.body.truncated, false);
  }
});

test("fulfilled isError envelopes retain sibling success and preserve the failure category", async () => {
  for (const [statusCode, message, kind] of [
    [401, "Sign in required", "authentication"], [403, "Access denied", "permission"],
    [403, "Insufficient Rovo credits", "credits"], [429, "Too many requests", "rate-limit"],
    [500, "Unspecified failure", "unknown"],
  ]) {
    const r = await run("sources", { sources: [issue, page] }, {
      ...good, getJiraIssue: () => ({ isError: true, statusCode, content: [{ type: "text", text: message }] }),
    });
    assert.equal(r.result[0].ok, false);
    assert.equal(r.result[0].error.kind, kind);
    assert.equal(r.result[1].ok, true);
  }
});

test("status failures without isError and rejected calls are not empty success", async () => {
  for (const handler of [
    () => ({ statusCode: 403, content: [{ type: "text", text: "Denied" }] }),
    () => { throw new Error("Network unavailable"); },
    () => ({ content: [{ type: "text", text: "not JSON" }] }),
    () => mcp({}),
  ]) {
    const r = await run("sources", { sources: [issue, page] }, { ...good, getJiraIssue: handler });
    assert.equal(r.result[0].ok, false);
    assert.equal(r.result[1].ok, true);
  }
});

test("missing graph capability leaves targeted source reads usable", async () => {
  const catalog = ["getJiraIssue", "getConfluencePage"];
  const graph = await run("context", { seeds: [issue] }, good, catalog);
  assert.equal(graph.result[0].error.kind, "missing-tool");
  assert.equal(graph.calls.length, 0);
  const source = await run("sources", { sources: [issue] }, good, catalog);
  assert.equal(source.result[0].ok, true);
});

test("context requests are one hop, bounded, and retain cursors and warnings", async () => {
  const graph = clone(fixture.graph);
  graph.data.data.pagination = { hasNextPage: true, endCursor: "more" };
  const r = await run("context", { seeds: [page, page] }, { getTeamworkGraphContext: () => mcp(graph) });
  assert.equal(r.calls.length, 2);
  assert.equal(r.peak, 2);
  for (const c of r.calls) {
    assert.equal(c.args.first, 20);
    assert.equal(c.args.detailLevel, "full");
    assert.equal(c.args.after, undefined);
    assert.deepEqual(c.args.targetObjectTypes, ["JiraWorkItem", "ConfluencePage"]);
  }
  assert.equal(r.result[0].value.pagination.endCursor, "more");
  assert.deepEqual(r.result[0].value.warnings, graph.data.warnings);
});

test("empty graph relationships keep warnings and pagination rather than implying exhaustive absence", async () => {
  const graph = clone(fixture.graph);
  graph.data.data.relationships = [];
  const r = await run("context", { seeds: [page] }, { getTeamworkGraphContext: () => mcp(graph) });
  assert.deepEqual(r.result[0].value.candidates, []);
  assert.equal(r.result[0].value.warnings.length, 1);
  assert.equal(r.calls.length, 1);
});

test("wrong source identities and missing page bodies fail rather than attaching data to the wrong source", async () => {
  const wrongIssue = { ...fixture.issue, key: "SAND-2", id: "10002" };
  const wrongPage = { ...fixture.page, id: "20002" };
  const r = await run("sources", { sources: [issue, page] }, {
    getJiraIssue: () => mcp(wrongIssue), getConfluencePage: () => mcp(wrongPage),
  });
  assert.deepEqual(r.result.map((x: any) => x.ok), [false, false]);
  const missing = await run("sources", { sources: [page] }, { getConfluencePage: () => mcp({ id: "20001" }) });
  assert.equal(missing.result[0].ok, false);
  const mismatched = await run("context", { seeds: [issue] }, { getTeamworkGraphContext: () => mcp(fixture.graph) });
  assert.equal(mismatched.result[0].ok, false);
});

test("invalid body offsets are per-source failures", async () => {
  for (const bodyOffset of [-1, 0.5, 999999]) {
    const r = await run("sources", { sources: [page], bodyOffset }, good);
    assert.equal(r.result[0].ok, false);
    assert.equal(r.result[0].error.kind, "input");
  }
});

test("malformed graph shapes and GraphQL errors are failures", async () => {
  const payloads = [{}, { data: { data: {} } }, { errors: [{ message: "Denied" }] },
    { data: { errors: [{ message: "Denied" }], data: fixture.graph.data.data } }];
  for (const payload of payloads) {
    const r = await run("context", { seeds: [page] }, { getTeamworkGraphContext: () => mcp(payload) });
    assert.equal(r.result[0].ok, false);
  }
});

test("context preserves distinct relationship provenance, deduplicates repeats and reports foreign targets", async () => {
  const graph = clone(fixture.graph);
  const relationship = graph.data.data.relationships[0];
  relationship.targets.push(clone(relationship.targets[0]));
  relationship.targets.push({ ...relationship.targets[0], ari: ari.replace(cloudId, "other-site") });
  graph.data.data.relationships.push({ ...clone(relationship), relationshipName: "another-relationship", targets: [relationship.targets[0]] });
  const r = await run("context", { seeds: [page] }, { getTeamworkGraphContext: () => mcp(graph) });
  assert.equal(r.result[0].value.candidates.length, 2);
  assert.equal(r.result[0].value.omitted.length, 1);
  assert.equal(r.result[0].value.seed, graph.data.data.object.ari);
});

test("server overflow is visible at both sides of the context candidate boundary", async () => {
  for (const count of [19, 20, 21]) {
    const graph = clone(fixture.graph);
    graph.data.data.relationships[0].targets = Array.from({ length: count }, (_, i) => ({ ari: ari + i }));
    const r = await run("context", { seeds: [page] }, { getTeamworkGraphContext: () => mcp(graph) });
    assert.equal(r.result[0].value.candidates.length, Math.min(count, 20));
    assert.equal(r.result[0].value.omitted.length, Math.max(0, count - 20));
  }
});

test("source and seed bounds are checked before calls, and extraneous seed keys cannot reroute them", async () => {
  for (const recipe of ["sources", "context"]) {
    const field = recipe === "sources" ? "sources" : "seeds";
    for (const count of [0, 3]) await assert.rejects(run(recipe, { [field]: Array(count).fill(page) }, good), /Expected 1\.\.2/);
    for (const count of [1, 2]) {
      const r = await run(recipe, { [field]: Array(count).fill({ ...page, cloudId: "foreign", after: "ignored" }) }, good);
      assert.equal(r.calls.length, count);
      assert.ok(r.calls.every(c => c.args.cloudId === cloudId && c.args.after === undefined));
    }
  }
});

test("search sends natural language and strips previews, duplicates, foreign sites and overflow visibly", async () => {
  const search = clone(fixture.search);
  search.results.push(clone(search.results[0]));
  search.results.push({ ...search.results[0], id: search.results[0].id.replace(cloudId, "foreign") });
  const r = await run("search", { query: "configuration approval" }, { search: () => mcp(search) });
  assert.deepEqual(r.calls[0].args, { query: "configuration approval" });
  assert.equal(r.result.candidates.length, 1);
  assert.equal(r.result.omitted.length, 1);
  assert.ok(!JSON.stringify(r.result).includes("ranked search preview"));
  assert.equal(r.result.coverage, "Ranked search, not exhaustive");
  for (const count of [9, 10, 11]) {
    search.results = Array.from({ length: count }, (_, i) => ({ ...fixture.search.results[0], id: fixture.search.results[0].id + i }));
    const next = await run("search", { query: "configuration approval" }, { search: () => mcp(search) });
    assert.equal(next.result.candidates.length, Math.min(count, 10));
    assert.equal(next.result.omitted.length, Math.max(0, count - 10));
  }
});

test("search and hydration reject unsupported payloads instead of manufacturing empty results", async () => {
  await assert.rejects(run("search", { query: "example" }, { search: () => mcp({}) }), /Expected search results/);
  await assert.rejects(run("hydrate", { objects: [ari] }, { getTeamworkGraphObject: () => mcp({ data: { data: {} } }) }), /Expected hydrated objects/);
});

test("hydration deduplicates selected ARIs and returns representation metadata, not raw bodies", async () => {
  const r = await run("hydrate", { objects: [ari, ari] }, good);
  assert.deepEqual(r.calls[0].args.objects, [ari]);
  assert.deepEqual(r.result.items[0].representations, ["atlasDocFormat"]);
  assert.deepEqual(r.result.missing, []);
  assert.ok(!JSON.stringify(r.result).includes("ATLAS_DOC_FORMAT"));
  assert.equal(r.calls.length, 1);
});

test("hydration rejects mixed sites before calling and exposes missing objects", async () => {
  await assert.rejects(run("hydrate", { objects: [ari, ari.replace(cloudId, "other-site")] }, good), /Mixed sites/);
  await assert.rejects(run("hydrate", { objects: [ari.replace(":page/", ":issue/")] }, good), /unsupported ARIs/);
  const r = await run("hydrate", { objects: [ari, ari + "1"] }, good);
  assert.deepEqual(r.result.missing, [ari + "1"]);
  await assert.rejects(run("hydrate", { objects: [ari + "1"] }, good), /Unexpected hydrated object/);
});

test("hydration bounds apply after deduplication and before a remote call", async () => {
  for (const count of [0, 11]) await assert.rejects(run("hydrate", { objects: Array.from({ length: count }, (_, i) => ari + i) }, good), /Expected 1\.\.10/);
  for (const count of [1, 9, 10]) {
    const r = await run("hydrate", { objects: Array.from({ length: count }, (_, i) => ari + i) }, {
      getTeamworkGraphObject: () => mcp({ data: { data: { objects: [] } } }),
    });
    assert.equal(r.calls.length, 1);
    assert.equal(r.result.missing.length, count);
  }
});

test("a write is blocked by the recipe guard; the harness sentinel also rejects a deliberately bad direct call", async () => {
  await assert.rejects(runCode('return await call("createJiraIssue", {});', { cloudId }, good), /Not an exploration read tool/);
  await assert.rejects(runCode('return await tools.mcp__atlassian__createJiraIssue({});', { cloudId }, good), /write sentinel/);
  const r = await run("sources", { sources: [issue] }, good);
  assert.equal(r.result[0].ok, true);
});

test("the complete recipe sequence uses four discovery calls and keeps source reads separate", async () => {
  const search = await run("search", { query: "example requirements" }, good);
  const contexts = await run("context", { seeds: [issue, page] }, good);
  const hydrate = await run("hydrate", { objects: [ari] }, good);
  const source = await run("sources", { sources: [issue, page] }, good);
  assert.ok(contexts.result.every((r: any) => r.ok));
  assert.equal(search.calls.length + contexts.calls.length + hydrate.calls.length, 4);
  assert.equal(source.calls.length, 2);
});
