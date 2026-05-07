/**
 * Shared editor base class for pi extensions that need to submit a
 * slash command in a single keystroke.
 *
 * Pi's built-in slash commands (`/new`, `/compact`, `/reload`, …) are
 * dispatched only when the active editor's `onSubmit` callback fires
 * inside `interactive-mode.js`. There is no public extension API to
 * trigger that dispatch, but a custom editor that calls
 * `super.handleInput("\r")` does fire `onSubmit` and submits the
 * editor contents as if the user had pressed Enter.
 *
 * `SubmitterEditor` exposes that capability as a single
 * `submitCommand(text)` method and registers itself in a module-scope
 * registry so cross-extension callers (notably leader-menu's
 * `command:` action handler) can reach the *currently mounted*
 * instance regardless of who installed it.
 *
 * ## Cross-extension contract
 *
 * Any extension that calls `ctx.ui.setEditorComponent(...)` and wants
 * leader-menu's `command:` chords to keep working in one keystroke
 * MUST extend `SubmitterEditor` rather than `CustomEditor` directly.
 * leader-menu's slash-command dispatch resolves
 * `getActiveSubmitter()` and calls `submitCommand` on whichever
 * instance is currently mounted; an editor that bypasses this base
 * regresses to the legacy two-step "insert text + press Enter"
 * behaviour.
 *
 * The convention is: callers set the editor text via
 * `ctx.ui.setEditorText(text)` themselves, then call
 * `submitter.submitCommand(text)` on the instance returned by
 * `getActiveSubmitter()`. The text argument is informational —
 * subclasses are free to inspect it for logging, but the base
 * implementation only synthesises the Enter press.
 *
 * ## Trivial-statelessness invariant
 *
 * `SubmitterEditor` deliberately adds *no* fields and overrides *no*
 * methods other than the constructor (registry wiring) and the new
 * `submitCommand`. This keeps the base immune to the state-skew
 * hazard documented in
 * design/log/2026-05-01-keybindings-editor-composition.org — the
 * prior plan rejected runtime-composed editor instances because two
 * editors cannot share text/render/autocomplete state without going
 * out of sync. Subclasses (e.g. vim-mode's `VimEditor`) are free to
 * carry their own state, but the base itself must remain a
 * structural extension point only.
 *
 * ## Module-state registry
 *
 * `getActiveSubmitter()` returns the most recently constructed
 * `SubmitterEditor` whose `detach()` has not been called. The
 * registry lives in module scope, which is shared across extensions
 * because pi's jiti loader deduplicates modules with the same
 * resolved path (verified empirically by `ansiPad` and
 * `registerLeaderMenu` in `pi-utils.ts`).
 */

import { CustomEditor } from "@mariozechner/pi-coding-agent";

/**
 * Module-scope registry of the currently mounted submitter. Written
 * by the constructor, cleared by {@link SubmitterEditor.detach}.
 *
 * A single slot (rather than a stack) is sufficient: pi mounts at
 * most one editor at a time. If multiple extensions install editors
 * the last write wins, matching pi's own
 * `ctx.ui.setEditorComponent()` semantics.
 */
let activeSubmitter: SubmitterEditor | null = null;

/**
 * Editor base that exposes a one-step `submitCommand(text)` for
 * slash-command dispatch and registers itself for cross-extension
 * lookup.
 *
 * Subclasses MUST preserve the trivial-statelessness invariant (see
 * module docstring) — add no fields and override no methods on the
 * base itself; carry your own state on the subclass.
 */
export class SubmitterEditor extends CustomEditor {
  // CustomEditor's constructor signature is forwarded implicitly via
  // the synthesised ES2022 default constructor; we only need a
  // hand-written constructor to register the instance.
  constructor(...args: ConstructorParameters<typeof CustomEditor>) {
    super(...args);
    activeSubmitter = this;
  }

  /**
   * Synthesise an Enter press so pi's `onSubmit` handler fires and
   * dispatches whatever text is currently in the editor.
   *
   * The `text` argument is informational only — it must already be
   * present in the editor (set by the caller via
   * `ctx.ui.setEditorText(text)`) before this method is invoked.
   * The base implementation deliberately does not touch editor
   * state; that keeps the trivial-statelessness invariant intact.
   */
  submitCommand(_text: string): void {
    super.handleInput("\r");
  }

  /**
   * Clear this instance from the active-submitter registry.
   *
   * Call from the owning extension's `session_shutdown` (or whenever
   * the editor is unmounted) to avoid leaking a reference to a
   * dismounted editor. Idempotent and safe to call when this
   * instance is not the active one.
   */
  detach(): void {
    if (activeSubmitter === this) {
      activeSubmitter = null;
    }
  }
}

/**
 * Return the currently mounted `SubmitterEditor`, or `null` if none
 * is registered.
 *
 * Intended for cross-extension callers that need to dispatch a
 * slash command without holding a stale reference to a specific
 * factory's instance. Always resolve fresh per call.
 */
export function getActiveSubmitter(): SubmitterEditor | null {
  return activeSubmitter;
}
