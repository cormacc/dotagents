import assert from "node:assert/strict";
import { test } from "node:test";
import { SCRIPT, parseLoad, readLoad } from "../mods/system/load.js";

// Captured from an Apple Silicon Mac (16 GB).
const MAC = `darwin
 16  6 79  10.70 19.60 15.66
---
Mach Virtual Memory Statistics: (page size of 16384 bytes)
Pages free:                                4767.
Pages wired down:                        158631.
Pages purgeable:                              2.
Anonymous pages:                         307116.
Pages occupied by compressor:            396969.
---
17179869184
---
"Device Utilization %"=9
`;

// Captured from a Coder workspace pod: 4 CPU quota and a 6.25 GiB memory limit on a 7.6 GiB node.
const POD = `linux
uptime 10676.68 10677.68
cg 793644681 793692192
cpumax 400000 100000
nproc 4
p0 cpu  73005 0 33839 4127185 4372 0 1274 284 0 0
p1 cpu  73008 0 33839 4127577 4372 0 1274 284 0 0
memcur 3801677824
memmax 6710886400
inactive 1610276864
MemTotal:        7917304 kB
MemAvailable:    5560344 kB
gpu
`;

// The same machine with no cgroup files: whole-host /proc numbers.
const HOST = POD
  .replace("cg 793644681 793692192", "cg")
  .replace("cpumax 400000 100000", "cpumax")
  .replace("p1 cpu  73008 0 33839 4127577", "p1 cpu  73105 0 33889 4127535")
  .replace(/memcur \d+/, "memcur").replace(/memmax \d+/, "memmax").replace(/inactive \d+/, "inactive")
  .replace(/^gpu.*$/m, "gpu 37");

const round = pct => Math.round(pct * 10) / 10;

test("macOS: Activity Monitor's memory used, CPU from the second iostat line, the busier GPU", () => {
  const load = parseLoad(MAC);
  assert.equal(load.cpu.pct, 21);
  assert.equal(load.cpu.tip, "CPU 21% · user 16%, system 6%");
  assert.equal(round(load.ram.pct), 82.3);
  assert.equal(load.ram.tip, "Memory 13.2 GB of 16.0 GB used");
  assert.deepEqual(load.gpu, { pct: 9, tip: "GPU 9% busy" });
});

test("Linux container: CPU against the cgroup quota, memory as working set against the limit", () => {
  const load = parseLoad(POD);
  assert.equal(round(load.cpu.pct), 1.2);
  assert.equal(load.cpu.tip, "CPU 1% of 4 CPUs (container limit)");
  assert.equal(round(load.ram.pct), 32.7);
  assert.equal(load.ram.tip, "Memory 2.0 GB of 6.3 GB used (container limit)");
  assert.equal(load.gpu, null, "no nvidia-smi: no GPU metric");
});

test("Linux host without cgroup files: /proc/stat deltas and MemTotal minus MemAvailable", () => {
  const load = parseLoad(HOST);
  assert.equal(load.cpu.pct, 30);
  assert.equal(load.cpu.tip, "CPU 30% · user 20%, system 10%");
  assert.equal(load.ram.tip, "Memory 2.2 GB of 7.6 GB used");
  assert.deepEqual(load.gpu, { pct: 37, tip: "GPU 37% busy" });
});

test("cgroup without limits: all CPUs and the host's memory total", () => {
  const load = parseLoad(POD
    .replace("cg 793644681 793692192", "cg 1000000 3000000")
    .replace("cpumax 400000 100000", "cpumax max 100000")
    .replace(/memmax \d+/, "memmax max"));
  assert.equal(round(load.cpu.pct), 50);
  assert.equal(load.cpu.tip, "CPU 50% of 4 CPUs");
  assert.equal(load.ram.tip, "Memory 2.0 GB of 7.6 GB used");
});

test("the read runs on the requested machine, in one shell call that picks the branch by uname", async () => {
  const calls = [];
  await readLoad(async (...args) => { calls.push(args); return { code: 0, stdout: POD }; }, "hub");
  assert.deepEqual(calls, [["sh", ["-c", SCRIPT], { on: "hub" }]]);
  assert.match(SCRIPT, /^if \[ "\$\(uname\)" = Darwin \]/);
  calls.length = 0;
  await readLoad(async (...args) => { calls.push(args); return { code: 0, stdout: MAC }; });
  assert.deepEqual(calls[0][2], { on: null }, "omitted means this computer");
});

test("a failed remote read is an error, never a local read", async () => {
  const calls = [];
  await assert.rejects(
    readLoad(async (...args) => { calls.push(args); return { code: 255, stdout: "", stderr: "hub: connection refused\n" }; }, "hub"),
    /hub: connection refused/);
  assert.equal(calls.length, 1);
});

test("output that is not the script's is an error, never a 0% reading", async () => {
  assert.throws(() => parseLoad("Welcome to hub\nsome banner\n"), /Unexpected output: Welcome to hub/);
  assert.throws(() => parseLoad(""), /Unexpected output: nothing/);
  await assert.rejects(
    readLoad(async () => ({ code: 0, stdout: "Last login: Mon\n" }), "hub"),
    /Unexpected output: Last login/);
  // A failed connection that printed a banner reports the connection's own error.
  await assert.rejects(
    readLoad(async () => ({ code: 255, stdout: "Last login: Mon\n", stderr: "hub: connection closed\n" }), "hub"),
    /^Error: hub: connection closed$/);
});

test("load is loud only when it stays high", async () => {
  const { sustained } = await import("../mods/system/load.js");
  assert.equal(sustained([99, 99, 99]), false, "too few reads");
  assert.equal(sustained(Array(10).fill(95)), true);
  assert.equal(sustained([...Array(9).fill(95), 40]), false, "it dropped");
  assert.equal(sustained([20, 20, ...Array(10).fill(85)]), true, "only the last reads count");
});
