import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import vm from 'node:vm'

const pluginUrl = new URL('../desktop/plugin.js', import.meta.url)

function atom(initial) {
  let value = initial
  return {
    get: () => value,
    set: next => {
      value = next
    },
    listen: () => () => {}
  }
}

function synthetic(context, values, identifier) {
  return new vm.SyntheticModule(
    Object.keys(values),
    function initialize() {
      for (const [key, value] of Object.entries(values)) this.setExport(key, value)
    },
    { context, identifier }
  )
}

async function loadPlugin() {
  let source
  try {
    source = await fs.readFile(pluginUrl, 'utf8')
  } catch {
    assert.fail('Hermes org-tasks desktop/plugin.js has not been implemented')
  }

  const context = vm.createContext({
    URLSearchParams,
    clearInterval,
    clearTimeout,
    console,
    setInterval,
    setTimeout
  })
  const component = () => null
  const sdk = {
    atom,
    Badge: component,
    Button: component,
    cn: (...values) => values.filter(Boolean).join(' '),
    Codicon: component,
    Dialog: component,
    DialogContent: component,
    DialogDescription: component,
    DialogFooter: component,
    DialogHeader: component,
    DialogTitle: component,
    host: {
      notify() {},
      state: {
        cwd: atom('/workspace'),
        profile: atom('default')
      }
    },
    KEYBINDS_AREA: 'keybinds',
    PALETTE_AREA: 'palette',
    PANES_AREA: 'panes',
    SearchField: component,
    STATUSBAR_AREAS: { right: 'statusBar.right' },
    TITLEBAR_AREAS: { center: 'titleBar.center' },
    Tip: component,
    useMutation: () => ({}),
    useQuery: () => ({}),
    useQueryClient: () => ({}),
    useValue: store => store.get()
  }
  const react = {
    useEffect: () => {},
    useMemo: fn => fn(),
    useRef: value => ({ current: value }),
    useState: value => [typeof value === 'function' ? value() : value, () => {}]
  }
  const jsxRuntime = {
    Fragment: Symbol('Fragment'),
    jsx: (type, props) => ({ type, props }),
    jsxs: (type, props) => ({ type, props })
  }
  const module = new vm.SourceTextModule(source, {
    context,
    identifier: pluginUrl.href
  })
  await module.link(specifier => {
    if (specifier === '@hermes/plugin-sdk') return synthetic(context, sdk, specifier)
    if (specifier === 'react') return synthetic(context, react, specifier)
    if (specifier === 'react/jsx-runtime') return synthetic(context, jsxRuntime, specifier)
    throw new Error(`Unexpected import: ${specifier}`)
  })
  await module.evaluate()
  return module.namespace
}

const ns = await loadPlugin()
const plain = value => JSON.parse(JSON.stringify(value))
const registrations = []
const ctx = {
  onDispose: () => {},
  registerMany: values => registrations.push(...values),
  rest: async () => ({}),
  storage: { get: (_key, fallback) => fallback, set: () => {} }
}

assert.equal(ns.default.id, 'org-tasks')
assert.equal(ns.default.defaultEnabled, true)
ns.default.register(ctx)

const byArea = area => registrations.filter(item => item.area === area)
assert.equal(byArea('statusBar.right').length, 1)
assert.equal(byArea('titleBar.center').length, 1)
assert.equal(byArea('panes').length, 1)
assert.equal(byArea('panes')[0].data.placement, 'bottom')
assert.deepEqual(plain(byArea('panes')[0].data.dock), { pane: 'workspace', pos: 'bottom' })

const palette = byArea('palette')[0].data
const keybind = byArea('keybinds')[0].data
assert.equal(palette.id, 'org-tasks.openSelector')
assert.equal(palette.action, 'org-tasks.openSelector')
assert.equal(palette.label, 'Org Tasks: Select task')
assert.equal(keybind.id, 'org-tasks.openSelector')
assert.deepEqual(plain(keybind.defaults), ['mod+alt+t'])

const parent = {
  id: 'parent',
  status: 'STARTED',
  summary: 'Parent',
  level: 1,
  children: [
    { id: 'done', status: 'DONE', summary: 'Completed child', level: 2, children: [], importChildren: [] },
    { id: 'active', status: 'TODO', summary: 'Needle task', level: 2, children: [], importChildren: [] }
  ],
  importChildren: []
}
assert.deepEqual(plain(ns.flattenTaskTree([parent], new Set(['parent'])).map(row => row.id)), ['parent'])
assert.deepEqual(plain(ns.flattenTaskTree([parent], new Set()).map(row => row.id)), ['parent', 'done', 'active'])
assert.deepEqual(plain(ns.filterTaskRows(ns.flattenTaskTree([parent], new Set()), 'needle').map(row => row.id)), [
  'parent',
  'active'
])
assert.deepEqual(plain(ns.compactTaskRows([parent], 'parent', 2)), {
  rows: [parent, parent.children[1]],
  omitted: 1
})

console.log('org-tasks desktop plugin contract: ok')
