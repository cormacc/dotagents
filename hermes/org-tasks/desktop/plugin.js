import {
  atom,
  Badge,
  Button,
  cn,
  Codicon,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  host,
  KEYBINDS_AREA,
  PALETTE_AREA,
  PANES_AREA,
  SearchField,
  STATUSBAR_AREAS,
  TITLEBAR_AREAS,
  Tip,
  useMutation,
  useQuery,
  useQueryClient,
  useValue
} from '@hermes/plugin-sdk'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Fragment, jsx, jsxs } from 'react/jsx-runtime'

const PLUGIN_ID = 'org-tasks'
const ACTION_ID = 'org-tasks.openSelector'
const DONE_STATUSES = new Set(['DONE', 'CANCELLED', 'CANCELED'])
const $selectorOpen = atom(false)
let restClient = null

function bindRest(rest) {
  restClient = rest
  return () => {
    if (restClient === rest) restClient = null
  }
}

function api(path, options) {
  if (!restClient) return Promise.reject(new Error('Org Tasks backend is not connected.'))
  return restClient(path, options)
}

function querySuffix(cwd) {
  return `?${new URLSearchParams({ cwd }).toString()}`
}

function fetchSelected(cwd) {
  return api(`/selected${querySuffix(cwd)}`, { timeoutMs: 15_000 })
}

function fetchTree(cwd) {
  return api(`/tree${querySuffix(cwd)}`, { timeoutMs: 15_000 })
}

function updateSelection(cwd, taskId) {
  return api(taskId ? '/selection' : '/selection/clear', {
    method: 'POST',
    body: taskId ? { cwd, taskId } : { cwd },
    timeoutMs: 15_000
  })
}

function selectedKey(profile, cwd) {
  return [PLUGIN_ID, 'selected', profile, cwd]
}

function treeKey(profile, cwd) {
  return [PLUGIN_ID, 'tree', profile, cwd]
}

function taskChildren(task) {
  return [...(Array.isArray(task.children) ? task.children : []), ...(Array.isArray(task.importChildren) ? task.importChildren : [])]
}

export function flattenTaskTree(tree, collapsed = new Set()) {
  const rows = []
  const seen = new Set()
  const visit = task => {
    if (!task || typeof task !== 'object' || seen.has(task.id)) return
    seen.add(task.id)
    rows.push(task)
    if (collapsed.has(task.id)) return
    for (const child of taskChildren(task)) visit(child)
  }
  for (const task of Array.isArray(tree) ? tree : []) visit(task)
  return rows
}

function parentMapForRows(rows) {
  const parents = new Map()
  const stack = []
  for (const row of rows) {
    const level = Number.isFinite(row.level) ? Math.max(1, row.level) : 1
    while (stack.length >= level) stack.pop()
    const inferredParent = stack.length ? stack[stack.length - 1].id : null
    const parentId = row.parentId || inferredParent
    if (parentId) parents.set(row.id, parentId)
    stack.push(row)
  }
  return parents
}

export function filterTaskRows(rows, query) {
  const needle = String(query || '').trim().toLocaleLowerCase()
  if (!needle) return rows
  const parents = parentMapForRows(rows)
  const included = new Set()
  const matches = task =>
    [task.summary, task.description, task.status, task.id, ...(Array.isArray(task.tags) ? task.tags : [])]
      .filter(Boolean)
      .some(value => String(value).toLocaleLowerCase().includes(needle))
  for (const task of rows) {
    if (!matches(task)) continue
    let current = task.id
    while (current && !included.has(current)) {
      included.add(current)
      current = parents.get(current)
    }
  }
  return rows.filter(task => included.has(task.id))
}

function findTask(tree, taskId) {
  for (const task of Array.isArray(tree) ? tree : []) {
    if (task.id === taskId) return task
    const found = findTask(taskChildren(task), taskId)
    if (found) return found
  }
  return null
}

export function compactTaskRows(tree, selectedId, limit = 6) {
  const selected = findTask(tree, selectedId)
  if (!selected) return { rows: [], omitted: 0 }
  const all = flattenTaskTree([selected])
  if (all.length <= limit) return { rows: all, omitted: 0 }
  const active = all.filter(task => !DONE_STATUSES.has(task.status))
  const completed = all.filter(task => DONE_STATUSES.has(task.status))
  const rows = [...active.slice(0, limit)]
  if (rows.length < limit) rows.push(...completed.slice(0, limit - rows.length))
  return { rows, omitted: all.length - rows.length }
}

export function openSelector() {
  $selectorOpen.set(true)
}

function errorText(error) {
  if (error && typeof error === 'object') {
    if (typeof error.message === 'string') return error.message
    if (typeof error.detail?.message === 'string') return error.detail.message
  }
  return 'Org Tasks is unavailable.'
}

function statusLabel(task) {
  if (!task) return 'No task selected'
  return `${task.status || 'TASK'} · ${task.summary || task.id}`
}

function useWorkspace() {
  return {
    cwd: useValue(host.state.cwd),
    profile: useValue(host.state.profile)
  }
}

function TaskStatusChip() {
  const { cwd, profile } = useWorkspace()
  const selected = useQuery({
    enabled: Boolean(cwd),
    queryFn: () => fetchSelected(cwd),
    queryKey: selectedKey(profile, cwd),
    refetchInterval: 10_000,
    retry: false,
    staleTime: 5_000
  })
  if (!cwd) return null
  const task = selected.data?.selected
  const stale = Boolean(selected.data?.selectedId && !task)
  const label = selected.isError ? 'Org Tasks unavailable' : selected.isPending ? 'Checking task…' : stale ? 'Selected task is missing' : statusLabel(task)
  return jsx(Tip, {
    label: selected.isError ? errorText(selected.error) : stale ? 'Review or clear the stale task selection.' : task?.description || 'Select the task Hermes should keep in focus.',
    children: jsxs('button', {
      type: 'button',
      className: cn(
        'inline-flex h-full max-w-[22rem] items-center gap-1.5 px-1.5 text-[0.6875rem] transition-colors',
        'text-(--ui-text-tertiary) hover:bg-(--chrome-action-hover) hover:text-foreground',
        selected.isError && 'text-(--ui-orange)'
      ),
      onClick: openSelector,
      children: [
        jsx(Codicon, { name: selected.isError ? 'warning' : task ? 'target' : 'circle-outline', size: '0.7rem' }),
        jsx('span', { className: 'truncate', children: label })
      ]
    })
  })
}

function RowBadge({ task }) {
  const variant = task.status === 'STARTED' ? 'default' : DONE_STATUSES.has(task.status) ? 'muted' : 'outline'
  return jsx(Badge, { size: 'xs', variant, children: task.status || 'TASK' })
}

function SelectorRow({ active, current, onActivate, onSelect, onToggle, task }) {
  const children = taskChildren(task)
  const expandable = children.length > 0
  return jsxs('button', {
    type: 'button',
    className: cn(
      'flex w-full min-w-0 items-center gap-1.5 rounded-[3px] py-1 pr-2 text-left text-xs',
      active ? 'bg-(--chrome-action-hover) text-foreground' : 'text-(--ui-text-secondary) hover:bg-(--chrome-action-hover)/60'
    ),
    style: { paddingLeft: `${8 + Math.max(0, Number(task.level || 1) - 1) * 14}px` },
    onClick: onActivate,
    onDoubleClick: onSelect,
    children: [
      expandable
        ? jsx('span', {
            className: 'flex size-4 shrink-0 items-center justify-center',
            onClick: event => {
              event.preventDefault()
              event.stopPropagation()
              onToggle()
            },
            children: jsx(Codicon, { name: task._collapsed ? 'chevron-right' : 'chevron-down', size: '0.7rem' })
          })
        : jsx('span', { className: 'size-4 shrink-0' }),
      current ? jsx(Codicon, { className: 'shrink-0 text-primary', name: 'star-full', size: '0.7rem' }) : null,
      jsx(RowBadge, { task }),
      jsx('span', { className: 'min-w-0 flex-1 truncate', children: task.summary || task.id }),
      task.local ? jsx(Badge, { size: 'xs', variant: 'warn', children: 'LOCAL' }) : null
    ]
  })
}

function TaskDetails({ task }) {
  if (!task) {
    return jsx('div', {
      className: 'flex h-full items-center justify-center p-6 text-xs text-(--ui-text-quaternary)',
      children: 'No matching task.'
    })
  }
  return jsxs('div', {
    className: 'flex h-full min-w-0 flex-col gap-3 p-4',
    children: [
      jsxs('div', {
        className: 'flex flex-wrap items-center gap-2',
        children: [jsx(RowBadge, { task }), task.local ? jsx(Badge, { variant: 'warn', children: 'Machine-local draft' }) : null]
      }),
      jsx('h3', { className: 'text-sm font-medium leading-snug', children: task.summary || task.id }),
      task.description
        ? jsx('p', { className: 'whitespace-pre-wrap text-xs leading-relaxed text-(--ui-text-secondary)', children: task.description })
        : jsx('p', { className: 'text-xs text-(--ui-text-quaternary)', children: 'No description.' }),
      Array.isArray(task.tags) && task.tags.length
        ? jsx('div', {
            className: 'flex flex-wrap gap-1',
            children: task.tags.map(tag => jsx(Badge, { variant: 'muted', children: tag }, tag))
          })
        : null,
      jsx('code', { className: 'mt-auto break-all text-[0.625rem] text-(--ui-text-quaternary)', children: task.id })
    ]
  })
}

function SelectorDialog() {
  const open = useValue($selectorOpen)
  const { cwd, profile } = useWorkspace()
  const [search, setSearch] = useState('')
  const [activeId, setActiveId] = useState(null)
  const [collapsed, setCollapsed] = useState(() => new Set())
  const searchRef = useRef(null)
  const queryClient = useQueryClient()
  const treeQuery = useQuery({
    enabled: open && Boolean(cwd),
    queryFn: () => fetchTree(cwd),
    queryKey: treeKey(profile, cwd),
    refetchInterval: open ? 15_000 : false,
    retry: false,
    staleTime: 5_000
  })
  const expandedRows = useMemo(() => flattenTaskTree(treeQuery.data?.tree || [], collapsed), [treeQuery.data, collapsed])
  const rows = useMemo(
    () => (search.trim() ? filterTaskRows(treeQuery.data?.rows || expandedRows, search) : expandedRows),
    [expandedRows, search, treeQuery.data]
  )
  const activeTask = rows.find(task => task.id === activeId) || rows.find(task => task.id === treeQuery.data?.selectedId) || rows[0]

  useEffect(() => {
    if (!open) return
    setSearch('')
    setActiveId(null)
    const timer = setTimeout(() => searchRef.current?.focus(), 0)
    return () => clearTimeout(timer)
  }, [open, cwd])

  useEffect(() => {
    if (rows.length && !rows.some(task => task.id === activeId)) setActiveId(treeQuery.data?.selectedId || rows[0].id)
  }, [activeId, rows, treeQuery.data])

  const selection = useMutation({
    mutationFn: taskId => updateSelection(cwd, taskId),
    onError: error => host.notify({ kind: 'error', message: errorText(error) }),
    onSuccess: async result => {
      await queryClient.invalidateQueries({ queryKey: [PLUGIN_ID] })
      $selectorOpen.set(false)
      host.notify({
        kind: 'success',
        message: result.selectedId ? 'Selected task updated.' : 'Task selection cleared.'
      })
    }
  })

  const setCurrentSelection = () => {
    if (!activeTask || !treeQuery.data?.writable || selection.isPending) return
    selection.mutate(activeTask.id === treeQuery.data.selectedId ? null : activeTask.id)
  }
  const move = delta => {
    if (!rows.length) return
    const current = Math.max(0, rows.findIndex(task => task.id === activeTask?.id))
    setActiveId(rows[(current + delta + rows.length) % rows.length].id)
  }
  const toggleActive = forceOpen => {
    if (!activeTask || !taskChildren(activeTask).length) return
    setCollapsed(previous => {
      const next = new Set(previous)
      const shouldCollapse = forceOpen === undefined ? !next.has(activeTask.id) : !forceOpen
      if (shouldCollapse) next.add(activeTask.id)
      else next.delete(activeTask.id)
      return next
    })
  }
  const onKeyDown = event => {
    const typing = event.target instanceof HTMLInputElement
    const interactive = event.target instanceof HTMLElement && Boolean(event.target.closest('button, a, [role="button"], [role="menuitem"]'))
    if (!typing && interactive) return
    if (event.key === 'ArrowDown' || (!typing && event.key === 'j')) {
      event.preventDefault()
      move(1)
    } else if (event.key === 'ArrowUp' || (!typing && event.key === 'k')) {
      event.preventDefault()
      move(-1)
    } else if (event.key === 'ArrowRight' && !typing) {
      event.preventDefault()
      toggleActive(true)
    } else if (event.key === 'ArrowLeft' && !typing) {
      event.preventDefault()
      toggleActive(false)
    } else if (event.key === 'Enter' || (!typing && event.key === 's')) {
      event.preventDefault()
      setCurrentSelection()
    } else if (!typing && event.key === ' ') {
      event.preventDefault()
      toggleActive()
    }
  }
  const banner = treeQuery.isError ? errorText(treeQuery.error) : null

  return jsx(Dialog, {
    open,
    onOpenChange: next => $selectorOpen.set(next),
    children: jsxs(DialogContent, {
      className: 'max-w-4xl',
      bodyClassName: 'gap-0 p-0 overflow-hidden',
      banner,
      onKeyDown,
      children: [
        jsxs(DialogHeader, {
          className: 'gap-2 border-b border-(--stroke-nous) px-4 pb-3 pt-4',
          children: [
            jsx(DialogTitle, { children: 'Task Focus' }),
            jsx(DialogDescription, { children: 'Search, inspect, and select the Org task Hermes should keep in focus.' }),
            jsx(SearchField, {
              'aria-label': 'Search Org tasks',
              containerClassName: 'w-full',
              inputClassName: 'w-full [field-sizing:fixed]',
              inputRef: searchRef,
              loading: treeQuery.isFetching,
              onChange: setSearch,
              placeholder: 'Filter by summary, status, tag, or UUID',
              value: search
            })
          ]
        }),
        jsxs('div', {
          className: 'grid min-h-[22rem] grid-cols-[minmax(16rem,0.9fr)_minmax(18rem,1.1fr)] divide-x divide-(--stroke-nous)',
          children: [
            jsx('div', {
              className: 'max-h-[25rem] overflow-y-auto p-2',
              children: !cwd
                ? jsx('div', { className: 'p-4 text-xs text-(--ui-text-quaternary)', children: 'Open a task-enabled project.' })
                : treeQuery.isPending
                  ? jsx('div', { className: 'p-4 text-xs text-(--ui-text-quaternary)', children: 'Loading tasks…' })
                : rows.length
                  ? rows.map(task =>
                      jsx(
                        SelectorRow,
                        {
                          active: task.id === activeTask?.id,
                          current: task.id === treeQuery.data?.selectedId,
                          onActivate: () => setActiveId(task.id),
                          onSelect: () => {
                            setActiveId(task.id)
                            if (treeQuery.data?.writable) selection.mutate(task.id === treeQuery.data.selectedId ? null : task.id)
                          },
                          onToggle: () => {
                            setActiveId(task.id)
                            setCollapsed(previous => {
                              const next = new Set(previous)
                              if (next.has(task.id)) next.delete(task.id)
                              else next.add(task.id)
                              return next
                            })
                          },
                          task: { ...task, _collapsed: collapsed.has(task.id) }
                        },
                        task.id
                      )
                    )
                  : jsx('div', { className: 'p-4 text-xs text-(--ui-text-quaternary)', children: 'No matching tasks.' })
            }),
            jsx(TaskDetails, { task: activeTask })
          ]
        }),
        jsxs(DialogFooter, {
          className: 'border-t border-(--stroke-nous) px-4 py-3',
          children: [
            jsx('span', {
              className: 'mr-auto text-[0.6875rem] text-(--ui-text-quaternary)',
              children: treeQuery.data?.writable ? '↑↓ navigate · Enter select · Space fold' : 'This task root is read-only.'
            }),
            jsx(Button, {
              disabled: !treeQuery.data?.selectedId || !treeQuery.data?.writable || selection.isPending,
              onClick: () => selection.mutate(null),
              variant: 'text',
              children: 'Clear selection'
            }),
            jsx(Button, { onClick: () => $selectorOpen.set(false), variant: 'text', children: 'Cancel' }),
            jsx(Button, {
              disabled: !activeTask || !treeQuery.data?.writable || selection.isPending,
              onClick: setCurrentSelection,
              children: activeTask?.id === treeQuery.data?.selectedId ? 'Clear current' : 'Select task'
            })
          ]
        })
      ]
    })
  })
}

function TaskFocusPane() {
  const { cwd, profile } = useWorkspace()
  const treeQuery = useQuery({
    enabled: Boolean(cwd),
    queryFn: () => fetchTree(cwd),
    queryKey: treeKey(profile, cwd),
    refetchInterval: 60_000,
    retry: false,
    staleTime: 10_000
  })
  if (!cwd) {
    return jsx('div', { className: 'flex h-full items-center justify-center text-xs text-(--ui-text-quaternary)', children: 'Open a task-enabled project.' })
  }
  if (treeQuery.isError) {
    return jsxs('div', {
      className: 'flex h-full items-center justify-between gap-3 px-3 text-xs text-(--ui-orange)',
      children: [jsx('span', { className: 'truncate', children: errorText(treeQuery.error) }), jsx(Button, { onClick: openSelector, size: 'xs', variant: 'ghost', children: 'Configure' })]
    })
  }
  const compact = compactTaskRows(treeQuery.data?.tree || [], treeQuery.data?.selectedId, 6)
  if (!treeQuery.data?.selectedId) {
    return jsxs('div', {
      className: 'flex h-full items-center justify-center gap-3 text-xs text-(--ui-text-tertiary)',
      children: [jsx('span', { children: 'No task selected.' }), jsx(Button, { onClick: openSelector, size: 'xs', variant: 'secondary', children: 'Select task' })]
    })
  }
  if (!compact.rows.length) {
    return jsxs('div', {
      className: 'flex h-full items-center justify-center gap-3 text-xs text-(--ui-orange)',
      children: [jsx('span', { children: 'Selected task is missing from the current graph.' }), jsx(Button, { onClick: openSelector, size: 'xs', variant: 'secondary', children: 'Review selection' })]
    })
  }
  return jsxs('div', {
    className: 'flex h-full min-w-0 flex-col p-2',
    children: [
      jsxs('div', {
        className: 'mb-1 flex items-center justify-between gap-2 px-1',
        children: [
          jsx('span', { className: 'text-[0.6875rem] font-medium uppercase tracking-wide text-(--ui-text-quaternary)', children: 'Selected subtree' }),
          jsx(Button, { onClick: openSelector, size: 'micro', variant: 'text', children: 'Change' })
        ]
      }),
      jsx('div', {
        className: 'min-h-0 flex-1 overflow-y-auto',
        children: compact.rows.map((task, index) =>
          jsxs(
            'div',
            {
              className: cn('flex min-w-0 items-center gap-1.5 rounded-[3px] px-1.5 py-0.5 text-xs', index === 0 && 'bg-(--chrome-action-hover)'),
              style: { paddingLeft: `${6 + Math.max(0, Number(task.level || 1) - Number(compact.rows[0]?.level || 1)) * 14}px` },
              children: [jsx(RowBadge, { task }), jsx('span', { className: 'truncate', children: task.summary || task.id })]
            },
            task.id
          )
        )
      }),
      compact.omitted
        ? jsx('div', { className: 'px-1 pt-1 text-[0.625rem] text-(--ui-text-quaternary)', children: `+${compact.omitted} more (completed tasks elided first)` })
        : null
    ]
  })
}

const plugin = {
  id: PLUGIN_ID,
  name: 'Org Tasks',
  defaultEnabled: true,
  register(ctx) {
    ctx.onDispose(bindRest(ctx.rest))
    ctx.registerMany([
      {
        id: 'selected-task',
        area: STATUSBAR_AREAS.right,
        order: 75,
        render: () => jsx(TaskStatusChip, {})
      },
      {
        id: 'selector-host',
        area: TITLEBAR_AREAS.center,
        order: 1_000,
        render: () => jsx(SelectorDialog, {})
      },
      {
        id: 'focus-pane',
        area: PANES_AREA,
        title: 'Task Focus',
        data: {
          placement: 'bottom',
          dock: { pane: 'workspace', pos: 'bottom' },
          height: '160px',
          maxHeight: '50vh'
        },
        render: () => jsx(TaskFocusPane, {})
      },
      {
        id: 'select-task-palette',
        area: PALETTE_AREA,
        data: {
          id: ACTION_ID,
          action: ACTION_ID,
          label: 'Org Tasks: Select task',
          keywords: ['org', 'tasks', 'focus', 'select', 'ot'],
          run: openSelector
        }
      },
      {
        id: 'open-selector-keybind',
        area: KEYBINDS_AREA,
        data: {
          id: ACTION_ID,
          category: 'view',
          defaults: ['mod+alt+t'],
          label: 'Org Tasks: Select task',
          run: openSelector
        }
      }
    ])
  }
}

export default plugin
