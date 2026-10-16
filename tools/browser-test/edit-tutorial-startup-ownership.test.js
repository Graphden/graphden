// A manual lesson start can win while the initial graph boot is still ending.
// The late boot resume must not replace its in-flight creation receipt.
const {chromium} = require('playwright');
const {assert, newContext, api, BASE} = require('./edit-test-helpers');
const {createBranchViaChip} = require('./tutorial-tour-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  let ownedBranch = null;
  try {
    const before = await api(page, 'GET', '/api/branches');
    const rows = Array.isArray(before) ? before : before.branches;
    assert(!rows.some(row => row.name === 'tutorial-vendor'),
      'the lesson branch name is available before this exact-ownership test');
    await page.goto(BASE + '/?branch=main');
    await page.waitForFunction(() => typeof startTutorial === 'function' && !!graphData,
      null, {timeout: 120000});
    await page.evaluate(async () => {
      await startTutorial('41');
      const step = _tourLesson().steps.findIndex(row => row.creates?.type === 'branch'
        && row.creates.name === 'tutorial-vendor');
      if (step < 0) throw new Error('lesson 41 lost its author branch step');
      _tourState.step = step;
      _tourSaveState();
      _tourRenderStep();
      const state = _tourState;
      const principal = JSON.stringify(_tourSessionPrincipal());
      const fetchBranch = window.authFetch;
      let release;
      const held = new Promise(resolve => { release = resolve; });
      window.authFetch = async (url, ...args) => {
        if (url === API.api_branches) {
          window.__gdHeldTourBranchBase = true;
          await held;
        }
        return fetchBranch(url, ...args);
      };
      window.__gdTourStartProbe = {state, principal, release, fetchBranch};
    });
    const creating = createBranchViaChip(page, 'tutorial-vendor');
    try {
      await page.waitForFunction(() => window.__gdHeldTourBranchBase, null, {timeout: 30000});
      const ownership = await page.evaluate(async () => {
        history.replaceState(null, '', location.pathname + '?branch=main&tutorial=01');
        localStorage.setItem('graphden.tour.next', '02');
        await maybeStartTutorial(); // the graph boot's delayed callback
        return {_sameState: _tourState === window.__gdTourStartProbe.state,
          _samePrincipal: JSON.stringify(_tourSessionPrincipal())
            === window.__gdTourStartProbe.principal,
          _consumed: !new URLSearchParams(location.search).has('tutorial')
            && !localStorage.getItem('graphden.tour.next')};
      });
      assert(ownership._samePrincipal, 'the probe did not change account or organization');
      assert(ownership._sameState, 'late boot preserved the active manual tour object');
      assert(ownership._consumed, 'losing boot requests cannot start on a later branch reload');
    } finally {
      await page.evaluate(() => {
        window.__gdTourStartProbe.release();
        window.authFetch = window.__gdTourStartProbe.fetchBranch;
      }).catch(() => {});
    }
    await creating;
    const after = await api(page, 'GET', '/api/branches');
    const afterRows = Array.isArray(after) ? after : after.branches;
    ownedBranch = afterRows.find(row => row.name === 'tutorial-vendor');
    assert(ownedBranch?.id && ownedBranch['base-branch-id'],
      'the ordinary branch Create completed with an exact identity');
    const receipt = await page.evaluate(() => JSON.parse(localStorage.getItem('graphden.tour'))
      ?.created?.find(row => row.type === 'branch' && row.name === 'tutorial-vendor'));
    assert(receipt?.receipt === 'created' && receipt.id === ownedBranch.id,
      'the uninterrupted lesson retained its exact branch receipt');

    // An uncertain isolated-branch response keeps its pending receipt when
    // paused, but must not open a fresh end dialog after the rejection.
    await page.evaluate(() => _tourTeardown());
    await page.goto(BASE + '/?branch=main');
    await page.waitForFunction(() => !!graphData, null, {timeout: 120000});
    await page.evaluate(async () => {
      const fetchBranch = window.authFetch;
      let release;
      const held = new Promise(resolve => { release = resolve; });
      window.authFetch = async (url, options) => {
        if (url === API.api_branches && options?.method === 'POST') {
          window.__gdHeldTourBranchPost = true;
          await held;
          throw new Error('uncertain branch response');
        }
        return fetchBranch(url, options);
      };
      window.__gdTourPostProbe = {release, fetchBranch};
      window.__gdTourPostStart = startTutorialIsolated('01');
    });
    await page.waitForFunction(() => window.__gdHeldTourBranchPost, null, {timeout: 30000});
    const pausedPost = await page.evaluate(async () => {
      const state = _tourState;
      _tourPause();
      window.__gdTourPostProbe.release();
      await window.__gdTourPostStart;
      window.authFetch = window.__gdTourPostProbe.fetchBranch;
      return {_sameState: _tourState === state,
        _pending: state.created?.[0]?.receipt === 'pending',
        _dialog: _tourEls?.pop?.classList.contains('gd-tour-visible')};
    });
    assert(pausedPost._sameState && pausedPost._pending && !pausedPost._dialog,
      'paused uncertain branch response preserves pending ownership without reopening a dialog');

    // A cancelled manual start cannot resurrect after its awaited source reply.
    await page.evaluate(async () => {
      _tourTeardown();
      const fetchLessons = _tourFetchLessons;
      let release;
      const held = new Promise(resolve => { release = resolve; });
      _tourFetchLessons = async () => { await held; return fetchLessons(); };
      const starting = startTutorial('41');
      _tourPause();
      release();
      const result = await starting;
      _tourFetchLessons = fetchLessons;
      if (result !== false || _tourState !== null) {
        throw new Error('paused start resumed after its intent was invalidated');
      }
    });
    await page.evaluate(async () => {
      const saved = {lessonId: '41', step: 0, created: [], activeBranch: 'main',
        principal: {accountId: 'previous-account', orgId: 'previous-organization'}};
      const confirmPrincipal = _tourConfirmPrincipal;
      let release;
      const held = new Promise(resolve => { release = resolve; });
      _tourConfirmPrincipal = async (...args) => { await held; return confirmPrincipal(...args); };
      const restoring = _tourRestoreSession(saved);
      window.dispatchEvent(new Event('gd-auth-changed'));
      release();
      const result = await restoring;
      _tourConfirmPrincipal = confirmPrincipal;
      if (result !== false || _tourEls?.pop?.classList.contains('gd-tour-visible')) {
        throw new Error('stale restore opened a dialog after account identity changed');
      }
      _tourTeardown();
    });
    await page.evaluate(async () => {
      const fetchLessons = _tourFetchLessons;
      let release;
      const held = new Promise(resolve => { release = resolve; });
      _tourFetchLessons = async () => { await held; return fetchLessons(); };
      const starting = startTutorial('41');
      window.dispatchEvent(new Event('gd-auth-changed'));
      release();
      const result = await starting;
      _tourFetchLessons = fetchLessons;
      if (result !== false || _tourState !== null) {
        throw new Error('auth-changed start resumed after its identity changed');
      }
    });
    console.log('PASS: manual tutorial start owns late boot and stale starts stay cancelled');
  } finally {
    await page.evaluate(() => { localStorage.removeItem('graphden.tour'); }).catch(() => {});
    if (ownedBranch?.id) {
      const rows = await api(page, 'GET', '/api/branches');
      const actual = (Array.isArray(rows) ? rows : rows.branches)
        .find(row => row.id === ownedBranch.id);
      if (actual) {
        assert(actual.name === ownedBranch.name
          && actual['base-branch-id'] === ownedBranch['base-branch-id'],
        'cleanup verifies the exact owned branch before deletion');
        assert((await api(page, 'DELETE', '/api/branches/' + ownedBranch.id)).ok,
          'cleanup removes only the exact created branch');
      }
    }
    await browser.close();
  }
})().catch(error => { console.error('FAIL:', error.message); process.exitCode = 1; });
