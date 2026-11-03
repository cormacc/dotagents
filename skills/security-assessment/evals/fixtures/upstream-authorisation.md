# Complete relevant source extracts

Supported usage: the authenticated user's server-issued tenant is the only tenant they may export. authenticate verifies the session and attaches req.user; otherwise it returns 401. This is the only caller of exportScoped, and the helper is private to this module. The source below is the entire relevant path. The database parameterises the structured query. The assignment covers only tenant authorisation of this path.

src/export.js:
```js
router.get('/export/:tenant', authenticate, async (req, res) => {
  if (req.user.tenant !== req.params.tenant) return res.sendStatus(403);
  return res.json(await exportScoped(req.params.tenant));
});
async function exportScoped(tenant) {
  return db.reports.findMany({where: {tenant}, take: 100});
}
```
Do not execute requests or inspect other systems. The alleged defect is that the helper itself has no tenant ownership check.
