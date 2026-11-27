// Check the contract in figma-page-snippets.mjs: each page snippet runs on
// its source text alone. Each one runs in a fresh vm context that holds
// only a stub of the browser globals, as it would in the Figma tab, where
// no module scope exists. A reference to anything outside the snippet
// throws a ReferenceError.
//
// Run: node --test test-figma-page-snippets.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import * as snippets from './figma-page-snippets.mjs';

// Arguments for each snippet. A new snippet without an entry fails the
// coverage test, so that every snippet is exercised.
const ARGS = {
  layerRows: [], expandCaretCentre: ['1:2'], selectionSummary: [], propertiesPanelText: [],
  searchResults: [], commentThreads: [], scrollList: ['[data-testid=comment-message]', 'top'],
  centreOf: ['button', '^Export'], tabCentre: ['Comments'], pageStatus: [], viewport: [], isMac: [],
};

// Browser globals with an empty document: every query finds nothing.
function browserStub() {
  return vm.createContext({
    document: { title: 't', querySelector: () => null, querySelectorAll: () => [] },
    location: { href: 'https://www.figma.com/design/k/n' },
    navigator: { platform: 'Linux x86_64' },
    innerWidth: 100, innerHeight: 100, devicePixelRatio: 1,
    getComputedStyle: () => ({ overflowY: 'visible' }),
  });
}

const runInPage = (fn, args) => vm.runInContext(`(${fn})(...${JSON.stringify(args)})`, browserStub());

test('every page snippet has test arguments', () => {
  assert.deepEqual(Object.keys(snippets).sort(), Object.keys(ARGS).sort());
});

for (const [name, fn] of Object.entries(snippets)) {
  test(`page snippet ${name} runs without module scope`, () => {
    assert.equal(typeof fn, 'function');
    const result = runInPage(fn, ARGS[name]);
    assert.doesNotThrow(() => JSON.stringify(result));
  });
}

// Positive control: the harness must catch a snippet that uses module scope.
const MODULE_CONSTANT = 1;
function leakySnippet() { return MODULE_CONSTANT; }

test('the harness rejects a snippet that uses module scope', () => {
  assert.equal(leakySnippet(), 1);
  assert.throws(() => runInPage(leakySnippet, []), /MODULE_CONSTANT is not defined/);
});
