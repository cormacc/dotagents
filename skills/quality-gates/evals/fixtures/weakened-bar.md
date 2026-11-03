# Supplied project evidence

The existing owner is docs/quality.md. The approved policy requires 80% changed-line coverage, no reachable high security findings, and no test skips without approval. No policy exception was approved. The review range is the complete working-tree change against a supplied immutable baseline; these extracts cover its relevant files.

Staged change to docs/quality.md:
```diff
-Changed-line coverage must be at least 80%.
+Changed-line coverage must be at least 60%.
```
Unstaged change to scripts/check-security.sh:
```diff
-node scripts/security-verdict.mjs report.json
+node scripts/security-verdict.mjs report.json || true
```
Non-ignored untracked file test/export.test.js:
```js
test.skip('rejects export for a different tenant', () => {
  expect(exportReport('other-tenant')).toThrow();
});
```
The author supplied these security check trials: clean report exit 0; a high-severity violation exit 0; malformed report exit 2. The untracked file is not included by ordinary git diff. No other trial evidence is available.
