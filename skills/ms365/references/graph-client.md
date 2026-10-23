# Graph client: editing SharePoint files

`scripts/graph.clj` is the write side of this skill. The MCP server stays read-only; this Babashka client changes files through a fixed set of commands, with its guardrails in code. Design and measurements: `design/log/2026-10-23-sharepoint-document-editing.org` in the dotagents repository.

## Contents

- Sign-in
- Commands
- Session and transaction
- Guardrails
- Excel writes
- Output and exit codes

## Sign-in

- `graph.clj login` opens the default browser (authorization code with PKCE, `http://localhost:{port}/callback`); the user approves once. `login --device` prints a device code instead, for a machine without a browser.
- The first sign-in needs the tenant and client ids of the Entra app: `login --tenant ID --client ID` saves them to `~/.config/ms365-graph/config.edn`. `MS365_MCP_TENANT_ID` and `MS365_MCP_CLIENT_ID` override the file.
- The token is stored in `~/.config/ms365-graph/token.json` (mode 600, directory 700). Each command renews the access token silently. The client asks for a new sign-in only when the refresh token fails: after about 90 days without use, a password change, an admin revocation, or a Conditional Access sign-in frequency policy.
- `whoami` shows the signed-in user. `logout` deletes the token.
- The client requests `Files.ReadWrite.All`, but Entra returns every scope consented for the app. The command set, not the token, limits what the client does.

## Commands

ITEM is a SharePoint file URL (as the browser shows it) or `DRIVE:ITEM` ids.

| Command | Does |
|---|---|
| `status ITEM` | Name, version, level (`published`, `draft`, `checkout`), check-out holder, version list |
| `checkout ITEM` | Check out; reports an existing check-out by the signed-in user instead of repeating it |
| `download ITEM FILE [--version V]` | Current content, or an earlier version |
| `upload ITEM FILE` | Replace the content of a checked-out file; an upload session above 4 MiB |
| `checkin ITEM --comment TEXT [--publish]` | Check in as a minor version; `--publish` makes a major version |
| `discard ITEM` | Discard the check-out and every change made during it |
| `excel-write ITEM --sheet NAME --range A1:F1 --values JSON [--overwrite]` | Write cells in a checked-out workbook |
| `transaction ITEM --comment TEXT (--exec 'CMD {in} {out}' \| --sheet --range --values) [--publish]` | Check out, apply one change, check in |

## Session and transaction

- Session (the default for document editing): `checkout` once, then `download`, edit and `upload` as often as needed, then one `checkin`. SharePoint creates the next minor version at check-out, uploads go into that draft without new versions, and the check-in keeps one minor version for the whole session. Report a check-out that is still held at the end of the work.
- Word content: edit the downloaded file with the `docx` skill (`docx.clj edit` for tracked changes with reason comments, `docx.clj clean` for the clean copy before publication).
- Transaction: one command checks out, applies the change, and checks in. `--exec` downloads the file to `{in}`, runs the command, and uploads `{out}`. On any failure after the check-out, the client discards the check-out, so the file returns to the last checked-in version.
- Use a transaction, never a session, for a one-line update to a shared log or register (for example, reserving the next number in a numbering log): other people update the same file, and a transaction holds the check-out for seconds. Use it also for a small document change when the user asks for one.
- Reviewers see tracked changes in desktop Word without a check-out; in a library that requires check-out, Word Online shows them only after a check-out.

## Guardrails

- `checkout` refuses a file that another user has checked out.
- `upload`, `checkin`, `discard` and `excel-write` refuse a file that the signed-in user has not checked out.
- `transaction` refuses a file that anyone has checked out; use the session commands for a file that is already checked out.
- `checkin` needs a non-blank comment. `--publish` is never a default; publish only when the user asks for it.
- `excel-write` refuses non-empty target cells unless `--overwrite`, and checks that the values have the shape of the range.

## Excel writes

- In a library that requires check-out, the workbook API refuses a write without a check-out (403 `EditModeWorkbookCheckedOutRequired`). Use `transaction` or a session.
- The client writes in a workbook session (`persistChanges: true`). Requests without a session read back stale data for a few seconds.
- String cells get the text number format before the values are written. Otherwise Excel converts text such as `Oct 2026` to a date serial, and a format set afterwards does not undo it.
- The Excel service saves a closed session's changes to the file a few seconds later. The client waits until the file's `cTag` changes (up to 60 s) before it returns, because a check-in before the save captures the file without the write.

## Output and exit codes

Each command prints `{"ok": true, "result": ...}` or `{"ok": false, "error": {"code": ..., "message": ...}}`. Check `ok` before reading `result`.

| Exit | Code | Meaning |
|---|---|---|
| 0 | | Success |
| 1 | `usage` | Bad arguments, or an `--exec` command failed |
| 2 | `refused` | A guardrail refused the operation |
| 3 | `graph` | Graph returned an error, a read-back did not match, or a save did not happen |
| 4 | `auth` | Sign-in needed |
