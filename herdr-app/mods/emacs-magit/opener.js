// The view shared by emacs-magit and emacs-plan (identical copies; see state.js).
// A key opens the mod as an overlay (`mod.open`); it runs emacs-tab for the
// focused terminal, focuses the resulting tab and closes itself.

import { View, div } from "gpui-kit";
import { exec, focused, panes, spaces, props, run, notify, closeOverlay, checking } from "herdr";
import { request, answer } from "./state.js";

const ADOPT_POLLS = 30;
const ADOPT_MS = 100;

export function emacsTabView(view, title) {
  return class EmacsTab extends View {
    init(_props, cx) {
      if (checking()) return;
      cx.spawn(async cx => {
        try {
          await this.open(cx);
        } catch (e) {
          notify({ title, body: String(e?.message ?? e), level: "error" });
        } finally {
          const overlay = props()?.overlay;
          if (overlay) closeOverlay(overlay);
        }
      });
    }

    async open(cx) {
      const req = request(view, focused(), panes(), spaces());
      if (req.reason) {
        notify({ title, body: req.reason, level: "info" });
        return;
      }
      const got = answer(await exec(req.command, req.args, req.options), req.machine);
      if (got.error) {
        notify({ title, body: got.error, level: "error" });
        return;
      }
      // A new terminal becomes a pane once the window adopts it.
      for (let i = 0; i < ADOPT_POLLS; i++) {
        if (panes().some(p => p.terminal === got.terminal)) break;
        await new Promise(resolve => cx.timer.after(ADOPT_MS, resolve));
      }
      run("pane.focus", { terminal: got.terminal });
    }

    render() {
      return div();
    }
  };
}
