// Shared helpers for the interactive-tutorial drift guards.
//
// The guard walks every step of every lesson by performing the real UI
// actions the tour asks for. That is deliberately slow, so the lessons are
// SPLIT across several `edit-tutorial-tour-*.test.js` files: the runner caps
// a single file at 5 minutes (a hang-bound contract, not a budget to raise),
// and one file walking nine lessons blew through it on the gate.
//
// Everything shared by those files lives here.

const {assert, api, deleteFnByName, submitInlineRow} = require('./edit-test-helpers');

const NS_NAME = 'tutorial';
const FN_NAME = 'one-plus-one';


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
  // tutorial-b parented on tutorial-a (the pre-fix lesson-04 failure),
  // and a fn that is still someone's parent refuses to delete (409). One
  // ordered pass clears the normal case; the second pass collects
  // whatever the first pass unblocked.
  // A service row pins its fn: `tutorial-daemon` refuses to delete while
  // lesson 35's row still points at it, so the row goes first.
  try {
    const svcs = await api(page, 'GET', '/api/services');
    for (const s of (svcs.services || [])) {
      if ((s['fn-name'] || '').startsWith('tutorial-')) {
        await api(page, 'DELETE', '/api/entities/service/' + s.id);
      }
    }
  } catch (_) { /* best-effort */ }
  const leftovers = ['tutorial-versioned', 'tutorial-bad-json',
                     // lesson 01 (1 + 1), lesson 17's fn under test
                     'one-plus-one', 'tutorial-sum',
                     'tutorial-b', 'tutorial-a', 'add-10-text', 'add-10',
                     'tutorial-json',
                     'tutorial-typed', 'tutorial-map', 'branch-demo',
                     'two-plus-two', 'tutorial-bump', 'tutorial-cell',
                     'tutorial-card', 'tutorial-button', 'tutorial-script',
                     'tutorial-renamed', 'tutorial-point', 'tutorial-daemon',
                     'tutorial-tick', 'review-demo',
                     // lesson 18's chain — a crash between its create and
                     // finishAndDelete 409s the next run's create.
                     'tutorial-sentence', 'tutorial-shout', 'tutorial-words',
                     // lesson 38's consumer, then its listener, then the
                     // response the listener hands out (the fn-ref edge is no
                     // dependency, but delete the pointer first).
                     'tutorial-endpoint', 'tutorial-fetch', 'tutorial-server',
                     'tutorial-hello',
                     // lessons 06 / 07 — children before parents.
                     'tutorial-sum-more', 'tutorial-base-sum',
                     'tutorial-cut-more', 'tutorial-cut',
                     // lesson 03's MI fn and lesson 09's callable (2026-09-20).
                     'tutorial-json-ok', 'tutorial-upper',
                     // lesson 16's secret-typed slot demo.
                     'tutorial-db-call'];
  // Per-browser view-state the lessons exercise (filters / views,
  // recents, last-used ns) — a leftover filter renders the next lesson's
  // Explorer as somebody else's narrowed tree. `graphden.tour.next` is the
  // same class of hazard with teeth: a lesson parked by "start the next one"
  // and never picked up would open a tour over the FOLLOWING test's page.
  try {
    await page.evaluate(() => {
      for (const k of ['graphden.explorer.filters', 'graphden.explorer.views',
                       'graphden.recentFns', 'graphden.lastNs', 'graphden.tour.next']) {
        localStorage.removeItem(k);
      }
      if (typeof gdClearFilters === 'function') gdClearFilters();
    });
  } catch (_) { /* page may not be on the editor yet */ }
  for (let pass = 0; pass < 2; pass++) {
    for (const nm of leftovers) {
      await retryingDelete(() => deleteFnByName(page, nm));
    }
  }
  // lesson 32's pin has to go before its namespaces: uninstall leaves the
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
    // ROOT namespaces only. `name` is a SEGMENT: the platform ships its own
    // `core.tests` / `web.tests` self-test modules, so a bare `n.name ===
    // 'tests'` match walks one of THOSE and spends a doomed round trip per
    // fn in it (403 package-owned / 409 still referenced) — 88 s of this
    // helper, per call, which is what pushed this file past the runner's
    // 5-minute cap. The lessons create their namespaces at the root.
    const ns = (tree.namespaces || []).find(
      (n) => n.name === nsName && !n['parent-id']);
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
  // install ("entities not found"), so a crashed lesson-17 run would poison
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
      // tutorial-release / tutorial-feature are the ones lessons 22 + 20
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


// Where the tour stood, read from the tour DATA the page runs — lesson id +
// slug + step — for a failure message. The walks' own labels are prose, and
// after the 2026-09 renumbering half of them named the wrong lesson; a gate log
// that says "lesson 23 (branches) · step 4/9" cannot drift that way.
async function tourWhere(page) {
  try {
    return await page.evaluate(() => {
      const t = document.querySelector('#gd-tour-pop .gd-tour-title');
      const title = t ? t.textContent.trim() : null;
      const lesson = (typeof _tourLesson === 'function') ? _tourLesson() : null;
      if (!lesson) return title ? '"' + title + '" (no lesson state)' : 'no tour open';
      const step = (typeof _tourState !== 'undefined' && _tourState) ? _tourState.step + 1 : '?';
      return 'lesson ' + lesson.id + (lesson.slug ? ' (' + lesson.slug + ')' : '')
        + ' · step ' + step + '/' + ((lesson.steps || []).length || '?')
        + (title ? ' · "' + title + '"' : '');
    });
  } catch (e) {
    return 'unreadable (' + e.message.split('\n')[0] + ')';
  }
}


// Deadlines are sized for the GATE's shared e2e stack, not a dev laptop:
// a write-following step there can stall >60s behind a registry recompile
// plus GC churn (observed 2026-08-19: three 45s branch-wait timeouts and
// one 60s seed-step timeout in one gate run). Polling keeps the success
// path fast — a generous ceiling only slows the FAILURE case.
// --- spotlight audit -------------------------------------------------------
//
// `GRAPHDEN_TOUR_AUDIT=<dir>` makes every lesson walk in this suite double
// as a SPOTLIGHT AUDIT: a sampler in the page watches the tour engine
// (`_tourStep` / `_tourEffTarget`) and, whenever the ringed element changes
// — a new step, or a `:targets` chain advancing to a menu item, a chooser,
// a picker row — records what is actually in the ring: the effective
// selector, the element (tag / class / text / the card it belongs to), the
// element under the ring's centre, how many visible elements the selector
// matched (an ambiguous selector rings "the first one"), the ring rect and
// whether the step popover covers its own target. Written to
// `<dir>/spotlight-audit-<pid>-<page>.json` — one file per page, so every
// walk of a run can share one directory; `node tour-spotlight-report.js
// <dir>` prints it per step, `--gate` reds a step whose target was never
// ringed. With `GRAPHDEN_TOUR_AUDIT_SHOTS=1` a screenshot is taken at every
// change too. This is how the 2026-09-15 lesson-18 audit found the ⋯ ring
// on the wrong card — the e2e walk drives by selector and never notices
// where the ring is; a person cannot miss it. run-edit-tests.sh sets the
// directory for every file it runs, so the gate audits every lesson walk.
const _tourAuditState = new WeakMap();
let _tourAuditPages = 0;

async function installSpotlightAudit(page) {
  const dir = process.env.GRAPHDEN_TOUR_AUDIT;
  if (!dir || _tourAuditState.has(page)) return;
  const fs = require('node:fs');
  const path = require('node:path');
  fs.mkdirSync(dir, {recursive: true});
  _tourAuditPages += 1;
  const state = {records: [], n: 0,
    file: path.join(dir, 'spotlight-audit-' + process.pid + '-' + _tourAuditPages + '.json')};
  _tourAuditState.set(page, state);
  const flush = () => {
    try {
      fs.writeFileSync(state.file, JSON.stringify(state.records, null, 1));
    } catch (_) { /* best effort */ }
  };
  await page.exposeFunction('__gdTourAuditSink', async (rec) => {
    state.n += 1;
    rec.n = state.n;
    state.records.push(rec);
    if (process.env.GRAPHDEN_TOUR_AUDIT_SHOTS) {
      try {
        await page.screenshot({path: path.join(dir,
          String(state.n).padStart(3, '0') + '-' + String(rec.lesson || '').replace(/\W+/g, '')
          + '-' + String(rec.step || 0) + '.png')});
      } catch (_) { /* mid-navigation */ }
    }
    flush();
  });
  const sampler = () => {
    if (window.__gdTourAuditTimer) return;
    const desc = (el) => {
      if (!el) return null;
      const owner = el.closest('.node-overlay');
      const within = el.closest('.row-actions-popover, .arg-value-edit-popover, .free-arg-bind-chooser, .fn-picker-popover, .execute-popover, .fn-peek-panel, .trace-view-panel, #gd-inspector, #side-menu');
      const cls = el.className && el.className.baseVal !== undefined ? el.className.baseVal : (el.className || '');
      return {
        tag: el.tagName.toLowerCase(), id: el.id || null, cls: String(cls).slice(0, 80),
        text: (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 60),
        card: owner ? (owner.dataset.fnName || owner.textContent.trim().replace(/\s+/g, ' ').slice(0, 40)) : null,
        within: within ? (within.id ? '#' + within.id : '.' + String(within.className).split(' ')[0]) : null,
      };
    };
    const rr = (el) => {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)];
    };
    window.__gdTourAuditTimer = setInterval(() => {
      try {
        if (typeof _tourStep !== 'function' || typeof _tourEffTarget !== 'function') return;
        const step = _tourStep();
        const lesson = (typeof _tourLesson === 'function') ? _tourLesson() : null;
        const pop = document.getElementById('gd-tour-pop');
        if (!step || !pop) return;
        const eff = _tourEffTarget(step);
        const el = eff ? document.querySelector(eff) : null;
        const spot = document.getElementById('gd-tour-spot');
        const spotVis = !!spot && spot.classList.contains('gd-tour-visible');
        const key = [lesson?.id, step.title, eff, spotVis, el ? 1 : 0].join('|');
        if (key === window.__gdTourAuditKey) return;
        // A ring is reported once it has HELD for two samples (~500 ms, a
        // tour tick): the first sample after a popover opens sees the step
        // popover where the tour is about to move it off the new target,
        // and that instant read as POPOVER-COVERS-TARGET on every chooser.
        if (key !== window.__gdTourAuditPending) {
          window.__gdTourAuditPending = key;
          return;
        }
        window.__gdTourAuditKey = key;
        const sr = spotVis ? rr(spot) : null;
        let under = null;
        if (sr) {
          const hits = document.elementsFromPoint(sr[0] + sr[2] / 2, sr[1] + sr[3] / 2)
            .filter((e) => !['gd-tour-spot', 'gd-tour-dim', 'gd-tour-pop'].includes(e.id));
          under = desc(hits[0]);
        }
        const pr = rr(pop);
        const tr = rr(el);
        const overlap = (a, b) => !!(a && b && a[0] < b[0] + b[2] && a[0] + a[2] > b[0]
          && a[1] < b[1] + b[3] && a[1] + a[3] > b[1]);
        const matches = eff ? Array.from(document.querySelectorAll(eff))
          .filter((e) => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }).length : 0;
        window.__gdTourAuditSink({
          lesson: lesson?.id || null, step: (typeof _tourState !== 'undefined' && _tourState) ? _tourState.step + 1 : null,
          title: step.title, target: step.target || null, eff,
          el: desc(el), elRect: tr, ring: sr, under, matches,
          centered: pop.classList.contains('gd-tour-centered'), popOverTarget: overlap(pr, tr),
        });
      } catch (_) { /* keep sampling */ }
    }, 250);
  };
  await page.addInitScript(sampler);
  try { await page.evaluate(sampler); } catch (_) { /* no document yet */ }
}

async function waitTourTitle(page, title, timeoutMs) {
  await installSpotlightAudit(page);
  await page.waitForFunction((expected) => {
    const t = document.querySelector('#gd-tour-pop .gd-tour-title');
    return t && t.textContent.trim() === expected;
  }, title, {timeout: timeoutMs || 120000, polling: 150});
  // Under the audit, settle like a reader: a walk acts within milliseconds
  // of the title, and on a step whose action REMOVES its target (Uninstall,
  // Revert, the diff chip's ×, a Save that closes its popover) the ring
  // never holds the two samples the audit records — the 2026-09-16 baseline
  // flagged seven such steps, every one of them fine for a person. Wait for
  // the step's effective target to be on screen (bounded — a target that
  // never comes is exactly what the gate should then report), then one tour
  // tick so the sampler sees the ring on it. The bound is generous: under
  // gate load a big canvas (map's, lesson 09) took over 5 s to land its
  // type chips, and the walk's own selector wait then found them and
  // pressed Next inside the ring's hold window — NEVER-RINGED for a step a
  // person reads for ten seconds.
  if (process.env.GRAPHDEN_TOUR_AUDIT) {
    await settleTourRing(page, 20000);
  }
}

// Wait (bounded) until the audit's sampler has RECORDED the current step with
// its effective target on screen — the ring held for a tour tick. Walks call
// it after their own "wait for X to render" when X is the step's target — the
// title arrived before X did, so waitTourTitle's settle could not see it.
//
// It used to wait for the target and then sleep a flat 650 ms (two 250 ms
// samples plus slack) on every step — ~370 steps a gate, ~4 minutes, most of
// it after the sampler had long committed. The sampler publishes what it
// committed in `window.__gdTourAuditKey` (`lesson|title|eff|spotVisible|el?`),
// so wait for exactly that: the same "held for two samples" guarantee the
// NEVER-RINGED verdict reads (a record with an element), no sooner and no
// later. A step with no target has nothing to ring and returns at once.
async function settleTourRing(page, timeoutMs) {
  if (!process.env.GRAPHDEN_TOUR_AUDIT) return;
  await installSpotlightAudit(page);
  await page.waitForFunction(() => {
    if (typeof _tourStep !== 'function' || typeof _tourEffTarget !== 'function') return true;
    const step = _tourStep();
    if (!step) return true;
    const eff = _tourEffTarget(step);
    if (!eff) return true;
    if (!document.querySelector(eff)) return false;
    const lesson = (typeof _tourLesson === 'function') ? _tourLesson() : null;
    const recorded = window.__gdTourAuditKey || '';
    return recorded.startsWith([lesson?.id, step.title, eff].join('|') + '|')
      && recorded.endsWith('|1');
  }, null, {timeout: timeoutMs || 5000, polling: 50}).catch(() => {});
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
// header's step counter ("lesson 29 · step 4/9") is the observable; a
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
  // the lens probe in `edit-tutorial-tour-picker` waits on.
  await page.waitForFunction((name) => {
    const row = Array.from(document.querySelectorAll('#entity-list .entity-item'))
      .find((e) => e.querySelector('.name')?.textContent.trim() === name);
    return row && !row.hasAttribute('hidden');
  }, fnName, {timeout: 30000, polling: 100});
  await page.evaluate(async (name) => { await selectFnByName(name); }, fnName);
}


// `expectOwner` (optional) — the fn whose row the ⋯ must belong to. The
// canvas re-renders asynchronously after a selection change, so clicking
// the FIRST ⋯ can hit the previous card: in lesson 04 that silently
// extended tutorial-a instead of str-upper, and the step's fn-parent
// check (correctly) never passed.
// Open the ⋯ of the card whose text starts with `ownerName` — not "the first
// ⋯ in the document", which is whatever the layout placed first: a parent
// row, an execute-result host, the card that was selected BEFORE the create
// the step just made. Lesson 26's description edit once fired against
// `const`'s ⋯ that way (the canvas had not re-rendered with the new child
// yet) — a PUT on the package-owned parent, 400, and a version that never
// existed. The canvas may take a while to grow the card under load, hence
// the long wait.
//
// `root` — the card must also be the canvas ROOT (the selected fn). A name
// alone is not enough right after a selection: the PREVIOUS canvas may hold a
// card of that name too (tutorial-map's :func card is `str-upper`), and its
// ⋯ → Extend is extend-IN-PLACE — a swap inside tutorial-map, not a new
// child of str-upper. Masked until 2026-09-24 by a flat 650 ms sleep after
// every tour title; `ownerName` null + `root` = whatever the root card is.
async function openRowActionsFor(page, ownerName, timeoutMs, opts = {}) {
  const pick = ({name, root}) => Array.from(document.querySelectorAll('.node-overlay')).find((ov) =>
    (!name || ov.textContent.trim().startsWith(name))
    && ov.querySelector('button.more-actions-trigger')
    && (!root || !!(window.graph && window.graph.nodes.get(ov.dataset.nodeId)?.data?.isRoot)));
  const arg = {name: ownerName || null, root: !!opts.root, src: pick.toString()};
  await page.waitForFunction(({name, root, src}) =>
    !!(new Function('return (' + src + ')')())({name, root}),
  arg, {timeout: timeoutMs || 90000, polling: 200});
  await page.evaluate(({name, root, src}) => {
    const ov = (new Function('return (' + src + ')')())({name, root});
    ov.querySelector('button.more-actions-trigger')
      .dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
  }, arg);
  await page.waitForSelector('.row-actions-popover', {timeout: 15000});
}


async function extendViaRowActions(page, childName, expectOwner) {
  // Extending the SELECTED fn: its ⋯ is the root card's (see openRowActionsFor).
  await openRowActionsFor(page, expectOwner || null, 90000, {root: true});
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
  await appendOrBindLiteralFromChooser(page, literalText);
}


// The FIRST item of a sequence slot when the card carries other `+`s too —
// or did a beat ago: right after a ref is picked into a sibling slot the card
// still shows that slot's placeholder until the re-render lands, and "click
// the first `.placeholder-binder`" opens the fn picker again instead of the
// chooser (lesson 09, 2326-09-14). Wait for the list slot's own anchor to be
// the only placeholder left, then take the literal path.
async function bindSeqAnchorPlaceholder(page, literalText) {
  // The anchor itself, whatever else is on the card (lesson 09 binds :coll
  // while :func's `+` is still there); it just has to be settled — no
  // placeholder without a node id, which is what a mid-re-render card shows.
  await page.waitForFunction(() => {
    const all = Array.from(document.querySelectorAll('.placeholder-binder'));
    return all.some((b) => b.classList.contains('is-seq-anchor'))
      && all.every((b) => b.closest('.node-overlay')?.dataset.nodeId);
  }, null, {timeout: 30000, polling: 150});
  await page.evaluate(() => document.querySelector('.placeholder-binder.is-seq-anchor').click());
  await appendOrBindLiteralFromChooser(page, literalText);
}


// The SECOND (and later) item of a sequence slot: a list that holds items
// still ends in a `+` — the append TAIL, the list's next free slot, drawn as
// the last branch of the fan (`.placeholder-binder.is-seq-anchor`, the same
// binder the empty list shows). Lessons 01 (1 + 1) and 14 (2 + 2) take this
// path; it waits for the re-render that follows the previous append to settle
// so the click lands on the tail and not on a binder about to be replaced.
async function appendSeqItemViaEdge(page, literalText) {
  await bindSeqAnchorPlaceholder(page, literalText);
}


// Shared tail of the two above: the literal / fn-ref chooser (if the slot
// offers one), the value form, Save, and the wait for the write to land.
async function appendOrBindLiteralFromChooser(page, literalText) {
  // Scalar slots offer "Bind literal"; sequence slots offer "Append
  // literal" (or "Insert literal" from an item's + ) — accept any.
  await page.waitForFunction(() => {
    return Array.from(document.querySelectorAll('button'))
      .some((b) => /^(Bind|Append|Insert) literal$/.test((b.textContent || '').trim()));
  }, null, {timeout: 8000, polling: 100});
  await page.evaluate(() => {
    Array.from(document.querySelectorAll('button'))
      .find((b) => /^(Bind|Append|Insert) literal$/.test((b.textContent || '').trim()))
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


// --- lesson 08 (types) helpers ----------------------------------------------

// Open the "+" binder, switch to fn-ref, expand the incompatible "Other"
// section and click the named candidate — which opens the server-rendered
// mismatch explainer instead of binding straight away.
async function pickIncompatFnRef(page, fnName) {
  await page.waitForSelector('.placeholder-binder', {timeout: 30000});
  await page.evaluate(() => document.querySelector('.placeholder-binder').click());
  await page.waitForFunction(() => Array.from(document.querySelectorAll('button'))
    .some((b) => b.textContent.trim() === 'Bind fn-ref'),
    null, {timeout: 15000, polling: 100});
  await page.evaluate(() => {
    Array.from(document.querySelectorAll('button'))
      .find((b) => b.textContent.trim() === 'Bind fn-ref').click();
  });
  await page.waitForSelector('.fn-picker-popover', {timeout: 15000});
  await page.fill('.fn-picker-popover input', fnName);
  // A typed filter lists every name match, the incompatible ones dimmed in
  // place (`.fn-picker-row-incompat`) — no section to expand. The verdict
  // arrives with the server's candidate set, so wait for the DIMMED row.
  const sel = '.fn-picker-popover .fn-picker-row-incompat[data-fn-name$="' + fnName + '"]';
  await page.waitForSelector(sel, {timeout: 30000});
  await page.click(sel);
  await page.waitForSelector('.mismatch-explainer.visible [data-pick-fn-id]', {timeout: 15000});
}


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


// --- lesson 23 (branches) helpers -------------------------------------------

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


// Run from the pane that is ALREADY open in the inspector (the Runs tab
// stays selected across selections once used), without going through
// ⋯ → ▶ Run — the path a reader takes when the form is right there. Returns
// the parsed `/api/execute` response, so the caller can assert the run was
// accepted under the fn's CURRENT interface (lesson 05: after a rename the
// pane must ask for the new name, or the run is rejected with "Unknown
// arg(s)" — the form was stale until 2026-09-14).
async function runFromOpenPane(page, formValue, argName) {
  // The pane is rebuilt asynchronously after the edit that preceded this
  // call, so wait for the rebuilt one: its arg row names the slot
  // (`data-slot-name`), and the widget inside it must have mounted.
  await page.waitForFunction((name) => {
    const p = document.querySelector('.execute-popover.visible');
    const row = p && p.querySelector('.execute-arg-form[data-slot-name="' + name + '"]');
    return !!(row && row.querySelector('[data-form-field]'));
  }, argName, {timeout: 30000, polling: 100});
  await page.evaluate(({v, name}) => {
    const f = document.querySelector('.execute-popover.visible .execute-arg-form[data-slot-name="'
                                     + name + '"] [data-form-field]');
    f.value = v;
    f.dispatchEvent(new Event('input', {bubbles: true}));
    f.dispatchEvent(new Event('change', {bubbles: true}));
  }, {v: formValue, name: argName});
  const [resp] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/execute') && r.request().method() === 'POST',
                         {timeout: 30000}),
    page.click('.execute-popover.visible .execute-run-btn'),
  ]);
  return resp.json().catch(() => ({}));
}


// --- lesson 17 (tests) helpers ----------------------------------------------
// Extracted from lesson 01's inline steps: the ns / fn / set-parent flows are
// identical, only the names differ.

async function createRootNamespace(page, name) {
  await page.waitForSelector('.create-root-ns-btn', {timeout: 15000});
  await page.click('.create-root-ns-btn');
  await submitInlineRow(page, name);
}


async function createFnInNamespace(page, nsName, fnName) {
  // Match the ROW BY PATH (`data-ns-path`), not by its label: a label is a
  // SEGMENT, and the platform ships `core.tests` / `web.tests`, whose rows
  // read "tests" exactly like the lesson's own root namespace once their
  // parent is expanded.
  // The inline create row only renders inside an EXPANDED namespace.
  await page.waitForFunction((path) => {
    return !!document.querySelector('.ns-header[data-ns-path="' + path + '"]');
  }, nsName, {timeout: 30000, polling: 200});
  await page.evaluate((path) => {
    const target = document.querySelector('.ns-header[data-ns-path="' + path + '"]');
    const arrow = target.querySelector('.ns-arrow');
    if (arrow && /▶/.test(arrow.textContent || '')) target.click();
  }, nsName);
  await page.waitForFunction((path) => {
    const target = document.querySelector('.ns-header[data-ns-path="' + path + '"]');
    const arrow = target?.querySelector('.ns-arrow');
    return arrow && /▼/.test(arrow.textContent || '');
  }, nsName, {timeout: 15000, polling: 100});
  // Find-and-click in ONE poll: the tree rebuilds when the expanded
  // namespace's fns land, and a one-shot lookup could miss the row.
  await page.waitForFunction((path) => {
    const target = document.querySelector('.ns-header[data-ns-path="' + path + '"]');
    const plus = target?.querySelector('.ns-plus-btn');
    if (!plus) return false;
    // A root created after the tree has grown sits at the Explorer's bottom
    // edge, and the `+` menu opens BELOW its row — off-screen, where a click
    // waits 45s and fails. Centre the row first; a reader scrolls, so does this.
    target.scrollIntoView({block: 'center'});
    plus.click();
    return true;
  }, nsName, {timeout: 15000, polling: 100});
  await page.waitForSelector('.create-menu', {timeout: 10000});
  await page.click('.create-menu-item[data-type="fn"]');
  await submitInlineRow(page, fnName);
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


// --- lesson 16 (effects) helper ---------------------------------------------
// Run a fn whose effects force the acknowledgement checkbox first.
// `ownerName` (optional) pins the ⋯ to THAT card's own row — with a child
// card on the canvas (a fn built from the outside in) the first ⋯ in the
// DOM may be the child's use-site menu, which has no ▶ Run.
async function runWithEffectAck(page, formValue, ownerName) {
  const trig = ownerName
    ? '.node-overlay[data-fn-name="' + ownerName + '"] .ancestor-line[data-level="0"] button.more-actions-trigger'
    : 'button.more-actions-trigger';
  await page.waitForSelector(trig, {timeout: 15000});
  await page.dispatchEvent(trig, 'mousedown');
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


// A finished lesson no longer just vanishes when it created nothing: the tour
// says so and offers what to read next (`Next up`). Dismiss that card if it is
// up — it renders one await AFTER the last step, so this waits for either
// outcome first — and then for the overlay to actually go.
async function waitTourClosed(page, ms) {
  const timeout = ms || 30000;
  await page.waitForFunction(() => {
    if (!document.querySelector('#gd-tour-pop')) return true;
    return Array.from(document.querySelectorAll('#gd-tour-pop .gd-tour-btn'))
      .some((b) => b.textContent.trim() === 'Close');
  }, null, {timeout, polling: 200});
  await clickTourButton(page, 'Close');
  await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
    null, {timeout, polling: 200});
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


// --- lesson 10 (components) helpers -----------------------------------------
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
// which is exactly where lesson 25 lives.
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


// Avatar → “Settings” — the account surface lessons 35 and 19 point at.
async function openAccountSettings(page) {
  await openAccountMenu(page);
  await page.evaluate(() => {
    const item = Array.from(document.querySelectorAll('.auth-menu-item'))
      .find((b) => /settings/i.test(b.textContent));
    if (!item) throw new Error('no "Settings" item in the account menu');
    item.click();
  });
  // Settings opens on Appearance; the account card is its own section.
  await page.waitForSelector('#gd-settings-nav button[data-section="account"]', {timeout: 30000});
  await page.evaluate(() => document.querySelector('#gd-settings-nav button[data-section="account"]').click());
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
  // The step's check is the RECORD tab being selected — so the same contract
  // holds here: a fill-and-Create inside the next 600ms hides the selected
  // tab from the tour's poll, and the lesson sticks on "New type…" with the
  // type already made (seen 2026-09-16).
  await page.waitForTimeout(1000);
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



// Bind the placeholder of a NAMED arg on the selected card — the binder
// nearest the edge label that carries `argName` (the label overlay sits
// just left of its target node, vertically centred on it; the binder is
// the target). `kind` is 'literal' (then `text` is typed) or 'fn-ref'
// (then `text` is the fn to pick). Use this when a card exposes several
// placeholders and the lesson names one: `bindFirstPlaceholder` takes
// whatever the canvas put first, which is not the lesson's choice.
async function bindNamedPlaceholder(page, argName, kind, text) {
  // A callable slot's label carries a leading λ (`λbase-handler`); match
  // the bare name either way.
  await page.waitForFunction((name) => {
    return Array.from(document.querySelectorAll('.edge-label-overlay span'))
      .some((sp) => sp.textContent.trim().replace(/^λ/, '') === name)
      && document.querySelector('.placeholder-binder');
  }, argName, {timeout: 30000, polling: 150});
  const found = await page.evaluate((name) => {
    const label = Array.from(document.querySelectorAll('.edge-label-overlay span'))
      .find((sp) => sp.textContent.trim().replace(/^λ/, '') === name);
    const lr = label.getBoundingClientRect();
    const ly = lr.top + lr.height / 2;
    let best = null;
    let bestD = Infinity;
    for (const b of document.querySelectorAll('.placeholder-binder')) {
      const r = b.getBoundingClientRect();
      if (r.left < lr.left) continue;
      const d = Math.abs((r.top + r.height / 2) - ly) + (r.left - lr.right) / 50;
      if (d < bestD) { bestD = d; best = b; }
    }
    if (!best) return false;
    best.click();
    return true;
  }, argName);
  assert(found, 'a placeholder binder next to the "' + argName + '" label');
  // `whole-list` — an EMPTY list slot's `+` offers "Bind fn-ref (whole
  // list)": the slot takes one fn's result as the entire list (lesson 18's
  // pipeline feeds :map's :coll with a :str-split that way).
  if (kind === 'fn-ref' || kind === 'whole-list') {
    const btnLabel = kind === 'whole-list' ? 'Bind fn-ref (whole list)' : 'Bind fn-ref';
    await page.waitForFunction((label) => {
      return !!document.querySelector('.fn-picker-popover')
        || Array.from(document.querySelectorAll('button'))
          .some((b) => b.textContent.trim() === label);
    }, btnLabel, {timeout: 15000, polling: 100});
    await page.evaluate((label) => {
      if (document.querySelector('.fn-picker-popover')) return;
      Array.from(document.querySelectorAll('button'))
        .find((b) => b.textContent.trim() === label).click();
    }, btnLabel);
    await page.waitForSelector('.fn-picker-popover', {timeout: 15000});
    await page.fill('.fn-picker-popover input', text);
    await page.waitForFunction((n) => {
      return Array.from(document.querySelectorAll('.fn-picker-row')).some((r) =>
        (r.querySelector('.fn-picker-row-main')?.textContent || '').includes(n));
    }, text, {timeout: 30000, polling: 150});
    await page.evaluate((n) => {
      Array.from(document.querySelectorAll('.fn-picker-row')).find((r) =>
        (r.querySelector('.fn-picker-row-main')?.textContent || '').includes(n)).click();
    }, text);
    await page.waitForFunction(() => !document.querySelector('.fn-picker-popover'),
      null, {timeout: 30000, polling: 150});
    return;
  }
  await page.waitForFunction(() => {
    return Array.from(document.querySelectorAll('button'))
      .some((b) => /^(Bind|Append) literal$/.test((b.textContent || '').trim()));
  }, null, {timeout: 8000, polling: 100});
  await page.evaluate(() => {
    Array.from(document.querySelectorAll('button'))
      .find((b) => /^(Bind|Append) literal$/.test((b.textContent || '').trim())).click();
  });
  await page.waitForFunction(() => {
    const pops = document.querySelectorAll('.arg-value-edit-popover');
    const pop = pops[pops.length - 1];
    return pop && (pop.querySelector('.arg-value-edit-input')
      || pop.querySelector('[data-form-field]'));
  }, null, {timeout: 10000, polling: 100});
  await page.evaluate((t) => {
    const pops = document.querySelectorAll('.arg-value-edit-popover');
    const pop = pops[pops.length - 1];
    const field = pop.querySelector('.arg-value-edit-input')
      || pop.querySelector('[data-form-field]');
    field.value = t;
    field.dispatchEvent(new Event('input', {bubbles: true}));
    field.dispatchEvent(new Event('change', {bubbles: true}));
    Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
      .find((b) => b.textContent.trim() === 'Save').click();
  }, text);
  await page.waitForFunction(() => !document.querySelector('.arg-value-edit-popover'),
    null, {timeout: 30000, polling: 150}).catch(() => {});
}

// The `+` of ONE card's slot — pinned by the fn the binding lands on AND
// the slot, because the outer and inner fn of a pipeline often expose the
// same slot name (:coll on both). `kind` as in `bindNamedPlaceholder`:
// 'literal' (then `text` is typed), 'fn-ref' / 'whole-list' (then `text`
// is the fn to pick; a callable slot skips the chooser and opens the
// picker directly — accepted either way).
async function bindPlaceholderOn(page, ownerName, argName, kind, text) {
  const sel = '.placeholder-binder[data-fn-name="' + ownerName + '"][data-arg-name="' + argName + '"]';
  await page.waitForSelector(sel, {timeout: 60000});
  await page.click(sel);
  if (kind === 'fn-ref' || kind === 'whole-list') {
    const btnLabel = kind === 'whole-list' ? 'Bind fn-ref (whole list)' : 'Bind fn-ref';
    await page.waitForFunction((label) => {
      return !!document.querySelector('.fn-picker-popover')
        || Array.from(document.querySelectorAll('.free-arg-bind-chooser button'))
          .some((b) => b.textContent.trim() === label);
    }, btnLabel, {timeout: 15000, polling: 100});
    await page.evaluate((label) => {
      if (document.querySelector('.fn-picker-popover')) return;
      Array.from(document.querySelectorAll('.free-arg-bind-chooser button'))
        .find((b) => b.textContent.trim() === label).click();
    }, btnLabel);
    await page.waitForSelector('.fn-picker-popover', {timeout: 15000});
    await page.fill('.fn-picker-popover input', text);
    // The row whose NAME is `text` — a bare-name filter also lists fns that
    // merely contain it (`cell` → `tutorial-cell`), so match the row's
    // qualified name's last segment, not a substring.
    const rowSel = '.fn-picker-popover .fn-picker-row[data-fn-name$=".' + text + '"], '
                 + '.fn-picker-popover .fn-picker-row[data-fn-name="' + text + '"]';
    await page.waitForSelector(rowSel, {timeout: 30000});
    await page.click(rowSel);
    await page.waitForFunction(() => !document.querySelector('.fn-picker-popover'),
      null, {timeout: 30000, polling: 150});
    return;
  }
  await page.waitForFunction(() => {
    return Array.from(document.querySelectorAll('.free-arg-bind-chooser button'))
      .some((b) => /^(Bind|Append) literal$/.test((b.textContent || '').trim()));
  }, null, {timeout: 8000, polling: 100});
  await page.evaluate(() => {
    Array.from(document.querySelectorAll('.free-arg-bind-chooser button'))
      .find((b) => /^(Bind|Append) literal$/.test((b.textContent || '').trim())).click();
  });
  await page.waitForFunction(() => {
    const pops = document.querySelectorAll('.arg-value-edit-popover');
    const pop = pops[pops.length - 1];
    return pop && (pop.querySelector('.arg-value-edit-input')
      || pop.querySelector('[data-form-field]'));
  }, null, {timeout: 10000, polling: 100});
  await page.evaluate((t) => {
    const pops = document.querySelectorAll('.arg-value-edit-popover');
    const pop = pops[pops.length - 1];
    const field = pop.querySelector('.arg-value-edit-input')
      || pop.querySelector('[data-form-field]');
    field.value = t;
    field.dispatchEvent(new Event('input', {bubbles: true}));
    field.dispatchEvent(new Event('change', {bubbles: true}));
    Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
      .find((b) => b.textContent.trim() === 'Save').click();
  }, text);
  await page.waitForFunction(() => !document.querySelector('.arg-value-edit-popover'),
    null, {timeout: 30000, polling: 150}).catch(() => {});
}


// ⋯ → Extend on the card of `cardName` — a fn that sits on the canvas
// because a slot binds it — naming the child `childName`. Extend IN PLACE:
// the child takes the card's place in that slot and the editor stays on
// the canvas it is on, so the helper waits for the CHILD's card, not for a
// navigation. Pinned to the card's own (depth-0) ⋯, not the first ⋯ painted.
async function extendInPlace(page, cardName, childName) {
  const trig = '.node-overlay[data-fn-name="' + cardName + '"] .ancestor-line[data-level="0"] button.more-actions-trigger';
  await page.waitForSelector(trig, {timeout: 60000});
  await page.evaluate((sel) => {
    document.querySelector(sel).dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
  }, trig);
  await page.waitForSelector('.row-actions-popover [data-action="extend-fn"]', {timeout: 15000});
  const hash = await page.evaluate(() => location.hash);
  await page.evaluate(() => {
    document.querySelector('.row-actions-popover [data-action="extend-fn"]')
      .dispatchEvent(new MouseEvent('click', {bubbles: true}));
  });
  await page.waitForSelector('.arg-value-edit-popover .arg-value-edit-input', {timeout: 10000});
  const hint = await page.evaluate(() => document.querySelector('.arg-value-edit-popover .arg-value-edit-hint')?.textContent || '');
  assert(/in place of/.test(hint), 'the Extend popover on a bound card says it extends in place: ' + hint);
  await page.evaluate((name) => {
    const pop = document.querySelector('.arg-value-edit-popover');
    const input = pop.querySelector('.arg-value-edit-input');
    input.value = name;
    input.dispatchEvent(new Event('input', {bubbles: true}));
    Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
      .find((b) => b.textContent.trim() === 'Save').click();
  }, childName);
  await page.waitForFunction(() => !document.querySelector('.arg-value-edit-popover'),
    null, {timeout: 30000, polling: 100});
  await page.waitForSelector('.node-overlay[data-fn-name="' + childName + '"]', {timeout: 60000});
  assert(await page.evaluate(() => location.hash) === hash,
    'extending in place stays on the canvas (hash ' + hash + ')');
}


// The seal popover behind an edge label's lock badge (editor-overlay-seal.js):
// open it on `argName`, set each checkbox in `wanted` (`{terminal: true,
// 'list-closed': false, required: true, 'slot-optional': …}`) to the state
// asked for, Save, wait for the popover to close. A checkbox already in the
// wanted state is left alone — a flip is a change, not a click.
//
// `fnName` (optional) — the fn whose card the badge must belong to. Right
// after a selection change the canvas re-renders asynchronously, and a badge
// clicked in that window opens the popover for the PREVIOUS card, or for one
// whose slot data has not landed yet (no "Close the list" box, because the
// slot does not read as a list yet). A person clicks once the card is drawn;
// so does this: the popover must name `fnName` and offer every wanted box,
// else it is dismissed and the badge clicked again. (Masked until 2026-09-24
// by a flat 650 ms sleep in every tour step's settle.)
async function setSealsViaBadge(page, argName, wanted, fnName) {
  const badge = '.edge-label-overlay[data-arg-name="' + argName + '"] .seal-badge';
  const deadline = Date.now() + 60000;
  for (;;) {
    await page.waitForSelector(badge, {timeout: 60000});
    await page.click(badge);
    await page.waitForSelector('.arg-value-edit-popover .seal-popover', {timeout: 15000});
    const ready = await waitUntil(page, ({keys, fn}) => {
      const pop = document.querySelector('.arg-value-edit-popover .seal-popover');
      if (!pop) return false;
      const head = pop.querySelector('.seal-popover-head')?.textContent || '';
      if (fn && !head.endsWith(' on ' + fn)) return false;
      return keys.every((k) => pop.querySelector('input[data-seal="' + k + '"]'));
    }, {keys: Object.keys(wanted), fn: fnName || null}, 3000);
    if (ready) break;
    assert(Date.now() < deadline, 'seal popover on :' + argName + ' offers ' + Object.keys(wanted).join(', ')
      + (fnName ? ' on ' + fnName : ''));
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('.arg-value-edit-popover'),
      null, {timeout: 15000, polling: 100}).catch(() => {});
    await new Promise((r) => setTimeout(r, 250));
  }
  for (const [key, on] of Object.entries(wanted)) {
    const sel = '.arg-value-edit-popover input[data-seal="' + key + '"]';
    const state = await page.$eval(sel, (i) => ({checked: i.checked, disabled: i.disabled}));
    assert(!state.disabled, 'seal "' + key + '" is offered enabled on :' + argName);
    if (state.checked !== !!on) await page.click(sel);
  }
  await page.evaluate(() => Array.from(document.querySelectorAll('.arg-value-edit-popover .arg-value-edit-btn'))
    .find((b) => b.textContent.trim() === 'Save').click());
  await page.waitForFunction(() => !document.querySelector('.arg-value-edit-popover'),
    null, {timeout: 30000, polling: 150});
}



// --- the verbs lessons 04 / 06 / 09 / 23 teach on the card itself ------------

// Delete a bound literal through its edit popover's Delete button (lesson 04
// "Take it away"). Fires a native confirm() — the caller's dialog handler
// accepts it. Resolves once the popover is gone.
async function deleteBoundValue(page) {
  await page.waitForSelector('.arg-value-editable', {timeout: 30000});
  await page.evaluate(() => document.querySelector('.arg-value-editable').click());
  await page.waitForSelector('.arg-value-edit-popover .arg-value-edit-btn-danger', {timeout: 15000});
  await page.evaluate(() => document.querySelector('.arg-value-edit-popover .arg-value-edit-btn-danger').click());
  await page.waitForFunction(() => !document.querySelector('.arg-value-edit-popover'),
    null, {timeout: 30000, polling: 100});
}


// The literal list item at rank `index`'s own order buttons (lesson 06):
// `dir` is 'up' / 'down'. Resolves when the item at that position reads
// something else (the graph reloaded).
async function moveSeqItem(page, index, dir) {
  const sel = '.edge-seq-item[data-index="' + index + '"] .arg-seq-btn-' + dir;
  await page.waitForSelector(sel, {timeout: 30000});
  const before = await page.evaluate((i) => {
    const ov = document.querySelector('.edge-seq-item[data-index="' + i + '"]');
    return ov ? ov.dataset.itemId : null;
  }, index);
  await page.evaluate((s) => document.querySelector(s).click(), sel);
  await page.waitForFunction(([i, id]) => {
    const ov = document.querySelector('.edge-seq-item[data-index="' + i + '"]');
    return ov && ov.dataset.itemId && ov.dataset.itemId !== id;
  }, [index, before], {timeout: 30000, polling: 150});
}


// `+` on the literal item at rank `index` → Insert literal → `text` → Save.
async function insertSeqLiteralBefore(page, index, text) {
  const sel = '.edge-seq-item[data-index="' + index + '"] .arg-seq-btn-insert';
  await page.waitForSelector(sel, {timeout: 30000});
  // A move just before this reloads the graph; a chooser opened while that
  // render lands is wiped by it — settle like a reader would.
  await page.waitForTimeout(2000);
  await page.waitForFunction(() => typeof graphReady === 'function' && graphReady() && !graph.animating,
    null, {timeout: 30000, polling: 100});
  await page.waitForSelector(sel, {timeout: 30000});
  await page.evaluate((s) => document.querySelector(s).click(), sel);
  await appendOrBindLiteralFromChooser(page, text);
}


// The Inspector Overview's "Call-site params" row for the selected fn →
// "These, in order" → tick `names` → Save (lesson 09). The row is what a
// reader on compact cards (the default) sees; the card's λ chip is the
// same popover. Resolves when the fn row carries the declaration.
async function setLambdaParamsViaInspector(page, fnName, names) {
  // The Inspector may sit on another tab (Runs, after a ▶) — the Overview
  // is where the rows live.
  await page.waitForSelector('#gd-insp-tab-overview', {timeout: 60000});
  await page.evaluate(() => document.getElementById('gd-insp-tab-overview').click());
  const row = '.gd-insp-row[data-action="lambda-params"].gd-insp-editable';
  await page.waitForSelector(row, {timeout: 60000});
  await page.evaluate((s) => document.querySelector(s).click(), row);
  await page.waitForSelector('.arg-value-edit-popover .lambda-params-edit', {timeout: 15000});
  await page.evaluate((ns) => {
    const pop = document.querySelector('.arg-value-edit-popover');
    const named = pop.querySelector('input[name="lp-mode"][value="named"]');
    named.checked = true;
    named.dispatchEvent(new Event('change', {bubbles: true}));
    for (const n of ns) {
      const cb = pop.querySelector('input[data-lambda-name="' + n + '"]');
      if (cb && !cb.checked) { cb.checked = true; cb.dispatchEvent(new Event('change', {bubbles: true})); }
    }
    Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
      .find((b) => b.textContent.trim() === 'Save').click();
  }, names);
  await page.waitForFunction(([n, ns]) => {
    const fn = Array.from(lookups.fnMap.values()).find((f) => f.name === n);
    return fn && JSON.stringify(fn['lambda-params']) === JSON.stringify(ns);
  }, [fnName, names], {timeout: 60000, polling: 200});
}


// The 📍 strip on `fnName`'s card → tick / untick Branch-local → Save
// (lesson 23). Resolves when the strip reads the new state.
async function setBranchLocalViaStrip(page, fnName, on) {
  const strip = '.node-overlay[data-fn-name="' + fnName + '"] .branch-local-strip';
  await page.waitForSelector(strip, {timeout: 60000});
  await page.evaluate((s) => document.querySelector(s).click(), strip);
  await page.waitForSelector('.arg-value-edit-popover input[data-branch-local="toggle"]', {timeout: 15000});
  await page.evaluate((want) => {
    const pop = document.querySelector('.arg-value-edit-popover');
    const cb = pop.querySelector('input[data-branch-local="toggle"]');
    if (cb.checked !== want) cb.click();
    Array.from(pop.querySelectorAll('.arg-value-edit-btn'))
      .find((b) => b.textContent.trim() === 'Save').click();
  }, on);
  await page.waitForFunction(([s, want]) => {
    const el = document.querySelector(s);
    return el && (el.dataset.state === 'own') === want;
  }, [strip, on], {timeout: 60000, polling: 200});
}


// ⋯ on the PARENT row (level 1) of `cardFnName`'s card → + (add another
// parent) → type `parentName` in the picker and click its row (lesson 03).
// Resolves when the card's fn carries two parents.
async function addMiParentViaParentRow(page, cardFnName, parentName) {
  const trigger = '.node-overlay[data-fn-name="' + cardFnName + '"] .ancestor-line[data-level="1"] button.more-actions-trigger';
  await page.waitForSelector(trigger, {timeout: 60000});
  await page.evaluate((s) => {
    document.querySelector(s).dispatchEvent(new MouseEvent('mousedown', {bubbles: true}));
  }, trigger);
  await page.waitForSelector('.row-actions-popover [data-action="add-mi-parent"]', {timeout: 15000});
  await page.evaluate(() => {
    document.querySelector('.row-actions-popover [data-action="add-mi-parent"]')
      .dispatchEvent(new MouseEvent('click', {bubbles: true}));
  });
  await page.waitForSelector('.fn-picker-popover .fn-picker-search', {timeout: 15000});
  await page.fill('.fn-picker-popover .fn-picker-search', parentName);
  const row = '.fn-picker-popover .fn-picker-row[data-fn-name$="' + parentName + '"]';
  await page.waitForSelector(row, {timeout: 30000});
  await page.evaluate((s) => document.querySelector(s).click(), row);
  await page.waitForFunction((name) => {
    const fn = (typeof graphData !== 'undefined' && graphData?.fns || []).find((f) => f.name === name);
    return fn && (fn['parent-ids'] || []).length === 2;
  }, cardFnName, {timeout: 90000, polling: 250});
}


// `+` on the named slot → the chooser reads "Bind <marker>" → click it →
// the marker's form (lesson 16: the secret form on sql-exec's :password).
// Returns the chooser's literal-side label so the caller can assert it.
async function openMarkerFormViaPlaceholder(page, argName, marker) {
  const sel = '.placeholder-binder[data-arg-name="' + argName + '"]';
  await page.waitForSelector(sel, {timeout: 60000});
  await page.evaluate((s) => document.querySelector(s).click(), sel);
  await page.waitForFunction((m) => Array.from(document.querySelectorAll('button'))
    .some((b) => b.textContent.trim() === 'Bind ' + m), marker, {timeout: 15000, polling: 100});
  const label = await page.evaluate((m) => Array.from(document.querySelectorAll('button'))
    .find((b) => b.textContent.trim() === 'Bind ' + m)?.textContent.trim(), marker);
  await page.evaluate((m) => Array.from(document.querySelectorAll('button'))
    .find((b) => b.textContent.trim() === 'Bind ' + m).click(), marker);
  return label;
}

module.exports = {
  NS_NAME, FN_NAME,
  retryingDelete, hardCleanup, tourTitle, tourWhere, waitTourTitle, settleTourRing, clickTourButton,
  waitUntil, tourProgress, clickTourAdvance,
  installSpotlightAudit,
  filterAndSelect, openRowActionsFor, extendViaRowActions, bindFirstPlaceholder,
  pickIncompatFnRef, pickAnyway, removeUseSiteBinding, waitClickable,
  createBranchViaChip, switchBranchViaChip, editBoundValue, runViaRowActions, runFromOpenPane,
  appendSeqItemViaEdge,
  bindSeqAnchorPlaceholder,
  createRootNamespace, createFnInNamespace, setParentViaStrip,
  runWithEffectAck, finishAndDelete, waitTourClosed, bindFnRefPlaceholder,
  bindNamedPlaceholder, bindPlaceholderOn, extendInPlace,
  bindOptionalArgChip, appendFnRefViaChip, renameArgViaEdgeLabel,
  createRecordType, openOperateSection, openAccountSettings, openAccountMenu,
  setSealsViaBadge, deleteBoundValue, moveSeqItem, insertSeqLiteralBefore,
  setLambdaParamsViaInspector, setBranchLocalViaStrip, addMiParentViaParentRow,
  openMarkerFormViaPlaceholder,
};
