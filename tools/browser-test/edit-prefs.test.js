// Prefs e2e — theme toggle + sidebar collapse / expand.
//
// Coverage:
//   • Theme: click #theme-toggle-btn flips body.theme-dark; flipping
//     again returns to light. localStorage preserves the choice.
//   • Sidebar: click #sidebar-collapse-btn adds body.sidebar-collapsed;
//     the floating #sidebar-expand-floating button appears and clicking
//     it reverses both. localStorage preserves the choice.
//
// Run from this directory:  node edit-prefs.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-prefs — theme toggle + sidebar collapse / expand');

  try {
    // newContext lands on `/` (no fn-selected). Wait for the prefs
    // buttons to be wired (`buildPrefsButtons` mounts them on load).
    await page.waitForFunction(
      () => !!document.getElementById('theme-toggle-btn')
            && !!document.getElementById('sidebar-collapse-btn'),
      null,
      {timeout: 10000, polling: 100});

    // ===================================================================
    // Phase A: theme toggle round-trip.
    // ===================================================================
    const initialTheme = await page.evaluate(() =>
      document.body.classList.contains('theme-dark'));

    await page.click('#theme-toggle-btn');
    await page.waitForFunction(
      (was) => document.body.classList.contains('theme-dark') !== was,
      initialTheme,
      {timeout: 3000, polling: 50});
    const afterFirst = await page.evaluate(() => ({
      dark: document.body.classList.contains('theme-dark'),
      stored: localStorage.getItem('graphden.prefs.theme'),
    }));
    assert(afterFirst.dark !== initialTheme,
           'theme flipped after first click (was '
           + initialTheme + ', now ' + afterFirst.dark + ')');
    assert(afterFirst.stored === (afterFirst.dark ? 'dark' : 'light'),
           'localStorage carries the new theme: ' + afterFirst.stored);

    await page.click('#theme-toggle-btn');
    await page.waitForFunction(
      (was) => document.body.classList.contains('theme-dark') === was,
      initialTheme,
      {timeout: 3000, polling: 50});
    const afterSecond = await page.evaluate(() => ({
      dark: document.body.classList.contains('theme-dark'),
      stored: localStorage.getItem('graphden.prefs.theme'),
    }));
    assert(afterSecond.dark === initialTheme,
           'second click returns to initial theme: ' + afterSecond.dark);
    assert(afterSecond.stored === (afterSecond.dark ? 'dark' : 'light'),
           'localStorage round-trips back: ' + afterSecond.stored);

    // ===================================================================
    // Phase B: sidebar collapse + expand round-trip.
    // ===================================================================
    const initialCollapsed = await page.evaluate(() =>
      document.body.classList.contains('sidebar-collapsed'));

    // If sidebar starts collapsed (from a prior run's localStorage),
    // expand first so we can exercise the collapse-then-expand flow
    // from a known state.
    if (initialCollapsed) {
      await page.evaluate(() => {
        const fab = document.getElementById('sidebar-expand-floating');
        fab?.click();
      });
      await page.waitForFunction(
        () => !document.body.classList.contains('sidebar-collapsed'),
        null,
        {timeout: 3000, polling: 50});
    }

    await page.click('#sidebar-collapse-btn');
    await page.waitForFunction(
      () => document.body.classList.contains('sidebar-collapsed'),
      null,
      {timeout: 3000, polling: 50});
    const collapsedState = await page.evaluate(() => ({
      collapsed: document.body.classList.contains('sidebar-collapsed'),
      stored: localStorage.getItem('graphden.prefs.sidebar-collapsed'),
      fabPresent: !!document.getElementById('sidebar-expand-floating'),
    }));
    assert(collapsedState.collapsed, 'body.sidebar-collapsed set after click');
    assert(collapsedState.stored === '1',
           "localStorage carries collapsed='1': " + collapsedState.stored);
    assert(collapsedState.fabPresent,
           '#sidebar-expand-floating button appears when collapsed');

    await page.evaluate(() => {
      document.getElementById('sidebar-expand-floating').click();
    });
    await page.waitForFunction(
      () => !document.body.classList.contains('sidebar-collapsed'),
      null,
      {timeout: 3000, polling: 50});
    const expandedState = await page.evaluate(() => ({
      collapsed: document.body.classList.contains('sidebar-collapsed'),
      stored: localStorage.getItem('graphden.prefs.sidebar-collapsed'),
    }));
    assert(!expandedState.collapsed, 'body.sidebar-collapsed cleared');
    assert(expandedState.stored === '0',
           "localStorage carries collapsed='0': " + expandedState.stored);

    console.log('✓ prefs verified — theme + sidebar round-trips');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
