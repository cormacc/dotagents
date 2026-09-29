---
name: ms365
description: Read Microsoft 365 data through the ms365 MCP server -- SharePoint and OneDrive documents, Outlook mail and calendar, Teams chats and channels, and the user directory. Use whenever the user asks to find, read, summarise, or ingest company documents, SharePoint sites or libraries, emails, meetings, Teams messages, or colleague details, even if they do not name Microsoft 365 or the MCP server.
compatibility: Requires the Softeria ms-365-mcp-server running in HTTP mode on 127.0.0.1:3365 and an MCP client configured for it (pi-mcp-adapter server name `ms365`). Setup is in README.org.
---

# ms365

The `ms365` MCP server exposes Microsoft Graph as read-only tools. It uses delegated permissions, so results include only what the signed-in user can see. It cannot send mail, post messages, or change data.

## Before the first call

1. Check that the server runs: `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3365/.well-known/oauth-protected-resource`. Expect `200`.
2. If the connection fails, start the server: `systemctl --user start ms365-mcp`. Wait approximately 10 seconds for `npx` to start it, then check again. The service is not started at login.
3. If a tool call reports that authentication is necessary, ask the user to run `/mcp-auth ms365`. Sign-in uses OAuth in the browser.

## Finding tools

Tools are proxied. Use `mcp({ search: "..." })` or `mcp({ server: "ms365" })` to find them, and `mcp({ describe: "<tool>" })` for parameters. Use `mcpScript` when a task needs several dependent calls, for example site then drive then search.

## Common tasks

- Search all documents :: `search-query` with a Microsoft Search request body. This call was verified:
  ```json
  {"body": {"requests": [{"entityTypes": ["driveItem"], "query": {"queryString": "procedure"}, "size": 5, "fields": ["name", "webUrl"]}]}}
  ```
  Other entity types include `message`, `event`, `chatMessage`, `site`, and `listItem`.
- Find a SharePoint site :: `search-sharepoint-sites` with a word in `search`, for example `"quality"`. A `*` wildcard is rejected with `400 Bad Request`.
- Search one document library :: `list-sharepoint-site-drives` for the drive ID, then `search-onedrive-files`.
- Read file content :: `download-bytes`, or `get-download-url` for a short-lived URL. Convert Office files to text before you analyse them, and check which converter is installed (for example `pandoc`) instead of assuming one.
- Mail :: `list-mail-messages`, `get-mail-message`, `list-mail-attachments`.
- Calendar :: `get-calendar-view` for a date range, `list-calendar-events`, `find-meeting-times`.
- Teams :: `list-chats`, `list-chat-messages`, `list-joined-teams`, `list-channel-messages`.
- Directory :: `list-users`, `get-user-manager`, `list-relevant-people`.

## Query rules

- Use a small `top` and a `select` list. The server caps `top` at 25.
- Do not combine `$filter` and `$search` in one request (server instruction).
- Some endpoints reject `select`. `list-joined-teams` returns `invalid_query_parameter` for it -- retry without `select`.
- Do not use `copilot-retrieve` unless the user confirms that they have Microsoft 365 Copilot licences. The tool needs them.
- A tool that is not listed needs a Graph scope outside the allow-list. Do not work around this. Tell the user which scope the tool needs; README.org describes how to add one.

## Data handling

Content that a tool returns enters the model context and goes to the LLM provider. Read only what the task needs. Do not copy mail, chat, or document content into files, commits, or task records unless the user asks.
