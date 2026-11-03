# Supplied project evidence

The existing owner is docs/quality.md. Thresholds and check entry points are unchanged. No new suppression or exception appears. Only test/validate.test.js changed; there are no other changes in the declared range.

Old tests:
```js
test('empty rejected', () => expect(validate('')).toEqual({ok:false, code:'EMPTY'}));
test('trimmed accepted', () => expect(validate('  x  ')).toEqual({ok:true, value:'x'}));
```
Replacement:
```js
test.each([
  ['', {ok:false, code:'EMPTY'}],
  ['  x  ', {ok:true, value:'x'}]
])('validates %p', (input, expected) => expect(validate(input)).toEqual(expected));
```
The author supplied the exact CI wrapper's trials for its agreed high-severity gate: clean exit 0; high finding exit 1; malformed scanner report exit 2; low finding exit 0. The test report supplies both rows as passing after the rewrite. These are supplied observations, not commands available to run in this assessment.
