// Reads CPU, memory and GPU load on this computer or a remote machine, with no Herdr imports,
// so the parsing and machine forwarding can be tested with plain Node.

// One shell call per read; both branches sample the CPU over 1 s.
// macOS: the first `iostat` report line is the average since boot, so take the second.
const MAC = [
  "iostat -n 0 -c 2 -w 1 | tail -1",
  "echo ---", "vm_stat",
  "echo ---", "sysctl -n hw.memsize",
  "echo ---", "ioreg -r -d 1 -c IOAccelerator | grep -o '\"Device Utilization %\"=[0-9]*'",
].join("; ");
// Linux: one `key values` line each. cgroup v2 files are the container's own share (a Coder or
// Kubernetes workspace); /proc/stat and /proc/meminfo describe the whole host and are the fallback.
const LINUX = [
  "c=/sys/fs/cgroup",
  "u0=$(cut -d' ' -f1 /proc/uptime); a=$(awk '/^usage_usec/{print $2}' $c/cpu.stat 2>/dev/null); p0=$(head -1 /proc/stat)",
  "sleep 1",
  "u1=$(cut -d' ' -f1 /proc/uptime); b=$(awk '/^usage_usec/{print $2}' $c/cpu.stat 2>/dev/null); p1=$(head -1 /proc/stat)",
  "echo \"uptime $u0 $u1\"; echo \"cg $a $b\"; echo \"cpumax $(cat $c/cpu.max 2>/dev/null)\"",
  "echo \"nproc $(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN)\"; echo \"p0 $p0\"; echo \"p1 $p1\"",
  "echo \"memcur $(cat $c/memory.current 2>/dev/null)\"; echo \"memmax $(cat $c/memory.max 2>/dev/null)\"",
  "echo \"inactive $(awk '/^inactive_file /{print $2}' $c/memory.stat 2>/dev/null)\"",
  "grep -E '^(MemTotal|MemAvailable):' /proc/meminfo",
  "echo \"gpu $(nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits 2>/dev/null | sort -n | tail -1)\"",
].join("\n");
export const SCRIPT = `if [ "$(uname)" = Darwin ]; then echo darwin; ${MAC}; else echo linux\n${LINUX}\nfi`;

export const gb = bytes => `${(bytes / 2 ** 30).toFixed(1)} GB`;
const clamp = pct => Math.min(100, Math.max(0, pct));

export function readMac(out) {
  const [cpuOut, vmOut, memOut, gpuOut] = out.split("---\n");
  const [user, sys, idle] = cpuOut.trim().split(/\s+/).map(Number);
  // Activity Monitor's "Memory Used": app memory (anonymous minus purgeable) + wired + compressed.
  const page = Number(/page size of (\d+)/.exec(vmOut)?.[1] ?? 16384);
  const pages = name => Number(new RegExp(`${name}:\\s+(\\d+)`).exec(vmOut)?.[1] ?? 0);
  const total = Number(memOut.trim());
  const used = (pages("Anonymous pages") - pages("Pages purgeable") + pages("Pages wired down") + pages("Pages occupied by compressor")) * page;
  // Apple Silicon has one GPU; a Mac with two reports both, so take the busier one.
  const gpus = [...(gpuOut ?? "").matchAll(/=(\d+)/g)].map(m => Number(m[1]));
  return {
    cpu: { pct: 100 - idle, tip: `CPU ${Math.round(100 - idle)}% · user ${Math.round(user)}%, system ${Math.round(sys)}%` },
    ram: { pct: total ? used / total * 100 : 0, tip: `Memory ${gb(used)} of ${gb(total)} used` },
    gpu: gpus.length ? { pct: Math.max(...gpus), tip: `GPU ${Math.max(...gpus)}% busy` } : null,
  };
}

export function readLinux(out) {
  const line = {};
  for (const l of out.split("\n")) {
    const [key, ...rest] = l.trim().split(/\s+/);
    if (key) line[key.replace(/:$/, "")] = rest;
  }
  const num = (key, i = 0) => (/^\d+(\.\d+)?$/.test(line[key]?.[i] ?? "") ? Number(line[key][i]) : null);

  // CPU: the container's usage against its quota (cpu.max "quota period"), else all CPUs.
  let cpu;
  const quota = num("cpumax", 0), period = num("cpumax", 1);
  const limit = quota && period ? quota / period : num("nproc") || 1;
  const secs = (num("uptime", 1) ?? 0) - (num("uptime", 0) ?? 0) || 1;
  if (num("cg", 0) !== null && num("cg", 1) !== null) {
    const pct = clamp((num("cg", 1) - num("cg", 0)) / 1e6 / secs / limit * 100);
    const of = Number.isInteger(limit) ? `${limit}` : limit.toFixed(1);
    cpu = { pct, tip: `CPU ${Math.round(pct)}% of ${of} CPU${limit === 1 ? "" : "s"}${quota ? " (container limit)" : ""}` };
  } else {
    // /proc/stat cpu line: user nice system idle iowait irq softirq steal ...
    const fields = key => (line[key] ?? []).slice(1).map(Number);
    const [a, b] = [fields("p0"), fields("p1")];
    const delta = i => (b[i] || 0) - (a[i] || 0);
    const total = b.reduce((s, _, i) => s + delta(i), 0), idle = delta(3) + delta(4);
    const pct = total > 0 ? clamp((total - idle) / total * 100) : 0;
    const share = n => (total > 0 ? Math.round(n / total * 100) : 0);
    cpu = { pct, tip: `CPU ${Math.round(pct)}% · user ${share(delta(0) + delta(1))}%, system ${share(delta(2))}%` };
  }

  // Memory: the container's working set (usage minus reclaimable file cache, as `kubectl top`
  // counts it) against its limit, else the host's MemTotal minus MemAvailable.
  let ram;
  const hostTotal = (num("MemTotal") ?? 0) * 1024, hostAvail = (num("MemAvailable") ?? 0) * 1024;
  const current = num("memcur");
  if (current !== null) {
    const max = num("memmax");
    const total = max && (!hostTotal || max < hostTotal) ? max : hostTotal;
    const used = Math.max(0, current - (num("inactive") ?? 0));
    ram = { pct: total ? clamp(used / total * 100) : 0, tip: `Memory ${gb(used)} of ${gb(total)} used${max ? " (container limit)" : ""}` };
  } else {
    const used = hostTotal - hostAvail;
    ram = { pct: hostTotal ? clamp(used / hostTotal * 100) : 0, tip: `Memory ${gb(used)} of ${gb(hostTotal)} used` };
  }

  const gpu = num("gpu");
  return { cpu, ram, gpu: gpu === null ? null : { pct: gpu, tip: `GPU ${gpu}% busy` } };
}

// The script's first line names the branch that ran; the rest is that branch's output.
// Anything else (a login banner, a broken connection) is an error, never a 0% reading.
export function parseLoad(stdout) {
  const nl = stdout.indexOf("\n");
  const os = (nl < 0 ? stdout : stdout.slice(0, nl)).trim(), out = nl < 0 ? "" : stdout.slice(nl + 1);
  if (os === "darwin") return readMac(out);
  if (os === "linux") return readLinux(out);
  throw new Error(`Unexpected output: ${(stdout.trim().split("\n")[0] || "nothing").slice(0, 80)}`);
}

// `machine` is a name from `hc machine ls`, or null for this computer. Never falls back to
// reading this computer when a remote machine fails: the caller shows the error instead.
export async function readLoad(exec, machine = null) {
  const r = await exec("sh", ["-c", SCRIPT], { on: machine });
  const failed = () => new Error((r.stderr || `exit ${r.code}`).trim());
  if (r.code !== 0 && !r.stdout) throw failed();
  try { return parseLoad(r.stdout); }
  catch (e) { throw r.code !== 0 && r.stderr ? failed() : e; }
}

// Load is loud only when it stays high: with several agents at work a busy CPU is normal, and a spike
// is no reason to look. True when each of the last `reads` reads (about 30 s at one read every 3 s)
// is at `at` percent or more.
export function sustained(history, at = 80, reads = 10) {
  return history.length >= reads && history.slice(-reads).every(pct => pct >= at);
}
