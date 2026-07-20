// SPDX-License-Identifier: EPL-2.0
// Copyright © 2026-present Marko Kocic <marko@euptera.com>

import * as fs from "node:fs";
import * as path from "node:path";
import { defineTool } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { evalExpr } from "../nrepl-client";

const PORT_FILES = [
  ".nrepl-port",
  "nrepl-port",
  ".shadow-cljs/nrepl.port",
  ".cider-nrepl.port",
];
const DEFAULT_PORTS = [7888, 1666, 50505, 58885, 63333, 7889];
const VALIDATION_TIMEOUT_MS = 5_000;

function abortError(): Error {
  return new Error("nREPL port discovery aborted");
}

function isPort(port: number): boolean {
  return Number.isInteger(port) && port >= 1 && port <= 65_535;
}

async function readPortFile(filePath: string): Promise<number | null> {
  try {
    const content = await fs.promises.readFile(filePath, "utf8");
    const port = Number(content.trim());
    return isPort(port) ? port : null;
  } catch {
    return null;
  }
}

async function findPortInDirectory(dir: string, signal?: AbortSignal): Promise<number | null> {
  for (const file of PORT_FILES) {
    if (signal?.aborted) throw abortError();
    const port = await readPortFile(path.join(dir, file));
    if (port !== null) return port;
  }
  return null;
}

async function validatePort(host: string, port: number, signal?: AbortSignal): Promise<boolean> {
  try {
    const result = await evalExpr({ host, port, code: "(+ 1 1)", timeout: VALIDATION_TIMEOUT_MS, signal });
    return result.vals.length > 0 && result.vals[0] === "2";
  } catch {
    return false;
  }
}

type ValidatePort = (host: string, port: number, signal?: AbortSignal) => Promise<boolean>;

async function validateWithinBudget(
  validate: ValidatePort,
  host: string,
  port: number,
  timeoutMs: number,
  signal?: AbortSignal,
): Promise<boolean> {
  if (signal?.aborted) throw abortError();

  const controller = new AbortController();
  const abort = () => controller.abort();
  signal?.addEventListener("abort", abort, { once: true });

  let timeoutHandle: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<false>((resolve) => {
    timeoutHandle = setTimeout(() => {
      controller.abort();
      resolve(false);
    }, timeoutMs);
  });
  let rejectCancelled: (reason: Error) => void;
  const cancelled = new Promise<never>((_, reject) => {
    rejectCancelled = reject;
  });
  const onCancelled = () => rejectCancelled(abortError());
  signal?.addEventListener("abort", onCancelled, { once: true });

  try {
    return await Promise.race([validate(host, port, controller.signal), timeout, cancelled]);
  } finally {
    if (timeoutHandle !== undefined) clearTimeout(timeoutHandle);
    signal?.removeEventListener("abort", abort);
    signal?.removeEventListener("abort", onCancelled);
  }
}

interface FindNreplPortDependencies {
  cwd?: () => string;
  validate?: ValidatePort;
  defaultPorts?: readonly number[];
  validationTimeoutMs?: number;
}

export function createFindNreplPortTool(deps: FindNreplPortDependencies = {}) {
  const getCwd = deps.cwd ?? (() => process.cwd());
  const checkPort = deps.validate ?? validatePort;
  const defaultPorts = deps.defaultPorts ?? DEFAULT_PORTS;
  const validationTimeoutMs = deps.validationTimeoutMs ?? VALIDATION_TIMEOUT_MS;

  return defineTool({
    name: "clojure_find_nrepl_port",
    label: "Clojure Find nREPL Port",
    description:
      "Find an nREPL port from project port files or common defaults. Each probe has a five-second budget and discovery stops when cancelled.",
    promptSnippet: "Find nREPL port in current directory",
    parameters: Type.Object({}),

    async execute(_toolCallId, _params, signal, _onUpdate, _ctx) {
      if (signal?.aborted) throw abortError();
      const cwd = getCwd();
      const host = "localhost";
      const portFromFile = await findPortInDirectory(cwd, signal);

      if (portFromFile !== null) {
        const isValid = await validateWithinBudget(checkPort, host, portFromFile, validationTimeoutMs, signal);
        if (isValid) {
          return {
            content: [{ type: "text", text: `Found nREPL port ${portFromFile} (from port file) at ${host}:${portFromFile}` }],
            details: { host, port: portFromFile, source: "port-file" },
          };
        }
      }

      for (const port of defaultPorts) {
        if (signal?.aborted) throw abortError();
        const isValid = await validateWithinBudget(checkPort, host, port, validationTimeoutMs, signal);
        if (isValid) {
          return {
            content: [{ type: "text", text: `Found nREPL port ${port} at ${host}:${port}` }],
            details: { host, port, source: "default-ports" },
          };
        }
      }

      if (signal?.aborted) throw abortError();
      throw new Error(`Clojure nREPL discovery failed in ${cwd}: no reachable port found; start nREPL and try again.`);
    },
  });
}

export const findNreplPortTool = createFindNreplPortTool();
