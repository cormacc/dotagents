import { readFile } from "node:fs/promises";

/** Read a TCP port from a file; return null for unreadable or invalid input. */
export async function readNreplPortFile(filePath: string): Promise<number | null> {
  try {
    const port = Number((await readFile(filePath, "utf8")).trim());
    return Number.isInteger(port) && port >= 1 && port <= 65_535 ? port : null;
  } catch {
    return null;
  }
}
