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

const OP_VALUES = ["labels", "value", "history", "track", "untrack", "db_query", "actions_tail"] as const;
type DataspexOp = typeof OP_VALUES[number];

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

function requireString(value: unknown, name: string): string {
  if (value == null) throw new Error(`dataspex: ${name} is required`);
  const text = String(value);
  if (text.trim().length === 0) throw new Error(`dataspex: ${name} must be a non-empty string`);
  return text;
}

function optionalString(value: unknown, defaultValue: string): string {
  return value != null ? String(value) : defaultValue;
}

function positiveInt(value: unknown, defaultValue: number, name: string): number {
  if (value == null) return defaultValue;
  const number = Number(value);
  if (!Number.isFinite(number) || !Number.isInteger(number) || number <= 0) {
    throw new Error(`dataspex: ${name} must be a positive integer`);
  }
  return number;
}

async function opLabels(target: DataspexTarget) {
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
  return success(text, target, { op: "labels" });
}

async function opValue(target: DataspexTarget, params: { label: string; path: string; fresh: boolean; limit: number; level: number }) {
  const label = stringLiteral(params.label);
  const navPathLiteral = stringLiteral(params.path);
  const valueExpr = params.fresh
    ? `(some-> (:ref entry#) deref)`
    : `(:val entry#)`;
  const text = await evalCljs(target, `
(do
  (require 'cljs.reader)
  (with-out-str
    (binding [*print-length* ${params.limit} *print-level* ${params.level}]
      (pr
        (let [entry# (get @dataspex.core/store ${label})
              value# ${valueExpr}
              path# (cljs.reader/read-string ${navPathLiteral})]
          (when-not (vector? path#)
            (throw (ex-info "dataspex value: path must be an EDN vector" {:path path#})))
          (if (seq path#) (get-in value# path#) value#))))))`);
  return success(text, target, { op: "value", label: params.label, path: params.path, fresh: params.fresh });
}

async function opHistory(target: DataspexTarget, params: { label: string; n: number; includeVal: boolean }) {
  const label = stringLiteral(params.label);
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
                       (take ${params.n})
                       (mapv (fn [h#] (select-keys h# ${keys}))))}))))`);
  return success(text, target, { op: "history", label: params.label, n: params.n, includeVal: params.includeVal });
}

async function opTrack(target: DataspexTarget, params: { label: string; historyLimit: number }) {
  const label = stringLiteral(params.label);
  const text = await evalCljs(target, `
(let [label# ${label}
      audit-label# (str label# "-audit")
      entry# (get @dataspex.core/store label#)
      ref# (:ref entry#)]
  (cond
    (nil? entry#)
    (throw (ex-info (str "dataspex track: no such label \\"" label# "\\"") {:reason :missing-label :label label#}))

    (contains? @dataspex.core/store audit-label#)
    (throw (ex-info (str "dataspex track: audit label \\"" audit-label# "\\" already exists; untrack first") {:reason :audit-label-exists :label audit-label#}))

    (nil? ref#)
    (throw (ex-info (str "dataspex track: label \\"" label# "\\" has no :ref to watch") {:reason :not-watchable :label label#}))

    :else
    (do (dataspex.core/inspect audit-label# ref# {:track-changes? true :history-limit ${params.historyLimit}})
        {:tracked audit-label# :history-limit ${params.historyLimit}})))`);
  return success(text, target, { op: "track", label: params.label, historyLimit: params.historyLimit });
}

async function opUntrack(target: DataspexTarget, params: { label: string }) {
  const label = stringLiteral(params.label);
  const text = await evalCljs(target, `
(let [label# ${label}
      audit-label# (if (.endsWith label# "-audit") label# (str label# "-audit"))
      was-present?# (contains? @dataspex.core/store audit-label#)]
  (dataspex.core/uninspect audit-label#)
  {:untracked audit-label# :was-present? was-present?#})`);
  return success(text, target, { op: "untrack", label: params.label });
}

async function opDbQuery(target: DataspexTarget, params: { label: string; q: string; args: string; limit: number; level: number }) {
  const label = stringLiteral(params.label);
  const q = stringLiteral(params.q);
  const args = stringLiteral(params.args);
  const text = await evalCljs(target, `
(do
  (require 'cljs.reader 'datascript.core)
  (with-out-str
    (binding [*print-length* ${params.limit} *print-level* ${params.level}]
      (pr
        (let [db# (:val (get @dataspex.core/store ${label}))
              q# (cljs.reader/read-string ${q})
              args# (cljs.reader/read-string ${args})]
          (apply datascript.core/q q# db# args#))))))`);
  return success(text, target, { op: "db_query", label: params.label });
}

async function opActionsTail(target: DataspexTarget, params: { label: string; n: number }) {
  const label = stringLiteral(params.label);
  const text = await evalCljs(target, `
(with-out-str
  (binding [*print-length* 80 *print-level* 5]
    (pr
      (let [li# (:val (get @dataspex.core/store ${label}))
            log# (aget li# "log")]
        (->> log#
             (take-last ${params.n})
             (mapv (fn [entry#]
                     {:dispatched-at (:dispatched-at entry#)
                      :actions (some-> (:actions entry#) (.-data))
                      :dispatch-data (:dispatch-data entry#)})))))))`);
  return success(text, target, { op: "actions_tail", label: params.label, n: params.n });
}

const targetParams = {
  buildId: Type.Optional(Type.String({ description: "shadow-cljs build id" })),
  port: Type.Optional(Type.Number({ description: "nREPL port" })),
  host: Type.Optional(Type.String({ description: "nREPL host", default: "localhost" })),
};

const opSchema = Type.Union(OP_VALUES.map((op) => Type.Literal(op)));

export default function (pi: ExtensionAPI) {
  pi.registerTool(defineTool({
    name: "dataspex",
    label: "Dataspex",
    description: "Inspect Dataspex CLJS runtime state with op labels,value,history,track,untrack,db_query,actions_tail.",
    promptSnippet: "Inspect Dataspex with op labels/value/history/track/untrack/db_query/actions_tail",
    parameters: Type.Object({
      op: opSchema,
      label: Type.Optional(Type.String({ description: "Dataspex label" })),
      path: Type.Optional(Type.String({ description: "EDN vector path" })),
      fresh: Type.Optional(Type.Boolean({ description: "deref :ref for value" })),
      n: Type.Optional(Type.Number({ description: "entry count" })),
      includeVal: Type.Optional(Type.Boolean({ description: "include history :val" })),
      historyLimit: Type.Optional(Type.Number({ description: "audit history limit" })),
      q: Type.Optional(Type.String({ description: "EDN datascript query" })),
      args: Type.Optional(Type.String({ description: "EDN query args vector" })),
      limit: Type.Optional(Type.Number({ description: "print length" })),
      level: Type.Optional(Type.Number({ description: "print level" })),
      ...targetParams,
    }),
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      try {
        const op = String(params.op) as DataspexOp;
        if (!(OP_VALUES as readonly string[]).includes(op)) {
          throw new Error(`dataspex: unknown op ${String(params.op)}; expected one of ${OP_VALUES.join(", ")}`);
        }

        let run: (target: DataspexTarget) => Promise<unknown>;
        switch (op) {
          case "labels":
            run = (target) => opLabels(target);
            break;
          case "value": {
            const label = requireString(params.label, "label");
            const path = params.path ? String(params.path) : "[]";
            const fresh = Boolean(params.fresh);
            const limit = positiveInt(params.limit, 50, "limit");
            const level = positiveInt(params.level, 5, "level");
            run = (target) => opValue(target, { label, path, fresh, limit, level });
            break;
          }
          case "history": {
            const label = requireString(params.label, "label");
            const n = positiveInt(params.n, 10, "n");
            const includeVal = Boolean(params.includeVal);
            run = (target) => opHistory(target, { label, n, includeVal });
            break;
          }
          case "track": {
            const label = requireString(params.label, "label");
            const historyLimit = positiveInt(params.historyLimit, 50, "historyLimit");
            run = (target) => opTrack(target, { label, historyLimit });
            break;
          }
          case "untrack": {
            const label = requireString(params.label, "label");
            run = (target) => opUntrack(target, { label });
            break;
          }
          case "db_query": {
            const label = requireString(params.label, "label");
            const q = requireString(params.q, "q");
            const args = optionalString(params.args, "[]");
            const limit = positiveInt(params.limit, 100, "limit");
            const level = positiveInt(params.level, 5, "level");
            run = (target) => opDbQuery(target, { label, q, args, limit, level });
            break;
          }
          case "actions_tail": {
            const label = optionalString(params.label, "Actions");
            const n = positiveInt(params.n, 20, "n");
            run = (target) => opActionsTail(target, { label, n });
            break;
          }
        }

        const target = await resolveTarget(params, ctx.cwd);
        return await run(target);
      } catch (e) {
        return errorResult(e instanceof Error ? e.message : String(e));
      }
    },
  }));
}
