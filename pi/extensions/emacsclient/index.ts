/**
 * Emacsclient extension for pi.
 *
 * Provides tools for interacting with a running Emacs session:
 *   - emacs_read: Read files/buffers with comprehensive metadata and navigation
 *   - emacs_write: Write to files/buffers via Emacs
 *   - emacs_eval: Evaluate arbitrary elisp
 *   - emacs_ts_query: Run tree-sitter queries against buffers
 *
 * Most tools require an Emacs server running (emacs --daemon or M-x server-start).
 * The `emacs:open` event will auto-start `emacs --daemon` if needed.
 * Set EMACS_SOCKET_NAME to specify a non-default socket.
 */

import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { Type } from "@sinclair/typebox";
import {
  buildTsQueryElisp,
  buildEvalElisp,
  buildReadElisp,
  buildWriteElisp,
  escapeElispString,
} from "./elisp.ts";
import { emacsEval, ensureEmacsServer } from "./emacsclient.ts";
import type { EmacsclientOptions } from "./emacsclient.ts";
import { focusWindow } from "../lib/wm.ts";

export default function (pi: ExtensionAPI) {
  // ------------------------------------------------------------------
  // Event: emacs:open — open a file in Emacs at a specific position
  // ------------------------------------------------------------------
  // Other extensions can emit this to open a file in Emacs:
  //   pi.events.emit("emacs:open", { file: "/path/to/file", line: 42 })
  // If no Emacs server is reachable, bootstrap one first.
  pi.events.on(
    "emacs:open",
    async (data: { file: string; line?: number; col?: number }) => {
      const opts = getOptions();
      if (!(await ensureEmacsServer(opts))) return;

      const file = data.file;
      const gotoLine =
        data.line != null
          ? `(goto-line ${data.line})`
          : "";
      const gotoCol =
        data.col != null
          ? `(move-to-column ${data.col})`
          : "";
      const elisp = `(progn
        (find-file "${escapeElispString(file)}")
        ${gotoLine}
        ${gotoCol}
        (select-frame-set-input-focus (selected-frame))
        nil)`;
      await emacsEval(elisp, opts);
      // Emacs's select-frame-set-input-focus doesn't reliably steal
      // focus on Wayland, so we ask the compositor directly.
      await focusWindow("emacs", opts.exec);
    },
  );

  // Cache for buffer metadata to reduce token usage
  // Key: buffer name or path, Value: last known metadata
  const metadataCache = new Map<string, Record<string, unknown>>();

  // Helper to filter metadata, returning only changed fields
  function getCachedMetadata(
    name: string,
    fullData: Record<string, unknown>
  ): Record<string, unknown> {
    const cached = metadataCache.get(name);

    // If no cache, return full data and cache it
    if (!cached) {
      metadataCache.set(name, { ...fullData });
      return fullData;
    }

    // Build result with only changed metadata
    const result: Record<string, unknown> = {};
    let hasChanges = false;

    // Always include content-related fields and important state fields
    if ('got' in fullData) {
      result.got = fullData.got;
    }
    if ('content' in fullData) {
      result.content = fullData.content;
    }
    // Always include unsaved and outdated to prevent LLM confusion about buffer state
    if ('unsaved' in fullData) {
      result.unsaved = fullData.unsaved;
    }
    if ('outdated' in fullData) {
      result.outdated = fullData.outdated;
    }

    // Compare metadata fields and include only changes
    for (const key of Object.keys(fullData)) {
      // Skip fields already handled above
      if (key === 'got' || key === 'content' || key === 'unsaved' || key === 'outdated') continue;

      const currentValue = fullData[key];
      const cachedValue = cached[key];

      // Deep comparison for nested objects (like point, region)
      const isDifferent = JSON.stringify(currentValue) !== JSON.stringify(cachedValue);

      if (isDifferent) {
        result[key] = currentValue;
        hasChanges = true;
      }
    }

    // Update cache with current state
    metadataCache.set(name, { ...fullData });

    // If only content changed, indicate metadata is unchanged
    if (!hasChanges && !('got' in result) && !('content' in result)) {
      result._cached = true;
    }

    return result;
  }

  // Build shared emacsclient options using pi.exec
  function getOptions(signal?: AbortSignal): EmacsclientOptions {
    const env = (globalThis as { [key: string]: unknown })["process"] as
      | { env?: Record<string, string | undefined> }
      | undefined;
    return {
      // Allow tests to override emacsclient/emacs binaries via environment variables
      binary: env?.env?.EMACSCLIENT_BINARY || "emacsclient",
      daemonBinary: env?.env?.EMACS_BINARY || "emacs",
      exec: (cmd, args, opts) =>
        pi.exec(cmd, args, {
          signal: opts?.signal,
          timeout: opts?.timeout,
        }),
      signal,
    };
  }

  // ------------------------------------------------------------------
  // Tool: emacs_eval
  // ------------------------------------------------------------------
  pi.registerTool({
    name: "emacs_eval",
    label: "Emacs Eval",
    description:
      "Eval SMALL ELisp expression in the long-running Emacs session & " +
        "return result (put big exprs in buffers instead!). Combine with " +
        "emacs_read/emacs_write to use search, EWW, eglot, etc.",
    parameters: Type.Object({
      expression: Type.String({
        description: "Emacs Lisp expression to evaluate",
      }),
    }),
    async execute(toolCallId, params, signal) {
      const elisp = buildEvalElisp(params.expression);
      const result = await emacsEval(elisp, getOptions(signal));

      if (!result.success) {
        return {
          content: [{ type: "text", text: `Error: ${result.error}` }],
          details: { error: result.error },
          isError: true,
        };
      }

      const text =
        typeof result.data === "string"
          ? result.data
          : JSON.stringify(result.data, null, 2);

      return {
        content: [{ type: "text", text }],
        details: { result: result.data },
      };
    },
  });

  // ------------------------------------------------------------------
  // Tool: emacs_ts_query
  // ------------------------------------------------------------------
  pi.registerTool({
    name: "emacs_ts_query",
    label: "Emacs TreeSitter Query",
    description:
      "Run a tree-sitter query against an Emacs buffer and optionally execute " +
      "an elisp action for each match. Returns the list of results. " +
      "Use this for structural code queries and syntax-aware edits.",
    parameters: Type.Object({
      buffer: Type.String({
        description: "Buffer name or file path",
      }),
      query: Type.String({
        description:
          'Tree-sitter query with @captures, e.g. "(function_definition name: (identifier) @name)"',
      }),
      lang: Type.Optional(
        Type.String({
          description:
            "Tree-sitter language hint (e.g. python, javascript). Auto-detected if omitted.",
        })
      ),
      action: Type.Optional(
        Type.String({
          description:
            "Elisp expression to evaluate for each match. " +
            "Each @capture from the query is bound as a variable holding the tree-sitter node. " +
            'Defaults to returning the matched node text. Example: \'(treesit-node-text node t)\'',
        })
      ),
    }),
    async execute(toolCallId, params, signal) {
      const elisp = buildTsQueryElisp(
        params.buffer,
        params.query,
        params.lang,
        params.action
      );
      const result = await emacsEval(elisp, getOptions(signal));

      if (!result.success) {
        return {
          content: [{ type: "text", text: `Error: ${result.error}` }],
          details: { error: result.error },
          isError: true,
        };
      }

      const results = result.data as string[];
      const text = JSON.stringify({ results, count: results.length }, null, 2);

      return {
        content: [{ type: "text", text }],
        details: { results },
      };
    },
  });

  // ------------------------------------------------------------------
  // Tool: emacs_read
  // ------------------------------------------------------------------
  pi.registerTool({
    name: "emacs_read",
    label: "Emacs Read File/Buffer",
    description:
      "Read content & state of an Emacs buffer (existing or new) up to a max " +
        "length (51200 chars). Can open paths (file/dir); can move point; " +
        "can limit to chars/lines/span; can build up Emacs state for later " +
        "reads/edits/etc.",
    parameters: Type.Object({
      name: Type.String({
        description:
          "Begin '/', './' or '../' for paths (inc. TRAMP); else is a buffer " +
            "name. New buffer opened if needed. '*', '/', '<' or '>' gets no " +
            "file ('*foo*' convention), else treated like './' prefix.",
      }),
      span: Type.Optional(
        Type.String({
          description:
            "Narrow to a span ID (result of a previous emacs_read)."
        })
      ),
      pos: Type.Optional(
        Type.Number({
          description:
            "Position to begin reading buffer/span. +ve counts from start " +
              "(1-indexed); -ve counts backwards from point. Default is 0, " +
              "which uses point.",
        })
      ),
      line: Type.Optional(
        Type.Number({
          description:
            "Same as 'pos' but for lines. If both are given, 'pos' is used.",
        })
      ),
      col: Type.Optional(
        Type.Number({
          description:
            "Optional column number to begin reading buffer, if using 'line'.",
        })
      ),
      length: Type.Optional(
        Type.Number({
          description:
            "Number of characters to read from buffer. Result may be " +
              "shorter due to end-of-buffer/span, truncation to max length, " +
              "or due to 'lines'. Default is max length (51200). " +
              "Hint: 0 saves tokens if we only want metadata or to move point.",
        })
      ),
      lines: Type.Optional(
        Type.Number({
          description:
            "Number of lines to read. Result may be shorter due to " +
              "end-of-buffer, or truncation to 'length' chars.",
        })
      ),
      move: Type.Optional(
        Type.Boolean({
          description:
            "When true, point moves to the end of what was read, so a " +
              "subsequent emacs_read with no 'pos' can continue from there. " +
              "When false (default), point remains in its original " +
              "position.",
          default: false,
        })
      ),
      temp: Type.Optional(
        Type.Boolean({
          description:
            "Kills buffer after reading, unless it was already open. Default: " +
              "false.",
          default: false,
        })
      ),
    }),
    async execute(toolCallId, params, signal) {
      const maxLength = 51200; // Default max length
      const elisp = buildReadElisp(
        params.name,
        {
          pos: params.pos,
          line: params.line,
          col: params.col,
          length: params.length,
          lines: params.lines,
          temp: params.temp,
          move: params.move,
          span: params.span,
        },
        maxLength
      );
      const result = await emacsEval(elisp, getOptions(signal));

      if (!result.success) {
        return {
          content: [{ type: "text", text: `Error: ${result.error}` }],
          details: { error: result.error },
          isError: true,
        };
      }

      const fullData = result.data as Record<string, unknown>;

      // Use cached metadata to reduce token usage

      const data = getCachedMetadata(params.name, fullData);

      // Extract the content pieces
      const gotContent = (data as any).got?.content || '';
      const regionContent = (data as any).region?.content || '';

      // Create a copy of data without the content fields
      const metaData = JSON.parse(JSON.stringify(data));
      if (metaData.got) {
        delete metaData.got.content;
      }
      if (metaData.region) {
        delete metaData.region.content;
      }

      // Build the content array
      const contentArray: { type: "text"; text: string }[] = [];

      // First message: raw got.content
      if (gotContent) {
        contentArray.push({ type: "text", text: gotContent });
      }

      // Second message: meta JSON
      let metaMessage = "meta: " + JSON.stringify(metaData, null, 2);
      if (regionContent) {
        metaMessage += "\nregion:";
      }
      contentArray.push({ type: "text", text: metaMessage });

      // Third message (if region.content exists): region content
      if (regionContent) {
        contentArray.push({ type: "text", text: regionContent });
      }

      return {
        content: contentArray,
        details: data,
      };
    },
  });

  // ------------------------------------------------------------------
  // Tool: emacs_write
  // ------------------------------------------------------------------
  pi.registerTool({
    name: "emacs_write",
    label: "Emacs Write to File/Buffer",
    description:
      "Insert text into Emacs buffer at a specific position, and/or type a " +
        "key sequence. Can create new files/buffers, move point, insert " +
        "content, type keys, and save. Old content remains, unless 'replace' " +
        "is given!",
    parameters: Type.Object({
      name: Type.String({
        description:
          "Begin '/', './' or '../' for paths (inc. TRAMP); else is a buffer " +
            "name. New buffer opened if needed. '*', '/', '<' or '>' gets no " +
            "file ('*foo*' convention), else treated like './' prefix.",
      }),
      insert: Type.Optional(Type.String({
        description: "Text to insert at the specified position.",
      })),
      type: Type.Optional(Type.String({
        description: "Keyboard macro to type in buffer (via 'kbd'). Runs " +
          "after insert (if given) and before save (if applicable).",
      })),
      pos: Type.Optional(
        Type.Number({
          description:
            "Position to insert at. Positive counts from start of buffer " +
              "(1-indexed); negative counts back from end. Conflicts with " +
              "'line', 'point', 'replace'.",
        })
      ),
      line: Type.Optional(
        Type.Number({
          description:
            "Line number to insert at. +ve is from start (1-indexed), -ve " +
              "counts back from end. Conflicts with 'pos', 'point', 'replace'.",
        })
      ),
      point: Type.Optional(
        Type.Boolean({
          description:
            "true to insert at point (start of file if newly opened). " +
              "Default when no 'pos' or 'line' given. Conflicts with those.",
          default: false,
        })
      ),
      replace: Type.Optional(
        Type.Boolean({
          description:
            "When true, completely clears the contents of buffer before " +
              "inserting. This makes 'point', 'pos' and 'line' meaningless."
        })
      ),
      save: Type.Optional(
        Type.Boolean({
          description:
            "If buffer is backed by a file, save it to disk after inserting. " +
              "Creates parent directories if needed. Default: true.",
          default: true,
        })
      ),
      temp: Type.Optional(
        Type.Boolean({
          description:
            "true to restore Emacs state afterwards: killing new buffers, " +
            "restoring point in existing buffers.",
          default: false,
        })
      ),
    }),
    async execute(toolCallId, params, signal) {
      const elisp = buildWriteElisp(
        params.name,
        params.insert,
        {
          pos: params.pos,
          line: params.line,
          point: params.point,
          replace: params.replace,
          save: params.save,
          temp: params.temp,
          type: params.type,
        }
      );
      const result = await emacsEval(elisp, getOptions(signal));

      if (!result.success) {
        return {
          content: [{ type: "text", text: `Error: ${result.error}` }],
          details: { error: result.error },
          isError: true,
        };
      }

      const fullData = result.data as Record<string, unknown>;

      // Use cached metadata to reduce token usage

      const data = getCachedMetadata(params.name, fullData);
      const text = JSON.stringify(data, null, 2);

      return {
        content: [{ type: "text", text }],
        details: data,
      };
    },
  });
}
