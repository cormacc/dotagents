// The guardian keeps its process-group ID allocated until the supervisor stops it.
// This also covers children whose immediate parent exits before discovery.
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

if (process.argv[2] === "guardian") {
  process.on("message", () => {});
  const child = spawn(process.argv[3], process.argv.slice(4), {
    stdio: ["ignore", "inherit", "ignore"],
  });
  child.on("error", () => process.send({ code: 127 }));
  child.on("exit", (code) => process.send({ code: code ?? 1 }));
} else {
  let guardian;
  let code = 1;
  let stopped = false;
  function stop() {
    if (stopped) return;
    stopped = true;
    if (guardian?.pid) {
      try { process.kill(-guardian.pid, "SIGKILL"); } catch {}
    }
  }
  process.on("SIGTERM", () => { stop(); process.exit(1); });
  process.on("SIGINT", () => { stop(); process.exit(1); });
  process.on("exit", stop);
  process.stdin.on("end", () => { stop(); process.exit(1); });
  process.stdin.resume();
  guardian = spawn(process.execPath, [fileURLToPath(import.meta.url), "guardian", ...process.argv.slice(2)], {
    detached: true,
    stdio: ["ignore", "inherit", "ignore", "ipc"],
  });
  guardian.on("error", () => process.exit(1));
  guardian.on("message", (message) => {
    code = Number.isInteger(message?.code) ? message.code : 1;
    stop();
  });
  guardian.on("exit", () => { stop(); process.exit(code); });
}
