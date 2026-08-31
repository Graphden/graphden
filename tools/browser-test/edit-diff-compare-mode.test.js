// Diff v2 e2e — compare mode (UX-v3: Δ IS the mode), the inspector
// diff panel, the Review dialog, anchored comments + suggestions.
//
// Flow:
//   1. Seed: feat branch off main; a probe fn on feat (so the diff has
//      an "Only in feat" group); propose feat.
//   2. From main, enter COMPARE MODE vs feat (gdEnterDiffMode) —
//      the ◐ chip renders, the Explorer badges the changed ns.
//   3. Compare mode survives a reload (localStorage) and exits via ×.
//   4. Δ modal: post an ANCHORED comment on the probe's group row →
//      it renders as an inline thread + count badge; the general
//      thread does NOT contain it; reopening shows it again.
//   5. Suggestions: a proposed CHILD of feat shows in feat's diff
//      Review dialog's suggestions section with a lazy Δ preview
//      (<details class="bd-sugg-preview">) + the ⇢ apply button.
//
// Run from this directory:  node edit-diff-compare-mode.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, openBranchPopover} =
  require('./edit-test-helpers');

const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const FEAT = 'cmp-feat' + RUN_ID;
const SUGG = 'suggest-cmp' + RUN_ID;
// A branch NAME with "/" can't ride a /api/branches/:ref/* path segment
// — the id-ref plumbing must keep it fully operable.
const SLASHED = 'suggest/sl' + RUN_ID;
const PROBE_FN = 'cmp-probe' + RUN_ID;
// Gets a DESCRIPTION-only edit on feat — the "substantive only" lens
// must hide it while the structural probe stays.
const COSM_FN = 'cmp-cosm' + RUN_ID;
// Gains a ref-binding to :current-time-ms on feat — its EFFECT SET
// changes (pure → time), which the effects lens must single out.
const EFF_FN = 'cmp-eff' + RUN_ID;
// Bound to 1 on main, retuned to 2 on feat — its value ARG NODE exists
// on main, so the canvas ring assertions have something to ring (the
// eff probe's slot is unbound here → placeholder, no arg overlay).
const VAL_FN = 'cmp-val' + RUN_ID;

async function cleanup(page) {
  try {
    const ents = await page.evaluate(async (branch) => {
      const r = await window.authFetch('/api/graph/entities',
                                       {headers: {'X-Graphden-Branch': branch}});
      return r.ok ? r.json() : null;
    }, FEAT);
    for (const nm of [EFF_FN, VAL_FN, 'cmp-sugg-fn' + RUN_ID]) {
      const f = (ents?.fns || []).find((x) => x.name === nm);
      if (f) {
        await page.evaluate(async (id) => {
          await window.authFetch('/api/entities/fn/' + id, {method: 'DELETE'});
        }, f.id);
      }
    }
    const cosm = (ents?.fns || []).find((f) => f.name === COSM_FN);
    if (cosm) {
      await page.evaluate(async (id) => {
        await window.authFetch('/api/entities/fn/' + id, {method: 'DELETE'});
      }, cosm.id);
    }
    const probe = (ents?.fns || []).find((f) => f.name === PROBE_FN);
    if (probe) {
      await page.evaluate(async ({id, branch}) => {
        await window.authFetch('/api/entities/fn/' + id, {
          method: 'DELETE',
          headers: {'X-Graphden-Branch': branch},
        });
      }, {id: probe.id, branch: FEAT});
    }
  } catch (_) {}
  // Delete by ID where we can resolve one — the slashed name can't be
  // a path ref.
  let rows = [];
  try {
    rows = (await api(page, 'GET', '/api/branches'))?.branches || [];
  } catch (_) {}
  for (const b of [SLASHED, SUGG, FEAT]) {
    const ref = rows.find((r) => r.name === b)?.id || b;
    try {
      await api(page, 'DELETE', '/api/branches/' + encodeURIComponent(ref));
    } catch (_) {}
  }
  try { await page.evaluate(() => localStorage.removeItem('graphden.diffAgainst')); }
  catch (_) {}
}

(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('dialog', (d) => {
    console.log('  [dialog]', d.type() + ':', d.message().slice(0, 160));
    d.accept();
  });
  console.log('edit-diff-compare-mode — compare mode / anchored comments / suggestions');

  try {
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002') + '/');
    await page.waitForSelector('#branch-chip-btn', {timeout: 10000});
    await cleanup(page);

    // =================================================================
    // Seed: feat + probe fn on feat + propose feat + suggestion child.
    // =================================================================
    assert((await api(page, 'POST', '/api/branches', {name: FEAT}))?.ok,
           'feat branch created');
    const mainEnts = await api(page, 'GET', '/api/graph/entities');
    const identity = mainEnts.fns.find((f) => f.name === 'identity');
    assert(identity, ':identity baseline resolved');
    const created = await page.evaluate(async ({name, parentId, branch}) => {
      const body = new URLSearchParams();
      body.set('name', name);
      body.set('parent-ids', parentId);
      const r = await window.authFetch('/api/entities/fn', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'X-Graphden-Branch': branch,
        },
        body: body.toString(),
      });
      return {status: r.status, body: await r.text()};
    }, {name: PROBE_FN, parentId: identity.id, branch: FEAT});
    assert(created.body.includes('created successfully'),
           'probe fn created on feat');
    // Cosmetic probe: exists on main, description edited on feat only.
    const cosmCreated = await page.evaluate(async ({name, parentId}) => {
      const body = new URLSearchParams();
      body.set('name', name);
      body.set('parent-ids', parentId);
      const r = await window.authFetch('/api/entities/fn', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: body.toString(),
      });
      return {status: r.status, body: await r.text()};
    }, {name: COSM_FN, parentId: identity.id});
    assert(cosmCreated.body.includes('created successfully'),
           'cosmetic probe created on main');
    const cosmId = ((await api(page, 'GET', '/api/graph/entities'))?.fns || [])
      .find((f) => f.name === COSM_FN)?.id;
    assert(cosmId, 'cosmetic probe id resolved');
    const cosmEdit = await page.evaluate(async ({id, branch}) => {
      const body = new URLSearchParams();
      body.set('description', 'reworded on the branch');
      const r = await window.authFetch('/api/entities/fn/' + id, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'X-Graphden-Branch': branch,
        },
        body: body.toString(),
      });
      return r.status;
    }, {id: cosmId, branch: FEAT});
    assert(cosmEdit === 200, 'description-only edit landed on feat: ' + cosmEdit);
    // Effects probe: composed from :coalesce on main (pure); on feat
    // its :value slot gets a ref to :current-time-ms → effects grow.
    const allEnts = await api(page, 'GET', '/api/graph/entities');
    const coalesce = allEnts.fns.find((f) => f.name === 'coalesce');
    const timeFn = allEnts.fns.find((f) => f.name === 'current-time-ms');
    assert(coalesce && timeFn, ':coalesce + :current-time-ms resolved');
    const effCreated = await page.evaluate(async ({name, parentId}) => {
      const body = new URLSearchParams();
      body.set('name', name);
      body.set('parent-ids', parentId);
      const r = await window.authFetch('/api/entities/fn', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: body.toString(),
      });
      return await r.text();
    }, {name: EFF_FN, parentId: coalesce.id});
    assert(effCreated.includes('created successfully'), 'effects probe created');
    const effId = ((await api(page, 'GET', '/api/graph/entities'))?.fns || [])
      .find((f) => f.name === EFF_FN)?.id;
    const valueSlot = (allEnts['fn-slots'] || [])
      .filter((fs) => fs['fn-id'] === coalesce.id)
      .map((fs) => (allEnts.slots || []).find((sl) => sl.id === fs['slot-id']))
      .find((sl) => sl && sl.name === 'value');
    assert(effId && valueSlot, 'effects probe id + :value slot resolved');
    const bindStatus = await page.evaluate(async ({fnId, slotId, refId, branch}) => {
      const body = new URLSearchParams();
      body.set('fn-id', fnId);
      body.set('slot-id', slotId);
      body.set('ref-fn-id', refId);
      const r = await window.authFetch('/api/entities/binding', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'X-Graphden-Branch': branch,
        },
        body: body.toString(),
      });
      return r.status;
    }, {fnId: effId, slotId: valueSlot.id, refId: timeFn.id, branch: FEAT});
    assert(bindStatus === 200, 'time-ref binding landed on feat: ' + bindStatus);
    // Value probe: bound on main, retuned on feat.
    const valCreated = await page.evaluate(async ({name, parentId}) => {
      const body = new URLSearchParams();
      body.set('name', name);
      body.set('parent-ids', parentId);
      const r = await window.authFetch('/api/entities/fn', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: body.toString(),
      });
      return await r.text();
    }, {name: VAL_FN, parentId: coalesce.id});
    assert(valCreated.includes('created successfully'), 'value probe created');
    const valId = ((await api(page, 'GET', '/api/graph/entities'))?.fns || [])
      .find((f) => f.name === VAL_FN)?.id;
    const valBind = await page.evaluate(async ({fnId, slotId}) => {
      const body = new URLSearchParams();
      body.set('fn-id', fnId);
      body.set('slot-id', slotId);
      body.set('value', '1');
      const r = await window.authFetch('/api/entities/binding', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: body.toString(),
      });
      const d = await r.text();
      return {status: r.status, body: d.slice(0, 120)};
    }, {fnId: valId, slotId: valueSlot.id});
    assert(valBind.status === 200, 'value probe bound on main: ' + JSON.stringify(valBind));
    const valBindingId = ((await api(page, 'GET', '/api/graph/entities'))?.bindings || [])
      .find((b) => b['fn-id'] === valId)?.id;
    assert(valBindingId, 'value probe binding id resolved');
    const valEdit = await page.evaluate(async ({id, branch}) => {
      const body = new URLSearchParams();
      body.set('value', '2');
      const r = await window.authFetch('/api/entities/binding/' + id, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'X-Graphden-Branch': branch,
        },
        body: body.toString(),
      });
      return r.status;
    }, {id: valBindingId, branch: FEAT});
    assert(valEdit === 200, 'value retuned on feat: ' + valEdit);
    assert((await api(page, 'POST',
                      '/api/branches/' + encodeURIComponent(FEAT) + '/propose',
                      {proposed: true}))?.ok, 'feat proposed');
    assert((await api(page, 'POST', '/api/branches',
                      {name: SUGG, 'base-branch-id': FEAT}))?.ok,
           'suggestion child branch created off feat');
    // The suggestion carries a REAL change so "⇢ apply" is observable.
    const SUGG_FN = 'cmp-sugg-fn' + RUN_ID;
    const suggCreated = await page.evaluate(async ({name, parentId, branch}) => {
      const body = new URLSearchParams();
      body.set('name', name);
      body.set('parent-ids', parentId);
      const r = await window.authFetch('/api/entities/fn', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'X-Graphden-Branch': branch,
        },
        body: body.toString(),
      });
      return await r.text();
    }, {name: SUGG_FN, parentId: identity.id, branch: SUGG});
    assert(suggCreated.includes('created successfully'), 'suggestion fn created');
    assert((await api(page, 'POST',
                      '/api/branches/' + encodeURIComponent(SUGG) + '/propose',
                      {proposed: true}))?.ok, 'suggestion proposed');

    // =================================================================
    // Phase A: compare mode from main vs feat — entered from the UI:
    // picking the second branch IS the inline ◐ on its row.
    // =================================================================
    let opened0 = await openBranchPopover(page);
    assert(opened0, 'branch popover opens for the Δ pick');
    await page.click('.branch-row[data-branch-name="' + FEAT + '"] .branch-row-diff');
    await page.waitForSelector('#gd-diff-chip', {timeout: 20000});
    const chipText = await page.evaluate(
      () => document.querySelector('.gd-diff-chip-label')?.textContent);
    assert(chipText === 'Δ vs ' + FEAT,
           '◐ chip renders the compared branch: ' + JSON.stringify(chipText));

    // The probe lives only on feat → a "−1" aggregate should land on a
    // namespace header (root fns land on the pseudo-root, which carries
    // no data-ns-path — so assert the MODE data instead + any ns badge
    // when present).
    const modeInfo = await page.evaluate(() => ({
      active: window.gdDiffModeActive(),
      branch: window.gdDiffModeBranch(),
    }));
    assert(modeInfo.active && modeInfo.branch === FEAT,
           'compare mode active vs feat: ' + JSON.stringify(modeInfo));

    // The picked row's Δ is lit; clicking it again clears the pick.
    await openBranchPopover(page);
    const litSel = '.branch-row[data-branch-name="' + FEAT + '"] .branch-row-diff.on';
    await page.waitForSelector(litSel, {timeout: 15000});
    await page.click(litSel);
    await page.waitForFunction(() => !document.getElementById('gd-diff-chip'),
                               {timeout: 15000});
    assert(true, 'clicking the lit Δ exits compare mode');
    await page.evaluate((other) => window.gdEnterDiffMode(other), FEAT);
    await page.waitForSelector('#gd-diff-chip', {timeout: 20000});

    // =================================================================
    // Phase A2: the TYPE LENS — "substantive only" hides the
    // description-only edit, keeps the structural probe.
    // =================================================================
    const cosmBefore = await page.evaluate(
      (id) => !!window.gdDiffVisibleGroup(id), cosmId);
    assert(cosmBefore, 'cosmetic (description-only) group visible by default');
    await page.evaluate(() => window.gdDiffSetLens({substantiveOnly: true}));
    const lensState = await page.evaluate((id) => ({
      cosm: !!window.gdDiffVisibleGroup(id),
      lens: window.gdDiffLens(),
    }), cosmId);
    assert(lensState.cosm === false,
           '"substantive only" hides the description-only group');
    assert(lensState.lens.substantiveOnly === true, 'lens state persisted');
    await page.evaluate(() => window.gdDiffSetLens({substantiveOnly: false}));

    // Effects: the async registry comparison marks the probe whose
    // effect set grew; the effects lens then singles it out.
    await page.waitForFunction(
      (id) => window.gdDiffModeGroup(id)?.__effects, effId, {timeout: 30000});
    const effLabel = await page.evaluate(
      (id) => window.gdDiffModeGroup(id).__effects, effId);
    assert(/^effects: pure here · time there$/.test(effLabel),
           'full per-branch effect-set delta (registry is branch-scoped): '
           + effLabel);
    await page.evaluate(() => window.gdDiffSetLens({effectsOnly: true}));
    const effLens = await page.evaluate(({a, b}) => ({
      eff: !!window.gdDiffVisibleGroup(a),
      cosm: !!window.gdDiffVisibleGroup(b),
    }), {a: effId, b: cosmId});
    assert(effLens.eff && !effLens.cosm,
           'effects lens keeps the effect-change, drops the cosmetic one');
    await page.evaluate(() => window.gdDiffSetLens({effectsOnly: false}));

    // Root-level fns aggregate onto the pseudo "(primitives)" header —
    // the mode must show SOMETHING before the group is expanded.
    await page.waitForFunction(() => {
      const b = document.querySelector('.ns-header-pseudo .gd-diff-ns-badge');
      return b && /[+±−]/.test(b.textContent);
    }, {timeout: 20000});
    assert(true, 'pseudo-root header carries the aggregate badge while collapsed');

    // Ghost row: PROBE_FN exists only on feat → a dimmed placeholder
    // appears in the expanded root group of the Explorer.
    await page.evaluate(() => {
      const h = document.querySelector('.ns-header-pseudo');
      if (h && h.getAttribute('aria-expanded') !== 'true') h.click();
    });
    await page.waitForFunction((nm) => {
      return Array.from(document.querySelectorAll('.gd-diff-ghost .name'))
        .some((n) => n.textContent === nm);
    }, PROBE_FN, {timeout: 20000});
    assert(true, 'ghost row for the branch-only fn appears in the Explorer');

    // The diff lens bar rides under the kind chips; "Δ changed" hides
    // fns that did not change — and auto-expands the groups that hold
    // the survivors (collapse the root first to prove it).
    await page.waitForSelector('#gd-diff-lens', {timeout: 15000});
    await page.evaluate(() => {
      const h = document.querySelector('.ns-header-pseudo');
      if (h && h.getAttribute('aria-expanded') === 'true') h.click();
    });
    await page.evaluate(() => {
      document.querySelector('#gd-diff-lens [data-lens-key="changedOnly"]').click();
    });
    await page.waitForFunction(() => {
      return document.querySelector('.ns-header-pseudo')
        ?.getAttribute('aria-expanded') === 'true';
    }, {timeout: 15000});
    assert(true, '"Δ changed" auto-expands the changed groups');
    await page.fill('#search-input', '');
    await page.waitForFunction(() => {
      const identityRow = Array.from(document.querySelectorAll('#entity-list .entity-item'))
        .find((e) => e.querySelector('.name')?.textContent.trim() === 'identity');
      return !identityRow || identityRow.classList.contains('gd-diff-lens-hidden')
        || identityRow.offsetParent === null;
    }, {timeout: 20000});
    assert(true, '"Δ changed" lens hides unchanged fns from the tree');
    await page.evaluate(() => {
      document.querySelector('#gd-diff-lens [data-lens-key="changedOnly"]').click();
    });

    // Sidebar: the modified probe's row carries the ± badge (the
    // Explorer IS the diff in compare mode).
    await page.fill('#search-input', EFF_FN);
    await page.waitForFunction((nm) => {
      const row = Array.from(document.querySelectorAll('#entity-list .entity-item'))
        .find((e) => e.querySelector('.name')?.textContent.trim() === nm);
      return row && row.classList.contains('gd-diff-changed')
        && row.querySelector('.gd-diff-badge')?.textContent === '±';
    }, EFF_FN, {timeout: 20000});
    assert(true, 'Explorer row badges the modified fn (±)');

    // Canvas: opening a changed fn rings its CARD; a fn with a bound
    // arg that changed also rings that ARG node.
    await page.evaluate(async (nm) => { await selectFnByName(nm); }, EFF_FN);
    await page.waitForSelector('.fn-overlay-diff-modified', {timeout: 20000});
    assert(true, 'canvas rings the changed card');
    await page.evaluate(async (nm) => { await selectFnByName(nm); }, VAL_FN);
    await page.waitForSelector('.arg-overlay-diff-focus', {timeout: 20000});
    assert(true, 'canvas rings the changed bound arg');

    // Inspector: the selected changed fn carries the diff panel — the
    // old → new fields and a 💬 anchor right there.
    await page.waitForSelector('#gd-diff-insp', {timeout: 20000});
    const insp = await page.evaluate(() => {
      const p = document.getElementById('gd-diff-insp');
      return {
        vs: p.querySelector('.gd-diff-insp-head')?.textContent || '',
        oldV: p.querySelector('.bd-old')?.textContent,
        newV: p.querySelector('.bd-new')?.textContent,
        hasCommentBtn: !!p.querySelector('.branch-diff-comment-btn'),
      };
    });
    assert(/vs /.test(insp.vs) && insp.oldV === '1' && insp.newV === '2'
           && insp.hasCommentBtn,
           'inspector diff panel shows old → new + 💬: ' + JSON.stringify(insp));
    // Anchor a comment FROM the inspector.
    await page.evaluate(() => {
      document.querySelector('#gd-diff-insp .branch-diff-comment-btn').click();
    });
    await page.waitForSelector('#gd-diff-insp .branch-diff-anchor-compose textarea',
                               {timeout: 10000});
    await page.fill('#gd-diff-insp .branch-diff-anchor-compose textarea',
                    'inspector says hi');
    await page.click('#gd-diff-insp .branch-comment-send');
    await page.waitForFunction(() => {
      const t = document.querySelector('#gd-diff-insp .branch-diff-anchor-thread');
      return t && /inspector says hi/.test(t.textContent);
    }, {timeout: 20000});
    assert(true, 'anchored comment posts from the inspector panel');
    await page.fill('#search-input', '');

    // The chip's menu (review cockpit) carries diff / propose / merge
    // actions + the lens toggles.
    await page.evaluate(() => document.querySelector('.gd-diff-chip-label').click());
    await page.waitForSelector('#gd-diff-chip-pop', {timeout: 10000});
    const menu = await page.evaluate(() => ({
      items: Array.from(document.querySelectorAll('#gd-diff-chip-pop .gd-pop-item'))
        .map((b) => b.textContent),
      lensBoxes: document.querySelectorAll('#gd-diff-chip-pop input[type="checkbox"]').length,
    }));
    assert(menu.items.some((t) => /Review & comments/.test(t))
           && menu.items.some((t) => /Merge/.test(t))
           && menu.items.some((t) => /Exit compare/.test(t)),
           'chip menu carries review / merge / exit: ' + JSON.stringify(menu.items));
    assert(!menu.items.some((t) => /Propose|Withdraw/.test(t)),
           'propose hidden on the root branch (main has no base to aim at)');
    assert(menu.lensBoxes === 5, 'chip menu carries the 5 lens toggles');
    await page.evaluate(() => document.getElementById('gd-diff-chip-scrim').click());

    // =================================================================
    // Phase B: mode survives reload; × exits.
    // =================================================================
    await page.reload();
    await page.waitForSelector('#branch-chip-btn', {timeout: 15000});
    await page.waitForSelector('#gd-diff-chip', {timeout: 40000});
    assert(true, 'compare mode restored after reload (chip present)');
    // JS click — the Explorer keeps re-rendering around the chip while
    // the freshly reloaded page settles, which can fail Playwright's
    // stability check even though the chip is visible and on top.
    await page.evaluate(() => document.querySelector('.gd-diff-chip-off').click());
    const chipGone = await page.evaluate(
      () => !document.getElementById('gd-diff-chip'));
    assert(chipGone, '× exits compare mode (chip removed)');
    const cleared = await page.evaluate(
      () => localStorage.getItem('graphden.diffAgainst') === null);
    assert(cleared, 'localStorage cleared on exit');
    const ringsGone = await page.evaluate(() => ({
      cards: document.querySelectorAll('.fn-overlay-diff').length,
      args: document.querySelectorAll('.arg-overlay-diff-focus').length,
    }));
    assert(ringsGone.cards === 0 && ringsGone.args === 0,
           'exit clears card AND arg rings: ' + JSON.stringify(ringsGone));

    // =================================================================
    // Phase C: anchored comment in the Δ modal.
    // =================================================================
    // Seed an ORPHAN first — anchored to an entity that is not in the
    // diff; it must fall back to the general thread with a context chip.
    const featId = ((await api(page, 'GET', '/api/branches'))?.branches || [])
      .find((b) => b.name === FEAT)?.id;
    assert((await api(page, 'POST',
                      '/api/branches/' + featId + '/comments',
                      {body: 'orphan note', 'entity-name': 'fn',
                       'entity-id': '00000000-0000-4000-8000-00000000dead'}))?.ok,
           'orphan-anchored comment posted');
    let opened = await openBranchPopover(page);
    assert(opened, 'branch popover opens');
    const rootReview = await page.evaluate(() => {
      const row = document.querySelector('.branch-row[data-branch-name="main"]');
      return !!row?.querySelector('.branch-row-review');
    });
    assert(!rootReview,
           'the root branch row hides Review & comments (no base to review into)');
    await page.click('.branch-row[data-branch-name="' + FEAT + '"] .branch-row-more');
    await page.click('.branch-row-more-menu.open .branch-row-review');
    await page.waitForSelector('.branch-diff-modal:not(.hidden)', {timeout: 20000});
    await page.waitForFunction(() => {
      const m = document.querySelector('.branch-diff-modal');
      return m && !m.querySelector('.branch-diff-loading')
        && m.querySelector('.branch-comments');
    }, {timeout: 45000});
    const reviewHead = await page.evaluate(() =>
      document.querySelector('.branch-diff-header')?.textContent || '');
    assert(/Review:/.test(reviewHead) && /→ main/.test(reviewHead),
           'review dialog frames the proposal against its BASE: ' + reviewHead);
    // The change list is client-rendered from diff-view; open it.
    await page.evaluate(() => {
      const d = document.querySelector('.bd-review-changes');
      if (d && !d.open) d.open = true;
    });
    await page.waitForSelector('.bd-review-changes .branch-diff-row', {timeout: 15000});

    // The probe's group row carries data-anchor attrs; open its composer.
    const rowSel = '.branch-diff-row[data-diff-fn-name="' + PROBE_FN + '"]';
    await page.waitForSelector(rowSel, {timeout: 15000});
    await page.evaluate((sel) => {
      document.querySelector(sel + ' .branch-diff-comment-btn').click();
    }, rowSel);
    await page.waitForSelector('.branch-diff-anchor-thread textarea', {timeout: 10000});
    await page.fill('.branch-diff-anchor-thread textarea', 'pin: rename this');
    await page.click('.branch-diff-anchor-thread .branch-comment-send');
    await page.waitForFunction(() => {
      const t = document.querySelector('.branch-diff-anchor-thread');
      return t && /pin: rename this/.test(t.textContent);
    }, {timeout: 20000});
    assert(true, 'anchored comment renders inline under its diff row');
    const badgeCount = await page.evaluate(() => {
      const b = document.querySelector(
        '.branch-diff-comment-btn.has-comments .bd-comment-count');
      return b?.textContent;
    });
    assert(badgeCount === '1', '💬 carries the thread count: '
           + JSON.stringify(badgeCount));
    const inGeneral = await page.evaluate(() => {
      const list = document.querySelector('.branch-comments-list');
      return list ? /pin: rename this/.test(list.textContent) : null;
    });
    assert(inGeneral === false,
           'anchored comment does NOT duplicate into the general thread');
    const orphan = await page.evaluate(() => {
      const list = document.querySelector('.branch-comments-list');
      return list && /\[on fn 00000000\]/.test(list.textContent)
        && /orphan note/.test(list.textContent);
    });
    assert(orphan, 'orphaned anchor falls back to the general thread with a context chip');

    // (UX-v3: no modal↔mode bridge needed — Δ IS the mode; the dialog
    // is reached from ⋯/cockpit and stays a conversation surface.)

    // The effects chip lands on the effect-probe's group row.
    await page.waitForFunction(() => {
      const c = document.querySelector('.bd-effects-chip');
      return c && /time/.test(c.textContent);
    }, {timeout: 30000});
    assert(true, 'modal chips the group whose effect set changed');

    // =================================================================
    // Phase D: suggestions section lists the proposed child + actions.
    // =================================================================
    await page.waitForFunction(() => {
      const m = document.querySelector('.branch-diff-suggestions');
      return m && m.querySelector('.branch-diff-suggestion-row, .branch-diff-suggestions-empty');
    }, {timeout: 20000});
    const sugg = await page.evaluate(() => {
      const row = document.querySelector('.branch-diff-suggestion-row');
      if (!row) return null;
      return {
        name: row.querySelector('.branch-diff-suggestion-name')?.textContent,
        buttons: Array.from(row.querySelectorAll('button')).map((b) => b.textContent),
      };
    });
    assert(sugg && sugg.name === SUGG,
           'suggestion row lists the proposed child branch: '
           + JSON.stringify(sugg));
    assert(sugg.buttons.includes('⇢ apply'),
           'suggestion row carries ⇢ apply');
    // UX-v3: the Δ view button became a lazy <details> preview that
    // client-renders diff-view(source, against=suggestion) on open.
    await page.evaluate(() => {
      const d = document.querySelector(
        '.branch-diff-suggestion-row + details.bd-sugg-preview, .bd-sugg-preview');
      if (d && !d.open) d.querySelector('summary').click();
    });
    await page.waitForFunction(() => {
      const d = document.querySelector('.bd-sugg-preview');
      return d?.open && (d.querySelector('.branch-diff-row')
                         || /no differences/i.test(d.textContent));
    }, {timeout: 20000});
    assert(true, 'suggestion Δ preview lazy-loads its diff on expand');
    const newBtn = await page.evaluate(() =>
      !!document.querySelector('.branch-diff-suggest-new'));
    assert(newBtn, '"+ Suggest a change" affordance present');

    // =================================================================
    // Phase E: a SLASH-named branch stays fully operable via id-refs.
    // =================================================================
    await page.evaluate(() => document.querySelector('.branch-diff-close').click());
    assert((await api(page, 'POST', '/api/branches',
                      {name: SLASHED, 'base-branch-id': FEAT}))?.ok,
           'slash-named branch created off feat');
    const slashedId = ((await api(page, 'GET', '/api/branches'))?.branches || [])
      .find((b) => b.name === SLASHED)?.id;
    assert(slashedId, 'slash-named branch id resolved');
    const propById = await api(page, 'POST',
                               '/api/branches/' + slashedId + '/propose',
                               {proposed: true});
    assert(propById?.ok, 'propose reaches a slash-named branch via its ID');

    // The popover's row ops must go by id too: withdraw the proposal
    // through the row's ⋯ → 📤 and watch the state flip.
    opened = await openBranchPopover(page);
    assert(opened, 'branch popover re-opens');
    const rowSelSl = '.branch-row[data-branch-name="' + SLASHED + '"]';
    await page.waitForSelector(rowSelSl, {timeout: 15000});
    const rowId = await page.evaluate(
      (sel) => document.querySelector(sel)?.getAttribute('data-branch-id'), rowSelSl);
    assert(rowId === slashedId, 'row carries data-branch-id: ' + rowId);
    await page.evaluate((sel) => {
      document.querySelector(sel + ' .branch-row-propose').click();
    }, rowSelSl);
    await page.waitForSelector(
      rowSelSl + ' .branch-row-propose:not(.on)',
      {timeout: 20000, state: 'attached'});
    assert(true, 'withdraw-proposal round-trips on a slash-named branch (id path)');

    // =================================================================
    // Phase F: ⇢ apply — the suggestion merges INTO the proposal.
    // =================================================================
    await page.evaluate(() => {
      const row = document.querySelector('.branch-diff-suggestion-row');
      Array.from(row.querySelectorAll('button'))
        .find((b) => /apply/.test(b.textContent)).click();
    });
    // The apply reloads on success; wait for the RELOAD specifically
    // (waitForLoadState alone passes instantly on the already-loaded page).
    await page.waitForFunction(() => !document.getElementById('gd-diff-chip')
      || true, {timeout: 1000}).catch(() => {});
    await page.waitForNavigation({waitUntil: 'load', timeout: 60000})
      .catch(() => console.log('  (no navigation after apply — merge likely errored)'));
    await page.waitForSelector('#branch-chip-btn', {timeout: 30000});
    const applied = await page.evaluate(async ({branch, nm}) => {
      const r = await window.authFetch('/api/graph/entities',
                                       {headers: {'X-Graphden-Branch': branch}});
      const d = await r.json();
      return (d.fns || []).some((f) => f.name === nm);
    }, {branch: FEAT, nm: SUGG_FN});
    assert(applied, 'applied suggestion is visible on the proposal branch');

    console.log('✓ compare mode / anchored comments / suggestions verified');
    await cleanup(page);
    await browser.close();
    process.exit(0);
  } catch (err) {
    console.error('✗ test failed:', err.message);
    try { await cleanup(page); } catch (_) {}
    await browser.close();
    process.exit(1);
  }
})();
