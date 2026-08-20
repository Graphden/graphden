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
  const leftovers = ['tutorial-b', 'tutorial-a', 'add-10', 'tutorial-json',
                     'tutorial-typed', 'tutorial-map', 'branch-demo',
                     'two-plus-two', 'tutorial-bump', 'tutorial-cell'];
  for (let pass = 0; pass < 2; pass++) {
    for (const nm of leftovers) {
      await retryingDelete(() => deleteFnByName(page, nm));
    }
  }
  try {
    const tree = await api(page, 'GET', '/api/graph/entities?scope=tree');
    for (const nsName of [NS_NAME, 'tests']) {
    const ns = (tree.namespaces || []).find((n) => n.name === nsName);
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
      // tutorial-NN-xxxx = an isolation branch; tutorial-branch = the one
      // lesson 08 forks by hand.
      if (/^tutorial-(\d\d-|branch$)/.test(b.name || '')) {
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
  await page.waitForTimeout(1200);
  // The incompatible candidates hide behind a collapsed "Other · N" header.
  await page.evaluate(() => {
    const hdr = Array.from(document.querySelectorAll('.fn-picker-section-header'))
      .find((h) => /Other/.test(h.textContent));
    if (hdr) hdr.click();
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


// --- lesson 08 (branches) helpers -------------------------------------------

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


// --- lesson 26 (tests) helpers ----------------------------------------------
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


// --- lesson 07 (effects) helper ---------------------------------------------
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
  assert(await clickTourButton(page, 'Delete them'), 'Delete them button');
  await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
    null, {timeout: 20000, polling: 200});
}


module.exports = {
  NS_NAME, FN_NAME,
  retryingDelete, hardCleanup, tourTitle, waitTourTitle, clickTourButton,
  filterAndSelect, extendViaRowActions, bindFirstPlaceholder,
  pickIncompatFnRef, pickAnyway, removeUseSiteBinding, waitClickable,
  createBranchViaChip, switchBranchViaChip, editBoundValue, runViaRowActions,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  runWithEffectAck, finishAndDelete,
};
