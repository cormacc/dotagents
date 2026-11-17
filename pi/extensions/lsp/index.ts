import { StringEnum } from "@earendil-works/pi-ai";
import { generateDiffString } from "@earendil-works/pi-coding-agent";
import { createHash, randomBytes } from "node:crypto";
import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import {
  dirname,
  extname,
  isAbsolute,
  join,
  relative,
  resolve,
} from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import type {
  ExtensionAPI,
  ExtensionContext,
} from "@earendil-works/pi-coding-agent";
import {
  truncateHead,
  DEFAULT_MAX_BYTES,
  DEFAULT_MAX_LINES,
  withFileMutationQueue,
} from "@earendil-works/pi-coding-agent";
import { Text } from "@earendil-works/pi-tui";
import { Type } from "typebox";
import { LspClient } from "./lsp-client";
import {
  languages,
  getSupportedLanguages,
  type LanguagePlugin,
} from "./languages";
import {
  formatReferences,
  invalidateFilePreview,
  formatDocumentSymbols,
  formatWorkspaceSymbol,
  formatCompletion,
  formatDiagnostics,
} from "./formatters";

const ACTIONS = [
  "definition",
  "references",
  "rename",
  "apply_rename",
  "document_symbols",
  "workspace_symbol",
  "completion",
  "diagnostics",
] as const;

type Action = (typeof ACTIONS)[number];

type Position = { line: number; character: number };
type Range = { start: Position; end: Position };
type Location = { uri: string; range: Range };
type LocationLink = {
  targetUri: string;
  targetRange: Range;
  targetSelectionRange: Range;
};
type DocumentSymbol = {
  name: string;
  kind: number;
  range?: Range;
  selectionRange?: Range;
  children?: DocumentSymbol[];
  location?: Location;
};

type TextEdit = {
  range: Range;
  newText: string;
};

type WorkspaceEdit = {
  changes?: Record<string, TextEdit[]>;
  documentChanges?: (
    | {
        textDocument: { uri: string; version?: number | null };
        edits: TextEdit[];
      }
    | { kind: string; uri?: string; oldUri?: string; newUri?: string }
  )[];
};

const LspParams = Type.Object({
  language: Type.String({
    description: `Language to use. Supported: ${getSupportedLanguages().join(", ")}`,
  }),
  action: StringEnum([...ACTIONS]),
  file: Type.Optional(
    Type.String({
      description:
        "File path (relative to cwd). Required for: references, rename, document_symbols, completion, diagnostics. Optional for definition (uses query instead).",
    }),
  ),
  line: Type.Optional(
    Type.Number({
      description:
        "1-based line number. Required for: references, rename, completion. Optional for definition (uses query instead).",
    }),
  ),
  character: Type.Optional(
    Type.Number({
      description:
        "1-based column number. Required for: references, rename, completion. Optional for definition (uses query instead).",
    }),
  ),
  new_name: Type.Optional(
    Type.String({ description: "New name for rename action" }),
  ),
  plan_id: Type.Optional(
    Type.String({ description: "From rename, for apply_rename" }),
  ),
  query: Type.Optional(
    Type.String({
      description:
        "Symbol name for definition (when file not provided) or search query for workspace_symbol",
    }),
  ),
});

function requireFile(file?: string): string {
  if (!file) throw new Error("'file' parameter is required for this action");
  return file.startsWith("@") ? file.slice(1) : file;
}

function requirePosition(
  line?: number,
  character?: number,
): { line: number; character: number } {
  if (line === undefined || character === undefined)
    throw new Error(
      "'line' and 'character' parameters are required for this action (1-based)",
    );
  return { line: line - 1, character: character - 1 };
}

function uriToPath(uri: string): string {
  try {
    return fileURLToPath(uri);
  } catch {
    return uri;
  }
}

function rangeContains(range: Range, pos: Position): boolean {
  if (pos.line < range.start.line || pos.line > range.end.line) return false;
  if (pos.line === range.start.line && pos.character < range.start.character)
    return false;
  if (pos.line === range.end.line && pos.character > range.end.character)
    return false;
  return true;
}

function rangeSize(range: Range): number {
  return (
    (range.end.line - range.start.line) * 100000 +
    (range.end.character - range.start.character)
  );
}

function findBestSymbolRange(
  symbols: DocumentSymbol[],
  pos: Position,
): Range | null {
  let best: Range | null = null;

  const visit = (symbol: DocumentSymbol) => {
    const bodyRange = symbol.range ?? symbol.location?.range;
    const selectionRange = symbol.selectionRange ?? symbol.location?.range;
    if (bodyRange && selectionRange && rangeContains(selectionRange, pos)) {
      if (!best || rangeSize(bodyRange) < rangeSize(best)) {
        best = bodyRange;
      }
    }

    for (const child of symbol.children ?? []) {
      visit(child);
    }
  };

  for (const symbol of symbols) {
    visit(symbol);
  }

  return best;
}

function getRangeText(path: string, range: Range): string | null {
  try {
    const lines = readFileSync(path, "utf8").split(/\r?\n/);
    const startLine = lines[range.start.line];
    const endLine = lines[range.end.line];
    if (startLine === undefined || endLine === undefined) return null;

    if (range.start.line === range.end.line) {
      return startLine.slice(range.start.character, range.end.character);
    }

    const parts = [startLine.slice(range.start.character)];
    for (let i = range.start.line + 1; i < range.end.line; i++) {
      parts.push(lines[i] ?? "");
    }
    parts.push(endLine.slice(0, range.end.character));
    return parts.join("\n");
  } catch {
    return null;
  }
}

type SymbolInformation = {
  name: string;
  kind: number;
  location?: Location;
  containerName?: string;
};

export type MatchTier = "exact" | "case-insensitive" | "partial";
const MATCH_TIERS: MatchTier[] = ["exact", "case-insensitive", "partial"];

export interface RankedSymbols {
  tier: MatchTier;
  /** Distinct locations in server order. */
  matches: Array<SymbolInformation & { location: Location }>;
}

function qualifiedNames(symbol: SymbolInformation): string[] {
  const names = [symbol.name];
  if (symbol.containerName) {
    for (const sep of ["::", ".", "/"]) {
      names.push(`${symbol.containerName}${sep}${symbol.name}`);
    }
  }
  return names;
}

function matchTier(symbol: SymbolInformation, query: string): MatchTier | null {
  const names = qualifiedNames(symbol);
  if (names.includes(query)) return "exact";
  const lower = query.toLowerCase();
  if (names.some((name) => name.toLowerCase() === lower)) {
    return "case-insensitive";
  }
  // Partial matching uses the bare name, so a namespace query does not match every member.
  if (symbol.name.toLowerCase().includes(lower)) return "partial";
  return null;
}

function locationKey(location: Location): string {
  const { start, end } = location.range;
  return `${uriToPath(location.uri)}:${start.line}:${start.character}:${end.line}:${end.character}`;
}

/** Return every distinct located symbol in the best non-empty tier: exact, case-insensitive, then partial name. */
export function rankSymbolMatches(
  symbols: unknown,
  query: string,
): RankedSymbols | null {
  const items = (Array.isArray(symbols) ? symbols : []) as SymbolInformation[];
  const byTier = new Map<MatchTier, RankedSymbols["matches"]>();
  const seen = new Set<string>();

  for (const symbol of items) {
    if (typeof symbol?.name !== "string" || !symbol.location?.range) continue;
    const tier = matchTier(symbol, query);
    if (!tier) continue;
    const key = locationKey(symbol.location);
    if (seen.has(key)) continue;
    seen.add(key);
    const tierMatches = byTier.get(tier) ?? [];
    tierMatches.push(symbol as RankedSymbols["matches"][number]);
    byTier.set(tier, tierMatches);
  }

  for (const tier of MATCH_TIERS) {
    const matches = byTier.get(tier);
    if (matches?.length) return { tier, matches };
  }
  return null;
}

/**
 * Position of a symbol's name. Some servers (clojure-lsp) give a workspace
 * symbol the range of its whole form, whose start is not on the name, and a
 * references request there finds nothing.
 */
async function symbolNamePosition(
  client: { documentSymbols(path: string, signal?: AbortSignal): Promise<unknown> },
  path: string,
  location: Location,
  name: string,
  signal?: AbortSignal,
): Promise<Position> {
  const start = location.range.start;
  let raw: unknown;
  try {
    raw = await client.documentSymbols(path, signal);
  } catch {
    return start;
  }
  let best: { body: Range; selection: Range } | null = null;
  const visit = (items: DocumentSymbol[]) => {
    for (const sym of items) {
      const body = sym.range ?? sym.location?.range;
      const selection = sym.selectionRange ?? sym.location?.range;
      if (
        body &&
        selection &&
        sym.name === name &&
        rangeContains(body, start) &&
        (!best || rangeSize(body) < rangeSize(best.body))
      ) {
        best = { body, selection };
      }
      if (sym.children) visit(sym.children);
    }
  };
  visit((Array.isArray(raw) ? raw : []) as DocumentSymbol[]);
  return (best as { selection: Range } | null)?.selection.start ?? start;
}

interface DocumentSymbolSource {
  getOpenDocumentPaths(): string[];
  documentSymbols(path: string, signal?: AbortSignal): Promise<unknown>;
}

/** Flatten the symbols of every open document into located symbol information. */
async function collectOpenDocumentSymbols(
  client: DocumentSymbolSource,
  signal?: AbortSignal,
): Promise<{ symbols: SymbolInformation[]; documents: number }> {
  const paths = client.getOpenDocumentPaths();
  const symbols: SymbolInformation[] = [];

  for (const path of paths) {
    if (signal?.aborted) throw new Error("Aborted");
    let rawSymbols: unknown;
    try {
      rawSymbols = await client.documentSymbols(path, signal);
    } catch {
      continue;
    }

    const visit = (items: DocumentSymbol[], containerName?: string) => {
      for (const sym of items) {
        const nameRange = sym.selectionRange ?? sym.range;
        const location: Location | undefined =
          sym.location ??
          (nameRange
            ? { uri: pathToFileURL(path).href, range: nameRange }
            : undefined);
        if (location) {
          symbols.push({
            name: sym.name,
            kind: sym.kind,
            location,
            ...(containerName ? { containerName } : {}),
          });
        }
        if (sym.children) visit(sym.children, sym.name);
      }
    };
    visit((Array.isArray(rawSymbols) ? rawSymbols : []) as DocumentSymbol[]);
  }

  return { symbols, documents: paths.length };
}

function getLanguageForPath(path: string): string {
  for (const plugin of Object.values(languages)) {
    const id = plugin.languageIdForPath(path);
    if (id) return id;
  }
  return "text";
}

const SKIP_DIRS = new Set([
  "node_modules",
  ".git",
  "dist",
  "build",
  "out",
  "__pycache__",
  ".nix",
  "vendor",
  "claudetmp",
]);

function findSourceFiles(
  rootDir: string,
  extensions: Set<string>,
  maxFiles = 50,
  maxDepth = 5,
): string[] {
  const results: string[] = [];

  function scan(dir: string, depth: number) {
    if (results.length >= maxFiles || depth > maxDepth) return;
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      if (results.length >= maxFiles) return;
      if (entry.isDirectory() && !SKIP_DIRS.has(entry.name)) {
        scan(join(dir, entry.name), depth + 1);
      } else if (entry.isFile()) {
        const ext = extname(entry.name);
        if (extensions.has(ext)) {
          results.push(join(dir, entry.name));
        }
      }
    }
  }

  scan(rootDir, 0);
  return results;
}

function getLineOffsets(content: string): number[] {
  const offsets = [0];
  for (let i = 0; i < content.length; i++) {
    const char = content[i];
    if (char === "\r") {
      if (content[i + 1] === "\n") i++;
      offsets.push(i + 1);
    } else if (char === "\n") {
      offsets.push(i + 1);
    }
  }
  return offsets;
}

function getLineContentEnd(
  content: string,
  lineOffsets: number[],
  line: number,
): number {
  let end =
    line + 1 < lineOffsets.length ? lineOffsets[line + 1] : content.length;
  if (end > 0 && content[end - 1] === "\n") end--;
  if (end > 0 && content[end - 1] === "\r") end--;
  return end;
}

function positionToOffset(
  content: string,
  lineOffsets: number[],
  pos: Position,
): number {
  if (pos.line < 0 || pos.line >= lineOffsets.length) {
    throw new Error(
      `Invalid edit position ${pos.line + 1}:${pos.character + 1}`,
    );
  }

  const lineStart = lineOffsets[pos.line];
  const lineEnd = getLineContentEnd(content, lineOffsets, pos.line);
  const clampedCharacter = Math.min(
    pos.character,
    Math.max(0, lineEnd - lineStart),
  );
  return lineStart + clampedCharacter;
}

function applyTextEdits(
  content: string,
  edits: TextEdit[],
  path: string,
): string {
  const lineOffsets = getLineOffsets(content);
  const normalized = edits.map((edit, index) => {
    const start = positionToOffset(content, lineOffsets, edit.range.start);
    const end = positionToOffset(content, lineOffsets, edit.range.end);
    if (end < start) {
      throw new Error(
        `Invalid edit range in ${path} at ${edit.range.start.line + 1}:${edit.range.start.character + 1}`,
      );
    }
    return { ...edit, start, end, index };
  });

  normalized.sort(
    (a, b) => a.start - b.start || a.end - b.end || a.index - b.index,
  );
  for (let i = 1; i < normalized.length; i++) {
    if (normalized[i].start < normalized[i - 1].end) {
      throw new Error(`Overlapping rename edits in ${path}`);
    }
  }

  normalized.sort(
    (a, b) => b.start - a.start || b.end - a.end || b.index - a.index,
  );

  let updated = content;
  for (const edit of normalized) {
    updated =
      updated.slice(0, edit.start) + edit.newText + updated.slice(edit.end);
  }
  return updated;
}

type ResourceOperation = { kind: string; uri?: string; oldUri?: string; newUri?: string };

function collectWorkspaceEditChanges(edit: WorkspaceEdit): {
  files: Map<string, TextEdit[]>;
  resourceOperations: ResourceOperation[];
} {
  const files = new Map<string, TextEdit[]>();
  const resourceOperations: ResourceOperation[] = [];

  if (edit.changes) {
    for (const [uri, edits] of Object.entries(edit.changes)) {
      files.set(uriToPath(uri), [...edits]);
    }
  }

  for (const change of edit.documentChanges ?? []) {
    if (!("edits" in change)) {
      resourceOperations.push(change);
      continue;
    }
    const path = uriToPath(change.textDocument.uri);
    const existing = files.get(path) ?? [];
    existing.push(...change.edits);
    files.set(path, existing);
  }

  return { files, resourceOperations };
}

async function withFileMutationQueues<T>(
  paths: string[],
  fn: () => Promise<T>,
): Promise<T> {
  const uniquePaths = [...new Set(paths)].sort();

  const run = async (index: number): Promise<T> => {
    if (index >= uniquePaths.length) return fn();
    return withFileMutationQueue(uniquePaths[index], () => run(index + 1));
  };

  return run(0);
}

function sha256(content: string): string {
  return createHash("sha256").update(content, "utf8").digest("hex");
}

function isInside(root: string, path: string): boolean {
  const rel = relative(resolve(root), resolve(path));
  return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
}

function describeResourceOperation(op: ResourceOperation): string {
  const paths = [op.uri, op.oldUri, op.newUri]
    .filter((uri): uri is string => Boolean(uri))
    .map(uriToPath);
  return [op.kind, paths.join(" -> ")].filter(Boolean).join(" ");
}

export interface RenamePlanFile {
  path: string;
  edits: TextEdit[];
  /** SHA-256 of the file content that the preview read; null if it could not be read. */
  fingerprint: string | null;
}

export interface RenamePlan {
  id: string;
  workspaceRoot: string;
  files: RenamePlanFile[];
  resourceOperations: ResourceOperation[];
}

export interface WorkspaceEditDiff {
  path: string;
  editCount: number;
  diff: string;
}

export interface RenamePreview {
  plan: RenamePlan;
  /** Reasons that apply will refuse the plan; empty when it can be applied. */
  problems: string[];
  summary: string;
  text: string;
  files: WorkspaceEditDiff[];
}

export interface AppliedWorkspaceEdit {
  summary: string;
  diff: string;
  files: WorkspaceEditDiff[];
}

function formatRange(range: Range): string {
  return `${range.start.line + 1}:${range.start.character + 1}-${range.end.line + 1}:${range.end.character + 1}`;
}

/**
 * Check a plan against the current file contents and compute each file's new
 * content. Returns the refusal reasons; `next` is complete only when there are none.
 */
function checkRenamePlan(
  plan: RenamePlan,
  read: (path: string) => string | null,
): {
  problems: string[];
  next: Map<string, { oldContent: string; newContent: string; editCount: number }>;
} {
  const problems: string[] = [];
  const next = new Map<string, { oldContent: string; newContent: string; editCount: number }>();

  for (const op of plan.resourceOperations) {
    problems.push(`Resource operation not supported: ${describeResourceOperation(op)}`);
  }

  for (const file of plan.files) {
    if (!isInside(plan.workspaceRoot, file.path)) {
      problems.push(`Edit target is outside the workspace root ${plan.workspaceRoot}: ${file.path}`);
      continue;
    }
    const content = read(file.path);
    if (content === null || file.fingerprint === null) {
      problems.push(`Cannot read ${file.path}`);
      continue;
    }
    if (sha256(content) !== file.fingerprint) {
      problems.push(`File changed since the preview: ${file.path}`);
      continue;
    }
    try {
      next.set(file.path, {
        oldContent: content,
        newContent: applyTextEdits(content, file.edits, file.path),
        editCount: file.edits.length,
      });
    } catch (error) {
      problems.push(error instanceof Error ? error.message : String(error));
    }
  }

  return { problems, next };
}

function readOrNull(path: string): string | null {
  try {
    return readFileSync(path, "utf8");
  } catch {
    return null;
  }
}

function diffsFor(
  next: Map<string, { oldContent: string; newContent: string; editCount: number }>,
): WorkspaceEditDiff[] {
  return [...next.entries()]
    .filter(([, c]) => c.oldContent !== c.newContent)
    .map(([path, c]) => ({
      path,
      editCount: c.editCount,
      diff: generateDiffString(c.oldContent, c.newContent).diff,
    }));
}

/** Fingerprint the target files and describe every edit. Writes no file. */
export function previewRename(
  edit: WorkspaceEdit,
  workspaceRoot: string,
  id: string = randomBytes(4).toString("hex"),
): RenamePreview {
  const { files, resourceOperations } = collectWorkspaceEditChanges(edit);
  const plan: RenamePlan = {
    id,
    workspaceRoot,
    resourceOperations,
    files: [...files.entries()].map(([path, edits]) => {
      const content = readOrNull(path);
      return { path, edits, fingerprint: content === null ? null : sha256(content) };
    }),
  };

  const { problems, next } = checkRenamePlan(plan, readOrNull);
  const diffs = diffsFor(next);
  const editCount = plan.files.reduce((total, file) => total + file.edits.length, 0);
  const summary = `Rename plan ${id}: ${editCount} edit(s) in ${plan.files.length} file(s); no file written.`;
  const sections = plan.files.map((file) => {
    const ranges = file.edits.map((e) => formatRange(e.range)).join(", ");
    const diff = diffs.find((d) => d.path === file.path)?.diff;
    return `${file.path} (${file.edits.length} edit(s): ${ranges}):${diff ? `\n${diff}` : ""}`;
  });
  const next_step =
    problems.length === 0
      ? `Apply with action "apply_rename" and plan_id "${id}". The plan is single-use and is refused if a target file changes first.`
      : `apply_rename will refuse this plan:\n${problems.map((p) => `- ${p}`).join("\n")}`;
  return {
    plan,
    problems,
    summary,
    text: [summary, next_step, ...sections].join("\n\n"),
    files: diffs,
  };
}

interface DocumentRefresher {
  refreshDocument(path: string): Promise<unknown>;
}

/**
 * Apply a previewed plan through the pi file-mutation queues. Re-checks every
 * target inside the queues and writes nothing unless all checks pass.
 */
export async function applyRenamePlan(
  plan: RenamePlan,
  client: DocumentRefresher,
): Promise<AppliedWorkspaceEdit> {
  const paths = plan.files.map((file) => file.path);

  return withFileMutationQueues(paths, async () => {
    const { problems, next } = checkRenamePlan(plan, readOrNull);
    if (problems.length > 0) {
      throw new Error(
        `Rename plan ${plan.id} refused; no file written:\n${problems.map((p) => `- ${p}`).join("\n")}`,
      );
    }

    const files = diffsFor(next);
    for (const file of files) {
      writeFileSync(file.path, next.get(file.path)!.newContent, "utf8");
      invalidateFilePreview(file.path);
    }
    for (const file of files) {
      await client.refreshDocument(file.path);
    }

    if (files.length === 0) {
      return { summary: "No rename changes generated.", diff: "No rename changes generated.", files };
    }
    const editCount = files.reduce((total, file) => total + file.editCount, 0);
    const summary = `Applied ${editCount} edit(s) in ${files.length} file(s).`;
    return {
      summary,
      diff: `${summary}\n\n${files.map((file) => `${file.path}:\n${file.diff}`).join("\n\n")}`,
      files,
    };
  });
}

export function buildLspToolResult(
  resultText: string,
  language: string,
  action: Action,
  summary: string,
) {
  let text = truncateHead(resultText, {
    maxLines: DEFAULT_MAX_LINES,
    maxBytes: DEFAULT_MAX_BYTES,
  }).content;
  if (text !== resultText) {
    // Reserve room for the notice so that the disclosed output stays within the normal bounds.
    const totalLines = resultText.split("\n").length;
    const totalBytes = Buffer.byteLength(resultText, "utf8");
    const kept = truncateHead(resultText, {
      maxLines: DEFAULT_MAX_LINES - 2,
      maxBytes: DEFAULT_MAX_BYTES - 200,
    });
    text = `${kept.content}\n\n[Output truncated: ${kept.outputLines} of ${totalLines} lines, ${kept.outputBytes} of ${totalBytes} bytes shown.]`;
  }

  return {
    content: [{ type: "text" as const, text }],
    details: {
      language,
      action,
      summary,
      ...(action === "rename" || action === "apply_rename"
        ? { diff: text }
        : {}),
    },
  };
}

type SymbolClientFor = (
  path: string,
) => Promise<{ documentSymbols(path: string): Promise<unknown> }>;

async function formatDefinitionWithImplementation(
  result: unknown,
  clientFor: SymbolClientFor,
): Promise<string> {
  if (!result) return "No definition found.";

  const locations = Array.isArray(result) ? result : [result];
  if (locations.length === 0) return "No definition found.";

  const symbolCache = new Map<string, DocumentSymbol[]>();
  const sections: string[] = [];
  const seen = new Set<string>();

  for (const location of locations as Array<Location | LocationLink>) {
    let path: string;
    let bodyRange: Range | null = null;
    let selectionPos: Position;

    if ("targetUri" in location) {
      path = uriToPath(location.targetUri);
      bodyRange = location.targetRange;
      selectionPos = location.targetSelectionRange.start;
    } else {
      path = uriToPath(location.uri);
      selectionPos = location.range.start;
    }

    if (!bodyRange) {
      let symbols = symbolCache.get(path);
      if (!symbols) {
        const client = await clientFor(path);
        const rawSymbols = (await client.documentSymbols(
          path,
        )) as DocumentSymbol[];
        symbols = Array.isArray(rawSymbols) ? rawSymbols : [];
        symbolCache.set(path, symbols);
      }
      bodyRange = findBestSymbolRange(symbols, selectionPos);
    }

    const range = bodyRange ?? { start: selectionPos, end: selectionPos };
    const key = `${path}:${range.start.line}:${range.start.character}:${range.end.line}:${range.end.character}`;
    if (seen.has(key)) continue;
    seen.add(key);

    const text = bodyRange ? getRangeText(path, bodyRange) : null;
    if (text) {
      sections.push(
        `${path}:${bodyRange.start.line + 1}\n\n\`\`\`${getLanguageForPath(path)}\n${text}\n\`\`\``,
      );
    } else {
      sections.push(`${path}:${selectionPos.line + 1}`);
    }
  }

  return sections.join("\n\n");
}

/** Scan bound for by-name lookups; see the known-limit note in resolveSymbolLocations. */
const SOURCE_SCAN_MAX_FILES = 50;
const SOURCE_SCAN_MAX_DEPTH = 5;

export interface LspExtensionOptions {
  /** Server factory; tests inject controlled clients. */
  createClient?: (
    language: string,
    plugin: LanguagePlugin,
    workspaceRoot: string,
  ) => LspClient;
}

function requireLanguage(language: string): LanguagePlugin {
  const plugin = languages[language];
  if (!plugin) {
    throw new Error(
      `Unsupported language: ${language}. Supported: ${getSupportedLanguages().join(", ")}`,
    );
  }
  return plugin;
}

/** Resolve a supplied file against the session cwd and reject it before any server starts if the language does not own its extension. */
function resolveLanguageFile(
  plugin: LanguagePlugin,
  cwd: string,
  file: string,
): string {
  const path = resolve(cwd, file.startsWith("@") ? file.slice(1) : file);
  if (!plugin.languageIdForPath(path)) {
    throw new Error(
      `File ${file} does not match language ${plugin.languageId} (extensions: ${[...plugin.fileExtensions].join(", ")})`,
    );
  }
  return path;
}

export default function (pi: ExtensionAPI, options: LspExtensionOptions = {}) {
  const servers = new Map<string, LspClient>();
  /** Previewed renames, in memory for this session only; each is removed on its first apply attempt. */
  const renamePlans = new Map<
    string,
    { plan: RenamePlan; language: string; serverKey: string }
  >();
  const createClient =
    options.createClient ??
    ((language, plugin, workspaceRoot) =>
      new LspClient(
        language,
        plugin.getConfig(workspaceRoot),
        languages,
        plugin.createIndexingTracker?.(),
      ));

  function getWorkspaceRoot(
    language: string,
    ctx: ExtensionContext,
    file?: string,
  ): string {
    const startDir = file
      ? dirname(resolve(ctx.cwd, file.startsWith("@") ? file.slice(1) : file))
      : ctx.cwd;

    const plugin = languages[language];
    if (plugin) return plugin.getWorkspaceRoot(startDir, ctx);
    return ctx.cwd;
  }

  async function getServer(
    language: string,
    workspaceRoot: string,
  ): Promise<LspClient> {
    const plugin = requireLanguage(language);
    const key = `${plugin.serverId}:${workspaceRoot}`;
    let client = servers.get(key);
    if (client?.isRunning()) return client;

    client = createClient(language, plugin, workspaceRoot);
    servers.set(key, client);
    await client.start();
    return client;
  }

  async function awaitOpenIndexing(
    client: LspClient,
    plugin: LanguagePlugin,
    fresh: boolean,
    signal?: AbortSignal,
  ): Promise<void> {
    if (fresh && plugin.indexOnOpenGraceMs) {
      await client.waitForIndexingAfterOpen(plugin.indexOnOpenGraceMs, 30000, signal);
    }
  }

  /** Open a bounded sample of the workspace once, so that servers without a project index can answer by-name lookups. */
  async function openSourceSample(
    client: LspClient,
    plugin: LanguagePlugin,
    workspaceRoot: string,
    signal?: AbortSignal,
  ): Promise<void> {
    if (client.openDocumentCount() > 0) return;
    const files = findSourceFiles(
      workspaceRoot,
      plugin.fileExtensions,
      SOURCE_SCAN_MAX_FILES,
      SOURCE_SCAN_MAX_DEPTH,
    );
    for (const f of files) {
      if (signal?.aborted) throw new Error("Aborted");
      await client.ensureDocumentOpen(f, plugin.languageIdForPath(f) ?? undefined);
    }
    await awaitOpenIndexing(client, plugin, true, signal);
  }

  /** Server for the workspace that owns `path`, with the document open under the requested language's ID. */
  async function clientForPath(
    language: string,
    ctx: ExtensionContext,
    path: string,
    signal?: AbortSignal,
  ): Promise<LspClient> {
    const plugin = requireLanguage(language);
    const root = getWorkspaceRoot(language, ctx, path);
    const client = await getServer(language, root);
    await openSourceSample(client, plugin, root, signal);
    await client.ensureDocumentOpen(path, plugin.languageIdForPath(path) ?? undefined);
    return client;
  }

  async function resolveSymbolLocations(
    language: string,
    ctx: ExtensionContext,
    query: string,
    signal?: AbortSignal,
  ): Promise<{ ranked: RankedSymbols; note?: string }> {
    const plugin = requireLanguage(language);
    const workspaceRoot = getWorkspaceRoot(language, ctx);
    const client = await getServer(language, workspaceRoot);
    await openSourceSample(client, plugin, workspaceRoot, signal);

    let ranked: RankedSymbols | null = null;
    try {
      ranked = rankSymbolMatches(await client.workspaceSymbol(query, signal), query);
    } catch {
      // workspace/symbol may fail without project config (e.g. tsserver)
    }
    if (ranked) return { ranked };

    // known-limit: the fallback sees only open documents, at most the bounded
    // source sample plus files opened by earlier requests. Revisit if lookups
    // must be workspace-complete without a server index.
    const { symbols, documents } = await collectOpenDocumentSymbols(client, signal);
    ranked = rankSymbolMatches(symbols, query);
    if (!ranked) throw new Error(`Symbol not found: ${query}`);
    return {
      ranked,
      note: `Workspace symbol search found no match; searched ${documents} open document(s) (scan limit ${SOURCE_SCAN_MAX_FILES} files, depth ${SOURCE_SCAN_MAX_DEPTH}). Other matches can exist.`,
    };
  }

  pi.on("session_shutdown", async () => {
    for (const [, client] of servers) {
      await client.stop().catch(() => {});
    }
    servers.clear();
    renamePlans.clear();
    invalidateFilePreview();
  });

  pi.registerTool({
    name: "lsp",
    label: "LSP",
    description: `Language Server Protocol tool. Starts LSP servers on demand and provides editor-like features: go-to-definition, find references, rename symbols, list document/workspace symbols, completions, and diagnostics. Supported languages: ${getSupportedLanguages().join(", ")}. Line and character numbers are 1-based. Both 'definition' and 'references' support 'query' to look up symbols by name without file/position. rename previews edits; apply_rename applies them. Pass .h as c or cpp.`,
    promptSnippet:
      `LSP operations (definition, references, rename, symbols, diagnostics) for ${getSupportedLanguages().join(", ")}`,
    promptGuidelines: [
      "Use lsp for code-symbol lookup and rename instead of plain text search when language support is available.",
      "Use the 'query' parameter on definition/references to look up symbols by name without a file/position.",
      "Use references before rename; line and character positions are 1-based.",
    ],
    parameters: LspParams,

    renderCall(args, theme) {
      let text = theme.fg("toolTitle", theme.bold("lsp "));
      text += theme.fg("accent", `${args.language} `);
      text += theme.fg("muted", args.action);

      if (args.file) {
        text += theme.fg("dim", ` ${args.file}`);
      }

      if (args.line !== undefined && args.character !== undefined) {
        text += theme.fg("dim", `:${args.line}:${args.character}`);
      }

      if (args.new_name) {
        text += theme.fg("dim", ` -> ${args.new_name}`);
      }

      if (args.query) {
        text += theme.fg("dim", ` ${JSON.stringify(args.query)}`);
      }

      return new Text(text, 0, 0);
    },

    async execute(_toolCallId, params, signal, onUpdate, ctx) {
      const { language, action } = params;

      if (action === "apply_rename") {
        if (!params.plan_id)
          throw new Error("'plan_id' parameter is required for apply_rename");
        const entry = renamePlans.get(params.plan_id);
        if (!entry)
          throw new Error(
            `Unknown or already used rename plan: ${params.plan_id}. Run rename again to get a new plan.`,
          );
        renamePlans.delete(params.plan_id);
        const applied = await applyRenamePlan(entry.plan, {
          refreshDocument: async (path) => {
            const server = servers.get(entry.serverKey);
            if (server?.isRunning()) await server.refreshDocument(path);
          },
        });
        return buildLspToolResult(
          applied.diff,
          entry.language,
          "apply_rename",
          applied.summary,
        );
      }

      const plugin = requireLanguage(language);
      const filePath = params.file
        ? resolveLanguageFile(plugin, ctx.cwd, params.file)
        : undefined;
      const workspaceRoot = getWorkspaceRoot(language, ctx, filePath);

      onUpdate?.({
        content: [
          {
            type: "text",
            text: `Starting LSP for ${language}...`,
          },
        ],
        details: {},
      });

      let client: LspClient;
      try {
        client = await getServer(language, workspaceRoot);
      } catch (e: unknown) {
        throw new Error(
          `Failed to start ${language} LSP server: ${e instanceof Error ? e.message : String(e)}`,
        );
      }

      await client.waitForIndexing(30000, signal);
      if (filePath) {
        const fresh = client.openDocumentCount() === 0;
        await client.ensureDocumentOpen(
          filePath,
          plugin.languageIdForPath(filePath) ?? undefined,
        );
        await awaitOpenIndexing(client, plugin, fresh, signal);
      }

      onUpdate?.({
        content: [
          {
            type: "text",
            text: `Running ${action}...`,
          },
        ],
        details: {},
      });

      let resultText: string;
      let renameSummary: string | undefined;

      switch (action as Action) {
        case "definition": {
          if (params.file) {
            const file = requireFile(filePath);
            const pos = requirePosition(params.line, params.character);
            const result = await client.definition(
              file,
              pos.line,
              pos.character,
              signal,
            );
            resultText = await formatDefinitionWithImplementation(
              result,
              async () => client,
            );
          } else {
            if (!params.query)
              throw new Error(
                "'query' (symbol name) is required for definition when 'file' is not provided",
              );
            const { ranked, note } = await resolveSymbolLocations(
              language,
              ctx,
              params.query,
              signal,
            );
            const body = await formatDefinitionWithImplementation(
              ranked.matches.map((match) => match.location),
              (path) => clientForPath(language, ctx, path, signal),
            );
            const count = ranked.matches.length;
            const header =
              count > 1
                ? `${count} definitions match ${JSON.stringify(params.query)} (${ranked.tier} name):\n\n`
                : "";
            resultText = `${header}${body}${note ? `\n\n${note}` : ""}`;
          }
          break;
        }

        case "references": {
          if (params.file) {
            const file = requireFile(filePath);
            const pos = requirePosition(params.line, params.character);
            const result = await client.references(
              file,
              pos.line,
              pos.character,
              true,
              signal,
            );
            resultText = formatReferences(result);
          } else {
            if (!params.query)
              throw new Error(
                "'query' (symbol name) is required for references when 'file' is not provided",
              );
            const { ranked, note } = await resolveSymbolLocations(
              language,
              ctx,
              params.query,
              signal,
            );
            // Single-target policy: references follow the first best-ranked
            // symbol only, so unrelated same-named symbols are never merged.
            const { location } = ranked.matches[0];
            const refClient = await clientForPath(
              language,
              ctx,
              uriToPath(location.uri),
              signal,
            );
            const namePos = await symbolNamePosition(
              refClient,
              uriToPath(location.uri),
              location,
              ranked.matches[0].name,
              signal,
            );
            const result = await refClient.references(
              uriToPath(location.uri),
              namePos.line,
              namePos.character,
              true,
              signal,
            );
            const others = ranked.matches.length - 1;
            const notes = [
              ...(others > 0
                ? [
                    `References are for ${uriToPath(location.uri)}:${location.range.start.line + 1}; ${others} other symbol(s) match. Give file, line and character to select one.`,
                  ]
                : []),
              ...(note ? [note] : []),
            ];
            resultText = [formatReferences(result), ...notes].join("\n\n");
          }
          break;
        }

        case "rename": {
          const file = requireFile(filePath);
          const pos = requirePosition(params.line, params.character);
          if (!params.new_name)
            throw new Error("'new_name' parameter is required for rename");
          const result = (await client.rename(
            file,
            pos.line,
            pos.character,
            params.new_name,
            signal,
          )) as WorkspaceEdit | null;
          const preview = result
            ? previewRename(result, workspaceRoot)
            : null;
          if (
            !preview ||
            (preview.plan.files.length === 0 &&
              preview.plan.resourceOperations.length === 0)
          ) {
            resultText = "No rename changes generated.";
            break;
          }
          renamePlans.set(preview.plan.id, {
            plan: preview.plan,
            language,
            serverKey: `${plugin.serverId}:${workspaceRoot}`,
          });
          resultText = preview.text;
          renameSummary = preview.summary;
          break;
        }

        case "document_symbols": {
          const file = requireFile(filePath);
          const result = await client.documentSymbols(file, signal);
          resultText = formatDocumentSymbols(result);
          break;
        }

        case "workspace_symbol": {
          const result = await client.workspaceSymbol(
            params.query ?? "",
            signal,
          );
          resultText = formatWorkspaceSymbol(result);
          break;
        }

        case "completion": {
          const file = requireFile(filePath);
          const pos = requirePosition(params.line, params.character);
          const result = await client.completion(
            file,
            pos.line,
            pos.character,
            signal,
          );
          resultText = formatCompletion(result);
          break;
        }

        case "diagnostics": {
          const file = requireFile(filePath);
          const diags = await client.getDiagnostics(file, signal);
          resultText = formatDiagnostics(diags);
          break;
        }

        default:
          throw new Error(
            `Unknown action: ${action}. Supported: ${ACTIONS.join(", ")}`,
          );
      }

      const lines = resultText.split("\n");
      const headerLine = lines[0] ?? "";
      let inCodeBlock = false;
      const sigLines: string[] = [];
      for (const line of lines.slice(1)) {
        const trimmed = line.trim();
        if (trimmed.startsWith("``")) {
          if (inCodeBlock) break;
          inCodeBlock = true;
          continue;
        }
        if (!inCodeBlock) continue;
        if (!trimmed) continue;
        sigLines.push(line);
        if (
          trimmed.endsWith("{") ||
          trimmed.endsWith(")") ||
          trimmed.endsWith(";") ||
          trimmed.endsWith(":")
        )
          break;
      }
      const summary =
        renameSummary ??
        (sigLines.length > 0
          ? headerLine + "\n" + sigLines.join("\n")
          : headerLine);

      return buildLspToolResult(resultText, language, action, summary);
    },

    renderResult(result, options, theme) {
      const textContent = result.content
        .filter((c): c is { type: "text"; text: string } => c.type === "text")
        .map((c) => c.text)
        .join("");

      const details = result.details as { summary?: string; diff?: string };
      if (!options.expanded) {
        const summary = details.summary ?? textContent.split("\n")[0] ?? "";
        return new Text(theme.fg("toolOutput", summary), 0, 0);
      }

      return new Text(
        theme.fg("toolOutput", details.diff ?? textContent),
        0,
        0,
      );
    },
  });

  pi.registerCommand("lsp-status", {
    description: "Show running LSP servers",
    handler: async (_args, ctx) => {
      if (servers.size === 0) {
        ctx.ui.notify("No LSP servers running", "info");
        return;
      }
      const lines: string[] = [];
      for (const [key, client] of servers) {
        const status = client.isRunning() ? "running" : "stopped";
        lines.push(`  ${key}: ${status}`);
      }
      ctx.ui.notify(`LSP servers:\n${lines.join("\n")}`, "info");
    },
  });

  pi.registerCommand("lsp-stop", {
    description: "Stop LSP servers (optionally specify language)",
    handler: async (args, ctx) => {
      if (args) {
        const matching = [...servers.entries()].filter(
          ([key]) => {
            const serverId = languages[args]?.serverId ?? args;
            return key === serverId || key.startsWith(`${serverId}:`);
          },
        );
        if (matching.length > 0) {
          for (const [key, client] of matching) {
            await client.stop().catch(() => {});
            servers.delete(key);
          }
          ctx.ui.notify(
            `Stopped ${matching.length} ${args} LSP server(s)`,
            "info",
          );
          return;
        }
      }

      for (const [, client] of servers) {
        await client.stop().catch(() => {});
      }
      servers.clear();
      ctx.ui.notify("Stopped all LSP servers", "info");
    },
  });
}
