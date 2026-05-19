# Bootstrap reference

Prefer `ot init` for new projects. It creates `TASKS.setup.org`, `TASKS.org`, and gitignored `TASKS.local.org` using the protocol defaults.

Manual fallback, when `ot` is unavailable:

1. Create `TASKS.setup.org` at the project root:

   ```org
   #+TODO: TODO(t) STARTED(s!) WAITING(w@/!) | DONE(d!) CANCELLED(c!)
   #+STARTUP: logdone logdrawer
   #+LINK: plan file:design/log/%s
   #+LINK: task file:../../TASKS.org::#%s
   #+LINK: archive file:../../TASKS.archive.org::#%s
   ```

2. Create `TASKS.org`:

   ```org
   #+TITLE: Project Tasks
   #+LINK: task file:TASKS.org::#%s
   #+LINK: archive file:TASKS.archive.org::#%s
   #+SETUPFILE: ./TASKS.local.org
   #+SETUPFILE: ./TASKS.setup.org
   #+ARCHIVE: TASKS.archive.org::* From %s

   * Improvements
   ```

3. Create `TASKS.local.org` and add it to `.gitignore`:

   ```org
   #+SELECTED:
   ```

4. Add the first actionable task under a semantic top-level section. Generate IDs with `ot uuid` if available; otherwise use a real UUID v4 from another trusted generator.

5. Put detailed plan/checklist/history in a change-record under the `plan` link target rather than bloating `TASKS.org`.
