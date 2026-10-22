# SharePoint versions and drafts

Recipes for version state and version downloads of a SharePoint or OneDrive file, from a `codemode` script. Each recipe returns only the fields it needs.

## Draft visibility

A document library can show minor (draft) versions only to users who can edit. A token with read-only file scopes then sees the library as a reader does: Graph serves the last major version as the current content, and the version list holds major versions only. The launcher requests `Files.ReadWrite.All` through `--extra-scopes` for this reason; the server stays read-only. See `README.org` section 'Changing the scopes'.

Check before you trust a draft: for a file that you know has drafts, `list-drive-item-versions` must return minor versions (`X.Y` with `Y > 0`). If it returns major versions only, the token cannot see drafts. Tell the user, and do not report the content as current.

## Locate a file

```js
const r = await tools.mcp__ms365__search_query({body: {requests: [{entityTypes: ["driveItem"],
  query: {queryString: "\"<file title>\""}, size: 5,
  fields: ["name", "webUrl", "id", "parentReference"]}]}});
if (r.isError) throw new Error(JSON.stringify(r.content));
const hits = JSON.parse(r.content[0].text).value[0].hitsContainers[0].hits
  .map(h => ({name: h.resource.name, url: h.resource.webUrl,
              drive: h.resource.parentReference.driveId, id: h.resource.id}));
```

Search also returns copies in other sites and in personal OneDrives. Select by the `webUrl` path.

## Version state

```js
const v = await tools.mcp__ms365__list_drive_item_versions({driveId: d, driveItemId: i});
const versions = JSON.parse(v.content[0].text).value.map(x => ({id: x.id, at: x.lastModifiedDateTime}));
const it = await tools.mcp__ms365__get_drive_item({driveId: d, driveItemId: i,
  select: ["publication", "lastModifiedDateTime", "webUrl"]});
const pub = JSON.parse(it.content[0].text).publication;   // {level: "draft"|"published", versionId}
```

- `versions` is newest first.
- `publication.level` is `draft` when the current version is a minor version.
- The last published version is the highest `N.0` in `versions`.

## Download a version

The current version and an earlier version need different calls:

| Version | Call | Note |
|---|---|---|
| Current | `get-download-url` on `/drives/<d>/items/<i>`, then `curl` | `download-bytes` on `/versions/<current>/content` fails with "You cannot get the content of the current version" |
| Earlier | `download-bytes` on `/drives/<d>/items/<i>/versions/<id>/content` | `get-download-url` rejects version paths |

The URL from `get-download-url` is pre-authenticated: anyone who has it can read the file until it expires. Fetch it in the same script, and do not return it.

```js
// Current version.
const u = await tools.mcp__ms365__get_download_url({target: `/drives/${d}/items/${i}`});
if (u.isError) throw new Error(JSON.stringify(u.content));
const url = JSON.parse(u.content[0].text).downloadUrl;
const b = await tools.bash({command: `curl -sS -f -o '${out}' '${url}' && stat -c %s '${out}'`, timeout: 120});
if (b.exit_code !== 0) throw new Error(b.output);

// Earlier version, e.g. "3.0".
const vb = await tools.mcp__ms365__download_bytes({target: `/drives/${d}/items/${i}/versions/${ver}/content`});
if (vb.isError) throw new Error(JSON.stringify(vb.content));
const bytes = JSON.parse(vb.content[0].text);
await tools.write({path: `${out}.b64`, content: bytes.contentBytes});
const c = await tools.bash({command: `base64 -d '${out}.b64' > '${out}' && rm '${out}.b64' && stat -c %s '${out}'`, timeout: 60});
// Check that the size equals bytes.contentLength.
```

Use absolute paths for `out`. Do not return `contentBytes` from the script: it is the whole file, base64-encoded.

## Live Excel data

For a workbook that people edit in Excel Online, read ranges with `get-excel-range` rather than downloading the file: it reads the live workbook. Find the worksheet ID with `list-excel-worksheets`. The draft-visibility rule above applies to the workbook too.
