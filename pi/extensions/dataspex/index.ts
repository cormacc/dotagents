import { Type } from "@sinclair/typebox";
import * as fs from "node:fs/promises";
import * as path from "node:path";
import { evalExpr } from "../pi-clojure/nrepl-client";

type ExtensionAPI = {
  registerTool(tool: unknown): void;
};

function defineTool<T>(tool: T): T {
  return tool;
}

const PORT_FILES = [
  ".shadow-cljs/nrepl.port",
  ".nrepl-port",
  "nrepl-port",
  ".cider-nrepl.port",
];

function stringLiteral(value: string): string {
  return JSON.stringify(value);
}

function buildLiteral(buildId: string): string {
  const normalized = buildId.startsWith(":") ? buildId.slice(1) : buildId;
  if (!/^[A-Za-z0-9_.*+!?'<=\>-]+$/.test(normalized)) {
    throw new Error(`Invalid shadow-cljs build id: ${buildId}`);
  }
  return `:${normalized}`;
}

function unwrapClojureString(value: string): string {
  if (value.startsWith('"') && value.endsWith('"')) {
    try {
      return JSON.parse(value) as string;
    } catch {
      return value.slice(1, -1);
    }
  }
  return value;
}

async function readPortFile(cwd: string, file: string): Promise<number | null> {
  try {
    const text = await fs.readFile(path.join(cwd, file), "utf8");
    const port = Number.parseInt(text.trim(), 10);
    return Number.isFinite(port) ? port : null;
  } catch {
    return null;
  }
}

async function discoverPort(cwd: string): Promise<number> {
  for (const file of PORT_FILES) {
    const port = await readPortFile(cwd, file);
    if (port != null) return port;
  }
  throw new Error(
    `No nREPL port found in ${cwd}. Pass port explicitly or run from a project with one of: ${PORT_FILES.join(", ")}`,
  );
}

async function evalJvm(host: string, port: number, code: string, timeout = 30_000): Promise<string> {
  const result = await evalExpr({ host, port, code, timeout });
  if (result.err.trim()) throw new Error(result.err.trim());
  return result.vals.length > 0 ? result.vals[result.vals.length - 1] : "nil";
}

function parseBuildNames(value: string): string[] {
  const names: string[] = [];
  const matcher = /"([^"\\]*(?:\\.[^"\\]*)*)"/g;
  let match: RegExpExecArray | null;
  while ((match = matcher.exec(value)) != null) {
    names.push(unwrapClojureString(`"${match[1]}"`));
  }
  return names;
}

async function discoverBuild(host: string, port: number): Promise<string> {
  const value = await evalJvm(
    host,
    port,
    `(do (require '[shadow.cljs.devtools.api :as shadow]) (vec (map name (shadow/active-builds))))`,
  );
  const builds = parseBuildNames(value);
  if (builds.length === 1) return builds[0];
  if (builds.length === 0) {
    throw new Error("No active shadow-cljs builds found. Start a watch or pass buildId explicitly.");
  }
  throw new Error(
    `Multiple active shadow-cljs builds found (${builds.map((b) => `:${b}`).join(", ")}). Pass buildId explicitly.`,
  );
}

interface DataspexTarget {
  host: string;
  port: number;
  buildId: string;
}

async function resolveTarget(params: { host?: string; port?: number; buildId?: string }, cwd: string): Promise<DataspexTarget> {
  const host = String(params.host ?? "localhost");
  const port = params.port != null ? Number(params.port) : await discoverPort(cwd);
  const buildId = params.buildId != null ? String(params.buildId).replace(/^:/, "") : await discoverBuild(host, port);
  return { host, port, buildId };
}

// The cljs form is expected to `(with-out-str (binding [...] (pr <expr>)))` its
// bounded representation, so its *return value* is an already-bounded string
// that shadow can serialise without hitting its 1 MB writer limit. We then
// `edn/read-string` `:results[0]` on the JVM side to unwrap shadow's pr-str of
// that string. We deliberately avoid `:out` because concurrent cljs-evals
// against the same build share `*out*` at the runtime level — each request's
// `:out` snapshot can include bytes printed by sibling requests, so the field
// is unreliable under pi's parallel tool execution.
//
// Forms that return a non-string value (e.g. the track/untrack maps) pass
// through `edn/read-string` cleanly as Clojure data, since shadow's
// `:results[0]` is the pr-str representation of that data.
async function evalCljs(target: DataspexTarget, cljsForm: string): Promise<string> {
  const code = `
(do
  (require '[shadow.cljs.devtools.api :as shadow]
           '[clojure.edn :as edn])
  (let [r# (shadow/cljs-eval ${buildLiteral(target.buildId)} ${stringLiteral(cljsForm)} {})]
    (when (seq (:err r#))
      (throw (ex-info (:err r#) r#)))
    (edn/read-string (or (first (:results r#)) "nil"))))`;
  return unwrapClojureString(await evalJvm(target.host, target.port, code));
}

function byteLength(text: string): number {
  return new TextEncoder().encode(text).length;
}

function success(text: string, target: DataspexTarget, extra: Record<string, unknown> = {}) {
  return {
    content: [{ type: "text" as const, text }],
    details: { ...extra, host: target.host, port: target.port, buildId: target.buildId, bytes: byteLength(text) },
  };
}

function errorResult(message: string, extra: Record<string, unknown> = {}) {
  return {
    content: [{ type: "text" as const, text: `Error: ${message}` }],
    details: { ...extra, error: message },
    isError: true,
  };
}

const targetParams = {
  buildId: Type.Optional(Type.String({ description: "shadow-cljs build id, with or without leading ':'" })),
  port: Type.Optional(Type.Number({ description: "nREPL port. Defaults to standard port files in the current working directory." })),
  host: Type.Optional(Type.String({ description: "nREPL host", default: "localhost" })),
};

export default function (pi: ExtensionAPI) {
  pi.registerTool(defineTool({
    name: "dataspex_labels",
    label: "Dataspex Labels",
    description: "List Dataspex user labels in a running ClojureScript app via shadow-cljs nREPL, excluding Dataspex internal store keys.",
    promptSnippet: "List Dataspex inspect labels in a running CLJS app",
    parameters: Type.Object({ ...targetParams }),
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      try {
        const target = await resolveTarget(params, ctx.cwd);
        const text = await evalCljs(target, `
(with-out-str
  (binding [*print-length* 200 *print-level* 5]
    (pr
      (into []
        (for [label (filter string? (keys @dataspex.core/store))
              :let [e (get @dataspex.core/store label)]]
          {:label label
           :rev (:rev e)
           :idx (:idx e)
           :history-len (count (:history e))
           :val-type (let [v# (:val e)
                           t# (str (type v#))]
                       (cond
                         (map? v#) "map"
                         (vector? v#) "vector"
                         (set? v#) "set"
                         (seq? v#) "seq"
                         :else (subs t# 0 (min 80 (count t#)))))
           :has-ref? (some? (:ref e))})))))`);
        return success(text, target);
      } catch (e) {
        return errorResult(e instanceof Error ? e.message : String(e));
      }
    },
  }));

  pi.registerTool(defineTool({
    name: "dataspex_value",
    label: "Dataspex Value",
    description: "Read a bounded current value from a Dataspex label. Optional path is an EDN vector such as [:patient :name]; fresh dereferences the underlying ref when present.",
    promptSnippet: "Read current value from a Dataspex label",
    parameters: Type.Object({
      label: Type.String({ description: "Dataspex label to read" }),
      path: Type.Optional(Type.String({ description: "EDN vector path to navigate, e.g. [:patient :name]. Defaults to []." })),
      fresh: Type.Optional(Type.Boolean({ description: "Dereference :ref instead of reading the last :val snapshot" })),
      limit: Type.Optional(Type.Number({ description: "CLJS *print-length* bound (default 50)" })),
      level: Type.Optional(Type.Number({ description: "CLJS *print-level* bound (default 5)" })),
      ...targetParams,
    }),
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      try {
        const target = await resolveTarget(params, ctx.cwd);
        const label = stringLiteral(String(params.label));
        const navPath = params.path ? String(params.path) : "[]";
        const navPathLiteral = stringLiteral(navPath);
        const limit = Number(params.limit ?? 50);
        const level = Number(params.level ?? 5);
        const valueExpr = params.fresh
          ? `(some-> (:ref entry#) deref)`
          : `(:val entry#)`;
        const text = await evalCljs(target, `
(do
  (require '[cljs.reader :as reader])
  (with-out-str
    (binding [*print-length* ${limit} *print-level* ${level}]
      (pr
        (let [entry# (get @dataspex.core/store ${label})
              value# ${valueExpr}
              path# (reader/read-string ${navPathLiteral})]
          (when-not (vector? path#)
            (throw (ex-info "dataspex_value: path must be an EDN vector" {:path path#})))
          (if (seq path#) (get-in value# path#) value#))))))`);
        return success(text, target, { label: params.label, path: navPath, fresh: Boolean(params.fresh) });
      } catch (e) {
        return errorResult(e instanceof Error ? e.message : String(e));
      }
    },
  }));

  pi.registerTool(defineTool({
    name: "dataspex_history",
    label: "Dataspex History",
    description: "Read the latest Dataspex audit history entries for a label. If <label>-audit exists, it is used as the parallel tracking label.",
    promptSnippet: "Read Dataspex audit history for a label",
    parameters: Type.Object({
      label: Type.String({ description: "Dataspex label or original label with a parallel <label>-audit tracker" }),
      n: Type.Optional(Type.Number({ description: "Number of latest entries to return (default 10)" })),
      includeVal: Type.Optional(Type.Boolean({ description: "Include full :val snapshots; default false" })),
      ...targetParams,
    }),
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      try {
        const target = await resolveTarget(params, ctx.cwd);
        const label = stringLiteral(String(params.label));
        const n = Number(params.n ?? 10);
        const keys = params.includeVal ? "[:rev :created-at :diff :val]" : "[:rev :created-at :diff]";
        const text = await evalCljs(target, `
(with-out-str
  (binding [*print-length* 80 *print-level* 6]
    (pr
      (let [label# ${label}
            audit-label# (str label# "-audit")
            actual-label# (if (contains? @dataspex.core/store audit-label#) audit-label# label#)
            hist# (:history (get @dataspex.core/store actual-label#))]
        {:label actual-label#
         :history (->> hist#
                       (take ${n})
                       (mapv (fn [h#] (select-keys h# ${keys}))))}))))`);
        return success(text, target, { label: params.label, n, includeVal: Boolean(params.includeVal) });
      } catch (e) {
        return errorResult(e instanceof Error ? e.message : String(e));
      }
    },
  }));

  pi.registerTool(defineTool({
    name: "dataspex_track",
    label: "Dataspex Track",
    description: "Register a parallel <label>-audit Dataspex label with :track-changes? true. Refuses to overwrite an existing audit label.",
    promptSnippet: "Track Dataspex changes with a parallel audit label",
    parameters: Type.Object({
      label: Type.String({ description: "Existing Dataspex label whose :ref should be tracked" }),
      historyLimit: Type.Optional(Type.Number({ description: "History limit fixed at registration time (default 50)" })),
      ...targetParams,
    }),
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      try {
        const target = await resolveTarget(params, ctx.cwd);
        const label = stringLiteral(String(params.label));
        const historyLimit = Number(params.historyLimit ?? 50);
        const text = await evalCljs(target, `
(let [label# ${label}
      audit-label# (str label# "-audit")
      entry# (get @dataspex.core/store label#)
      ref# (:ref entry#)]
  (cond
    (nil? entry#)
    (throw (ex-info (str "dataspex_track: no such label \\"" label# "\\"") {:reason :missing-label :label label#}))

    (contains? @dataspex.core/store audit-label#)
    (throw (ex-info (str "dataspex_track: audit label \\"" audit-label# "\\" already exists; untrack first") {:reason :audit-label-exists :label audit-label#}))

    (nil? ref#)
    (throw (ex-info (str "dataspex_track: label \\"" label# "\\" has no :ref to watch") {:reason :not-watchable :label label#}))

    :else
    (do (dataspex.core/inspect audit-label# ref# {:track-changes? true :history-limit ${historyLimit}})
        {:tracked audit-label# :history-limit ${historyLimit}})))`);
        return success(text, target, { label: params.label, historyLimit });
      } catch (e) {
        return errorResult(e instanceof Error ? e.message : String(e));
      }
    },
  }));

  pi.registerTool(defineTool({
    name: "dataspex_untrack",
    label: "Dataspex Untrack",
    description: "Remove the parallel <label>-audit Dataspex label created by dataspex_track.",
    promptSnippet: "Remove a Dataspex parallel audit label",
    parameters: Type.Object({
      label: Type.String({ description: "Original label or explicit <label>-audit label" }),
      ...targetParams,
    }),
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      try {
        const target = await resolveTarget(params, ctx.cwd);
        const label = stringLiteral(String(params.label));
        const text = await evalCljs(target, `
(let [label# ${label}
      audit-label# (if (.endsWith label# "-audit") label# (str label# "-audit"))
      was-present?# (contains? @dataspex.core/store audit-label#)]
  (dataspex.core/uninspect audit-label#)
  {:untracked audit-label# :was-present? was-present?#})`);
        return success(text, target, { label: params.label });
      } catch (e) {
        return errorResult(e instanceof Error ? e.message : String(e));
      }
    },
  }));

  pi.registerTool(defineTool({
    name: "dataspex_db_query",
    label: "Dataspex DB Query",
    description: "Run a datascript query against a DB stored under a Dataspex label. Returns the result set only, never the whole DB.",
    promptSnippet: "Query a runtime Datascript DB exposed through Dataspex",
    parameters: Type.Object({
      label: Type.String({ description: "Dataspex label whose value is a datascript DB or conn snapshot" }),
      q: Type.String({ description: "EDN datascript query, e.g. [:find ?e :where [?e :patient/id]]" }),
      args: Type.Optional(Type.String({ description: "EDN vector of extra query args after the DB, default []" })),
      limit: Type.Optional(Type.Number({ description: "CLJS *print-length* bound (default 100)" })),
      level: Type.Optional(Type.Number({ description: "CLJS *print-level* bound (default 5)" })),
      ...targetParams,
    }),
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      try {
        const target = await resolveTarget(params, ctx.cwd);
        const label = stringLiteral(String(params.label));
        const q = stringLiteral(String(params.q));
        const args = stringLiteral(String(params.args ?? "[]"));
        const limit = Number(params.limit ?? 100);
        const level = Number(params.level ?? 5);
        const text = await evalCljs(target, `
(do
  (require '[cljs.reader :as reader] '[datascript.core :as d])
  (with-out-str
    (binding [*print-length* ${limit} *print-level* ${level}]
      (pr
        (let [db# (:val (get @dataspex.core/store ${label}))
              q# (reader/read-string ${q})
              args# (reader/read-string ${args})]
          (apply d/q q# db# args#))))))`);
        return success(text, target, { label: params.label });
      } catch (e) {
        return errorResult(e instanceof Error ? e.message : String(e));
      }
    },
  }));

  pi.registerTool(defineTool({
    name: "dataspex_actions_tail",
    label: "Dataspex Actions Tail",
    description: "Read the latest nexus action log entries from a Dataspex LogInspector label, projected to dispatch time and data by default.",
    promptSnippet: "Tail nexus action log entries exposed through Dataspex",
    parameters: Type.Object({
      label: Type.Optional(Type.String({ description: "Dataspex action-log label (default Actions)" })),
      n: Type.Optional(Type.Number({ description: "Number of latest entries to return (default 20)" })),
      ...targetParams,
    }),
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      try {
        const target = await resolveTarget(params, ctx.cwd);
        const label = stringLiteral(String(params.label ?? "Actions"));
        const n = Number(params.n ?? 20);
        const text = await evalCljs(target, `
(with-out-str
  (binding [*print-length* 80 *print-level* 5]
    (pr
      (let [li# (:val (get @dataspex.core/store ${label}))
            log# (aget li# "log")]
        (->> log#
             (take-last ${n})
             (mapv (fn [entry#]
                     (select-keys entry# [:dispatched-at :dispatch-data]))))))))`);
        return success(text, target, { label: params.label ?? "Actions", n });
      } catch (e) {
        return errorResult(e instanceof Error ? e.message : String(e));
      }
    },
  }));
}
