'use strict';

const assert = require('node:assert/strict');
const {closeTourBrowser, existingCleanupNames} = require('../browser-test/tutorial-tour-helpers');

async function main() {
  const events = [];
  const page = {close: async () => { events.push('page'); }};
  const browser = {close: async () => { events.push('browser'); }};
  const recover = async (seen) => {
    assert.equal(seen, page);
    events.push('recover');
  };

  await closeTourBrowser(browser, page, false, recover);
  assert.deepEqual(events, ['page', 'browser'], 'successful walk skips the broad recovery sweep');

  events.length = 0;
  await closeTourBrowser(browser, page, true, recover);
  assert.deepEqual(events, ['page', 'recover', 'browser'], 'failed walk recovers after closing the editor');

  events.length = 0;
  await assert.rejects(closeTourBrowser(browser, page, true, async () => {
    events.push('recover');
    throw new Error('cleanup failed');
  }), /cleanup failed/);
  assert.deepEqual(events, ['page', 'recover', 'browser'], 'browser closes even if recovery fails');

  const names = ['tutorial-b', 'tutorial-a', 'other'];
  assert.deepEqual(await existingCleanupNames(page, names, async () => ({
    fns: [{name: 'tutorial-a'}, {name: 'tutorial-b'}]
  })), ['tutorial-b', 'tutorial-a'], 'index filters absent names without changing deletion order');
  const originalWarn = console.warn;
  console.warn = () => {};
  try {
    assert.deepEqual(await existingCleanupNames(page, names, async () => ({status: 503})), names,
      'an invalid index falls back to the exhaustive sweep');
    assert.deepEqual(await existingCleanupNames(page, names, async () => { throw new Error('offline'); }), names,
      'a failed index request also falls back to the exhaustive sweep');
  } finally {
    console.warn = originalWarn;
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
