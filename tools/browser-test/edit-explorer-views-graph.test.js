// Explorer views SAVED IN THE GRAPH — the round trip a team relies on:
//
//   1. compose a filter set (uses + namespace + effect) from the chips;
//   2. "Save in the graph…" → an fn extending `explorer-view` with the
//      axes bound (through the entity API, like every editor form);
//   3. `GET /api/views` lists it with the axes decoded;
//   4. the view chip's popover shows it under "In the graph"; applying it
//      restores the same chips and the same member list;
//   5. `▶ Run` of the view fn (POST /api/execute) answers the member list;
//   6. a second view that `also`s the first (bound by hand) applies as a
//      "view <name>" chip and its members are the INTERSECTION — the
//      chip-side and the fn-side agree.
//
// Run:  GRAPHDEN_URL=http://localhost:<port> node edit-explorer-views-graph.test.js

'use strict';

const {chromium} = require('playwright');
const {assert, newContext, api, deleteFnByName, BASE} = require('./edit-test-helpers');

const VIEW_A = 'e2e-app-on-const';
const VIEW_B = 'e2e-app-on-const-handlers';

(async () => {
  const {browser, page} = await newContext(chromium);
  let failed = false;
  try {
    for (const n of [VIEW_B, VIEW_A]) await deleteFnByName(page, n).catch(() => {});
    await page.goto(BASE + '/');
    await page.waitForFunction(() => typeof graphData !== 'undefined' && graphData
      && document.querySelector('#entity-list [role="treeitem"]'), null, {timeout: 90000});
    await page.evaluate(() => gdClearFilters());

    // 1. the set: uses const · in app · fx network — a non-empty
    // intersection on the shipped packages.
    const constId = await page.evaluate(async () => {
      const r = await authFetch(API.api_graph_entities + '?scope=search&q=const');
      return (await r.json()).fns.find((f) => f.name === 'const').id;
    });
    await page.evaluate((id) => {
      gdAddUses({id, name: 'const'});
      gdToggleNamespace('app');
      gdToggleEffect('network');
    }, constId);
    await page.waitForFunction(() => Array.isArray(gdViewMembers()) && gdViewMembers().length > 0,
      null, {timeout: 30000, polling: 200});
    const before = await page.evaluate(() => gdViewMembers().map((f) => f.id).sort());
    assert(before.length > 0, 'the chip set has members (' + before.length + ')');

    // 2. Save in the graph…
    await page.evaluate((nm) => gdShareViewToGraph(nm), VIEW_A);
    await page.waitForFunction((nm) => document.querySelector('#gd-ws-chip b')?.textContent === nm,
      VIEW_A, {timeout: 60000, polling: 200});
    await page.waitForTimeout(1500);

    // 3. listed with the axes decoded
    const views = await api(page, 'GET', '/api/views');
    const a = (Array.isArray(views) ? views : []).find((v) => v.name === VIEW_A);
    assert(a, 'GET /api/views lists the saved view (got: ' + JSON.stringify(views).slice(0, 200) + ')');
    assert(a && a.filters.uses.join() === constId && a.filters.namespaces.join() === 'app'
      && a.filters.effects.join() === 'network',
      'the axes decoded back: ' + JSON.stringify(a && a.filters));

    // 4. apply from the chip's popover → same chips, same members
    await page.evaluate(() => gdClearFilters());
    await page.click('#gd-ws-chip');
    await page.waitForSelector('#gd-ws-pop .gd-views-apply[aria-label="Apply view ' + VIEW_A + ' from the graph"]', {timeout: 15000});
    await page.click('#gd-ws-pop .gd-views-apply[aria-label="Apply view ' + VIEW_A + ' from the graph"]');
    await page.waitForFunction(() => Array.isArray(gdViewMembers()) && gdViewMembers().length > 0,
      null, {timeout: 30000, polling: 200});
    const applied = await page.evaluate(() => ({
      chip: document.querySelector('#gd-ws-chip b')?.textContent,
      chips: [...document.querySelectorAll('#gd-filter-chips .kind-label')].map((e) => e.textContent).sort(),
      members: gdViewMembers().map((f) => f.id).sort(),
    }));
    assert(applied.chip === VIEW_A, 'the chip names the applied graph view');
    assert(applied.chips.join('|') === ['in app', 'uses core.logic.const', 'fx network'].sort().join('|'),
      'the same chips come back: ' + applied.chips.join('|'));
    assert(JSON.stringify(applied.members) === JSON.stringify(before),
      'the same members come back (' + applied.members.length + ')');

    // 5. ▶ Run of the view fn answers the member list
    const run = await api(page, 'POST', '/api/execute', {'fn-id': a.id, args: {}, 'persist?': false});
    const ran = (run.result?.fns || []).map((f) => f.id).sort();
    assert(run.status === 'succeeded' && JSON.stringify(ran) === JSON.stringify(before),
      '▶ Run of the view fn = the same member list (' + run.status + ', ' + ran.length + ')');

    // 6. a second view that `also`s the first + a name axis, bound by hand
    // through the entity API (what "Save in the graph…" does), then
    // applied from the popover: a "view" chip and the intersection.
    const evId = await page.evaluate(async () => {
      const r = await authFetch(API.api_graph_entities + '?scope=search&q=explorer-view');
      return (await r.json()).fns.find((f) => f.name === 'explorer-view').id;
    });
    const slots = await page.evaluate(async (id) => {
      const r = await authFetch(API.api_graph_entities + '?scope=subtree&root-id=' + id);
      const d = await r.json();
      return Object.fromEntries(d.slots.map((s) => [s.name, s.id]));
    }, evId);
    await api(page, 'POST', '/api/entities/fn', 'name=' + VIEW_B + '&parent-ids=' + evId);
    await page.waitForTimeout(800);
    const bId = (await api(page, 'GET', '/api/graph/entities?scope=search&q=' + VIEW_B)).fns
      .find((f) => f.name === VIEW_B)?.id;
    assert(bId, 'the second view fn was created');
    await api(page, 'POST', '/api/entities/binding', 'fn-id=' + bId + '&slot-id=' + slots.also + '&ref-fn-id=' + a.id);
    await api(page, 'POST', '/api/entities/binding', 'fn-id=' + bId + '&slot-id=' + slots.name + '&value=' + encodeURIComponent('"handler"'));
    await page.waitForTimeout(800);
    const expected = await api(page, 'POST', '/api/views/members', {name: 'handler', views: [a.id]});
    const expectedIds = (expected.fns || []).map((f) => f.id).sort();
    assert(expectedIds.length > 0 && expectedIds.length < before.length,
      'the intersection is a strict, non-empty subset (' + expectedIds.length + ' of ' + before.length + ')');
    // The graph changed under the editor — a reload of the graph (what
    // every editor write ends in) must drop the graph-views cache: no
    // manual invalidation here.
    await page.evaluate(async () => { gdClearFilters(); await initGraph(); });
    await page.click('#gd-ws-chip');
    await page.waitForSelector('#gd-ws-pop .gd-views-apply[aria-label="Apply view ' + VIEW_B + ' from the graph"]', {timeout: 15000});
    await page.click('#gd-ws-pop .gd-views-apply[aria-label="Apply view ' + VIEW_B + ' from the graph"]');
    await page.waitForFunction(() => Array.isArray(gdViewMembers()) && gdViewMembers().length > 0,
      null, {timeout: 30000, polling: 200});
    const composed = await page.evaluate(() => ({
      chips: [...document.querySelectorAll('#gd-filter-chips .kind-label')].map((e) => e.textContent).sort(),
      members: gdViewMembers().map((f) => f.id).sort(),
    }));
    assert(composed.chips.join('|') === ['name handler', 'view ' + VIEW_A].sort().join('|'),
      'the composed view applies as a name chip + a "view" chip: ' + composed.chips.join('|'));
    assert(JSON.stringify(composed.members) === JSON.stringify(expectedIds),
      'its members are the intersection (' + composed.members.length + ')');
    const runB = await api(page, 'POST', '/api/execute', {'fn-id': bId, args: {}, 'persist?': false});
    const ranB = (runB.result?.fns || []).map((f) => f.id).sort();
    assert(JSON.stringify(ranB) === JSON.stringify(expectedIds),
      '▶ Run of the composed view fn agrees (' + runB.status + ', ' + ranB.length + ')');

    // A graph write while a server-side filter is on re-evaluates the
    // members (quietly — the list never blanks): add a fn that uses const
    // in app, reload the graph, the count grows by one.
    await page.evaluate(() => gdClearFilters());
    // (uses const · in app alone is over the 500 cap — the effect keeps it
    // small enough for a +1 to be visible.)
    await page.evaluate((id) => { gdAddUses({id, name: 'const'}); gdToggleNamespace('app'); gdToggleEffect('network'); }, constId);
    await page.waitForFunction(() => Array.isArray(gdViewMembers()) && gdViewMembers().length > 0,
      null, {timeout: 30000, polling: 200});
    const n0 = await page.evaluate(() => gdViewMembers().length);
    const appNsId = await page.evaluate(() => graphData.namespaces.find((n) => n.name === 'app' && !n['parent-id'])?.id);
    // A child of a network-effect fn that itself uses const: extend one of
    // the current members, so the new fn inherits both the use and the effect.
    const memberId = await page.evaluate(() => gdViewMembers()[0].id);
    await api(page, 'POST', '/api/entities/fn', 'name=e2e-uses-const-probe&parent-ids=' + memberId + '&namespace-id=' + appNsId);
    await page.evaluate(async () => { await initGraph(); });
    await page.waitForFunction((n) => Array.isArray(gdViewMembers()) && gdViewMembers().length === n + 1,
      n0, {timeout: 30000, polling: 200});
    const blanked = await page.evaluate(() => !!document.querySelector('#entity-list .loading'));
    assert(!blanked, 'the member list re-evaluated after the write without blanking (' + n0 + ' → ' + (n0 + 1) + ')');
    await deleteFnByName(page, 'e2e-uses-const-probe').catch(() => {});

    // A saved view naming a fn that was deleted since: the chip stays (it
    // is what the reader saved) but is MARKED, and the set is empty with a
    // reason — not a silent nothing. Point a personal view's `uses` at the
    // probe fn's id (gone now), apply it.
    const probeIdGone = await page.evaluate(async () => {
      const r = await authFetch(API.api_graph_entities + '?scope=search&q=e2e-uses-const-probe');
      return (await r.json()).fns.some((f) => f.name === 'e2e-uses-const-probe');
    });
    assert(!probeIdGone, 'the probe fn is gone');
    await page.evaluate(() => gdClearFilters());
    await page.evaluate((id) => gdApplyView({name: 'e2e-dangling', filters: {uses: [{id, name: 'e2e-uses-const-probe'}]}}), '00000000-0000-4000-8000-000000000001');
    await page.waitForFunction(() => Array.isArray(gdViewMembers()), null, {timeout: 30000, polling: 200});
    const dangling = await page.evaluate(() => {
      const chip = document.querySelector('#gd-filter-chips .gd-filter-chip');
      return { cls: chip?.className, label: chip?.getAttribute('aria-label'), members: gdViewMembers().length,
               missing: gdViewMissing() };
    });
    assert(dangling.members === 0, 'a dangling uses chip yields an empty set');
    assert(/gd-filter-chip-missing/.test(dangling.cls || '') && /no longer exists/.test(dangling.label || ''),
      'the chip is marked and says why: ' + dangling.label);
    assert(dangling.missing.uses.length === 1, 'the server named the missing id');

    // The cap, said in the tree: "uses const" alone is over 500 on this graph.
    await page.evaluate(() => gdClearFilters());
    await page.evaluate((id) => gdAddUses({id, name: 'const'}), constId);
    await page.waitForFunction(() => Array.isArray(gdViewMembers()) && gdViewMembers().length > 0,
      null, {timeout: 30000, polling: 200});
    const capped = await page.evaluate(() => ({
      total: gdViewTotal(), shown: gdViewMembers().length,
      note: [...document.querySelectorAll('#entity-list .loading')].map((e) => e.textContent).find((t) => /matching fns/.test(t)) || '',
    }));
    assert(capped.shown === 500 && capped.total > 500, 'the server capped at 500 of ' + capped.total);
    assert(new RegExp('Showing 500 of ' + capped.total).test(capped.note), 'the tree says how many were cut: ' + capped.note);

    await page.evaluate(() => gdClearFilters());
    console.log('explorer-views-graph — PASS');
  } catch (err) {
    failed = true;
    console.log('FAIL: ' + (err && err.message || err));
    await page.screenshot({path: '/tmp/edit-explorer-views-graph-fail.png'}).catch(() => {});
  } finally {
    await page.close().catch(() => {});
    for (const n of ['e2e-uses-const-probe', VIEW_B, VIEW_A]) await deleteFnByName(page, n).catch(() => {});
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
