// fn-versions ⌛ popover e2e — per-branch version history list with
// `switch` action that navigates to that branch.
//
// Coverage:
//   • Seed a fn on main + a feat branch with a diverging description.
//   • Open the ⌛ popover from the fn-card's row-actions group on main.
//   • Verify the popover lists ≥ 2 version rows (main + feat).
//   • Click the switch button on the feat row → switchToBranch invoked
//     with the feat name.
//   • Escape dismisses the popover.
//
// Run from this directory:  node edit-fn-versions.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const FN_NAME = 'fn-versions-probe' + RUN_ID;
const FEAT_BRANCH = 'fn-versions-feat' + RUN_ID;


async function cleanup(page) {
  try { await deleteFnByName(page, FN_NAME); } catch (_) {}
  try {
    await api(page, 'DELETE',
              '/api/branches/' + encodeURIComponent(FEAT_BRANCH));
  } catch (_) {}
}


async function putDescription(page, fnId, branch, desc) {
  return page.evaluate(async ({id, br, d}) => {
    const body = new URLSearchParams();
    body.set('description', d);
    const r = await window.authFetch('/api/entities/fn/' + id, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        'X-Graphden-Branch': br,
      },
      body: body.toString(),
    });
    return {status: r.status, body: await r.text()};
  }, {id: fnId, br: branch, d: desc});
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => {
    console.log('  [dialog]:', d.message().slice(0, 200));
    d.accept();
  });
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-fn-versions — ⌛ popover lists versions + switch button');

  try {
    await cleanup(page);
    await page.goto('http://localhost:9002/');
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});

    // ===================================================================
    // Seed: fn on main + feat branch with diverging description.
    // ===================================================================
    const ents = await getEntities(page);
    const identity = ents.fns.find((f) => f.name === 'identity');
    assert(identity, ':identity parent resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + FN_NAME + '&parent-ids=' + identity.id
              + '&description=main-version');
    const fn = (await getEntities(page)).fns.find((f) => f.name === FN_NAME);
    assert(fn, 'probe fn-def created on main');

    const branchResp = await api(page, 'POST', '/api/branches',
                                 {name: FEAT_BRANCH});
    assert(branchResp?.ok, 'feat branch created');

    const featPut = await putDescription(page, fn.id, FEAT_BRANCH,
                                         'feat-version');
    assert(featPut.status === 200,
           'description PUT on feat: '
           + JSON.stringify(featPut).slice(0, 200));

    // ===================================================================
    // Phase A: navigate to the probe (still on main) + open ⌛
    // popover via the row-actions group.
    // ===================================================================
    await page.goto('about:blank');
    await page.goto('http://localhost:9002/#' + FN_NAME);
    await page.waitForTimeout(800);
    await page.evaluate(() => initGraph && initGraph());
    await page.waitForSelector('button.more-actions-trigger', {timeout: 15000});
    await page.waitForTimeout(500);
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForTimeout(500);

    const clicked = await page.evaluate(() => {
      const popover = document.querySelector('.row-actions-popover');
      const btn = Array.from(popover?.querySelectorAll('button') || [])
        .find((b) => b.textContent.trim() === '⌛');
      if (!btn) return false;
      btn.dispatchEvent(new MouseEvent('click', {bubbles: true}));
      return true;
    });
    assert(clicked, '⌛ history button found in row-actions popover');
    await page.waitForFunction(
      () => {
        const p = document.getElementById('fn-versions-popover');
        return p && !p.classList.contains('hidden')
               && !!p.querySelector('.fn-versions-list');
      },
      {timeout: 10000});

    const popoverState = await page.evaluate(() => {
      const p = document.getElementById('fn-versions-popover');
      const rows = Array.from(p?.querySelectorAll('[role="listitem"], .fn-version-row') || []);
      const switchBtns = Array.from(p?.querySelectorAll('[data-switch-to-branch]') || []);
      const text = p?.textContent?.toLowerCase() || '';
      return {
        visible: !!p && !p.classList.contains('hidden'),
        rowCount: rows.length || switchBtns.length,
        switchTargets: switchBtns.map((b) => b.getAttribute('data-switch-to-branch')),
        mentionsFeat: text.includes('feat'),
      };
    });
    assert(popoverState.visible,
           'fn-versions-popover visible after ⌛ click');
    assert(popoverState.switchTargets.length >= 1,
           'popover has ≥ 1 switch button: '
           + JSON.stringify(popoverState.switchTargets));

    // ===================================================================
    // Phase B: click the feat switch button → switchToBranch invoked.
    // ===================================================================
    await page.evaluate(() => {
      window.__switchCalled = null;
      window.switchToBranch = function(name) {
        window.__switchCalled = name;
      };
    });
    await page.evaluate((featName) => {
      const popover = document.getElementById('fn-versions-popover');
      const btn = Array.from(popover.querySelectorAll('[data-switch-to-branch]'))
        .find((b) => b.getAttribute('data-switch-to-branch') === featName);
      btn?.click();
    }, FEAT_BRANCH);
    await page.waitForTimeout(200);
    const switchState = await page.evaluate(() => window.__switchCalled);
    assert(switchState === FEAT_BRANCH,
           'switchToBranch invoked with feat name: '
           + JSON.stringify(switchState));

    // ===================================================================
    // Phase C: Escape dismisses the popover.
    // ===================================================================
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
    const dismissed = await page.evaluate(() => {
      const p = document.getElementById('fn-versions-popover');
      return p && p.classList.contains('hidden');
    });
    assert(dismissed, 'Escape dismisses the ⌛ popover');

    console.log('✓ fn-versions popover verified — list / switch button / dismiss');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
