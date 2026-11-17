import { existsSync } from "node:fs";
import { dirname, extname, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import type { LspServerConfig } from "../lsp-client";

export interface IndexingTracker {
  handleMessage(msg: { method?: string; params?: unknown }): void;
  isDone(): boolean;
}

export interface LanguagePlugin {
  languageId: string;
  /** Server identity: languages with the same ID share one server process per workspace root. */
  serverId: string;
  getConfig: (cwd: string) => LspServerConfig[];
  getWorkspaceRoot: (startDir: string, ctx: { cwd: string }) => string;
  fileExtensions: Set<string>;
  languageIdForPath: (filePath: string) => string | null;
  createIndexingTracker?: () => IndexingTracker;
  /** The server indexes only after a document opens: wait this long for indexing to begin on a fresh server. */
  indexOnOpenGraceMs?: number;
}

/** One server family: how to launch it, where its workspace root is, and which files it owns. */
interface LanguageSpec {
  /** Shared server identity; defaults to the catalogue key. */
  server?: string;
  command: string;
  args: string[];
  settings?: Record<string, unknown>;
  /** Marker files; the nearest ancestor directory that holds any of them is the root. Empty: the start directory. */
  rootMarkers: string[];
  /** File extension (case-sensitive, with dot) to LSP document language ID. Also the source-file set for symbol scans. */
  extensions: Record<string, string>;
  createIndexingTracker?: () => IndexingTracker;
  indexOnOpenGraceMs?: number;
}

function findNearestContainingDir(startDir: string, markers: string[]): string | null {
  let current = resolve(startDir);
  while (true) {
    for (const marker of markers) {
      if (existsSync(join(current, marker))) return current;
    }
    const parent = dirname(current);
    if (parent === current) return null;
    current = parent;
  }
}

/** Ready once every `$/progress` token that began has ended; ready at once if none begins. */
function progressTracker(): IndexingTracker {
  const tokens = new Set<string | number>();
  let started = false;
  return {
    handleMessage(msg) {
      if (msg.method !== "$/progress") return;
      const params = msg.params as { token: string | number; value: { kind: string } };
      if (params.value.kind === "begin") {
        tokens.add(params.token);
        started = true;
      } else if (params.value.kind === "end") {
        tokens.delete(params.token);
      }
    },
    isDone: () => !started || tokens.size === 0,
  };
}

/** basedpyright reports no progress tokens; it logs "Found N source files" when its scan ends. */
function basedpyrightTracker(): IndexingTracker {
  let done = false;
  return {
    handleMessage(msg) {
      if (msg.method !== "window/logMessage") return;
      const params = msg.params as { message: string };
      if (/Found \d+ source files/.test(params.message)) done = true;
    },
    isDone: () => done,
  };
}

/** clangd serves C and C++ from one process; each document keeps the ID of the language that opened it. */
const CLANGD = {
  server: "clangd",
  command: "clangd",
  args: ["--background-index"],
  rootMarkers: ["compile_commands.json", "compile_flags.txt", ".clangd"],
  createIndexingTracker: progressTracker,
  // clangd 21 began background-index progress 22 ms after the first didOpen in the firmware smoke.
  indexOnOpenGraceMs: 2000,
} satisfies Omit<LanguageSpec, "extensions">;

const CATALOGUE: Record<string, LanguageSpec> = {
  nix: {
    command: "nil",
    args: ["--stdio"],
    rootMarkers: ["flake.nix", "default.nix", "shell.nix"],
    extensions: { ".nix": "nix" },
  },
  python: {
    command: "nix-shell",
    args: ["-p", "basedpyright", "--run", "basedpyright-langserver --stdio"],
    settings: { basedpyright: { analysis: { diagnosticMode: "openFilesOnly" } } },
    rootMarkers: [],
    extensions: { ".py": "python", ".pyi": "python" },
    createIndexingTracker: basedpyrightTracker,
  },
  typescript: {
    command: "typescript-language-server",
    args: ["--stdio"],
    rootMarkers: ["tsconfig.json", "jsconfig.json", "package.json"],
    extensions: {
      ".ts": "typescript",
      ".mts": "typescript",
      ".cts": "typescript",
      ".tsx": "typescriptreact",
      ".js": "javascript",
      ".mjs": "javascript",
      ".cjs": "javascript",
      ".jsx": "javascriptreact",
    },
    createIndexingTracker: progressTracker,
  },
  go: {
    command: "gopls",
    args: ["serve"],
    rootMarkers: ["go.mod"],
    extensions: { ".go": "go" },
    createIndexingTracker: progressTracker,
  },
  clojure: {
    command: "clojure-lsp",
    args: ["listen"],
    rootMarkers: ["deps.edn", "project.clj", "bb.edn", "shadow-cljs.edn", ".lsp/config.edn"],
    extensions: {
      ".clj": "clojure",
      ".cljs": "clojurescript",
      ".cljc": "clojure",
      ".edn": "edn",
      ".bb": "clojure",
      ".cljd": "clojure",
    },
    createIndexingTracker: progressTracker,
  },
  // .h belongs to both: the requested language selects the header's document ID.
  c: { ...CLANGD, extensions: { ".c": "c", ".h": "c" } },
  cpp: {
    ...CLANGD,
    extensions: {
      ".cpp": "cpp",
      ".cc": "cpp",
      ".cxx": "cpp",
      ".c++": "cpp",
      ".C": "cpp",
      ".hpp": "cpp",
      ".hh": "cpp",
      ".hxx": "cpp",
      ".h++": "cpp",
      ".H": "cpp",
      ".h": "cpp",
    },
  },
};

function toPlugin(languageId: string, spec: LanguageSpec): LanguagePlugin {
  const plugin: LanguagePlugin = {
    languageId,
    serverId: spec.server ?? languageId,
    getConfig: (cwd) => [
      {
        command: spec.command,
        args: spec.args,
        rootUri: pathToFileURL(cwd).href,
        ...(spec.settings ? { settings: spec.settings } : {}),
      },
    ],
    getWorkspaceRoot: (startDir) =>
      (spec.rootMarkers.length > 0 && findNearestContainingDir(startDir, spec.rootMarkers)) || startDir,
    fileExtensions: new Set(Object.keys(spec.extensions)),
    languageIdForPath: (filePath) => spec.extensions[extname(filePath)] ?? null,
  };
  if (spec.createIndexingTracker) plugin.createIndexingTracker = spec.createIndexingTracker;
  if (spec.indexOnOpenGraceMs) plugin.indexOnOpenGraceMs = spec.indexOnOpenGraceMs;
  return plugin;
}

export const languages: Record<string, LanguagePlugin> = Object.fromEntries(
  Object.entries(CATALOGUE).map(([id, spec]) => [id, toPlugin(id, spec)]),
);

export function getSupportedLanguages(): string[] {
  return Object.keys(languages);
}
