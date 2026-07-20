import { getAgentDir } from "@earendil-works/pi-coding-agent";
import { join } from "node:path";

/** Resolve a file beneath pi's configured global agent directory. */
export function getAgentPath(...segments: string[]): string {
  return join(getAgentDir(), ...segments);
}
