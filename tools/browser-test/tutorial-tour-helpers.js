// Shared helpers for the interactive-tutorial drift guards.
//
// The guard walks every step of every lesson by performing the real UI
// actions the tour asks for. That is deliberately slow, so the lessons are
// SPLIT across several `edit-tutorial-tour-*.test.js` files: the runner caps
// a single file at 5 minutes (a hang-bound contract, not a budget to raise),
// and one file walking nine lessons blew through it on the gate.
//
// Everything shared by those files lives here.

const {assert, api, deleteFnByName} = require('./edit-test-helpers');

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
  // CHILDREN BEFORE PARENTS, and twice: a crashed run can leave
  // tutorial-b parented on tutorial-a (the pre-fix lesson-03 failure),
  // and a fn that is still someone's parent refuses to delete (409). One
  // ordered pass clears the normal case; the second pass collects
  // whatever the first pass unblocked.
  // A service row pins its fn: `tutorial-daemon` refuses to delete while
  // lesson 32's row still points at it, so the row goes first.
  try {
    const svcs = await api(page, 'GET', '/api/services');
    for (const s of (svcs.services || [])) {
      if ((s['fn-name'] || '').startsWith('tutorial-')) {
        await api(page, 'DELETE', '/api/entities/service/' + s.id);
      }
    }
  } catch (_) { /* best-effort */ }
  const leftovers = ['tutorial-versioned', 'tutorial-bad-json',
                     'tutorial-b', 'tutorial-a', 'add-10-text', 'add-10',
                     'tutorial-json',
                     'tutorial-typed', 'tutorial-map', 'branch-demo',
                     'two-plus-two', 'tutorial-bump', 'tutorial-cell',
                     'tutorial-card', 'tutorial-button', 'tutorial-script',
                     'tutorial-renamed', 'tutorial-point', 'tutorial-daemon',
                     'tutorial-tick', 'review-demo',
                     // lesson 15's chain — a crash between its create and
                     // finishAndDelete 409s the next run's create.
                     'tutorial-outer', 'tutorial-inner'];
  // Per-browser view-state the lessons exercise (smart views, recents,
  // last-used ns) — a leftover active view renders the next lesson's
  // Explorer as somebody else's virtual tree.
  try {
    await page.evaluate(() => {
      for (const k of ['graphden.smartViews', 'graphden.recentFns',
                       'graphden.lastNs']) localStorage.removeItem(k);
      if (typeof gdClearSmartView === 'function') gdClearSmartView();
    });
  } catch (_) { /* page may not be on the editor yet */ }
  for (let pass = 0; pass < 2; pass++) {
    for (const nm of leftovers) {
      await retryingDelete(() => deleteFnByName(page, nm));
    }
  }
  // lesson 29's pin has to go before its namespaces: uninstall leaves the
  // MATERIALISED `mycorp@1-0-0` copy behind by design, and deleting `greet`
  // by name (as this sweep once did) gutted that copy — the next install
  // then answered 404 "Entities not found" for a package that looked fine
  // in the registry.
  try {
    await api(page, 'DELETE', '/api/packages/uninstall?name=mycorp-hello');
  } catch (_) { /* not installed */ }
  try {
    const tree = await api(page, 'GET', '/api/graph/entities?scope=tree');
    for (const nsName of [NS_NAME, 'tests', 'mycorp', 'mycorp@1-0-0']) {
    const ns = (tree.namespaces || []).find((n) => n.name === nsName);
    if (ns) {
      const sub = await api(
        page, 'GET', '/api/graph/entities?scope=namespace&namespace-id=' + ns.id);
      for (const f of (sub.fns || [])) {
        if (f['namespace-id'] === ns.id) {
          await api(page, 'DELETE', '/api/entities/fn/' + f.id);
        }
      }
      await api(page, 'DELETE', '/api/entities/ns/' + ns.id);
    }
    }
  } catch (_) { /* best-effort */ }
  // A published package-version whose namespace is gone answers 404 on
  // install ("entities not found"), so a crashed lesson-14 run would poison
  // the next one. The tour withdraws its own release; this is the belt.
  try {
    const rows = await api(page, 'GET', '/api/packages');
    for (const row of (Array.isArray(rows) ? rows : (rows.packages || []))) {
      if ((row?.name || '').startsWith('mycorp')) {
        await api(page, 'DELETE', '/api/packages/withdraw?name='
                  + encodeURIComponent(row.name)
                  + '&version=' + encodeURIComponent(row.version));
      }
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
      // tutorial-NN-xxxx = an isolation branch; tutorial-branch /
      // tutorial-release / tutorial-feature are the ones lessons 19 + 20
      // fork by hand.
      if (/^tutorial-(\d\d[a-z]?-|branch$|release$|feature$)/.test(b.name || '')) {
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


// Poll `fn` (a page-side predicate) until it is true or `ms` elapse; returns
// whether it became true. Replaces the "click, then sleep a second before
// re-checking" shape in the panel-open retry loops below and in the tests:
// the success path now exits as soon as the panel is up instead of always
// paying the full interval, and the failure path is unchanged.
async function waitUntil(page, fn, arg, ms) {
  const deadline = Date.now() + (ms || 1000);
  for (;;) {
    if (await page.evaluate(fn, arg)) return true;
    if (Date.now() >= deadline) return false;
    await new Promise((r) => setTimeout(r, 50));
  }
}


function tourProgress(page) {
  return page.evaluate(() => {
    const p = document.querySelector('#gd-tour-pop .gd-tour-progress');
    return p ? p.textContent.trim() : null;
  });
}


// Click a tour button and wait for the popover to ACTUALLY move on. The
// header's step counter ("lesson 26 · step 4/9") is the observable; a
// finished lesson swaps the step popover for a centered dialog with no
// counter, which counts as advancing too.
//
// This replaces the fixed 400-1500 ms sleeps that used to follow a Next: they
// were guesses at this same event, and on the loaded gate stack they were
// sometimes short — the next assertion then read the PREVIOUS step, which is
// exactly the class of "e2e flake" that costs a whole gate run.
async function clickTourAdvance(page, label, timeoutMs) {
  const before = await tourProgress(page);
  if (!(await clickTourButton(page, label))) return false;
  await page.waitForFunction((prev) => {
    const p = document.querySelector('#gd-tour-pop .gd-tour-progress');
    return !p || p.textContent.trim() !== prev;
  }, before, {timeout: timeoutMs || 60000, polling: 100});
  return true;
}


async function filterAndSelect(page, filterText, fnName) {
  await page.fill('input[placeholder="Filter..."]', filterText);
  // The filter is debounced and server-side; wait for the row to actually
  // be in the tree rather than for a fixed slice of time. Same observable
  // the lens probe in `edit-tutorial-tour-ops` waits on.
  await page.waitForFunction((name) => {
    const row = Array.from(document.querySelectorAll('#entity-list .entity-item'))
      .find((e) => e.querySelector('.name')?.textContent.trim() === name);
    return row && !row.hasAttribute('hidden');
  }, fnName, {timeout: 30000, polling: 100});
  await page.evaluate(async (name) => { await selectFnByName(name); }, fnName);
}


// `expectOwner` (optional) — the fn whose row the ⋯ must belong to. The
// canvas re-renders asynchronously after a selection change, so clicking
// the FIRST ⋯ can hit the previous card: in lesson 03 that silently
// extended tutorial-a instead of str-upper, and the step's fn-parent
// check (correctly) never passed.
async function extendViaRowActions(page, childName, expectOwner) {
  await page.waitForSelector('button.more-actions-trigger', {timeout: 15000});
  if (expectOwner) {
    // Wait for the owner's card, then open ITS ⋯ — not "the first overlay",
    // which is whatever the layout happened to place first (a parent row, an
    // execute-result host, the previously selected card).
    await page.waitForFunction((name) => {
      return Array.from(document.querySelectorAll('.node-overlay')).some((ov) =>
        ov.textContent.trim().startsWith(name)
        && ov.querySelector('button.more-actions-trigger'));
    }, expectOwner, {timeout: 90000, polling: 200});
    await page.evaluate((name) => {
      const ov = Array.from(document.querySelectorAll('.node-overlay')).find((o) =>
        o.textContent.trim().startsWith(name)
        && o.querySelector('button.more-actions-trigger'));
      ov.querySelector('button.more-actions-trigger')
        .dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
    }, expectOwner);
  } else {
    await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
  }
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


// --- lesson 05 (types) helpers ----------------------------------------------

// Open the "+" binder, switch to fn-ref, expand the incompatible "Other"
// section and click the named candidate — which opens the server-rendered
// mismatch explainer instead of binding straight away.
async function pickIncompatFnRef(page, fnName) {
  await page.waitForSelector('.placeholder-binder', {timeout: 15000});
  await page.evaluate(() => document.querySelector('.placeholder-binder').click());
  await page.waitForFunction(() => Array.from(document.querySelectorAll('button'))
    .some((b) => b.textContent.trim() === 'Bind fn-ref'),
  null, {timeout: 10000, polling: 100});
  await page.evaluate(() => {
    Array.from(document.querySelectorAll('button'))
      .find((b) => b.textContent.trim() === 'Bind fn-ref').click();
  });
  await page.waitForSelector('.fn-picker-popover', {timeout: 15000});
  await page.fill('.fn-picker-popover input', fnName);
  // The incompatible candidates hide behind a collapsed "Other · N" header,
  // which only appears once the typed filter's candidates have loaded. The
  // click below used to be a silent no-op when it fired too early, and the
  // failure surfaced one line later as a missing `.fn-picker-row-incompat`.
  await page.waitForFunction(() => Array.from(
    document.querySelectorAll('.fn-picker-section-header'))
    .some((h) => /Other/.test(h.textContent)),
  null, {timeout: 30000, polling: 100});
  await page.evaluate(() => {
    Array.from(document.querySelectorAll('.fn-picker-section-header'))
      .find((h) => /Other/.test(h.textContent)).click();
  });
  await page.waitForSelector('.fn-picker-row-incompat', {timeout: 10000});
  await page.evaluate(() => document.querySelector('.fn-picker-row-incompat').click());
}


// Confirm the mismatch explainer's "Pick anyway" — the write lands and the
// fn gains a type-error badge (diagnostic, not a rejection).
async function pickAnyway(page) {
  await page.waitForSelector('.mismatch-explainer.visible [data-pick-fn-id]',
    {timeout: 15000});
  await page.evaluate(() => {
    document.querySelector('.mismatch-explainer [data-pick-fn-id]').click();
  });
}


// Remove a use-site binding through the arg node's ⋯ menu. Fires a native
// confirm() — the caller must have a dialog handler installed.
async function removeUseSiteBinding(page, ownerText) {
  await page.evaluate((txt) => {
    const btns = Array.from(document.querySelectorAll('button.more-actions-trigger'));
    const target = btns.find((b) => {
      const ov = b.closest('.node-overlay');
      return ov && ov.textContent.trim().startsWith(txt);
    }) || btns[btns.length - 1];
    target.dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
  }, ownerText);
  await page.waitForFunction(() => !!document.querySelector(
    '.row-actions-popover [data-action="remove-use-site-binding"]'),
  null, {timeout: 15000, polling: 100});
  await page.evaluate(() => {
    document.querySelector('.row-actions-popover [data-action="remove-use-site-binding"]')
      .dispatchEvent(new MouseEvent('click', {bubbles: true}));
  });
}


// --- lesson 20 (branches) helpers -------------------------------------------

// The tour popover repositions on a tick; clicking the chip the instant a
// step renders can land on the popover instead. Wait until the chip is the
// element actually under its own centre.
async function waitClickable(page, selector) {
  await page.waitForFunction((sel) => {
    const el = document.querySelector(sel);
    if (!el) return false;
    const r = el.getBoundingClientRect();
    if (!r.width || !r.height) return false;
    const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return !!hit && (hit === el || el.contains(hit));
  }, selector, {timeout: 60000, polling: 200});
}


async function createBranchViaChip(page, name) {
  await waitClickable(page, '#branch-chip-btn');
  // dispatch, not page.click: the tour popover re-positions on a tick, and
  // Playwright's actionability wait can race it forever even though the chip
  // IS hittable (waitClickable above already asserted that).
  await page.evaluate(() => document.getElementById('branch-chip-btn').click());
  await page.waitForSelector('#branch-create-input', {timeout: 15000});
  await page.fill('#branch-create-input', name);
  await page.evaluate(() => document.getElementById('branch-create-btn').click());
  // switchToBranch reloads the page; the tour resumes from localStorage.
  await page.waitForFunction((n) => new URLSearchParams(location.search).get('branch') === n,
    name, {timeout: 120000, polling: 300});
}


async function switchBranchViaChip(page, name) {
  await waitClickable(page, '#branch-chip-btn');
  // dispatch, not page.click: the tour popover re-positions on a tick, and
  // Playwright's actionability wait can race it forever even though the chip
  // IS hittable (waitClickable above already asserted that).
  await page.evaluate(() => document.getElementById('branch-chip-btn').click());
  await page.waitForSelector('.branch-row[data-branch-name]', {timeout: 15000});
  await page.evaluate((n) => {
    Array.from(document.querySelectorAll('.branch-row[data-branch-name]'))
      .find((r) => r.getAttribute('data-branch-name') === n).click();
  }, name);
  await page.waitForFunction((n) => {
    const cur = new URLSearchParams(location.search).get('branch');
    return n === 'main' ? !cur : cur === n;
  }, name, {timeout: 120000, polling: 300});
}


// Edit an already-bound literal in place (the value node is clickable).
async function editBoundValue(page, text) {
  await page.waitForSelector('.arg-value-editable', {timeout: 30000});
  await page.evaluate(() => document.querySelector('.arg-value-editable').click());
  await page.waitForFunction(() => {
    const pops = document.querySelectorAll('.arg-value-edit-popover');
    const pop = pops[pops.length - 1];
    return pop && pop.querySelector('[data-form-field], .arg-value-edit-input');
  }, null, {timeout: 15000, polling: 100});
  await page.evaluate((v) => {
    const pops = document.querySelectorAll('.arg-value-edit-popover');
    const pop = pops[pops.length - 1];
    const f = pop.querySelector('[data-form-field]') || pop.querySelector('.arg-value-edit-input');
    f.value = v;
    f.dispatchEvent(new Event('input', {bubbles: true}));
    f.dispatchEvent(new Event('change', {bubbles: true}));
    Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
      .find((b) => b.textContent.trim() === 'Save').click();
  }, text);
  await page.waitForFunction(() => !document.querySelector('.arg-value-edit-popover'),
    null, {timeout: 30000, polling: 100});
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


// --- lesson 14 (tests) helpers ----------------------------------------------
// Extracted from lesson 01's inline steps: the ns / fn / set-parent flows are
// identical, only the names differ.

async function createRootNamespace(page, name) {
  await page.waitForSelector('.create-root-ns-btn', {timeout: 15000});
  await page.click('.create-root-ns-btn');
  await page.waitForSelector('.inline-input', {timeout: 10000});
  await page.fill('.inline-input', name);
  await page.press('.inline-input', 'Enter');
}


async function createFnInNamespace(page, nsName, fnName) {
  // The inline create row only renders inside an EXPANDED namespace.
  await page.waitForFunction((name) => {
    return Array.from(document.querySelectorAll('.ns-header'))
      .some((h) => h.querySelector('.ns-label')?.textContent.trim() === name);
  }, nsName, {timeout: 30000, polling: 200});
  await page.evaluate((name) => {
    const target = Array.from(document.querySelectorAll('.ns-header'))
      .find((h) => h.querySelector('.ns-label')?.textContent.trim() === name);
    const arrow = target.querySelector('.ns-arrow');
    if (arrow && /▶/.test(arrow.textContent || '')) target.click();
  }, nsName);
  await page.waitForFunction((name) => {
    const target = Array.from(document.querySelectorAll('.ns-header'))
      .find((h) => h.querySelector('.ns-label')?.textContent.trim() === name);
    const arrow = target?.querySelector('.ns-arrow');
    return arrow && /▼/.test(arrow.textContent || '');
  }, nsName, {timeout: 15000, polling: 100});
  await page.evaluate((name) => {
    const target = Array.from(document.querySelectorAll('.ns-header'))
      .find((h) => h.querySelector('.ns-label')?.textContent.trim() === name);
    target.querySelector('.ns-plus-btn').click();
  }, nsName);
  await page.waitForSelector('.create-menu', {timeout: 10000});
  await page.click('.create-menu-item[data-type="fn"]');
  await page.waitForSelector('.inline-input', {timeout: 10000});
  await page.fill('.inline-input', fnName);
  await page.press('.inline-input', 'Enter');
}


async function setParentViaStrip(page, parentName) {
  await page.waitForSelector('.reparent-strip', {timeout: 30000});
  await page.click('.reparent-strip');
  await page.waitForSelector('.fn-picker-popover', {timeout: 15000});
  await page.fill('.fn-picker-search', parentName);
  await page.waitForFunction((name) => {
    return Array.from(document.querySelectorAll('.fn-picker-row')).some((r) => {
      const main = r.querySelector('.fn-picker-row-main');
      return main && new RegExp('(^|\\.)' + name + '$')
        .test(main.textContent.trim().replace(/^:/, ''));
    });
  }, parentName, {timeout: 15000, polling: 100});
  await page.evaluate((name) => {
    const row = Array.from(document.querySelectorAll('.fn-picker-row')).find((r) => {
      const main = r.querySelector('.fn-picker-row-main');
      return main && new RegExp('(^|\\.)' + name + '$')
        .test(main.textContent.trim().replace(/^:/, ''));
    });
    row.click();
  }, parentName);
}


// --- lesson 13 (effects) helper ---------------------------------------------
// Run a fn whose effects force the acknowledgement checkbox first.
async function runWithEffectAck(page, formValue) {
  await page.waitForSelector('button.more-actions-trigger', {timeout: 15000});
  await page.dispatchEvent('button.more-actions-trigger', 'mousedown');
  await page.waitForSelector('.row-actions-popover button', {timeout: 15000});
  await page.evaluate(() => {
    Array.from(document.querySelectorAll('.row-actions-popover button'))
      .find((b) => b.textContent.trim() === '▶')
      .dispatchEvent(new MouseEvent('click', {bubbles: true}));
  });
  await page.waitForSelector('.execute-popover.visible .execute-run-btn', {timeout: 15000});
  await page.waitForSelector('.execute-effects-warning', {timeout: 15000});
  const wasDisabled = await page.evaluate(
    () => !!document.querySelector('.execute-run-btn')?.disabled);
  assert(wasDisabled, 'Run is disabled until side effects are acknowledged');
  await page.evaluate(() => document.querySelector('.execute-confirm-checkbox').click());
  await page.waitForFunction(
    () => !document.querySelector('.execute-run-btn')?.disabled,
    null, {timeout: 10000, polling: 100});
  if (formValue !== undefined) {
    await page.evaluate((v) => {
      const p = document.querySelector('.execute-popover.visible');
      const f = p.querySelector('[data-form-field]') || p.querySelector('.arg-value-edit-input');
      if (f) {
        f.value = v;
        f.dispatchEvent(new Event('input', {bubbles: true}));
        f.dispatchEvent(new Event('change', {bubbles: true}));
      }
    }, formValue);
  }
  await page.evaluate(() => document.querySelector('.execute-run-btn').click());
}


async function finishAndDelete(page) {
  assert(await clickTourButton(page, 'Finish'), 'Finish button');
  await waitTourTitle(page, 'Clean up tutorial items?');
  // The cleanup prompt's title lands one render before its buttons — clicking
  // on the title alone raced the button into existence on a loaded stack.
  // The deadline matches the other tour waits (the gate's shared stack can
  // stall a render well past 20s), and either wording counts: an isolated
  // lesson offers "Delete branch & return" instead.
  await page.waitForFunction(() => Array.from(
    document.querySelectorAll('#gd-tour-pop .gd-tour-btn'))
    .some((b) => /^(Delete them|Delete branch & return)$/.test(b.textContent.trim())),
  null, {timeout: 120000, polling: 150});
  assert(await clickTourButton(page, 'Delete them'), 'Delete them button');
  await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
    null, {timeout: 20000, polling: 200});
}


// Bind the currently-shown placeholder to a fn-ref through the picker.
// Waits for the FILTERED row to appear rather than sleeping: the picker
// re-renders per keystroke, and clicking before it settles picks nothing
// (or the wrong row).
async function bindFnRefPlaceholder(page, fnName) {
  await page.waitForSelector('.placeholder-binder', {timeout: 30000});
  await page.evaluate(() => document.querySelector('.placeholder-binder').click());
  // Two shapes: a scalar slot offers the literal/fn-ref choice, while a
  // CALLABLE slot (`[:fn …]`, e.g. :future's :body) cannot take a literal
  // at all and opens the picker straight away. Accept whichever appears.
  await page.waitForFunction(() => {
    return !!document.querySelector('.fn-picker-popover')
      || Array.from(document.querySelectorAll('button'))
        .some((b) => b.textContent.trim() === 'Bind fn-ref');
  }, null, {timeout: 15000, polling: 100});
  await page.evaluate(() => {
    if (document.querySelector('.fn-picker-popover')) return;
    Array.from(document.querySelectorAll('button'))
      .find((b) => b.textContent.trim() === 'Bind fn-ref').click();
  });
  await page.waitForSelector('.fn-picker-popover', {timeout: 15000});
  await page.fill('.fn-picker-popover input', fnName);
  await page.waitForFunction((name) => {
    return Array.from(document.querySelectorAll('.fn-picker-row')).some((r) =>
      (r.querySelector('.fn-picker-row-main')?.textContent || '').includes(name));
  }, fnName, {timeout: 30000, polling: 150});
  await page.evaluate((name) => {
    const row = Array.from(document.querySelectorAll('.fn-picker-row')).find((r) =>
      (r.querySelector('.fn-picker-row-main')?.textContent || '').includes(name));
    row.click();
  }, fnName);
  await page.waitForFunction(() => !document.querySelector('.fn-picker-popover'),
    null, {timeout: 30000, polling: 150});
}


// --- lesson 07 (components) helpers -----------------------------------------
// A component's inputs arrive as propagated FREE args. Since the
// unified-arg-edges redesign they render as ordinary placeholder EDGES
// from the card (lighter/dashed, `+` on the placeholder node) — the
// former amber `?name` chip strip is gone. Click-by-name = find the
// unset edge carrying the arg name, click its target's binder.

async function chipByName(page, chipName) {
  await page.waitForFunction((name) => {
    const gv = window.graphView;
    if (!gv) return false;
    const edge = gv.edgeList().find(
      (e) => e.data?.argName === name && e.data?.isUnset);
    if (!edge) return false;
    return !!document.querySelector(
      '.placeholder-binder[data-node-id="' + edge.data.target + '"]');
  }, chipName, {timeout: 30000, polling: 150});
  await page.evaluate((name) => {
    const edge = window.graphView.edgeList().find(
      (e) => e.data?.argName === name && e.data?.isUnset);
    document.querySelector(
      '.placeholder-binder[data-node-id="' + edge.data.target + '"]').click();
  }, chipName);
}


// `opts.code` — the slot is code-typed (`:js-source` / CSS / EDN), so the
// value form upgrades its textarea to CodeMirror and CM is then the source
// of truth: writing `textarea.value` directly is silently discarded on save.
// `window.gdCode.set` is the wrapper's documented write seam.
async function bindOptionalArgChip(page, chipName, literalText, opts) {
  await chipByName(page, chipName);
  await page.waitForFunction(() => Array.from(document.querySelectorAll('button'))
    .some((b) => b.textContent.trim() === 'Bind literal'),
  null, {timeout: 15000, polling: 100});
  await page.evaluate(() => {
    Array.from(document.querySelectorAll('button'))
      .find((b) => b.textContent.trim() === 'Bind literal').click();
  });
  await page.waitForFunction(() => {
    const pops = document.querySelectorAll('.arg-value-edit-popover');
    const pop = pops[pops.length - 1];
    return pop && (pop.querySelector('.arg-value-edit-input')
      || pop.querySelector('[data-form-field]'));
  }, null, {timeout: 10000, polling: 100});
  await page.evaluate(({text, code}) => {
    const pops = document.querySelectorAll('.arg-value-edit-popover');
    const pop = pops[pops.length - 1];
    const field = pop.querySelector('.arg-value-edit-input')
      || pop.querySelector('[data-form-field]');
    if (code && window.gdCode) {
      window.gdCode.set(field, text);
    } else {
      field.value = text;
      field.dispatchEvent(new Event('input', {bubbles: true}));
      field.dispatchEvent(new Event('change', {bubbles: true}));
    }
    Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
      .find((b) => b.textContent.trim() === 'Save').click();
  }, {text: literalText, code: !!(opts && opts.code)});
  await page.waitForFunction(
    () => !document.querySelector('.arg-value-edit-popover'),
    null, {timeout: 20000, polling: 100});
}


// A LIST-typed chip (`?children` on any container) appends items instead of
// binding one value — same flow the sequence anchor's `+` opens.
async function appendFnRefViaChip(page, chipName, fnName) {
  await chipByName(page, chipName);
  await page.waitForFunction(() => Array.from(document.querySelectorAll('button'))
    .some((b) => b.textContent.trim() === 'Append fn-ref'),
  null, {timeout: 15000, polling: 100});
  await page.evaluate(() => {
    Array.from(document.querySelectorAll('button'))
      .find((b) => b.textContent.trim() === 'Append fn-ref').click();
  });
  await page.waitForSelector('.fn-picker-popover', {timeout: 15000});
  await page.fill('.fn-picker-popover input', fnName);
  await page.waitForFunction((name) => Array.from(
    document.querySelectorAll('.fn-picker-row')).some((r) =>
    (r.querySelector('.fn-picker-row-main')?.textContent || '').includes(name)),
  fnName, {timeout: 30000, polling: 150});
  await page.evaluate((name) => {
    Array.from(document.querySelectorAll('.fn-picker-row')).find((r) =>
      (r.querySelector('.fn-picker-row-main')?.textContent || '').includes(name))
      .click();
  }, fnName);
  await page.waitForFunction(() => !document.querySelector('.fn-picker-popover'),
    null, {timeout: 30000, polling: 150});
}


// Rename an arg through its EDGE LABEL — the name span, not the type chip
// beside it. The rename writes a `:rename-to` binding, which mints the
// rename-view slot; the label on the edge changes to the new name.
async function renameArgViaEdgeLabel(page, currentName, newName) {
  await page.waitForFunction((name) => Array.from(
    document.querySelectorAll('.edge-label-overlay span'))
    .some((sp) => sp.textContent.trim() === name && sp.title === 'Click to rename arg'),
  currentName, {timeout: 30000, polling: 150});
  // A row-actions popover left open from an earlier step sits over the
  // label. Close it the way a user does — Escape — and NOT by removing the
  // node: the editor holds a singleton reference to that element, so
  // deleting it leaves every later ⋯ click with nothing to open.
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => {
    const pop = document.querySelector('.row-actions-popover');
    return !pop || pop.offsetParent === null;
  }, null, {timeout: 10000, polling: 50});
  await page.evaluate((name) => {
    Array.from(document.querySelectorAll('.edge-label-overlay span'))
      .find((sp) => sp.textContent.trim() === name
                 && sp.title === 'Click to rename arg')
      .dispatchEvent(new MouseEvent('click', {bubbles: true}));
  }, currentName);
  await page.waitForFunction(() => {
    const pop = document.querySelector('.arg-value-edit-popover');
    return pop && pop.getAttribute('aria-label') === 'Rename arg';
  }, null, {timeout: 15000, polling: 100});
  await page.evaluate((name) => {
    const pop = document.querySelector('.arg-value-edit-popover');
    const input = pop.querySelector('input');
    input.value = name;
    input.dispatchEvent(new Event('input', {bubbles: true}));
    Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
      .find((b) => b.textContent.trim() === 'Save').click();
  }, newName);
  await page.waitForFunction(
    () => !document.querySelector('.arg-value-edit-popover'),
    null, {timeout: 20000, polling: 100});
}


// Create a RECORD type-row through the ns row's create menu: + → New type…
// → Record tab → name + field pairs → Create. `fields` is [[name, type], …]
// and must not exceed the two rows the form starts with (the lesson uses
// exactly two; "+ add row" is the user's affordance for more).
// Avatar → “Organization” → <section> — the path SIX lessons now spell out
// (16 Members, 17 Grants, 20 Apps, 29 Errors/Type errors, 31 Roles, 18
// Monitoring). Two guards had their own copy and they had already drifted:
// one skipped the account menu entirely by setting the hash, so the step the
// lesson describes went unexercised; the other clicked the section once and
// flaked, because the nav re-renders as the surface opens and a click can
// land before its handler is bound. One helper, both behaviours.
// The account button is an AVATAR chip in accounts mode and a LOCK icon on a
// token deployment — same menu, different trigger. A helper (or a lesson)
// that names only the avatar silently excludes every self-hosted instance,
// which is exactly where lesson 22 lives.
async function openAccountMenu(page) {
  await page.waitForSelector('.auth-avatar, #auth-lock-btn', {timeout: 30000});
  await page.evaluate(() => {
    (document.querySelector('.auth-avatar')
     || document.getElementById('auth-lock-btn')).click();
  });
  await page.waitForSelector('.auth-menu-item', {timeout: 15000});
}


async function openOperateSection(page, section) {
  await openAccountMenu(page);
  await page.evaluate(() => {
    const item = Array.from(document.querySelectorAll('.auth-menu-item'))
      .find((b) => /organization/i.test(b.textContent));
    if (!item) throw new Error('no "Organization" item in the account menu');
    item.click();
  });
  await page.waitForSelector('#gd-operate-nav button[data-section="' + section + '"]',
                             {timeout: 30000});
  const up = () => page.evaluate((s) => {
    const el = document.querySelector('#gd-operate-panels > [data-section="' + s + '"]');
    if (!el || el.hasAttribute('hidden')) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }, section);
  for (let i = 0; i < 10 && !(await up()); i++) {
    await page.evaluate((s) => {
      document.querySelector('#gd-operate-nav button[data-section="' + s + '"]')?.click();
    }, section);
    await waitUntil(page, (s) => {
      const el = document.querySelector('#gd-operate-panels > [data-section="' + s + '"]');
      if (!el || el.hasAttribute('hidden')) return false;
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    }, section, 1000);
  }
  if (!await up()) {
    throw new Error('Operate → ' + section + ' did not open');
  }
}


// Avatar → “Settings” — the account surface lessons 32 and 16 point at.
async function openAccountSettings(page) {
  await openAccountMenu(page);
  await page.evaluate(() => {
    const item = Array.from(document.querySelectorAll('.auth-menu-item'))
      .find((b) => /settings/i.test(b.textContent));
    if (!item) throw new Error('no "Settings" item in the account menu');
    item.click();
  });
  await page.waitForSelector('#gd-acct-idents', {timeout: 30000});
}


async function createRecordType(page, nsPath, typeName, fields) {
  await page.waitForSelector('.ns-header[data-ns-path="' + nsPath + '"]',
    {timeout: 30000});
  await page.evaluate((path) => {
    const h = document.querySelector('.ns-header[data-ns-path="' + path + '"]');
    h.querySelector('.ns-plus-btn').click();
  }, nsPath);
  await page.waitForSelector('.create-menu [data-type="type"]', {timeout: 15000});
  await page.evaluate(() => {
    document.querySelector('.create-menu [data-type="type"]').click();
  });
  await page.waitForSelector('.type-create-popover', {timeout: 15000});
  // The lesson's step completes on this popover being OPEN, and the tour polls
  // for it every 600ms. A guard that opens and submits it inside one frame
  // never performs the step a reader takes seconds over — so hold it open past
  // a tick. (Before `dom` checks measured visibility, this passed for the
  // wrong reason: the popover is a SINGLETON that is emptied, not removed, so
  // a presence check stayed true forever once it had opened ONCE.)
  //
  // This one sleep is a CONTRACT, not a settle: it exists to be slower than
  // the tour's own 600ms poll. There is no faster observable to wait for —
  // waiting for the step to be satisfied is precisely what it enables.
  await page.waitForTimeout(1000);
  await page.evaluate(() => {
    Array.from(document.querySelectorAll('.type-create-popover button'))
      .find((b) => b.textContent.trim() === 'Record').click();
  });
  await page.waitForFunction(
    () => document.querySelectorAll('.type-create-pair-key').length >= 2,
    null, {timeout: 15000, polling: 100});
  await page.evaluate(({name, pairs}) => {
    const pop = document.querySelector('.type-create-popover');
    const set = (el, v) => {
      el.value = v;
      el.dispatchEvent(new Event('input', {bubbles: true}));
    };
    set(pop.querySelector('input.type-create-input'), name);
    const keys = Array.from(pop.querySelectorAll('.type-create-pair-key'));
    const vals = Array.from(pop.querySelectorAll('.type-create-pair-val'));
    pairs.forEach(([k, t], i) => { set(keys[i], k); set(vals[i], t); });
    Array.from(pop.querySelectorAll('button'))
      .find((b) => b.textContent.trim() === 'Create').click();
  }, {name: typeName, pairs: fields});
  // The popover element is a SINGLETON: on success it is emptied and
  // hidden, not removed — waiting for the node to disappear waits forever.
  await page.waitForFunction(() => {
    const pop = document.querySelector('.type-create-popover');
    return !pop || pop.textContent.trim() === '';
  }, null, {timeout: 30000, polling: 150});
}


module.exports = {
  NS_NAME, FN_NAME,
  retryingDelete, hardCleanup, tourTitle, waitTourTitle, clickTourButton,
  waitUntil, tourProgress, clickTourAdvance,
  filterAndSelect, extendViaRowActions, bindFirstPlaceholder,
  pickIncompatFnRef, pickAnyway, removeUseSiteBinding, waitClickable,
  createBranchViaChip, switchBranchViaChip, editBoundValue, runViaRowActions,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  runWithEffectAck, finishAndDelete, bindFnRefPlaceholder,
  bindOptionalArgChip, appendFnRefViaChip, renameArgViaEdgeLabel,
  createRecordType, openOperateSection, openAccountSettings, openAccountMenu,
};
