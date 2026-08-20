// Interactive-tutorial drift guard — walks EVERY step of lessons 01/02/04 by
// performing the real UI actions the tour asks for, asserting the tour
// auto-advances after each. This is the contract that keeps the tour's
// spotlight selectors + completion checks honest against the live editor:
// a renamed class or a changed create/bind/run flow fails HERE, not on a
// visitor (same philosophy as `bb devtour-check` for the code tour).
//
// Also covers the end-of-tour cleanup offer: the final "Delete them" click
// must actually remove the tutorial ns + fn (leak discipline — the runner
// counts rows before/after each file).
//
// Run from this directory:  node edit-tutorial-tour.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, deleteFnByName} = require('./edit-test-helpers');

const NS_NAME = 'tutorial';
const FN_NAME = 'hello-handler';


// Retry wrapper for cleanup deletes: a DELETE fired right after a UI write
// can 409 while the write (or its invalidation) is still settling on the
// loaded gate stack. A short backoff clears the transient case without
// masking a REAL in-use 409 (three failures still surface as a leak).
async function retryingDelete(fn) {
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      await fn();
      return;
    } catch (_) { /* fall through to backoff */ }
    await new Promise((r) => setTimeout(r, 5000));
  }
  try { await fn(); } catch (_) { /* best-effort — leak counter reports */ }
}


async function hardCleanup(page) {
  // Belt for a mid-test failure: remove the tutorial fn + ns via the API
  // so the runner's leak counter stays clean even when the tour's own
  // cleanup never ran. The fn is deleted BY NAME first — a prior crash can
  // leave it orphaned outside the (deleted) tutorial ns, where the
  // ns-subtree walk below would miss it and the next run's create 409s.
  await retryingDelete(() => deleteFnByName(page, FN_NAME));
  for (const nm of ['add-10', 'tutorial-json']) {
    await retryingDelete(() => deleteFnByName(page, nm));
  }
  try {
    const tree = await api(page, 'GET', '/api/graph/entities?scope=tree');
    const ns = (tree.namespaces || []).find((n) => n.name === NS_NAME);
    if (ns) {
      const sub = await api(
        page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + ns.id);
      for (const f of (sub.fns || [])) {
        if (f['namespace-id'] === ns.id) {
          await api(page, 'DELETE', '/api/entities/fn/' + f.id);
        }
      }
      await api(page, 'DELETE', '/api/entities/ns/' + ns.id);
    }
  } catch (_) { /* best-effort */ }
  // A stray OWN binding on the package parents the lessons extend (:add /
  // to-json-string) is the 2026-08-20 poisoning fingerprint — a "+" click
  // that landed on the parent instead of the child. Package fns own no
  // bindings, so ANY own binding here is damage; remove it so the rest of
  // the e2e suite (and the next attempt) runs against a healthy stack.
  try {
    for (const parentName of ['add', 'to-json-string']) {
      const found = await api(page, 'GET',
        '/api/graph/entities?scope=search&q=' + parentName);
      const parent = (found.fns || []).find(
        (f) => f.name === parentName && !(f['parent-ids'] || []).length);
      if (!parent) continue;
      const sub = await api(page, 'GET',
        '/api/graph/entities?scope=subtree&root-id=' + parent.id);
      for (const b of (sub.bindings || [])) {
        if (b['fn-id'] === parent.id) {
          console.log('  ! stray binding on ' + parentName + ' — removing');
          await api(page, 'DELETE', '/api/entities/binding/' + b.id);
        }
      }
    }
  } catch (_) { /* best-effort */ }
  // Leaked isolation branches: a run that dies between startTutorialIsolated
  // and "Delete branch & return" leaves a tutorial-NN-xxxx branch behind, and
  // each retry of the suite adds another. Sweep them so per-branch contexts
  // don't pile up across gate attempts.
  try {
    const branches = await api(page, 'GET', '/api/branches');
    for (const b of (Array.isArray(branches) ? branches : (branches.branches || []))) {
      if (/^tutorial-\d\d-/.test(b.name || '')) {
        await api(page, 'DELETE', '/api/branches/' + encodeURIComponent(b.name));
      }
    }
  } catch (_) { /* best-effort */ }
}


function tourTitle(page) {
  return page.evaluate(() => {
    const t = document.querySelector('#gd-tour-pop .gd-tour-title');
    return t ? t.textContent.trim() : null;
  });
}


// Deadlines are sized for the GATE's shared e2e stack, not a dev laptop:
// a write-following step there can stall >60s behind a registry recompile
// plus GC churn (observed 2026-08-19: three 45s branch-wait timeouts and
// one 60s seed-step timeout in one gate run). Polling keeps the success
// path fast — a generous ceiling only slows the FAILURE case.
async function waitTourTitle(page, title, timeoutMs) {
  await page.waitForFunction((expected) => {
    const t = document.querySelector('#gd-tour-pop .gd-tour-title');
    return t && t.textContent.trim() === expected;
  }, title, {timeout: timeoutMs || 120000, polling: 150});
}


function clickTourButton(page, label) {
  return page.evaluate((want) => {
    const btn = Array.from(
      document.querySelectorAll('#gd-tour-pop .gd-tour-btn'))
      .find((b) => b.textContent.trim() === want);
    if (!btn) return false;
    btn.click();
    return true;
  }, label);
}


async function filterAndSelect(page, filterText, fnName) {
  await page.fill('input[placeholder="Filter..."]', filterText);
  await page.waitForTimeout(900);
  await page.evaluate(async (name) => { await selectFnByName(name); }, fnName);
}


async function extendViaRowActions(page, childName) {
  await page.waitForSelector('button.more-actions-trigger', {timeout: 15000});
  await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
  await page.waitForFunction(() => !!document.querySelector(
    '.row-actions-popover [data-action="extend-fn"]'), null,
    {timeout: 15000, polling: 100});
  await page.evaluate(() => {
    document.querySelector('.row-actions-popover [data-action="extend-fn"]')
      .dispatchEvent(new MouseEvent('click', {bubbles: true}));
  });
  await page.waitForSelector('.arg-value-edit-popover .arg-value-edit-input',
    {timeout: 10000});
  await page.evaluate((name) => {
    const pop = document.querySelector('.arg-value-edit-popover');
    const input = pop.querySelector('.arg-value-edit-input');
    input.value = name;
    input.dispatchEvent(new Event('input', {bubbles: true}));
    Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
      .find((b) => b.textContent.trim() === 'Save').click();
  }, childName);
  // The extend popover unmounts on success — waiting for that prevents
  // the NEXT bind step from typing into this (dead) popover's input.
  await page.waitForFunction(
    () => !document.querySelector('.arg-value-edit-popover'),
    null, {timeout: 15000, polling: 100});
}


async function bindFirstPlaceholder(page, literalText) {
  await page.waitForSelector('.placeholder-binder', {timeout: 15000});
  await page.evaluate(() => {
    document.querySelector('.placeholder-binder').click();
  });
  // Scalar slots offer "Bind literal"; sequence slots offer "Append
  // literal" — accept either.
  await page.waitForFunction(() => {
    return Array.from(document.querySelectorAll('button'))
      .some((b) => /^(Bind|Append) literal$/.test((b.textContent || '').trim()));
  }, null, {timeout: 8000, polling: 100});
  await page.evaluate(() => {
    Array.from(document.querySelectorAll('button'))
      .find((b) => /^(Bind|Append) literal$/.test((b.textContent || '').trim()))
      .click();
  });
  await page.waitForFunction(() => {
    const pops = document.querySelectorAll('.arg-value-edit-popover');
    const pop = pops[pops.length - 1];
    return pop && (pop.querySelector('.arg-value-edit-input')
      || pop.querySelector('[data-form-field]'));
  }, null, {timeout: 10000, polling: 100});
  await page.evaluate((text) => {
    const pops = document.querySelectorAll('.arg-value-edit-popover');
    const pop = pops[pops.length - 1];
    const field = pop.querySelector('.arg-value-edit-input')
      || pop.querySelector('[data-form-field]');
    field.value = text;
    field.dispatchEvent(new Event('input', {bubbles: true}));
    field.dispatchEvent(new Event('change', {bubbles: true}));
    Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
      .find((b) => b.textContent.trim() === 'Save').click();
  }, literalText);
  // The bind/append popover unmounts once the write's response lands —
  // waiting here keeps a later cleanup DELETE from racing an in-flight
  // write on the same fn (the 2026-08-19 gate-poisoning trigger window).
  await page.waitForFunction(
    () => !document.querySelector('.arg-value-edit-popover'),
    null, {timeout: 60000, polling: 100});
}


async function runViaRowActions(page, formValue) {
  await page.waitForSelector('button.more-actions-trigger', {timeout: 15000});
  await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
  await page.waitForSelector('.row-actions-popover button', {timeout: 15000});
  await page.evaluate(() => {
    Array.from(document.querySelectorAll('.row-actions-popover button'))
      .find((b) => b.textContent.trim() === '▶')
      .dispatchEvent(new MouseEvent('click', {bubbles: true}));
  });
  await page.waitForSelector('.execute-popover.visible .execute-run-btn',
    {timeout: 10000});
  if (formValue !== undefined) {
    await page.waitForFunction(() => {
      const p = document.querySelector('.execute-popover.visible');
      return p && p.querySelector('[data-form-field]');
    }, null, {timeout: 10000, polling: 100}).catch(() => {});
    await page.evaluate((v) => {
      const p = document.querySelector('.execute-popover.visible');
      const f = p.querySelector('[data-form-field]');
      if (f) {
        f.value = v;
        f.dispatchEvent(new Event('input', {bubbles: true}));
        f.dispatchEvent(new Event('change', {bubbles: true}));
      }
    }, formValue);
  }
  await page.click('.execute-popover.visible .execute-run-btn');
}


async function finishAndDelete(page) {
  assert(await clickTourButton(page, 'Finish'), 'Finish button');
  await waitTourTitle(page, 'Clean up tutorial items?');
  assert(await clickTourButton(page, 'Delete them'), 'Delete them button');
  await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
    null, {timeout: 20000, polling: 200});
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-tutorial-tour — lesson 01 walked end-to-end');
  let failed = false;
  try {
    await hardCleanup(page); // a previous failed run must not pre-pass checks

    const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';
    await page.goto(BASE + '/?tutorial=01');

    // Step 1 — welcome (manual).
    await waitTourTitle(page, 'Welcome to the interactive tutorial', 150000);
    console.log('  step 1: welcome shown');
    assert(await clickTourButton(page, 'Next'), 'welcome Next button');

    // Step 2 — create the namespace through the real sidebar flow.
    await waitTourTitle(page, 'Create a namespace');
    await page.waitForSelector('.create-root-ns-btn', {timeout: 10000});
    await page.click('.create-root-ns-btn');
    await page.waitForSelector('.inline-input', {timeout: 5000});
    await page.fill('.inline-input', NS_NAME);
    await page.press('.inline-input', 'Enter');
    await waitTourTitle(page, 'Add a function');
    console.log('  step 2: namespace created, tour advanced');

    // Step 3 — create the fn via the ns "+" menu. The inline row only
    // renders inside an EXPANDED namespace (the lesson text says so too),
    // so expand first.
    await page.evaluate((name) => {
      const headers = Array.from(document.querySelectorAll('.ns-header'));
      const target = headers.find(
        (h) => h.querySelector('.ns-label')?.textContent.trim() === name);
      if (!target) throw new Error('namespace row not found: ' + name);
      const arrow = target.querySelector('.ns-arrow');
      if (arrow && /▶/.test(arrow.textContent || '')) target.click();
    }, NS_NAME);
    await page.waitForFunction((name) => {
      const headers = Array.from(document.querySelectorAll('.ns-header'));
      const target = headers.find(
        (h) => h.querySelector('.ns-label')?.textContent.trim() === name);
      const arrow = target?.querySelector('.ns-arrow');
      return arrow && /▼/.test(arrow.textContent || '');
    }, NS_NAME, {timeout: 5000, polling: 100});
    await page.evaluate((name) => {
      const headers = Array.from(document.querySelectorAll('.ns-header'));
      const target = headers.find(
        (h) => h.querySelector('.ns-label')?.textContent.trim() === name);
      const plus = target.querySelector('.ns-plus-btn');
      if (!plus) throw new Error('ns-plus-btn not found');
      plus.click();
    }, NS_NAME);
    await page.waitForSelector('.create-menu', {timeout: 5000});
    await page.click('.create-menu-item[data-type="fn"]');
    await page.waitForSelector('.inline-input', {timeout: 5000});
    await page.fill('.inline-input', FN_NAME);
    await page.press('.inline-input', 'Enter');
    await waitTourTitle(page, 'Set the parent', 150000);
    console.log('  step 3: fn created, tour advanced');

    // Step 4 — assign :const through the reparent strip + fn picker.
    await page.waitForSelector('.reparent-strip', {timeout: 15000});
    await page.click('.reparent-strip');
    await page.waitForSelector('.fn-picker-popover', {timeout: 10000});
    await page.fill('.fn-picker-search', 'const');
    await page.waitForFunction(() => {
      return Array.from(document.querySelectorAll('.fn-picker-row'))
        .some((r) => {
          const main = r.querySelector('.fn-picker-row-main');
          return main && /(^|\.)const$/.test(main.textContent.trim().replace(/^:/, ''));
        });
    }, null, {timeout: 10000, polling: 100});
    await page.evaluate(() => {
      const row = Array.from(document.querySelectorAll('.fn-picker-row'))
        .find((r) => {
          const main = r.querySelector('.fn-picker-row-main');
          return main && /(^|\.)const$/.test(main.textContent.trim().replace(/^:/, ''));
        });
      row.click();
    });
    await waitTourTitle(page, 'Bind :value', 150000);
    console.log('  step 4: parent set, tour advanced');

    // Step 5 — bind the :value literal.
    await page.waitForSelector('.placeholder-binder', {timeout: 15000});
    await page.evaluate(() => {
      document.querySelector('.placeholder-binder').click();
    });
    await page.waitForSelector('.free-arg-bind-chooser', {timeout: 5000});
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('.free-arg-bind-chooser button'))
        .find((b) => /Bind literal/.test(b.textContent || '')).click();
    });
    // The value form is a server partial; a plain field mounts async.
    await page.waitForFunction(() => {
      const pop = document.querySelector('.arg-value-edit-popover');
      return pop && (pop.querySelector('.arg-value-edit-input')
        || pop.querySelector('[data-form-field]'));
    }, null, {timeout: 10000, polling: 100});
    await page.evaluate(() => {
      const pop = document.querySelector('.arg-value-edit-popover');
      const field = pop.querySelector('.arg-value-edit-input')
        || pop.querySelector('[data-form-field]');
      field.value = '{"status": 200, "body": "Hello!"}';
      field.dispatchEvent(new Event('input', {bubbles: true}));
      field.dispatchEvent(new Event('change', {bubbles: true}));
      Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
        .find((b) => b.textContent.trim() === 'Save').click();
    });
    await waitTourTitle(page, 'Run it', 150000);
    console.log('  step 5: value bound, tour advanced');

    // Step 6 — run the fn via ⋯ → ▶ → Run.
    await page.waitForSelector('button.more-actions-trigger', {timeout: 15000});
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
    await page.waitForSelector('.row-actions-popover button', {timeout: 15000});
    const ranOpen = await page.evaluate(() => {
      const runBtn = Array.from(
        document.querySelectorAll('.row-actions-popover button'))
        .find((b) => b.textContent.trim() === '▶');
      if (!runBtn) return false;
      runBtn.dispatchEvent(new MouseEvent('click', {bubbles: true}));
      return true;
    });
    assert(ranOpen, '▶ surfaced in row-actions popover');
    await page.waitForSelector('.execute-popover.visible .execute-run-btn',
      {timeout: 10000});
    await page.click('.execute-popover.visible .execute-run-btn');
    await waitTourTitle(page, "That's the whole loop", 150000);
    console.log('  step 6: executed, tour advanced');

    // Step 7 — finish → cleanup dialog → delete what the tour created.
    assert(await clickTourButton(page, 'Finish'), 'Finish button');
    await waitTourTitle(page, 'Clean up tutorial items?');
    assert(await clickTourButton(page, 'Delete them'), 'Delete them button');
    await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
      null, {timeout: 20000, polling: 200});
    console.log('  step 7: cleanup ran, tour closed');

    // The tour's own cleanup must have removed both rows.
    const tree = await api(page, 'GET', '/api/graph/entities?scope=tree');
    assert(!(tree.namespaces || []).some((n) => n.name === NS_NAME),
      'tutorial namespace deleted by the tour cleanup');

    // ---------- Lesson 02 — parents & inheritance (extend flow) ----------
    await page.goto(BASE + '/?tutorial=02');
    await waitTourTitle(page, 'Inheritance, hands on', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 02 Next');
    await waitTourTitle(page, 'Find :add');
    await filterAndSelect(page, 'add', 'add');
    await waitTourTitle(page, 'Extend it');
    await extendViaRowActions(page, 'add-10');
    // The selection-gate step ("The editor opened add-10") auto-advances
    // once the editor re-selects the child — waiting for the NEXT title
    // therefore guarantees add-10 is selected, so the "+" click below
    // cannot land on :add's own placeholder (the 2026-08-20 poisoning).
    await waitTourTitle(page, 'Seed the inherited slot', 150000);
    await bindFirstPlaceholder(page, '10');
    await waitTourTitle(page, 'Run the child', 150000);
    await runViaRowActions(page);
    await waitTourTitle(page, "That's inheritance", 150000);
    await finishAndDelete(page);
    console.log('  lesson 02: walked + cleaned');

    // ---------- Lesson 04 — free arguments ----------
    await page.goto(BASE + '/?tutorial=04');
    await waitTourTitle(page, 'Free args: the template mechanism', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 04 Next');
    await waitTourTitle(page, 'Find to-json-string');
    await filterAndSelect(page, 'to-json', 'to-json-string');
    await waitTourTitle(page, 'A free arg becomes a Run field');
    await runViaRowActions(page, '{"a": 1}');
    await waitTourTitle(page, 'Pin it in a child', 150000);
    await extendViaRowActions(page, 'tutorial-json');
    // Selection gate again — "Bind :data in the child" only appears once
    // tutorial-json is the selected fn, so the "+" is the child's.
    await waitTourTitle(page, 'Bind :data in the child', 150000);
    await bindFirstPlaceholder(page, '{"greeting": "hello"}');
    await waitTourTitle(page, 'Bound beats free', 150000);
    assert(await clickTourButton(page, 'Next'), 'lesson 04 step-5 Next');
    await waitTourTitle(page, 'Templates, specialized');
    await finishAndDelete(page);
    console.log('  lesson 04: walked + cleaned');

    // ---------- Branch isolation (org mode entry) ----------
    const startedIso = await page.evaluate(async () => {
      return await window.startTutorialIsolated('01');
    });
    assert(startedIso, 'startTutorialIsolated returned true');
    // First load on a fresh branch compiles that branch's registry on the
    // (loaded) gate stack — by far the slowest wait in this file.
    await page.waitForFunction(() => {
      return /[?&]branch=tutorial-01-/.test(location.search)
        && !!document.querySelector('#gd-tour-pop .gd-tour-title');
    }, null, {timeout: 240000, polling: 300});
    await waitTourTitle(page, 'Welcome to the interactive tutorial', 150000);
    await page.evaluate(() => {
      Array.from(document.querySelectorAll('#gd-tour-pop .gd-tour-btn'))
        .find((b) => b.textContent.trim() === 'End tour').click();
    });
    await waitTourTitle(page, 'Delete the tutorial branch?');
    assert(await clickTourButton(page, 'Delete branch & return'),
      'Delete branch & return button');
    await page.waitForFunction(() => !/[?&]branch=/.test(location.search),
      null, {timeout: 240000, polling: 300});
    console.log('  branch isolation: created, resumed, deleted, returned');

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour title at failure:', await tourTitle(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await hardCleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
