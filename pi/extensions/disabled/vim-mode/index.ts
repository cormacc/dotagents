/**
 * vim-mode extension for pi — Vim-style modal editor.
 *
 * Single-responsibility: when this extension is loaded, the modal
 * editor is installed unconditionally. There is no runtime toggle.
 * To disable, remove the extension directory (or in dotfiles, comment
 * out / Nix-toggle its inclusion).
 *
 * Modes: Insert (default), Normal, Visual, Visual-Line.
 *
 * Bare leader keys in Normal mode delegate to the sibling
 * `leader-menu` extension via a `leader-menu:open` event — the modal
 * grammar itself contains no leader-menu state. The set of
 * recognised leader keys is sourced from `leader-menu:keys-resolved`
 * so user-reconfigured leaders take effect without code changes here.
 *
 * Settings: none of its own. The shared `debug` flag (per-keypress
 * logger) lives in `~/.pi/agent/leader-menu.json` and is read on
 * editor construction. See `loadDebugFlag()` near the entry point.
 */

import { type ExtensionAPI } from "@mariozechner/pi-coding-agent";
import {
  matchesKey,
  truncateToWidth,
  visibleWidth,
  type EditorTheme,
  type TUI,
} from "@mariozechner/pi-tui";
import type { KeybindingsManager } from "@mariozechner/pi-coding-agent";
// Path matches the plan acceptance criteria and is correct after
// re-enable (when this directory becomes `extensions/vim-mode/`).
// While vim-mode lives in `disabled/`, the file is not loaded so the
// import doesn't have to resolve at runtime.
import { SubmitterEditor } from "../lib/editor.js";
// vim-mode no longer maintains its own settings file. The only flag
// that survived the toggle removal (`debug`) lives in
// `~/.pi/agent/leader-menu.json` alongside the leader-key config; see
// `loadDebugFlag()` near the entry point.
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";

// ─── Vim normal-mode reserved keys ──────────────────────────────────────────
//
// Single source of truth for the keys consumed by VimEditor's
// normal-mode dispatcher. Used by the `leader-menu:keys-resolved`
// subscriber to warn when a user-configured leader trigger key would
// be silently swallowed by this grammar. Other extensions (notably
// `leader-menu`) deliberately do not duplicate this set — the
// detection lives here because vim-mode owns its grammar.
const VIM_NORMAL_RESERVED_KEYS = new Set<string>([
  // motions
  "h", "l", "j", "k", "w", "W", "e", "E", "b", "B", "0", "$", "^",
  // mode switches
  "i", "a", "I", "A", "o", "O", "v", "V",
  // operators
  "d", "c", "y", ">", "<",
  // find character
  "f", "F", "t", "T",
  // repeat find
  ";", ",",
  // single-key commands
  "x", "X", "r", "s", "S", "p", "P", "u", ".", "J", "~", "D", "C", "Y",
]);

function displayLeaderKey(key: string): string {
  if (key === " ") return "SPC";
  if (key === "\t") return "TAB";
  return key;
}

// ─── Types ───────────────────────────────────────────────────────────────────

type Mode = "normal" | "insert" | "visual" | "visual-line";

interface EditorState {
  lines: string[];
  cursorLine: number;
  cursorCol: number;
}

/** A position in the editor */
interface Pos {
  line: number;
  col: number;
}

/** A range defined by two positions (inclusive of start, exclusive of end for char ops) */
interface Range {
  start: Pos;
  end: Pos;
  linewise: boolean;
}

/** Describes a repeatable change */
interface Recordable {
  /** The raw key sequence that produced this change */
  keys: string;
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function clamp(val: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, val));
}

/** Is the character a "word" character (alphanumeric or underscore)? */
function isWordChar(ch: string): boolean {
  return /[\w]/.test(ch);
}

/** Is the character whitespace? */
function isWhitespace(ch: string): boolean {
  return /\s/.test(ch);
}

/** Is the character a symbol/punctuation (not word and not whitespace)? */
function isSymbol(ch: string): boolean {
  return !isWordChar(ch) && !isWhitespace(ch);
}

/** Compare two positions: negative if a < b, 0 if equal, positive if a > b */
function comparePos(a: Pos, b: Pos): number {
  if (a.line !== b.line) return a.line - b.line;
  return a.col - b.col;
}

function minPos(a: Pos, b: Pos): Pos {
  return comparePos(a, b) <= 0 ? a : b;
}

function maxPos(a: Pos, b: Pos): Pos {
  return comparePos(a, b) >= 0 ? a : b;
}

// ─── The Modal Editor ────────────────────────────────────────────────────────

// VimEditor inherits one-step slash-command submission via the
// shared SubmitterEditor base in `extensions/lib/editor.ts`. Keep
// VimEditor a SubmitterEditor subclass so `command:` leader chords
// continue to dispatch in a single keystroke when vim-mode is loaded
// alongside leader-menu. See the cross-extension contract in
// editor.ts and design/log/2026-05-05-leader-menu-slash-submission.org.
class VimEditor extends SubmitterEditor {
  private mode: Mode = "insert";
  private _tui: TUI;
  private _theme: EditorTheme;

  // Operator-pending state
  private pendingOperator: string | null = null;

  // Numeric count accumulator
  private countStr: string = "";

  // f/t/F/T awaiting char
  private pendingFind: { type: "f" | "F" | "t" | "T" } | null = null;
  // Last f/t for ; and , repeat
  private lastFind: { type: "f" | "F" | "t" | "T"; char: string } | null = null;

  // r awaiting char
  private pendingReplace: boolean = false;

  // Leader-menu chord delegation. The modal grammar itself contains no
  // leader-menu state — bare leader keys in Normal mode are forwarded
  // to the sibling `leader-menu` extension via a `leader-menu:open`
  // event. Both the dispatch callback and the set of recognised
  // leader keys are captured at construction-time / via the
  // `leader-menu:keys-resolved` event — no long-lived ExtensionAPI
  // reference is needed.
  private emitLeaderOpen: ((rootKey: string) => void) | null = null;
  /** Trigger keys recognised as leader chords in Normal mode. Updated
   *  by the entry point in response to `leader-menu:keys-resolved`. */
  private leaderKeys: Set<string> = new Set([" ", ","]);

  // Visual mode anchor
  private visualAnchor: Pos = { line: 0, col: 0 };

  // External width constraint (e.g. from git-diff side panel)
  private widthConstraintFraction: number = 0;
  private widthConstraintMinCols: number = 0;

  // Vim register (yank buffer)
  private register: string = "";
  private registerLinewise: boolean = false;

  // Dot-repeat
  private lastChange: string | null = null;
  private recording: boolean = false;
  private recordBuffer: string = "";
  private replaying: boolean = false;

  // Extension context for leader commands
  private ctx: any = null;

  constructor(tui: TUI, theme: EditorTheme, keybindings: KeybindingsManager) {
    super(tui, theme, keybindings);
    this._tui = tui;
    this._theme = theme;
  }

  setContext(ctx: any) {
    this.ctx = ctx;
    this.updateStatus();
  }

  /** Wire up the cross-extension hook for bare-leader chords in Normal mode. */
  setLeaderOpenEmitter(emit: (rootKey: string) => void): void {
    this.emitLeaderOpen = emit;
  }

  /** Update the leader-key set used for bare-leader detection in Normal mode. */
  setLeaderKeys(keys: Iterable<string>): void {
    const next = new Set<string>();
    for (const k of keys) {
      if (k && k.length > 0) next.add(k);
    }
    if (next.size === 0) {
      // Defensive: keep the defaults so we don't silently disable
      // leader chords if leader-menu emits an empty set.
      next.add(" ");
      next.add(",");
    }
    this.leaderKeys = next;
  }

  private updateStatus(): void {
    if (!this.ctx?.ui) return;
    const label = this.mode === "visual-line" ? "VISUAL-LINE" : this.mode.toUpperCase();
    this.ctx.ui.setStatus("vim-mode", label);
  }

  /** [DEBUG] When true, every key press is reported via ctx.ui.notify. */
  private debugEnabled: boolean = false;

  /** [DEBUG] Toggle the per-keypress notification logger. */
  setDebugEnabled(enabled: boolean): void {
    this.debugEnabled = enabled;
  }

  /** Reserve a fraction of terminal width for side panels. */
  setWidthConstraint(fraction: number, minCols: number = 0) {
    this.widthConstraintFraction = Math.max(0, fraction);
    this.widthConstraintMinCols = Math.max(0, minCols);
    this.invalidate();
  }

  // ─── State access (private fields of parent Editor) ───────────────────

  private get st(): EditorState {
    return (this as any).state;
  }

  private pushUndo(): void {
    (this as any).pushUndoSnapshot();
  }

  private doSetCursorCol(col: number): void {
    (this as any).setCursorCol(col);
  }

  private triggerOnChange(): void {
    if ((this as any).onChange) {
      (this as any).onChange(this.getText());
    }
  }

  /** Set cursor position directly */
  private setCursor(line: number, col: number): void {
    const st = this.st;
    st.cursorLine = clamp(line, 0, st.lines.length - 1);
    const lineLen = st.lines[st.cursorLine]?.length ?? 0;
    // In normal mode, cursor can't be past last char (but can be at col 0 on empty line)
    const maxCol = this.mode === "insert" ? lineLen : Math.max(0, lineLen - 1);
    this.doSetCursorCol(clamp(col, 0, maxCol));
  }

  /** Ensure cursor is within bounds for current mode */
  private clampCursor(): void {
    this.setCursor(this.st.cursorLine, this.st.cursorCol);
  }

  private lineText(line: number): string {
    return this.st.lines[line] ?? "";
  }

  private curLine(): string {
    return this.lineText(this.st.cursorLine);
  }

  private curPos(): Pos {
    return { line: this.st.cursorLine, col: this.st.cursorCol };
  }

  // ─── Text mutation helpers ────────────────────────────────────────────

  /** Delete text in a range, return the deleted text. Caller must pushUndo first. */
  private deleteRange(range: Range): string {
    const st = this.st;
    let deleted: string;

    if (range.linewise) {
      const startLine = range.start.line;
      const endLine = range.end.line;
      const deletedLines = st.lines.splice(startLine, endLine - startLine + 1);
      deleted = deletedLines.join("\n") + "\n";
      if (st.lines.length === 0) st.lines = [""];
      st.cursorLine = clamp(startLine, 0, st.lines.length - 1);
      // Move to first non-blank
      const line = this.lineText(st.cursorLine);
      const firstNonBlank = line.search(/\S/);
      this.doSetCursorCol(firstNonBlank >= 0 ? firstNonBlank : 0);
    } else {
      const { start, end } = range;
      if (start.line === end.line) {
        const line = st.lines[start.line] ?? "";
        deleted = line.slice(start.col, end.col);
        st.lines[start.line] = line.slice(0, start.col) + line.slice(end.col);
      } else {
        const firstLine = st.lines[start.line] ?? "";
        const lastLine = st.lines[end.line] ?? "";
        const firstPart = firstLine.slice(0, start.col);
        deleted = firstLine.slice(start.col);
        for (let i = start.line + 1; i < end.line; i++) {
          deleted += "\n" + (st.lines[i] ?? "");
        }
        deleted += "\n" + lastLine.slice(0, end.col);
        const lastPart = lastLine.slice(end.col);
        st.lines[start.line] = firstPart + lastPart;
        st.lines.splice(start.line + 1, end.line - start.line);
      }
      st.cursorLine = start.line;
      this.doSetCursorCol(start.col);
    }

    if (st.lines.length === 0) st.lines = [""];
    this.triggerOnChange();
    this.invalidate();
    return deleted;
  }

  /** Insert text at a position. Returns the end position after insertion. */
  private insertTextAt(pos: Pos, text: string): Pos {
    const st = this.st;
    const line = st.lines[pos.line] ?? "";
    const before = line.slice(0, pos.col);
    const after = line.slice(pos.col);
    const insertLines = text.split("\n");

    if (insertLines.length === 1) {
      st.lines[pos.line] = before + insertLines[0] + after;
      return { line: pos.line, col: pos.col + insertLines[0].length };
    } else {
      st.lines[pos.line] = before + insertLines[0];
      const middleLines = insertLines.slice(1, -1);
      const lastInsert = insertLines[insertLines.length - 1];
      const newLines = [...middleLines, lastInsert + after];
      st.lines.splice(pos.line + 1, 0, ...newLines);
      return {
        line: pos.line + insertLines.length - 1,
        col: lastInsert.length,
      };
    }
  }

  /** Get the text within a range */
  private getTextInRange(range: Range): string {
    const st = this.st;
    if (range.linewise) {
      const lines = st.lines.slice(range.start.line, range.end.line + 1);
      return lines.join("\n") + "\n";
    }
    const { start, end } = range;
    if (start.line === end.line) {
      return (st.lines[start.line] ?? "").slice(start.col, end.col);
    }
    let text = (st.lines[start.line] ?? "").slice(start.col);
    for (let i = start.line + 1; i < end.line; i++) {
      text += "\n" + (st.lines[i] ?? "");
    }
    text += "\n" + (st.lines[end.line] ?? "").slice(0, end.col);
    return text;
  }

  /** Indent lines in range */
  private indentLines(
    startLine: number,
    endLine: number,
    dedent: boolean,
  ): void {
    const st = this.st;
    for (let i = startLine; i <= endLine && i < st.lines.length; i++) {
      if (dedent) {
        // Remove up to 2 leading spaces or 1 tab
        st.lines[i] = (st.lines[i] ?? "").replace(/^(\t| {1,2})/, "");
      } else {
        st.lines[i] = "  " + (st.lines[i] ?? "");
      }
    }
    this.triggerOnChange();
    this.invalidate();
  }

  // ─── Motion computation ──────────────────────────────────────────────
  // Each motion returns a Range from current cursor position to the target.

  /** Move left by count chars */
  private motionLeft(count: number): Range {
    const pos = this.curPos();
    const newCol = Math.max(0, pos.col - count);
    return {
      start: { line: pos.line, col: newCol },
      end: { line: pos.line, col: pos.col },
      linewise: false,
    };
  }

  /** Move right by count chars */
  private motionRight(count: number): Range {
    const pos = this.curPos();
    const lineLen = this.curLine().length;
    const maxCol = this.mode === "insert" ? lineLen : Math.max(0, lineLen - 1);
    const newCol = Math.min(maxCol, pos.col + count);
    return {
      start: { line: pos.line, col: pos.col },
      end: { line: pos.line, col: newCol },
      linewise: false,
    };
  }

  /** Move down by count lines */
  private motionDown(count: number): Range {
    const pos = this.curPos();
    const newLine = Math.min(this.st.lines.length - 1, pos.line + count);
    return {
      start: { line: pos.line, col: 0 },
      end: { line: newLine, col: 0 },
      linewise: true,
    };
  }

  /** Move up by count lines */
  private motionUp(count: number): Range {
    const pos = this.curPos();
    const newLine = Math.max(0, pos.line - count);
    return {
      start: { line: newLine, col: 0 },
      end: { line: pos.line, col: 0 },
      linewise: true,
    };
  }

  /** w motion: next word start */
  private motionWordForward(count: number, bigWord: boolean = false): Range {
    const start = this.curPos();
    let { line, col } = start;
    const st = this.st;

    for (let i = 0; i < count; i++) {
      const text = this.lineText(line);
      if (col >= text.length) {
        // Move to next line
        if (line < st.lines.length - 1) {
          line++;
          col = 0;
          // Skip leading whitespace on new line
          const newText = this.lineText(line);
          while (col < newText.length && isWhitespace(newText[col])) col++;
        }
        continue;
      }

      const ch = text[col];
      if (bigWord) {
        // WORD: skip non-whitespace, then whitespace
        while (col < text.length && !isWhitespace(text[col])) col++;
        while (col < text.length && isWhitespace(text[col])) col++;
      } else {
        // word: skip same-class chars, then whitespace
        if (isWordChar(ch)) {
          while (col < text.length && isWordChar(text[col])) col++;
        } else if (isSymbol(ch)) {
          while (col < text.length && isSymbol(text[col])) col++;
        } else {
          while (col < text.length && isWhitespace(text[col])) col++;
        }
        // Skip whitespace after word
        while (col < text.length && isWhitespace(text[col])) col++;
      }

      // If we're at end of line, move to start of next line
      if (col >= text.length && line < st.lines.length - 1) {
        line++;
        col = 0;
        const newText = this.lineText(line);
        while (col < newText.length && isWhitespace(newText[col])) col++;
      }
    }

    return {
      start: minPos(start, { line, col }),
      end: maxPos(start, { line, col }),
      linewise: false,
    };
  }

  /** e motion: end of word */
  private motionWordEnd(count: number, bigWord: boolean = false): Range {
    const start = this.curPos();
    let { line, col } = start;
    const st = this.st;

    for (let i = 0; i < count; i++) {
      // Move at least one char forward
      col++;
      let text = this.lineText(line);

      // Skip past end of line
      if (col >= text.length && line < st.lines.length - 1) {
        line++;
        col = 0;
        text = this.lineText(line);
      }

      // Skip whitespace
      while (col < text.length && isWhitespace(text[col])) {
        col++;
        if (col >= text.length && line < st.lines.length - 1) {
          line++;
          col = 0;
          text = this.lineText(line);
        }
      }

      if (col < text.length) {
        const ch = text[col];
        if (bigWord) {
          while (col + 1 < text.length && !isWhitespace(text[col + 1])) col++;
        } else if (isWordChar(ch)) {
          while (col + 1 < text.length && isWordChar(text[col + 1])) col++;
        } else if (isSymbol(ch)) {
          while (col + 1 < text.length && isSymbol(text[col + 1])) col++;
        }
      }
    }

    // For operator use, end is inclusive, so we add 1 for the range
    return {
      start: minPos(start, { line, col: col + 1 }),
      end: maxPos(start, { line, col: col + 1 }),
      linewise: false,
    };
  }

  /** b motion: previous word start */
  private motionWordBackward(count: number, bigWord: boolean = false): Range {
    const start = this.curPos();
    let { line, col } = start;

    for (let i = 0; i < count; i++) {
      // Move at least one char backward
      col--;
      let text = this.lineText(line);

      if (col < 0 && line > 0) {
        line--;
        text = this.lineText(line);
        col = text.length - 1;
      }
      if (col < 0) {
        col = 0;
        continue;
      }

      // Skip whitespace backward
      while (col >= 0 && isWhitespace(text[col])) {
        col--;
        if (col < 0 && line > 0) {
          line--;
          text = this.lineText(line);
          col = text.length - 1;
        }
      }

      if (col < 0) {
        col = 0;
        continue;
      }

      const ch = text[col];
      if (bigWord) {
        while (col > 0 && !isWhitespace(text[col - 1])) col--;
      } else if (isWordChar(ch)) {
        while (col > 0 && isWordChar(text[col - 1])) col--;
      } else if (isSymbol(ch)) {
        while (col > 0 && isSymbol(text[col - 1])) col--;
      }
    }

    return {
      start: minPos(start, { line, col }),
      end: maxPos(start, { line, col }),
      linewise: false,
    };
  }

  /** 0 motion: start of line */
  private motionLineStart(): Range {
    const pos = this.curPos();
    return { start: { line: pos.line, col: 0 }, end: pos, linewise: false };
  }

  /** $ motion: end of line */
  private motionLineEnd(count: number): Range {
    const pos = this.curPos();
    const targetLine = Math.min(this.st.lines.length - 1, pos.line + count - 1);
    const lineLen = this.lineText(targetLine).length;
    return {
      start: minPos(pos, { line: targetLine, col: lineLen }),
      end: maxPos(pos, { line: targetLine, col: lineLen }),
      linewise: false,
    };
  }

  /** ^ motion: first non-blank */
  private motionFirstNonBlank(): Range {
    const pos = this.curPos();
    const line = this.curLine();
    const idx = line.search(/\S/);
    const col = idx >= 0 ? idx : 0;
    return {
      start: minPos(pos, { line: pos.line, col }),
      end: maxPos(pos, { line: pos.line, col }),
      linewise: false,
    };
  }

  /** f/F/t/T motion: find char */
  private motionFindChar(
    type: "f" | "F" | "t" | "T",
    char: string,
    count: number,
  ): Range | null {
    const pos = this.curPos();
    const line = this.curLine();
    let col = pos.col;
    const forward = type === "f" || type === "t";
    const till = type === "t" || type === "T";

    for (let i = 0; i < count; i++) {
      if (forward) {
        let found = -1;
        for (let j = col + 1; j < line.length; j++) {
          if (line[j] === char) {
            found = j;
            break;
          }
        }
        if (found === -1) return null;
        col = found;
      } else {
        let found = -1;
        for (let j = col - 1; j >= 0; j--) {
          if (line[j] === char) {
            found = j;
            break;
          }
        }
        if (found === -1) return null;
        col = found;
      }
    }

    if (till) {
      col = forward ? col - 1 : col + 1;
    }

    // For operators, range needs to include the char for f, and be exclusive-end
    const target = { line: pos.line, col };
    if (forward) {
      return {
        start: pos,
        end: { line: pos.line, col: col + 1 },
        linewise: false,
      };
    } else {
      return { start: { line: pos.line, col }, end: pos, linewise: false };
    }
  }

  // ─── Text objects ────────────────────────────────────────────────────

  /** Inner/around word text object */
  private textObjectWord(
    inner: boolean,
    bigWord: boolean = false,
  ): Range | null {
    const pos = this.curPos();
    const line = this.curLine();
    if (line.length === 0) return null;

    const ch = line[pos.col];
    if (!ch) return null;

    let start = pos.col;
    let end = pos.col;

    const classify = bigWord
      ? (c: string) => (isWhitespace(c) ? "ws" : "word")
      : (c: string) =>
          isWordChar(c) ? "word" : isWhitespace(c) ? "ws" : "sym";

    const cls = classify(ch);

    // Expand to cover contiguous same-class chars
    while (start > 0 && classify(line[start - 1]) === cls) start--;
    while (end < line.length - 1 && classify(line[end + 1]) === cls) end++;

    if (!inner) {
      // "around" includes trailing whitespace (or leading if at end)
      if (end + 1 < line.length && isWhitespace(line[end + 1])) {
        while (end + 1 < line.length && isWhitespace(line[end + 1])) end++;
      } else if (start > 0 && isWhitespace(line[start - 1])) {
        while (start > 0 && isWhitespace(line[start - 1])) start--;
      }
    }

    return {
      start: { line: pos.line, col: start },
      end: { line: pos.line, col: end + 1 },
      linewise: false,
    };
  }

  /** Inner/around quoted string text object */
  private textObjectQuote(quote: string, inner: boolean): Range | null {
    const pos = this.curPos();
    const line = this.curLine();

    // Find opening quote at or before cursor
    let open = -1;
    for (let i = pos.col; i >= 0; i--) {
      if (line[i] === quote) {
        open = i;
        break;
      }
    }
    if (open === -1) {
      // Try forward from cursor
      for (let i = pos.col; i < line.length; i++) {
        if (line[i] === quote) {
          open = i;
          break;
        }
      }
    }
    if (open === -1) return null;

    // Find closing quote after opening
    let close = -1;
    for (let i = open + 1; i < line.length; i++) {
      if (line[i] === quote && (i === 0 || line[i - 1] !== "\\")) {
        close = i;
        break;
      }
    }
    if (close === -1) return null;

    // If cursor is outside the quotes, check if there's a pair starting at cursor
    if (pos.col > close) return null;

    if (inner) {
      return {
        start: { line: pos.line, col: open + 1 },
        end: { line: pos.line, col: close },
        linewise: false,
      };
    } else {
      return {
        start: { line: pos.line, col: open },
        end: { line: pos.line, col: close + 1 },
        linewise: false,
      };
    }
  }

  /** Inner/around bracket text object — searches across lines */
  private textObjectBracket(
    openBracket: string,
    closeBracket: string,
    inner: boolean,
  ): Range | null {
    const st = this.st;
    const pos = this.curPos();

    // Find matching open bracket (searching backward)
    let depth = 0;
    let openPos: Pos | null = null;

    outer_open: for (let l = pos.line; l >= 0; l--) {
      const line = this.lineText(l);
      const startCol = l === pos.line ? pos.col : line.length - 1;
      for (let c = startCol; c >= 0; c--) {
        if (line[c] === closeBracket && !(l === pos.line && c === pos.col))
          depth++;
        if (line[c] === openBracket) {
          if (depth === 0) {
            openPos = { line: l, col: c };
            break outer_open;
          }
          depth--;
        }
      }
    }
    if (!openPos) return null;

    // Find matching close bracket (searching forward)
    depth = 0;
    let closePos: Pos | null = null;

    outer_close: for (let l = openPos.line; l < st.lines.length; l++) {
      const line = this.lineText(l);
      const startCol = l === openPos.line ? openPos.col + 1 : 0;
      for (let c = startCol; c < line.length; c++) {
        if (line[c] === openBracket) depth++;
        if (line[c] === closeBracket) {
          if (depth === 0) {
            closePos = { line: l, col: c };
            break outer_close;
          }
          depth--;
        }
      }
    }
    if (!closePos) return null;

    if (inner) {
      // Inside the brackets
      let startPos = { line: openPos.line, col: openPos.col + 1 };
      let endPos = { line: closePos.line, col: closePos.col };
      return { start: startPos, end: endPos, linewise: false };
    } else {
      return {
        start: openPos,
        end: { line: closePos.line, col: closePos.col + 1 },
        linewise: false,
      };
    }
  }

  /** Inner/around paragraph text object */
  private textObjectParagraph(inner: boolean): Range | null {
    const st = this.st;
    const pos = this.curPos();

    const isBlank = (l: number) => (st.lines[l] ?? "").trim() === "";

    // Find paragraph boundaries
    let startLine = pos.line;
    let endLine = pos.line;

    if (isBlank(pos.line)) {
      // On a blank line: select the blank block
      while (startLine > 0 && isBlank(startLine - 1)) startLine--;
      while (endLine < st.lines.length - 1 && isBlank(endLine + 1)) endLine++;
    } else {
      // On a non-blank line: select the non-blank block
      while (startLine > 0 && !isBlank(startLine - 1)) startLine--;
      while (endLine < st.lines.length - 1 && !isBlank(endLine + 1)) endLine++;
    }

    if (!inner) {
      // "around": include trailing blank lines (or leading if at end)
      if (endLine < st.lines.length - 1) {
        while (endLine < st.lines.length - 1 && isBlank(endLine + 1)) endLine++;
      } else {
        while (startLine > 0 && isBlank(startLine - 1)) startLine--;
      }
    }

    return {
      start: { line: startLine, col: 0 },
      end: { line: endLine, col: this.lineText(endLine).length },
      linewise: true,
    };
  }

  /** Parse a text object key sequence: returns Range or null */
  private parseTextObject(modifier: string, obj: string): Range | null {
    const inner = modifier === "i";
    switch (obj) {
      case "w":
        return this.textObjectWord(inner, false);
      case "W":
        return this.textObjectWord(inner, true);
      case "p":
        return this.textObjectParagraph(inner);
      case '"':
        return this.textObjectQuote('"', inner);
      case "'":
        return this.textObjectQuote("'", inner);
      case "`":
        return this.textObjectQuote("`", inner);
      case "(":
      case ")":
      case "b":
        return this.textObjectBracket("(", ")", inner);
      case "[":
      case "]":
        return this.textObjectBracket("[", "]", inner);
      case "{":
      case "}":
      case "B":
        return this.textObjectBracket("{", "}", inner);
      default:
        return null;
    }
  }

  // ─── Resolve a motion key to a Range ─────────────────────────────────

  private resolveMotion(key: string, count: number): Range | null {
    switch (key) {
      case "h":
        return this.motionLeft(count);
      case "l":
        return this.motionRight(count);
      case "j":
        return this.motionDown(count);
      case "k":
        return this.motionUp(count);
      case "w":
        return this.motionWordForward(count, false);
      case "W":
        return this.motionWordForward(count, true);
      case "e":
        return this.motionWordEnd(count, false);
      case "E":
        return this.motionWordEnd(count, true);
      case "b":
        return this.motionWordBackward(count, false);
      case "B":
        return this.motionWordBackward(count, true);
      case "0":
        return this.motionLineStart();
      case "$":
        return this.motionLineEnd(count);
      case "^":
        return this.motionFirstNonBlank();
      default:
        return null;
    }
  }

  // ─── Apply operator to range ─────────────────────────────────────────

  private applyOperator(op: string, range: Range): void {
    this.pushUndo();

    switch (op) {
      case "d": {
        const deleted = this.deleteRange(range);
        this.register = deleted;
        this.registerLinewise = range.linewise;
        this.clampCursor();
        break;
      }
      case "c": {
        const deleted = this.deleteRange(range);
        this.register = deleted;
        this.registerLinewise = range.linewise;
        if (range.linewise) {
          // Open a line for insertion
          const st = this.st;
          const insertLine = Math.min(st.cursorLine, st.lines.length - 1);
          st.lines.splice(insertLine, 0, "");
          st.cursorLine = insertLine;
          this.doSetCursorCol(0);
        }
        this.switchMode("insert");
        break;
      }
      case "y": {
        this.register = this.getTextInRange(range);
        this.registerLinewise = range.linewise;
        // Cursor goes to start of range
        this.setCursor(range.start.line, range.start.col);
        break;
      }
      case ">": {
        const startLine = range.start.line;
        const endLine = range.linewise ? range.end.line : range.end.line;
        this.indentLines(startLine, endLine, false);
        this.clampCursor();
        break;
      }
      case "<": {
        const startLine = range.start.line;
        const endLine = range.linewise ? range.end.line : range.end.line;
        this.indentLines(startLine, endLine, true);
        this.clampCursor();
        break;
      }
    }
  }

  // ─── Visual mode ─────────────────────────────────────────────────────

  /** Get the visual selection as a Range */
  private getVisualRange(): Range {
    const cursor = this.curPos();
    if (this.mode === "visual-line") {
      const startLine = Math.min(this.visualAnchor.line, cursor.line);
      const endLine = Math.max(this.visualAnchor.line, cursor.line);
      return {
        start: { line: startLine, col: 0 },
        end: { line: endLine, col: this.lineText(endLine).length },
        linewise: true,
      };
    }
    // Character-wise visual
    const start = minPos(this.visualAnchor, cursor);
    let end = maxPos(this.visualAnchor, cursor);
    // Visual selection is inclusive of cursor char, so end.col + 1
    return {
      start,
      end: { line: end.line, col: end.col + 1 },
      linewise: false,
    };
  }

  // ─── Mode switching ──────────────────────────────────────────────────

  private switchMode(newMode: Mode): void {
    // When modal editing is disabled the editor stays in insert mode.

    const oldMode = this.mode;
    this.mode = newMode;

    if (newMode === "normal") {
      // In normal mode, cursor can't be past last char
      this.clampCursor();
      this.pendingOperator = null;
      this.countStr = "";
      this.pendingFind = null;
      this.pendingReplace = false;
    }

    if (newMode === "visual" || newMode === "visual-line") {
      this.visualAnchor = this.curPos();
    }

    if (newMode === "insert" && oldMode !== "insert") {
      this.startRecording();
    }

    if (oldMode === "insert" && newMode !== "insert") {
      this.stopRecording();
    }

    this.updateStatus();
    this.invalidate();
  }

  // ─── Dot repeat recording ────────────────────────────────────────────

  private startRecording(): void {
    if (this.replaying) return;
    this.recording = true;
    this.recordBuffer = "";
  }

  private stopRecording(): void {
    if (this.replaying) return;
    if (this.recording && this.recordBuffer.length > 0) {
      this.lastChange = this.recordBuffer;
    }
    this.recording = false;
  }

  private recordKey(key: string): void {
    if (this.recording && !this.replaying) {
      this.recordBuffer += key;
    }
  }

  // ─── Main input handler ──────────────────────────────────────────────

  handleInput(data: string): void {
    // [DEBUG] Temporary: log every key press to the pi console so we can
    // visually inspect what bytes the terminal is delivering. Remove once
    // the capital-letter leader-key issue is understood.
    this.debugLogKey(data);

    // When modal editing is enabled, alt+escape replaces bare escape as
    // the abort/interrupt key (bare escape switches to Normal / clears
    // pending state). When modal is disabled, bare escape falls through
    // to pi's default handling.
    if (matchesKey(data, "alt+escape")) {
      super.handleInput("\x1b");
      return;
    }

    // Always pass through control sequences that pi needs
    if (this.shouldPassthrough(data)) {
      super.handleInput(data);
      return;
    }

    switch (this.mode) {
      case "insert":
        this.handleInsertMode(data);
        break;
      case "normal":
        this.handleNormalMode(data);
        break;
      case "visual":
      case "visual-line":
        this.handleVisualMode(data);
        break;
    }
  }

  /** [DEBUG] Render a key-press as "raw" + hex + length + mode and notify. */
  private debugLogKey(data: string): void {
    if (!this.debugEnabled) return;
    const escaped = data
      .split("")
      .map((ch) => {
        const code = ch.charCodeAt(0);
        if (ch === "\x1b") return "\\e";
        if (ch === "\r") return "\\r";
        if (ch === "\n") return "\\n";
        if (ch === "\t") return "\\t";
        if (ch === "\\") return "\\\\";
        if (code < 0x20 || code === 0x7f) return `\\x${code.toString(16).padStart(2, "0")}`;
        return ch;
      })
      .join("");
    const hex = Array.from(data)
      .map((ch) => ch.charCodeAt(0).toString(16).padStart(2, "0"))
      .join(" ");
    const msg = `kb: "${escaped}" [${hex}] len=${data.length} mode=${this.mode}`;
    this.ctx?.ui?.notify?.(msg, "info");
  }

  /** Keys that always pass through to pi regardless of mode */
  private shouldPassthrough(data: string): boolean {
    // Ctrl+key combinations that pi handles
    if (matchesKey(data, "ctrl+c")) return true;
    if (matchesKey(data, "ctrl+d")) return true;
    if (matchesKey(data, "ctrl+z")) return true;
    if (matchesKey(data, "ctrl+l")) return true;
    if (matchesKey(data, "ctrl+p")) return true;
    if (matchesKey(data, "ctrl+shift+p")) return true;
    if (matchesKey(data, "shift+tab")) return true;
    if (matchesKey(data, "ctrl+o")) return true;
    if (matchesKey(data, "ctrl+t")) return true;
    if (matchesKey(data, "ctrl+g")) return true;
    if (matchesKey(data, "ctrl+v")) return true;
    if (matchesKey(data, "alt+enter")) return true;
    if (matchesKey(data, "alt+up")) return true;
    return false;
  }

  // ─── Insert mode ─────────────────────────────────────────────────────

  private handleInsertMode(data: string): void {
    // alt+space and alt+, in Insert mode are handled by leader-menu's
    // global registerShortcut handlers — no action needed here. We
    // simply do not forward them to the underlying editor.
    if (matchesKey(data, "alt+space") || matchesKey(data, "alt+,")) {
      return;
    }

    // Escape switches to normal only when modal editing is enabled.
    // Otherwise escape falls through to pi (via shouldPassthrough + the
    // base editor) so that app.interrupt still works.
    if (matchesKey(data, "escape")) {
      this.switchMode("normal");
      // Move cursor back one (vim behavior)
      if (this.st.cursorCol > 0) {
        this.doSetCursorCol(this.st.cursorCol - 1);
      }
      return;
    }

    // Record for dot-repeat
    this.recordKey(data);

    // Everything else goes to the underlying editor
    super.handleInput(data);
  }

  // ─── Normal mode ─────────────────────────────────────────────────────

  private handleNormalMode(data: string): void {
    // Escape: clear pending state and stay in Normal mode (does not abort).
    // Use alt+escape to abort (see handleInput).
    if (matchesKey(data, "escape")) {
      this.pendingOperator = null;
      this.countStr = "";
      this.pendingFind = null;
      this.pendingReplace = false;
      this.invalidate();
      return;
    }

    // Handle Enter: submit message
    if (matchesKey(data, "enter")) {
      super.handleInput(data);
      // After submit, go back to insert mode
      this.mode = "insert";
      return;
    }

    // f/t/F/T awaiting char
    if (this.pendingFind) {
      this.handlePendingFind(data);
      return;
    }

    // r awaiting char
    if (this.pendingReplace) {
      this.handlePendingReplace(data);
      return;
    }

    // Operator pending — waiting for motion or text object
    if (this.pendingOperator) {
      this.handleOperatorPending(data);
      return;
    }

    // Accumulate count digits (but "0" only if we already have digits)
    if (data >= "1" && data <= "9") {
      this.countStr += data;
      return;
    }
    if (data === "0" && this.countStr.length > 0) {
      this.countStr += data;
      return;
    }

    const count = this.consumeCount();
    this.dispatchNormal(data, count);
  }

  private consumeCount(): number {
    const count = this.countStr ? parseInt(this.countStr, 10) : 1;
    this.countStr = "";
    return count;
  }

  private dispatchNormal(key: string, count: number): void {
    // ── Motions (cursor movement only) ──
    const motion = this.resolveMotion(key, count);
    if (motion) {
      // Move cursor to the "other end" of the motion range
      const pos = this.curPos();
      const target =
        comparePos(pos, motion.start) === 0 ? motion.end : motion.start;
      if (motion.linewise) {
        if (key === "j") {
          this.setCursor(
            Math.min(this.st.lines.length - 1, pos.line + count),
            pos.col,
          );
        } else if (key === "k") {
          this.setCursor(Math.max(0, pos.line - count), pos.col);
        }
      } else {
        this.setCursor(target.line, target.col);
      }
      this.invalidate();
      return;
    }

    switch (key) {
      // ── Mode switches ──
      case "i":
        this.startRecording();
        this.recordBuffer = "i";
        this.switchMode("insert");
        return;
      case "a":
        this.startRecording();
        this.recordBuffer = "a";
        if (this.curLine().length > 0) {
          this.doSetCursorCol(this.st.cursorCol + 1);
        }
        this.switchMode("insert");
        return;
      case "I":
        this.startRecording();
        this.recordBuffer = "I";
        const fnb = this.curLine().search(/\S/);
        this.doSetCursorCol(fnb >= 0 ? fnb : 0);
        this.switchMode("insert");
        return;
      case "A":
        this.startRecording();
        this.recordBuffer = "A";
        this.doSetCursorCol(this.curLine().length);
        this.switchMode("insert");
        return;
      case "o":
        this.startRecording();
        this.recordBuffer = "o";
        this.pushUndo();
        this.st.lines.splice(this.st.cursorLine + 1, 0, "");
        this.st.cursorLine++;
        this.doSetCursorCol(0);
        this.triggerOnChange();
        this.switchMode("insert");
        return;
      case "O":
        this.startRecording();
        this.recordBuffer = "O";
        this.pushUndo();
        this.st.lines.splice(this.st.cursorLine, 0, "");
        this.doSetCursorCol(0);
        this.triggerOnChange();
        this.switchMode("insert");
        return;
      case "v":
        this.switchMode("visual");
        return;
      case "V":
        this.switchMode("visual-line");
        return;

      // ── Operators (enter pending state) ──
      case "d":
      case "c":
      case "y":
      case ">":
      case "<":
        this.pendingOperator = key;
        return;

      // ── Find character ──
      case "f":
        this.pendingFind = { type: "f" };
        return;
      case "F":
        this.pendingFind = { type: "F" };
        return;
      case "t":
        this.pendingFind = { type: "t" };
        return;
      case "T":
        this.pendingFind = { type: "T" };
        return;

      // ── Repeat find ──
      case ";":
      case ",": {
        if (!this.lastFind) return;
        // `,` reverses the direction of the last f/F/t/T.
        const reversed: { f: "F"; F: "f"; t: "T"; T: "t" } = { f: "F", F: "f", t: "T", T: "t" };
        const type = key === "," ? reversed[this.lastFind.type] : this.lastFind.type;
        const range = this.motionFindChar(type, this.lastFind.char, count);
        if (range) {
          const pos = this.curPos();
          const target =
            comparePos(pos, range.start) === 0
              ? { line: range.end.line, col: range.end.col - 1 }
              : range.start;
          this.setCursor(target.line, target.col);
          this.invalidate();
        }
        return;
      }

      // ── Single-key commands ──
      case "x":
        for (let i = 0; i < count; i++) {
          const line = this.curLine();
          if (line.length > 0 && this.st.cursorCol < line.length) {
            this.pushUndo();
            const deleted = line[this.st.cursorCol];
            this.st.lines[this.st.cursorLine] =
              line.slice(0, this.st.cursorCol) +
              line.slice(this.st.cursorCol + 1);
            this.register = deleted;
            this.registerLinewise = false;
            this.clampCursor();
            this.triggerOnChange();
          }
        }
        this.lastChange = count > 1 ? `${count}x` : "x";
        this.invalidate();
        return;

      case "X":
        for (let i = 0; i < count; i++) {
          if (this.st.cursorCol > 0) {
            this.pushUndo();
            const line = this.curLine();
            const deleted = line[this.st.cursorCol - 1];
            this.st.lines[this.st.cursorLine] =
              line.slice(0, this.st.cursorCol - 1) +
              line.slice(this.st.cursorCol);
            this.register = deleted;
            this.registerLinewise = false;
            this.doSetCursorCol(this.st.cursorCol - 1);
            this.triggerOnChange();
          }
        }
        this.lastChange = count > 1 ? `${count}X` : "X";
        this.invalidate();
        return;

      case "r":
        this.pendingReplace = true;
        return;

      case "s":
        // Substitute: delete char and enter insert
        this.pushUndo();
        {
          const line = this.curLine();
          if (line.length > 0 && this.st.cursorCol < line.length) {
            this.register = line[this.st.cursorCol];
            this.registerLinewise = false;
            this.st.lines[this.st.cursorLine] =
              line.slice(0, this.st.cursorCol) +
              line.slice(this.st.cursorCol + 1);
            this.triggerOnChange();
          }
        }
        this.lastChange = "s";
        this.switchMode("insert");
        return;

      case "S":
        // Substitute entire line
        this.pushUndo();
        this.register = this.curLine();
        this.registerLinewise = false;
        this.st.lines[this.st.cursorLine] = "";
        this.doSetCursorCol(0);
        this.triggerOnChange();
        this.lastChange = "S";
        this.switchMode("insert");
        return;

      case "p":
        this.paste(false, count);
        return;

      case "P":
        this.paste(true, count);
        return;

      case "u":
        (this as any).undo();
        this.invalidate();
        return;

      case ".":
        this.dotRepeat();
        return;

      case "J":
        this.joinLines(count);
        return;

      case "~":
        this.toggleCase(count);
        return;

      case "D":
        // Delete to end of line
        this.applyOperator("d", this.motionLineEnd(1));
        this.lastChange = "D";
        return;

      case "C":
        // Change to end of line
        this.applyOperator("c", this.motionLineEnd(1));
        this.lastChange = "C";
        return;

      case "Y":
        // Yank line
        this.applyOperator("y", {
          start: { line: this.st.cursorLine, col: 0 },
          end: { line: this.st.cursorLine, col: this.curLine().length },
          linewise: true,
        });
        return;

      // ── Scroll ──
      // Ctrl-d / Ctrl-u are handled via escape sequences
      default:
        if (matchesKey(key, "ctrl+d")) {
          this.pageScroll(1, count);
          return;
        }
        if (matchesKey(key, "ctrl+u")) {
          this.pageScroll(-1, count);
          return;
        }
        if (matchesKey(key, "ctrl+r")) {
          // Redo — not available in base editor, ignore
          return;
        }

        // ── Leader chord delegation ──
        // Bare leader keys in Normal mode open leader-menu's overlay.
        // The overlay is a modal `ctx.ui.custom()` so it grabs focus
        // and handles all subsequent keys itself — no leader state
        // lives in this editor. The set of recognised leader keys is
        // pushed by leader-menu via `leader-menu:keys-resolved` so a
        // user-reconfigured leader (e.g. `~/.pi/agent/leader-menu.json`
        // with `globalLeader: "/"`) takes effect without code changes.
        if (this.leaderKeys.has(key)) {
          this.emitLeaderOpen?.(key);
          return;
        }

        // Ignore unrecognized keys in normal mode
        break;
    }
  }

  // ─── Operator-pending mode ───────────────────────────────────────────

  private handleOperatorPending(data: string): void {
    const op = this.pendingOperator!;
    const count = this.consumeCount();

    // Accumulate more count digits
    if (data >= "1" && data <= "9") {
      this.countStr += data;
      return;
    }
    if (data === "0" && this.countStr.length > 0) {
      this.countStr += data;
      return;
    }

    // Doubled operator = operate on current line(s)
    if (data === op) {
      this.pendingOperator = null;
      const startLine = this.st.cursorLine;
      const endLine = Math.min(this.st.lines.length - 1, startLine + count - 1);
      const range: Range = {
        start: { line: startLine, col: 0 },
        end: { line: endLine, col: this.lineText(endLine).length },
        linewise: true,
      };
      const recordStr = count > 1 ? `${count}${op}${op}` : `${op}${op}`;
      this.applyOperator(op, range);
      this.lastChange = recordStr;
      return;
    }

    // Text object: i/a followed by object key
    if (data === "i" || data === "a") {
      // Need another key for the text object type
      this.pendingOperator = null;
      // Store state and wait for next key
      const modifier = data;
      const originalOp = op;
      // Temporarily hijack handleInput for the next key
      const origHandler = this.handleInput.bind(this);
      this.handleInput = (nextKey: string) => {
        this.handleInput = origHandler;
        const range = this.parseTextObject(modifier, nextKey);
        if (range) {
          this.applyOperator(originalOp, range);
          this.lastChange = `${originalOp}${modifier}${nextKey}`;
        }
      };
      return;
    }

    // f/t/F/T in operator-pending
    if (data === "f" || data === "F" || data === "t" || data === "T") {
      this.pendingOperator = null;
      const findType = data as "f" | "F" | "t" | "T";
      const originalOp = op;
      const origHandler = this.handleInput.bind(this);
      this.handleInput = (charKey: string) => {
        this.handleInput = origHandler;
        const range = this.motionFindChar(findType, charKey, count);
        if (range) {
          this.lastFind = { type: findType, char: charKey };
          this.applyOperator(originalOp, range);
          this.lastChange = `${originalOp}${findType}${charKey}`;
        }
      };
      return;
    }

    // Try as a motion
    const motion = this.resolveMotion(data, count);
    this.pendingOperator = null;
    if (motion) {
      const recordStr = count > 1 ? `${op}${count}${data}` : `${op}${data}`;
      this.applyOperator(op, motion);
      this.lastChange = recordStr;
    }
    // If not a valid motion, just cancel
  }

  // ─── Pending find (f/t/F/T) in normal mode ──────────────────────────

  private handlePendingFind(data: string): void {
    const findType = this.pendingFind!.type;
    this.pendingFind = null;
    const count = this.consumeCount();

    const range = this.motionFindChar(findType, data, count);
    if (range) {
      this.lastFind = { type: findType, char: data };
      // Move cursor to the found char
      const pos = this.curPos();
      const forward = findType === "f" || findType === "t";
      if (forward) {
        this.setCursor(range.end.line, range.end.col - 1);
      } else {
        this.setCursor(range.start.line, range.start.col);
      }
      this.invalidate();
    }
  }

  // ─── Pending replace (r) ─────────────────────────────────────────────

  private handlePendingReplace(data: string): void {
    this.pendingReplace = false;
    if (data.length !== 1 || data.charCodeAt(0) < 32) return;

    const line = this.curLine();
    const col = this.st.cursorCol;
    if (col >= line.length) return;

    this.pushUndo();
    this.st.lines[this.st.cursorLine] =
      line.slice(0, col) + data + line.slice(col + 1);
    this.triggerOnChange();
    this.lastChange = `r${data}`;
    this.invalidate();
  }

  // ─── Visual mode ─────────────────────────────────────────────────────

  private handleVisualMode(data: string): void {
    if (matchesKey(data, "escape")) {
      this.switchMode("normal");
      return;
    }

    // Count accumulation
    if (data >= "1" && data <= "9") {
      this.countStr += data;
      return;
    }
    if (data === "0" && this.countStr.length > 0) {
      this.countStr += data;
      return;
    }

    const count = this.consumeCount();

    // Motions — extend selection
    const motion = this.resolveMotion(data, count);
    if (motion) {
      const pos = this.curPos();
      if (data === "j") {
        this.setCursor(
          Math.min(this.st.lines.length - 1, pos.line + count),
          pos.col,
        );
      } else if (data === "k") {
        this.setCursor(Math.max(0, pos.line - count), pos.col);
      } else {
        const target =
          comparePos(pos, motion.start) === 0 ? motion.end : motion.start;
        this.setCursor(target.line, target.col);
      }
      this.invalidate();
      return;
    }

    // f/t/F/T in visual
    if (data === "f" || data === "F" || data === "t" || data === "T") {
      const findType = data as "f" | "F" | "t" | "T";
      const origHandler = this.handleInput.bind(this);
      this.handleInput = (charKey: string) => {
        this.handleInput = origHandler;
        const range = this.motionFindChar(findType, charKey, count);
        if (range) {
          this.lastFind = { type: findType, char: charKey };
          const forward = findType === "f" || findType === "t";
          if (forward) {
            this.setCursor(range.end.line, range.end.col - 1);
          } else {
            this.setCursor(range.start.line, range.start.col);
          }
          this.invalidate();
        }
      };
      return;
    }

    // Operators on selection
    switch (data) {
      case "d":
      case "x": {
        const range = this.getVisualRange();
        this.applyOperator("d", range);
        this.lastChange = null;
        this.switchMode("normal");
        return;
      }
      case "c":
      case "s": {
        const range = this.getVisualRange();
        this.applyOperator("c", range);
        this.lastChange = null;
        return;
      }
      case "y": {
        const range = this.getVisualRange();
        this.applyOperator("y", range);
        this.switchMode("normal");
        return;
      }
      case ">": {
        const range = this.getVisualRange();
        this.pushUndo();
        this.indentLines(range.start.line, range.end.line, false);
        this.switchMode("normal");
        return;
      }
      case "<": {
        const range = this.getVisualRange();
        this.pushUndo();
        this.indentLines(range.start.line, range.end.line, true);
        this.switchMode("normal");
        return;
      }
      case "~": {
        const range = this.getVisualRange();
        this.pushUndo();
        this.toggleCaseInRange(range);
        this.switchMode("normal");
        return;
      }
      case "u": {
        const range = this.getVisualRange();
        this.pushUndo();
        this.changeCaseInRange(range, "lower");
        this.switchMode("normal");
        return;
      }
      case "U": {
        const range = this.getVisualRange();
        this.pushUndo();
        this.changeCaseInRange(range, "upper");
        this.switchMode("normal");
        return;
      }
      case "o": {
        // Swap cursor and anchor
        const tmp = this.curPos();
        this.setCursor(this.visualAnchor.line, this.visualAnchor.col);
        this.visualAnchor = tmp;
        this.invalidate();
        return;
      }
      case "v": {
        if (this.mode === "visual") {
          this.switchMode("normal");
        } else {
          this.mode = "visual";
          this.invalidate();
        }
        return;
      }
      case "V": {
        if (this.mode === "visual-line") {
          this.switchMode("normal");
        } else {
          this.mode = "visual-line";
          this.invalidate();
        }
        return;
      }
    }
  }

  // ─── Paste ───────────────────────────────────────────────────────────

  private paste(before: boolean, count: number): void {
    if (!this.register) return;
    this.pushUndo();

    const st = this.st;
    for (let i = 0; i < count; i++) {
      if (this.registerLinewise) {
        const text = this.register.endsWith("\n")
          ? this.register.slice(0, -1)
          : this.register;
        const lines = text.split("\n");
        if (before) {
          st.lines.splice(st.cursorLine, 0, ...lines);
        } else {
          st.lines.splice(st.cursorLine + 1, 0, ...lines);
          if (i === 0) st.cursorLine++;
        }
        // Move to first non-blank of first pasted line
        const pastedLine = this.lineText(st.cursorLine);
        const fnb = pastedLine.search(/\S/);
        this.doSetCursorCol(fnb >= 0 ? fnb : 0);
      } else {
        const line = this.curLine();
        if (before) {
          const endPos = this.insertTextAt(
            { line: st.cursorLine, col: st.cursorCol },
            this.register,
          );
          this.setCursor(endPos.line, Math.max(0, endPos.col - 1));
        } else {
          const insertCol = Math.min(st.cursorCol + 1, line.length);
          const endPos = this.insertTextAt(
            { line: st.cursorLine, col: insertCol },
            this.register,
          );
          this.setCursor(endPos.line, Math.max(0, endPos.col - 1));
        }
      }
    }

    this.triggerOnChange();
    this.lastChange = before
      ? `${count > 1 ? count : ""}P`
      : `${count > 1 ? count : ""}p`;
    this.invalidate();
  }

  // ─── Join lines ──────────────────────────────────────────────────────

  private joinLines(count: number): void {
    const st = this.st;
    this.pushUndo();

    for (let i = 0; i < count; i++) {
      if (st.cursorLine >= st.lines.length - 1) break;
      const line = this.curLine();
      const nextLine = (st.lines[st.cursorLine + 1] ?? "").trimStart();
      const joinCol = line.length;
      st.lines[st.cursorLine] = line + (nextLine ? " " + nextLine : "");
      st.lines.splice(st.cursorLine + 1, 1);
      this.doSetCursorCol(joinCol);
    }

    this.triggerOnChange();
    this.lastChange = count > 1 ? `${count}J` : "J";
    this.invalidate();
  }

  // ─── Toggle case ─────────────────────────────────────────────────────

  private toggleCase(count: number): void {
    const st = this.st;
    const line = this.curLine();
    this.pushUndo();

    let col = st.cursorCol;
    const chars = line.split("");
    for (let i = 0; i < count && col < chars.length; i++, col++) {
      const ch = chars[col];
      chars[col] =
        ch === ch.toLowerCase() ? ch.toUpperCase() : ch.toLowerCase();
    }
    st.lines[st.cursorLine] = chars.join("");
    this.doSetCursorCol(Math.min(col, Math.max(0, chars.length - 1)));
    this.triggerOnChange();
    this.lastChange = count > 1 ? `${count}~` : "~";
    this.invalidate();
  }

  private toggleCaseInRange(range: Range): void {
    const st = this.st;
    if (range.linewise) {
      for (let l = range.start.line; l <= range.end.line; l++) {
        st.lines[l] = (st.lines[l] ?? "")
          .split("")
          .map((ch) =>
            ch === ch.toLowerCase() ? ch.toUpperCase() : ch.toLowerCase(),
          )
          .join("");
      }
    } else {
      if (range.start.line === range.end.line) {
        const line = st.lines[range.start.line] ?? "";
        const before = line.slice(0, range.start.col);
        const middle = line
          .slice(range.start.col, range.end.col)
          .split("")
          .map((ch) =>
            ch === ch.toLowerCase() ? ch.toUpperCase() : ch.toLowerCase(),
          )
          .join("");
        const after = line.slice(range.end.col);
        st.lines[range.start.line] = before + middle + after;
      }
    }
    this.triggerOnChange();
    this.invalidate();
  }

  private changeCaseInRange(range: Range, toCase: "upper" | "lower"): void {
    const st = this.st;
    const transform =
      toCase === "upper"
        ? (s: string) => s.toUpperCase()
        : (s: string) => s.toLowerCase();
    if (range.linewise) {
      for (let l = range.start.line; l <= range.end.line; l++) {
        st.lines[l] = transform(st.lines[l] ?? "");
      }
    } else if (range.start.line === range.end.line) {
      const line = st.lines[range.start.line] ?? "";
      st.lines[range.start.line] =
        line.slice(0, range.start.col) +
        transform(line.slice(range.start.col, range.end.col)) +
        line.slice(range.end.col);
    }
    this.setCursor(range.start.line, range.start.col);
    this.triggerOnChange();
    this.invalidate();
  }

  // ─── Page scroll ─────────────────────────────────────────────────────

  private pageScroll(direction: number, count: number): void {
    // Approximate half-page as 10 lines
    const amount = 10 * count * direction;
    const newLine = clamp(
      this.st.cursorLine + amount,
      0,
      this.st.lines.length - 1,
    );
    this.setCursor(newLine, this.st.cursorCol);
    this.invalidate();
  }

  // ─── Dot repeat ──────────────────────────────────────────────────────

  private dotRepeat(): void {
    if (!this.lastChange) return;
    this.replaying = true;
    for (const ch of this.lastChange) {
      this.handleInput(ch);
    }
    this.replaying = false;
  }

  /** Pass a key sequence through to pi */
  passthrough(seq: string): void {
    super.handleInput(seq);
  }

  // submitCommand is inherited from SubmitterEditor: it presses
  // Enter via super.handleInput("\r"). Callers (leader-menu's
  // LeaderActionHost) set the editor text via
  // ctx.ui.setEditorText(text) before invoking submitCommand, so
  // the legacy "set text + press Enter" combo is preserved without
  // VimEditor carrying its own copy of the logic.

  // ─── Render ──────────────────────────────────────────────────────────

  render(width: number): string[] {
    const reservedCols =
      this.widthConstraintFraction > 0
        ? Math.max(
            this.widthConstraintMinCols,
            Math.floor(width * this.widthConstraintFraction),
          )
        : 0;
    const effectiveWidth =
      reservedCols > 0 ? Math.max(40, width - reservedCols) : width;
    const lines = super.render(effectiveWidth);
    if (lines.length === 0) return lines;

    const last = lines.length - 1;

    // Pending indicator (mode itself is exposed via ctx.ui.setStatus)
    let pending = "";
    if (this.countStr) pending += this.countStr;
    if (this.pendingOperator) pending += this.pendingOperator;
    if (this.pendingFind) pending += this.pendingFind.type;
    if (this.pendingReplace) pending += "r";

    if (pending) pending = " " + pending + " ";

    const totalSuffix = pending;
    if (totalSuffix && visibleWidth(lines[last]!) >= totalSuffix.length) {
      // (leader-pending suffix is rendered by leader-menu's own overlay
      //  now — nothing to add here.)
      lines[last] =
        truncateToWidth(lines[last]!, effectiveWidth - totalSuffix.length, "") +
        totalSuffix;
    }

    // Visual mode highlighting — render selection via ANSI reverse video
    if (this.mode === "visual" || this.mode === "visual-line") {
      const range = this.getVisualRange();
      // We'd need to modify rendered lines with ANSI codes
      // For now, the mode indicator and cursor position convey the selection
      // Full highlighting would require deeper integration with the render pipeline
    }

    return lines;
  }
}

// ─── Extension entry point ───────────────────────────────────────────────────
//
// vim-mode is a single-responsibility extension: when loaded, it
// installs the modal editor unconditionally. There is no runtime
// toggle, no settings file of its own, and no enable/disable events.
//
// To "disable" vim-mode, remove the extension directory (or in the
// dotfiles repo, comment out / Nix-toggle its inclusion). The legacy
// `~/.pi/agent/vim-mode.json` is no longer read; the `debug` flag (the
// only field that survived the toggle removal) lives in
// `~/.pi/agent/leader-menu.json` and is migrated automatically by the
// `leader-menu` extension on first run.

const LEADER_MENU_SETTINGS_PATH = join(homedir(), ".pi", "agent", "leader-menu.json");

function loadDebugFlag(): boolean {
  try {
    if (!existsSync(LEADER_MENU_SETTINGS_PATH)) return false;
    const parsed = JSON.parse(readFileSync(LEADER_MENU_SETTINGS_PATH, "utf-8"));
    return parsed?.debug === true;
  } catch {
    return false;
  }
}

export default function (pi: ExtensionAPI) {
  let activeEditor: VimEditor | null = null;
  let currentCtx: any = null;
  /** Resolved leader keys, pushed by leader-menu via
   *  `leader-menu:keys-resolved`. Defaults match leader-menu's defaults
   *  so a session that loads vim-mode before leader-menu still works. */
  let leaderKeys: Set<string> = new Set([" ", ","]);

  /** Latest `leader-menu:keys-resolved` payload, cached so the
   *  clash-warning runs whether the event arrives before or after
   *  this extension's `session_start` (load order is not guaranteed).
   *  Cleared on shutdown; warning fires once per session, gated by
   *  `clashWarningEmitted`. */
  let lastKeysResolved: {
    globalLeader?: string;
    localLeader?: string;
    userConfigured?: { globalLeader?: boolean; localLeader?: boolean };
  } | null = null;
  let clashWarningEmitted = false;

  function maybeWarnGrammarClashes(): void {
    if (clashWarningEmitted) return;
    if (!currentCtx?.ui?.notify) return;
    if (!lastKeysResolved) return;
    const data = lastKeysResolved;
    const notify = currentCtx.ui.notify.bind(currentCtx.ui);
    const checkClash = (
      slot: "globalLeader" | "localLeader",
      key: string | undefined,
    ) => {
      if (!key) return;
      if (!data.userConfigured?.[slot]) return;
      if (!VIM_NORMAL_RESERVED_KEYS.has(key)) return;
      const display = displayLeaderKey(key);
      notify(
        `vim-mode: configured ${slot} "${display}" clashes with ` +
          `normal-mode grammar; bare key won't fire there. ` +
          `Use ctrl+${display} or pick a non-grammar key.`,
        "warning",
      );
    };
    checkClash("globalLeader", data.globalLeader);
    checkClash("localLeader", data.localLeader);
    clashWarningEmitted = true;
  }

  /** Cleanup handles for pi event subscriptions. */
  const eventCleanups: (() => void)[] = [];

  pi.on("session_shutdown", () => {
    for (const cleanup of eventCleanups) cleanup();
    eventCleanups.length = 0;
    currentCtx?.ui?.setStatus?.("vim-mode", undefined);
    activeEditor = null;
    currentCtx = null;
    lastKeysResolved = null;
    clashWarningEmitted = false;
  });

  pi.on("session_start", (_event, ctx) => {
    currentCtx = ctx;
    ctx.ui.setEditorComponent(
      (tui: TUI, theme: EditorTheme, keybindings: KeybindingsManager) => {
        const editor = new VimEditor(tui, theme, keybindings);
        editor.setContext(ctx);
        editor.setLeaderOpenEmitter((rootKey: string) => {
          pi.events.emit("leader-menu:open", { rootKey });
        });
        editor.setLeaderKeys(leaderKeys);
        editor.setDebugEnabled(loadDebugFlag());
        activeEditor = editor;
        return editor;
      },
    );
    // If `leader-menu:keys-resolved` arrived before our ctx was set
    // (load order is not guaranteed), the warning was deferred. Run
    // it now that ctx is available.
    maybeWarnGrammarClashes();
  });

  // ── leader-menu:keys-resolved subscription ──────────────────────
  // Sibling `leader-menu` extension publishes the resolved
  // (config-aware) leader keys after init. We cache them so a
  // user-reconfigured leader (`~/.pi/agent/leader-menu.json`) takes
  // effect on the next session without code changes here.
  //
  // We also own the grammar-clash warning: when the user explicitly
  // configures a leader trigger that overlaps with vim's normal-mode
  // grammar, bare-key dispatch will be shadowed in Normal mode. The
  // `userConfigured` flags from the event payload scope the warning
  // to user choices so the shipped default (which deliberately
  // matches the common Vim convention) stays silent. The warning is
  // run via `maybeWarnGrammarClashes()`, which is also called from
  // `session_start` to handle the case where this event arrives
  // before our ctx is set (load order is not guaranteed).
  eventCleanups.push(
    pi.events.on(
      "leader-menu:keys-resolved",
      (data: {
        globalLeader?: string;
        localLeader?: string;
        userConfigured?: { globalLeader?: boolean; localLeader?: boolean };
      }) => {
        lastKeysResolved = data;
        const next = new Set<string>();
        if (data?.globalLeader) next.add(data.globalLeader);
        if (data?.localLeader) next.add(data.localLeader);
        if (next.size > 0) leaderKeys = next;
        activeEditor?.setLeaderKeys(leaderKeys);
        maybeWarnGrammarClashes();
      },
    ),
  );

  // ── editor:width-constraint subscription ─────────────────────────
  // Other extensions (e.g. git-diff) request that the editor reserve
  // some screen width. The constraint method lives on VimEditor.
  eventCleanups.push(
    pi.events.on("editor:width-constraint", (data: any) => {
      const fraction = typeof data?.fraction === "number" ? data.fraction : 0;
      const minCols = typeof data?.minCols === "number" ? data.minCols : 0;
      activeEditor?.setWidthConstraint(fraction, minCols);
    }),
  );
}
