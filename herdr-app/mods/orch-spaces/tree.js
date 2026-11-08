// The rows under each space in the orch-spaces mod: its agents, every live
// sub-agent under the terminal that started it, and its tabs without an agent.
// One rule for every row.

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

// agents(), terminals() and spaces() -> Map(space id -> rows). An agent row is
// `{ terminal, pane, lines }`: `pane` when the agent has one, `lines` the tree
// marks in front of it. An agent whose parent is live is shown under that parent
// only, so a sub-agent with its own pane is not listed twice. A tab row is
// `{ tab, lines }`, for a tab that holds no agent; it is a root, after the roots
// of the tabs before it.
export function spaceTree(agentList, terminalList, spaceList = []) {
  const byAddress = new Map(terminalList.map((t) => [t.address, t]));
  const live = (t) => t?.state === "live";
  const kids = (address) => terminalList.filter((k) => k.parent === address && live(k));
  const paneOf = new Map(agentList.map((a) => [a.address, a.pane]));
  // `list`: entries `{ terminal }` or `{ tab }`.
  const walk = (list, lead) => list.flatMap(({ terminal, tab }, i) => {
    const last = i === list.length - 1;
    const lines = [...lead, last ? "└" : "├"];
    if (tab) return [{ tab, lines }];
    return [{ terminal, pane: paneOf.get(terminal.address) ?? null, lines },
            ...walk(kids(terminal.address).map((k) => ({ terminal: k })), [...lead, last ? " " : "│"])];
  });
  const roots = new Map(); // space -> [{ terminal, tab }]
  for (const a of agentList) {
    const t = byAddress.get(a.address) ?? { address: a.address, status: a.status, program: a.program };
    if (live(byAddress.get(t.parent))) continue;
    if (!roots.has(a.space)) roots.set(a.space, []);
    roots.get(a.space).push({ terminal: t, tab: a.tab });
  }
  const withAgents = new Set(agentList.map((a) => a.tab));
  const lists = new Map([...roots].map(([space, list]) => [space, list.map(({ terminal }) => ({ terminal }))]));
  for (const s of spaceList) {
    const mine = roots.get(s.id) ?? [];
    const tabIds = new Set((s.tabs ?? []).map((tab) => tab.id));
    const ordered = (s.tabs ?? []).flatMap((tab) => withAgents.has(tab.id)
      ? mine.filter((r) => r.tab === tab.id).map(({ terminal }) => ({ terminal }))
      : [{ tab }]);
    // An agent whose tab the space does not list (not described yet) goes last.
    const rest = mine.filter((r) => !tabIds.has(r.tab)).map(({ terminal }) => ({ terminal }));
    if (ordered.length || rest.length) lists.set(s.id, [...ordered, ...rest]);
  }
  return new Map([...lists].map(([space, list]) => [space, walk(list, [])]));
}
