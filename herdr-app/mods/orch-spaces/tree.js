// The agent rows of the orch-spaces mod: each space's agents, and every live
// sub-agent under the terminal that started it. One rule for every row.

// The label `oh` gave an agent it started, else what the terminal is called.
export function agentName(t) {
  return t.labels?.["oh.label"] || t.name || t.template || t.program?.name || t.address;
}

// `listed`: agents() status by address, the app's word (done: finished unseen).
// An unlisted sub-agent reports to its parent, so its done is idle; error is blocked.
export function agentStatus(t, listed) {
  const s = listed.get(t.address) ?? (t.status === "done" ? "idle" : t.status);
  return s === "error" ? "blocked" : s;
}

// agents() and terminals() -> Map(space id -> rows). A row is
// `{ terminal, pane, lines }`: `pane` when the agent has one, `lines` the tree
// marks in front of it. An agent whose parent is live is shown under that parent
// only, so a sub-agent with its own pane is not listed twice.
export function agentTree(agentList, terminalList) {
  const byAddress = new Map(terminalList.map((t) => [t.address, t]));
  const live = (t) => t?.state === "live";
  const kids = (address) => terminalList.filter((k) => k.parent === address && live(k));
  const paneOf = new Map(agentList.map((a) => [a.address, a.pane]));
  const walk = (list, lead) => list.flatMap((t, i) => {
    const last = i === list.length - 1;
    return [{ terminal: t, pane: paneOf.get(t.address) ?? null, lines: [...lead, last ? "└" : "├"] },
            ...walk(kids(t.address), [...lead, last ? " " : "│"])];
  });
  const roots = new Map();
  for (const a of agentList) {
    const t = byAddress.get(a.address) ?? { address: a.address, status: a.status, program: a.program };
    if (live(byAddress.get(t.parent))) continue;
    if (!roots.has(a.space)) roots.set(a.space, []);
    roots.get(a.space).push(t);
  }
  return new Map([...roots].map(([space, list]) => [space, walk(list, [])]));
}
