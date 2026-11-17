#!/usr/bin/env tsx
/** Regression tests for LSP rename plans, diffs and location-preview invalidation. */

import {
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { DEFAULT_MAX_BYTES, DEFAULT_MAX_LINES } from "@earendil-works/pi-coding-agent";
import lsp, { applyRenamePlan, buildLspToolResult, previewRename } from "./index.ts";
import { formatReferences } from "./formatters.ts";

let passed = 0;
let failed = 0;

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

async function test(name: string, fn: () => Promise<void> | void) {
  try {
    await fn();
    console.log(`ok - ${name}`);
    passed++;
  } catch (error) {
    console.log(`not ok - ${name}`);
    console.log(`  # ${error instanceof Error ? error.message : String(error)}`);
    failed++;
  }
}

function fixture() {
  const dir = mkdtempSync(join(tmpdir(), "pi-lsp-test-"));
  return {
    dir,
    path: (name: string) => join(dir, name),
    dispose: () => rmSync(dir, { recursive: true, force: true }),
  };
}

function edit(line: number, start: number, end: number, newText: string) {
  return {
    range: {
      start: { line, character: start },
      end: { line, character: end },
    },
    newText,
  };
}

type Edit = Parameters<typeof previewRename>[0];

/** Preview then apply, as the tool does across two calls. The root "/" admits every fixture path. */
function applyWorkspaceEdit(
  edit: Edit,
  client: { refreshDocument(path: string): Promise<unknown> },
) {
  return applyRenamePlan(previewRename(edit, "/").plan, client);
}

function refresher() {
  const refreshed: string[] = [];
  return {
    refreshed,
    refreshDocument: async (path: string) => {
      refreshed.push(path);
    },
  };
}

(async () => {
  await test("single-file rename returns a standard before/after diff", async () => {
    const files = fixture();
    try {
      const path = files.path("single.ts");
      writeFileSync(path, "const oldName = 1;\n", "utf8");
      const client = refresher();

      const result = await applyWorkspaceEdit(
        {
          changes: {
            [pathToFileURL(path).href]: [edit(0, 6, 13, "newName")],
          },
        },
        client,
      );

      assert(result.summary === "Applied 1 edit(s) in 1 file(s).", result.summary);
      assert(result.files.length === 1, "expected one changed file");
      assert(result.diff.includes("-1 const oldName = 1;"), result.diff);
      assert(result.diff.includes("+1 const newName = 1;"), result.diff);
      assert(client.refreshed.join() === path, "changed file was not refreshed");
    } finally {
      files.dispose();
    }
  });

  await test("multi-file documentChanges preserve a diff for each file", async () => {
    const files = fixture();
    try {
      const alpha = files.path("alpha.ts");
      const beta = files.path("beta.ts");
      writeFileSync(alpha, "old\n", "utf8");
      writeFileSync(beta, "old\n", "utf8");
      const client = refresher();

      const result = await applyWorkspaceEdit(
        {
          documentChanges: [
            {
              textDocument: { uri: pathToFileURL(alpha).href },
              edits: [edit(0, 0, 3, "new")],
            },
            {
              textDocument: { uri: pathToFileURL(beta).href },
              edits: [edit(0, 0, 3, "new")],
            },
          ],
        },
        client,
      );

      assert(result.files.length === 2, "expected two changed files");
      for (const path of [alpha, beta]) {
        assert(result.diff.includes(`${path}:`), `missing diff header for ${path}`);
      }
      assert(client.refreshed.length === 2, "each changed file must refresh once");
    } finally {
      files.dispose();
    }
  });

  await test("same-file workspace edits remain serialized through document refresh", async () => {
    const files = fixture();
    try {
      const path = files.path("queued.ts");
      const uri = pathToFileURL(path).href;
      writeFileSync(path, "one two\n", "utf8");

      let releaseFirstRefresh!: () => void;
      const firstRefreshReleased = new Promise<void>((resolve) => {
        releaseFirstRefresh = resolve;
      });
      let firstRefreshStarted!: () => void;
      const firstRefresh = new Promise<void>((resolve) => {
        firstRefreshStarted = resolve;
      });
      let refreshes = 0;
      const client = {
        refreshDocument: async () => {
          refreshes++;
          if (refreshes === 1) {
            firstRefreshStarted();
            await firstRefreshReleased;
          }
        },
      };

      const first = applyWorkspaceEdit(
        { changes: { [uri]: [edit(0, 0, 3, "ONE")] } },
        client,
      );
      await firstRefresh;
      const second = applyWorkspaceEdit(
        { changes: { [uri]: [edit(0, 4, 7, "TWO")] } },
        client,
      );
      await Promise.resolve();

      assert(refreshes === 1, "second mutation entered the queue before the first refresh completed");
      assert(readFileSync(path, "utf8") === "ONE two\n", "second mutation wrote before the first queue completed");

      releaseFirstRefresh();
      await Promise.all([first, second]);
      assert(readFileSync(path, "utf8") === "ONE TWO\n", "serialized mutations lost one workspace edit");
    } finally {
      files.dispose();
    }
  });

  await test("rename invalidates cached location previews after writing", async () => {
    const files = fixture();
    try {
      const path = files.path("preview.ts");
      const uri = pathToFileURL(path).href;
      writeFileSync(path, "const oldName = 1;\n", "utf8");
      const location = { uri, range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } } };
      assert(formatReferences([location]).includes("oldName"), "failed to warm preview cache");

      await applyWorkspaceEdit(
        { changes: { [uri]: [edit(0, 6, 13, "newName")] } },
        refresher(),
      );

      assert(formatReferences([location]).includes("newName"), "preview cache remained stale after rename");
    } finally {
      files.dispose();
    }
  });

  await test("session shutdown clears cached previews for a replacement session", async () => {
    const files = fixture();
    try {
      const path = files.path("replacement.ts");
      const uri = pathToFileURL(path).href;
      const location = { uri, range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } } };
      writeFileSync(path, "before replacement\n", "utf8");
      assert(formatReferences([location]).includes("before replacement"), "failed to warm preview cache");

      let shutdown: (() => Promise<void>) | undefined;
      lsp({
        on: (event: string, handler: () => Promise<void>) => {
          if (event === "session_shutdown") shutdown = handler;
        },
        registerTool: () => {},
        registerCommand: () => {},
      } as any);
      assert(shutdown, "LSP extension did not register session cleanup");

      writeFileSync(path, "after replacement\n", "utf8");
      await shutdown();
      assert(formatReferences([location]).includes("after replacement"), "preview cache survived session shutdown");
    } finally {
      files.dispose();
    }
  });

  await test("rename rendering keeps the summary compact and expands the standard diff", () => {
    let tool: any;
    lsp({
      on: () => {},
      registerTool: (definition: unknown) => {
        tool = definition;
      },
      registerCommand: () => {},
    } as any);
    assert(tool, "LSP tool was not registered");

    const theme = { fg: (_tone: string, text: string) => text, bold: (text: string) => text };
    const result = {
      content: [{ type: "text", text: "bounded model output" }],
      details: {
        summary: "Applied 1 edit(s) in 1 file(s).",
        diff: "file.ts:\n-1 old\n+1 new",
      },
    };
    const collapsed = tool.renderResult(result, { expanded: false }, theme).render(80).join("\n").trimEnd();
    const expanded = tool.renderResult(result, { expanded: true }, theme).render(80).join("\n");

    assert(collapsed === result.details.summary, `unexpected collapsed output: ${collapsed}`);
    assert(expanded.includes("-1 old") && expanded.includes("+1 new"), `unexpected expanded output: ${expanded}`);
  });

  await test("large rename diff is bounded in model output and expanded details", async () => {
    const files = fixture();
    try {
      const path = files.path("large.ts");
      const oldContent = "old\n".repeat(DEFAULT_MAX_LINES + 100);
      writeFileSync(path, oldContent, "utf8");
      const result = await applyWorkspaceEdit(
        {
          changes: {
            [pathToFileURL(path).href]: [
              {
                range: {
                  start: { line: 0, character: 0 },
                  end: { line: DEFAULT_MAX_LINES + 100, character: 0 },
                },
                newText: "new\n".repeat(DEFAULT_MAX_LINES + 100),
              },
            ],
          },
        },
        refresher(),
      );
      const output = buildLspToolResult(
        result.diff,
        "typescript",
        "rename",
        result.summary,
      );
      const content = output.content[0].text;
      const detail = output.details.diff;

      assert(content !== result.diff, "large diff must be truncated for model-visible content");
      assert(detail === content, "expanded rename detail must use the bounded tool diff");
      assert(Buffer.byteLength(content, "utf8") <= DEFAULT_MAX_BYTES, "bounded model output exceeded byte limit");
      assert(content.split("\n").length <= DEFAULT_MAX_LINES, "bounded model output exceeded line limit");
      assert(Buffer.byteLength(detail, "utf8") <= DEFAULT_MAX_BYTES, "bounded expanded detail exceeded byte limit");
      assert(detail.split("\n").length <= DEFAULT_MAX_LINES, "bounded expanded detail exceeded line limit");
    } finally {
      files.dispose();
    }
  });

  await test("rename preview writes nothing and lists every edit with a plan identifier", async () => {
    const files = fixture();
    try {
      const alpha = files.path("alpha.c");
      const beta = files.path("beta.c");
      writeFileSync(alpha, "int old;\nint x = old;\n", "utf8");
      writeFileSync(beta, "extern int old;\n", "utf8");
      const preview = previewRename(
        {
          changes: {
            [pathToFileURL(alpha).href]: [edit(0, 4, 7, "neu"), edit(1, 8, 11, "neu")],
            [pathToFileURL(beta).href]: [edit(0, 11, 14, "neu")],
          },
        },
        files.dir,
        "feedbeef",
      );
      assert(preview.plan.id === "feedbeef", "plan id");
      assert(preview.problems.length === 0, preview.problems.join("; "));
      assert(preview.summary === "Rename plan feedbeef: 3 edit(s) in 2 file(s); no file written.", preview.summary);
      assert(preview.text.includes(`${alpha} (2 edit(s): 1:5-1:8, 2:9-2:12):`), preview.text);
      assert(preview.text.includes(`${beta} (1 edit(s): 1:12-1:15):`), preview.text);
      assert(preview.text.includes('plan_id "feedbeef"'), preview.text);
      assert(preview.text.includes("+1 int neu;"), preview.text);
      assert(readFileSync(alpha, "utf8") === "int old;\nint x = old;\n", "preview wrote alpha");
      assert(readFileSync(beta, "utf8") === "extern int old;\n", "preview wrote beta");

      const applied = await applyRenamePlan(preview.plan, refresher());
      assert(applied.summary === "Applied 3 edit(s) in 2 file(s).", applied.summary);
      assert(readFileSync(alpha, "utf8") === "int neu;\nint x = neu;\n", "alpha not applied");
      assert(readFileSync(beta, "utf8") === "extern int neu;\n", "beta not applied");
    } finally {
      files.dispose();
    }
  });

  async function refused(
    plan: ReturnType<typeof previewRename>["plan"],
    pattern: RegExp,
  ) {
    const client = refresher();
    try {
      await applyRenamePlan(plan, client);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      assert(pattern.test(message), `unexpected refusal: ${message}`);
      assert(message.includes("no file written"), message);
      assert(client.refreshed.length === 0, "refused plan refreshed a document");
      return;
    }
    throw new Error("apply did not refuse the plan");
  }

  await test("apply refuses every file when one target changed after the preview", async () => {
    const files = fixture();
    try {
      const alpha = files.path("alpha.ts");
      const beta = files.path("beta.ts");
      writeFileSync(alpha, "old\n", "utf8");
      writeFileSync(beta, "old\n", "utf8");
      const { plan } = previewRename(
        {
          changes: {
            [pathToFileURL(alpha).href]: [edit(0, 0, 3, "new")],
            [pathToFileURL(beta).href]: [edit(0, 0, 3, "new")],
          },
        },
        files.dir,
      );
      writeFileSync(beta, "old \n", "utf8");
      await refused(plan, new RegExp(`File changed since the preview: ${beta}`));
      assert(readFileSync(alpha, "utf8") === "old\n", "unchanged file was written");
      assert(readFileSync(beta, "utf8") === "old \n", "changed file was overwritten");
    } finally {
      files.dispose();
    }
  });

  await test("apply refuses overlapping edits", async () => {
    const files = fixture();
    try {
      const path = files.path("overlap.ts");
      writeFileSync(path, "abcdef\n", "utf8");
      const preview = previewRename(
        { changes: { [pathToFileURL(path).href]: [edit(0, 0, 4, "x"), edit(0, 2, 6, "y")] } },
        files.dir,
      );
      assert(preview.problems.some((p) => /Overlapping/.test(p)), preview.problems.join("; "));
      assert(preview.text.includes("apply_rename will refuse this plan"), preview.text);
      await refused(preview.plan, /Overlapping rename edits/);
      assert(readFileSync(path, "utf8") === "abcdef\n", "overlap was written");
    } finally {
      files.dispose();
    }
  });

  await test("apply refuses an edit outside the workspace root", async () => {
    const root = fixture();
    const outside = fixture();
    try {
      const inner = root.path("inner.ts");
      const outer = outside.path("outer.ts");
      writeFileSync(inner, "old\n", "utf8");
      writeFileSync(outer, "old\n", "utf8");
      const { plan } = previewRename(
        {
          changes: {
            [pathToFileURL(inner).href]: [edit(0, 0, 3, "new")],
            [pathToFileURL(outer).href]: [edit(0, 0, 3, "new")],
          },
        },
        root.dir,
      );
      await refused(plan, new RegExp(`outside the workspace root ${root.dir}: ${outer}`));
      assert(readFileSync(inner, "utf8") === "old\n", "inner file was written");
      assert(readFileSync(outer, "utf8") === "old\n", "outer file was written");
    } finally {
      root.dispose();
      outside.dispose();
    }
  });

  await test("apply refuses and reports resource operations", async () => {
    const files = fixture();
    try {
      const path = files.path("ns.clj");
      writeFileSync(path, "(ns old)\n", "utf8");
      const preview = previewRename(
        {
          documentChanges: [
            { textDocument: { uri: pathToFileURL(path).href, version: 1 }, edits: [edit(0, 4, 7, "new")] },
            { kind: "rename", oldUri: pathToFileURL(path).href, newUri: pathToFileURL(files.path("new.clj")).href },
          ],
        },
        files.dir,
      );
      const reason = `Resource operation not supported: rename ${path} -> ${files.path("new.clj")}`;
      assert(preview.problems.includes(reason), preview.problems.join("; "));
      await refused(preview.plan, new RegExp(reason.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
      assert(readFileSync(path, "utf8") === "(ns old)\n", "text edit was written");
    } finally {
      files.dispose();
    }
  });

  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
})();
