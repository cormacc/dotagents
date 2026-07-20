#!/usr/bin/env tsx
import { join } from "node:path";
import { getAgentPath } from "./agent-paths.ts";

const originalAgentDir = process.env.PI_CODING_AGENT_DIR;
const overriddenAgentDir = "/tmp/pi-agent-directory-override";

try {
  process.env.PI_CODING_AGENT_DIR = overriddenAgentDir;
  const expected = join(overriddenAgentDir, "skills", "org-tasks", "scripts", "ot");
  const actual = getAgentPath("skills", "org-tasks", "scripts", "ot");
  if (actual !== expected) {
    throw new Error(`expected ${expected}, got ${actual}`);
  }
  console.log("ok - global agent paths honor PI_CODING_AGENT_DIR");
} finally {
  if (originalAgentDir === undefined) delete process.env.PI_CODING_AGENT_DIR;
  else process.env.PI_CODING_AGENT_DIR = originalAgentDir;
}
