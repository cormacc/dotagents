# Complete relevant source extracts

Supported usage: authenticated users can export reports belonging only to their own tenant. Each authenticated user has one server-issued tenant property. No administrator or cross-tenant role is supported by this route. The database accepts the supplied tenant string without an implicit permission check.

src/routes.js:
```js
router.get('/export', authenticate, exportReports);
```
src/auth.js:
```js
function authenticate(req, res, next) {
  req.user = verifySession(req.cookies.session);
  if (!req.user) return res.sendStatus(401);
  next();
}
```
src/export.js:
```js
async function exportReports(req, res) {
  const tenant = req.query.tenant ?? req.user.tenant;
  const reports = await db.reports.findMany({where: {tenant}, take: 100});
  return res.json(reports);
}
```
The assessment is source-only. No live system, real session or production data is available. Do not speculate about code outside these complete extracts.
