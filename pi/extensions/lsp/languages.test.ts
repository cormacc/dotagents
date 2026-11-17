#!/usr/bin/env tsx
/** Characterisation tests for the built-in language catalogue: launch commands, root selection, document language IDs and indexing trackers. */

import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { pathToFileURL } from "node:url";
import { languages, getSupportedLanguages } from "./languages/index.ts";

let passed = 0;
let failed = 0;

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEqual(actual: unknown, expected: unknown, message: string) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) throw new Error(`${message}: expected ${e}, got ${a}`);
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

function tree(files: string[]) {
  const dir = mkdtempSync(join(tmpdir(), "pi-lsp-lang-"));
  for (const f of files) {
    mkdirSync(dirname(join(dir, f)), { recursive: true });
    writeFileSync(join(dir, f), "", "utf8");
  }
  return { dir, dispose: () => rmSync(dir, { recursive: true, force: true }) };
}

const ctx = { cwd: "/nonexistent" };

// Pinned from the per-language modules at ef33181; c and cpp are the accepted clangd additions.
const LAUNCH: Record<string, { command: string; args: string[]; settings?: unknown }> = {
  nix: { command: "nil", args: ["--stdio"] },
  python: {
    command: "nix-shell",
    args: ["-p", "basedpyright", "--run", "basedpyright-langserver --stdio"],
    settings: { basedpyright: { analysis: { diagnosticMode: "openFilesOnly" } } },
  },
  typescript: { command: "typescript-language-server", args: ["--stdio"] },
  go: { command: "gopls", args: ["serve"] },
  clojure: { command: "clojure-lsp", args: ["listen"] },
  c: { command: "clangd", args: ["--background-index"] },
  cpp: { command: "clangd", args: ["--background-index"] },
};

const ROOT_MARKERS: Record<string, string[]> = {
  nix: ["flake.nix", "default.nix", "shell.nix"],
  python: [],
  typescript: ["tsconfig.json", "jsconfig.json", "package.json"],
  go: ["go.mod"],
  clojure: ["deps.edn", "project.clj", "bb.edn", "shadow-cljs.edn", ".lsp/config.edn"],
  c: ["compile_commands.json", "compile_flags.txt", ".clangd"],
  cpp: ["compile_commands.json", "compile_flags.txt", ".clangd"],
};

const DOC_IDS: Record<string, Record<string, string>> = {
  nix: { "a.nix": "nix" },
  python: { "a.py": "python", "a.pyi": "python" },
  typescript: {
    "a.ts": "typescript",
    "a.mts": "typescript",
    "a.cts": "typescript",
    "a.d.ts": "typescript",
    "a.tsx": "typescriptreact",
    "a.js": "javascript",
    "a.mjs": "javascript",
    "a.cjs": "javascript",
    "a.jsx": "javascriptreact",
  },
  go: { "a.go": "go" },
  clojure: {
    "a.clj": "clojure",
    "a.cljs": "clojurescript",
    "a.cljc": "clojure",
    "a.edn": "edn",
    "a.bb": "clojure",
    "a.cljd": "clojure",
  },
  c: { "a.c": "c", "a.h": "c" },
  cpp: {
    "a.cpp": "cpp",
    "a.cc": "cpp",
    "a.cxx": "cpp",
    "a.c++": "cpp",
    "a.C": "cpp",
    "a.hpp": "cpp",
    "a.hh": "cpp",
    "a.hxx": "cpp",
    "a.h++": "cpp",
    "a.H": "cpp",
    "a.h": "cpp",
  },
};

(async () => {
  await test("catalogue keeps the supported languages in order", () => {
    assertEqual(getSupportedLanguages(), ["nix", "python", "typescript", "go", "clojure", "c", "cpp"], "languages");
  });

  await test("c and cpp share the clangd server identity; other families own theirs", () => {
    assertEqual(languages.c.serverId, "clangd", "c server");
    assertEqual(languages.cpp.serverId, "clangd", "cpp server");
    for (const lang of ["nix", "python", "typescript", "go", "clojure"]) {
      assertEqual(languages[lang].serverId, lang, `${lang} server`);
    }
  });

  await test("C and C++ extension case stays significant", () => {
    assertEqual(languages.c.languageIdForPath("/x/a.c"), "c", ".c in c");
    assertEqual(languages.c.languageIdForPath("/x/a.C"), null, ".C is not c");
    assertEqual(languages.cpp.languageIdForPath("/x/a.C"), "cpp", ".C in cpp");
    assertEqual(languages.cpp.languageIdForPath("/x/a.c"), null, ".c is not cpp");
  });

  for (const [lang, launch] of Object.entries(LAUNCH)) {
    await test(`${lang} launch command, arguments and settings are unchanged`, () => {
      const root = "/work/project";
      const configs = languages[lang].getConfig(root);
      assertEqual(configs.length, 1, "config count");
      const [config] = configs;
      assertEqual(config.command, launch.command, "command");
      assertEqual(config.args, launch.args, "args");
      assertEqual(config.settings, launch.settings, "settings");
      assertEqual(config.rootUri, pathToFileURL(root).href, "rootUri");
    });
  }

  for (const [lang, markers] of Object.entries(ROOT_MARKERS)) {
    await test(`${lang} root selection: nearest marker wins, otherwise the start directory`, () => {
      const plugin = languages[lang];
      const none = tree(["a/b/file"]);
      try {
        const start = join(none.dir, "a/b");
        assertEqual(plugin.getWorkspaceRoot(start, ctx), start, "missing marker falls back to start dir");
      } finally {
        none.dispose();
      }
      for (const marker of markers) {
        const t = tree([marker, `a/${marker}`, "a/b/c/file"]);
        try {
          const start = join(t.dir, "a/b/c");
          assertEqual(plugin.getWorkspaceRoot(start, ctx), join(t.dir, "a"), `nearest ${marker}`);
          assertEqual(plugin.getWorkspaceRoot(join(t.dir, "a"), ctx), join(t.dir, "a"), `${marker} in start dir`);
        } finally {
          t.dispose();
        }
      }
      if (markers.length > 0) {
        // A marker in an ancestor wins over a different marker further up.
        const t = tree([markers[0], `a/${markers[markers.length - 1]}`, "a/b/file"]);
        try {
          assertEqual(plugin.getWorkspaceRoot(join(t.dir, "a/b"), ctx), join(t.dir, "a"), "nearest of mixed markers");
        } finally {
          t.dispose();
        }
      }
    });
  }

  for (const [lang, ids] of Object.entries(DOC_IDS)) {
    await test(`${lang} document language IDs are unchanged`, () => {
      for (const [file, id] of Object.entries(ids)) {
        assertEqual(languages[lang].languageIdForPath(`/x/${file}`), id, file);
      }
      assertEqual(languages[lang].languageIdForPath("/x/a.unknown"), null, "unknown extension");
    });
  }

  await test("each family's source-file extensions cover exactly its document-ID mapping", () => {
    for (const [lang, ids] of Object.entries(DOC_IDS)) {
      const exts = new Set(Object.keys(ids).map((f) => f.slice(f.lastIndexOf("."))));
      assertEqual([...languages[lang].fileExtensions].sort(), [...exts].sort(), `${lang} extensions`);
    }
  });

  await test("no extension maps to two server families", () => {
    const owner = new Map<string, string>();
    for (const plugin of Object.values(languages)) {
      for (const ext of plugin.fileExtensions) {
        const prior = owner.get(ext);
        assert(!prior || prior === plugin.serverId, `${ext} is claimed by ${prior} and ${plugin.serverId}`);
        owner.set(ext, plugin.serverId);
      }
    }
  });

  for (const lang of ["go", "typescript", "clojure", "c", "cpp"]) {
    await test(`${lang} progress tracker: done until a progress begins, then until every token ends`, () => {
      const tracker = languages[lang].createIndexingTracker?.();
      assert(tracker, "tracker exists");
      assert(tracker.isDone(), "done before any progress");
      tracker.handleMessage({ method: "$/progress", params: { token: 1, value: { kind: "begin" } } });
      tracker.handleMessage({ method: "$/progress", params: { token: "b", value: { kind: "begin" } } });
      assert(!tracker.isDone(), "not done while tokens are open");
      tracker.handleMessage({ method: "$/progress", params: { token: 1, value: { kind: "report" } } });
      tracker.handleMessage({ method: "$/progress", params: { token: 1, value: { kind: "end" } } });
      assert(!tracker.isDone(), "not done while one token is open");
      tracker.handleMessage({ method: "window/logMessage", params: { message: "x" } });
      tracker.handleMessage({ method: "$/progress", params: { token: "b", value: { kind: "end" } } });
      assert(tracker.isDone(), "done after every token ends");
    });
  }

  await test("python tracker: done only after the 'Found N source files' log message", () => {
    const tracker = languages.python.createIndexingTracker?.();
    assert(tracker, "tracker exists");
    assert(!tracker.isDone(), "not done initially");
    tracker.handleMessage({ method: "$/progress", params: { token: 1, value: { kind: "end" } } });
    tracker.handleMessage({ method: "window/logMessage", params: { message: "Searching" } });
    assert(!tracker.isDone(), "not done on other messages");
    tracker.handleMessage({ method: "window/logMessage", params: { message: "Found 12 source files" } });
    assert(tracker.isDone(), "done after the log message");
  });

  await test("nix has no indexing tracker", () => {
    assertEqual(languages.nix.createIndexingTracker, undefined, "nix tracker");
  });

  await test("trackers are independent per call", () => {
    const a = languages.go.createIndexingTracker?.();
    const b = languages.go.createIndexingTracker?.();
    assert(a && b && a !== b, "fresh tracker per call");
    a.handleMessage({ method: "$/progress", params: { token: 1, value: { kind: "begin" } } });
    assert(!a.isDone() && b.isDone(), "state is not shared");
  });

  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
})();
