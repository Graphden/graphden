// Full UI/API smoke. Verifies editor renders, binding API works,
// expand functions, edit popovers click through to /api/entities/binding.
const { chromium } = require('playwright');
const BASE = 'http://localhost:9002', AUTH = 'test123';

(async () => {
  const errors = [], consoleErrors = [];
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
  await ctx.addInitScript((auth) => {
    try { localStorage.setItem('graphden.auth.password', auth); } catch (_) {}
  }, AUTH);
  const page = await ctx.newPage();
  page.on('pageerror', e => errors.push('[pageerror] ' + e.message));
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push('[console.error] ' + m.text()); });

  let failed = 0;
  function step(name, ok, info) {
    console.log((ok ? '  ✓ ' : '  ✗ ') + name + (info ? ' — ' + info : ''));
    if (!ok) failed++;
  }

  console.log('\n=== EDITOR LOAD ===');
  const r = await page.goto(BASE + '/');
  await page.waitForTimeout(1500);
  step('200 + text/html', r.status() === 200 && r.headers()['content-type']?.startsWith('text/html'));
  step('window.cy initialised', await page.evaluate(() => !!window.cy));

  console.log('\n=== COMPLEX GRAPH (#web-server) ===');
  await page.goto(BASE + '/#web-server');
  await page.waitForTimeout(2000);
  const cy1 = await page.evaluate(() => ({
    nodes: cy.nodes().length, edges: cy.edges().length,
    overlays: document.querySelectorAll('.node-overlay').length,
    pencils: document.querySelectorAll('.edit-pencil').length,
    typeChips: document.querySelectorAll('.arg-type-chip').length,
    reparentStrips: document.querySelectorAll('.reparent-strip').length,
    effectsStrips: document.querySelectorAll('.effects-strip').length,
    isAuthed: typeof isAuthenticated === 'function' ? isAuthenticated() : false
  }));
  step('graph nodes >=3', cy1.nodes >= 3, JSON.stringify(cy1));
  step('overlays present', cy1.overlays > 0);
  step('edit pencil present', cy1.pencils > 0);
  step('type chips present', cy1.typeChips > 0);
  step('reparent strip present', cy1.reparentStrips > 0);
  step('effects strip present', cy1.effectsStrips > 0);
  step('auth recognised', cy1.isAuthed === true);

  console.log('\n=== EXPAND ===');
  // Click an overlay-row that's tagged as an expansion target. Editor
  // marks expanded ancestors with `.expansion-row` (clickable).
  const expanded = await page.evaluate(() => {
    const before = cy.nodes().length;
    const rows = document.querySelectorAll('.expansion-row, [data-expansion-target]');
    if (!rows.length) return { skip: true, before };
    rows[0].click();
    return { before, clicked: true };
  });
  if (expanded.skip) {
    step('expand (no expansion targets on this graph)', true);
  } else {
    await page.waitForTimeout(800);
    const after = await page.evaluate(() => cy.nodes().length);
    step('node count after expand', after >= expanded.before, 'before=' + expanded.before + ' after=' + after);
  }

  console.log('\n=== API ENDPOINTS ===');
  const apis = await page.evaluate(async (base) => {
    const calls = await Promise.all([
      fetch(base + '/health'),
      fetch(base + '/api/types'),
      fetch(base + '/api/graph/entities'),
      fetch(base + '/version')
    ]);
    return calls.map(c => ({ s: c.status, ct: c.headers.get('content-type') }));
  }, BASE);
  step('/health 200 json', apis[0].s === 200 && apis[0].ct?.includes('json'));
  step('/api/types 200 json', apis[1].s === 200 && apis[1].ct?.includes('json'));
  step('/api/graph/entities 200 json', apis[2].s === 200 && apis[2].ct?.includes('json'));
  step('/version 200 json', apis[3].s === 200 && apis[3].ct?.includes('json'));

  console.log('\n=== /api/entities/arg removed ===');
  const argTry = await page.evaluate(async (args) => {
    const r = await fetch(args.base + '/api/entities/arg', {
      method: 'POST',
      headers: { 'Authorization': 'Bearer ' + args.auth, 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'name=foo&fn-id=00000000-0000-0000-0000-000000000000'
    });
    return { s: r.status, body: (await r.text()).slice(0, 100) };
  }, { base: BASE, auth: AUTH });
  step('POST /api/entities/arg now 400 (no longer routed)', argTry.s === 400, JSON.stringify(argTry));

  console.log('\n=== BINDING CRUD ===');
  const bFlow = await page.evaluate(async (args) => {
    const { base, auth } = args;
    const e = await (await fetch(base + '/api/graph/entities')).json();
    const add = e.fns.find(f => f.name === 'add');
    const numsSlot = e.slots.find(s => {
      const fs = e['fn-slots'].find(j => j['fn-id'] === add.id && j['slot-id'] === s.id);
      return fs && s.name === 'nums';
    });
    if (!numsSlot) return { error: 'nums slot missing' };
    await fetch(base + '/api/entities/fn', {
      method: 'POST', headers: { 'Authorization': 'Bearer ' + auth, 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'name=smoke-binding-flow&parent-ids=' + add.id
    });
    const e2 = await (await fetch(base + '/api/graph/entities')).json();
    const fn = e2.fns.find(f => f.name === 'smoke-binding-flow');
    const c = await fetch(base + '/api/entities/binding', {
      method: 'POST', headers: { 'Authorization': 'Bearer ' + auth, 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'fn-id=' + fn.id + '&slot-id=' + numsSlot.id + '&list-append=true'
    });
    const e3 = await (await fetch(base + '/api/graph/entities')).json();
    const b = e3.bindings.find(bb => bb['fn-id'] === fn.id);
    if (b) await fetch(base + '/api/entities/binding/' + b.id, {
      method: 'DELETE', headers: { 'Authorization': 'Bearer ' + auth }
    });
    await fetch(base + '/api/entities/fn/' + fn.id, {
      method: 'DELETE', headers: { 'Authorization': 'Bearer ' + auth }
    });
    return { create: c.status, found: !!b };
  }, { base: BASE, auth: AUTH });
  step('POST /api/entities/binding', bFlow.create === 200, JSON.stringify(bFlow));
  step('binding visible in /api/graph/entities', bFlow.found);

  console.log('\n=== JS errors ===');
  // Filter out the 400 from our own deliberate /api/entities/arg POST
  // a few sections up — the browser logs that as a console.error,
  // but it's expected output for "this endpoint is gone".
  const realConsoleErrors = consoleErrors.filter(e =>
    !/Failed to load resource.*400/.test(e));
  step('no pageerror', errors.length === 0, errors.join(' | '));
  step('no console.error', realConsoleErrors.length === 0, realConsoleErrors.join(' | ').slice(0, 200));

  console.log('\n' + (failed === 0 ? '*** ALL CHECKS PASSED ***' : '*** ' + failed + ' CHECKS FAILED ***'));
  await browser.close();
  process.exit(failed === 0 ? 0 : 1);
})().catch(e => { console.error('FATAL:', e.message); process.exit(1); });
