// Re-records the landing's product imagery against a LIVE demo of the
// current editor (default https://app.graphden.dev/?demo=1 — override
// with GRAPHDEN_DEMO_URL for a worktree stack). Output:
//
//   editor-demo.gif           hero loop: 4 keyframes, 1120x700 (select →
//                             step into the handler → Bindings → Versions)
//   landing-diff-review.png   Review dialog "What changed" list @2x
//   landing-diff-canvas.png   compare mode on the canvas (chip, lens
//                             bar, ringed card) @2x
//
// The files live in graphden-cloud's `resources/landing/` — the landing
// package serves them from its own origin through fixed routes
// (`:_landing-asset-*` in resources/packages/landing/landing/fns.edn),
// versioned by the backend build hash. Point LANDING_ASSETS_DIR at that
// directory (default: the sibling checkout ../../../graphden-cloud/
// resources/landing), commit there, and the next release ships them.
// The demo sandbox is disposable, so the fixture fns (notify /
// render-message / …) are created fresh and never cleaned up.
//
//   cd tools/browser-test && node landing-shots.js
const { chromium } = require('playwright');
const path = require('path');
const { execFileSync } = require('child_process');

const DEMO = process.env.GRAPHDEN_DEMO_URL || 'https://app.graphden.dev/?demo=1';
const OUT = process.env.LANDING_ASSETS_DIR
  || path.resolve(__dirname, '..', '..', '..', 'graphden-cloud', 'resources', 'landing');
if (!require('fs').existsSync(OUT)) {
  console.error('LANDING_ASSETS_DIR does not exist: ' + OUT);
  process.exit(2);
}
const BR = 'feature/urgency';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function form(page, method, urlPath, fields, branch) {
  return page.evaluate(async ({ method, urlPath, fields, branch }) => {
    const body = new URLSearchParams();
    for (const [k, v] of Object.entries(fields)) body.set(k, v);
    const headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    if (branch) headers['X-Graphden-Branch'] = branch;
    const r = await window.authFetch(urlPath, { method, headers, body: body.toString() });
    const text = await r.text();
    if (r.status !== 200 || text.includes('type-warnings')) {
      throw new Error(method + ' ' + urlPath + ' → ' + r.status + ' ' + text.slice(0, 300));
    }
  }, { method, urlPath, fields, branch });
}

async function entities(page, branch) {
  return page.evaluate(async (branch) => {
    const headers = branch ? { 'X-Graphden-Branch': branch } : {};
    return (await window.authFetch('/api/graph/entities', { headers })).json();
  }, branch);
}

async function openDemo(browser, viewport, dpr) {
  const ctx = await browser.newContext({ viewport, deviceScaleFactor: dpr });
  // A worktree stack (GRAPHDEN_DEMO_URL=http://localhost:<port>/) has no
  // demo session — sign in the way the e2e helpers do.
  if (!/graphden\.dev/.test(DEMO)) {
    const auth = process.env.AUTH_TOKEN || 'test123';
    await ctx.addInitScript((a) => {
      try { localStorage.setItem('graphden.auth.password', a); } catch (_) {}
    }, auth);
  }
  const page = await ctx.newPage();
  await page.goto(DEMO, { waitUntil: 'networkidle', timeout: 90000 });
  await page.waitForSelector('#branch-chip-btn', { timeout: 60000 });
  await sleep(2000);
  return page;
}

// --- the diff fixture: notify (http-post) on main, retargeted on a branch
async function seedDiff(page) {
  let ents = await entities(page);
  const fn = (n) => ents.fns.find((f) => f.name === n);
  const slotsOf = (id) => ents['fn-slots'].filter((fs) => fs['fn-id'] === id)
    .map((fs) => ents.slots.find((s) => s.id === fs['slot-id']));
  const slot = (fnName, slotName) => slotsOf(fn(fnName).id).find((s) => s.name === slotName);
  const valueSlot = slot('const', 'value');
  const urlSlot = slot('http-request', 'url');
  const bodySlot = slot('http-request', 'body');
  // Idempotent: a worktree stack keeps its DB between runs, so a fixture
  // that already exists is reused rather than 409'd.
  const mk = async (name, parent, branch) => {
    ents = await entities(page, branch);
    if (fn(name)) return fn(name).id;
    await form(page, 'POST', '/api/entities/fn', { name, 'parent-ids': fn(parent).id }, branch);
    ents = await entities(page, branch);
    return fn(name).id;
  };
  const bind = async (fields, branch) => {
    ents = await entities(page, branch);
    const have = ents.bindings.find((b) => b['fn-id'] === fields['fn-id'] && b['slot-id'] === fields['slot-id']);
    if (have) return;
    await form(page, 'POST', '/api/entities/binding', fields, branch);
  };
  const render = await mk('render-message', 'const');
  await bind({ 'fn-id': render, 'slot-id': valueSlot.id, value: JSON.stringify('New comment on your issue') });
  const notify = await mk('notify', 'http-post');
  await bind({ 'fn-id': notify, 'slot-id': urlSlot.id, value: JSON.stringify('https://hooks.example.com/team') });
  await bind({ 'fn-id': notify, 'slot-id': bodySlot.id, 'ref-fn-id': render });
  // A dependant that the branch never touches: it inherits notify's
  // bindings, so compare mode marks it "changed inside" (∿ via notify).
  await mk('on-issue-comment', 'notify');
  await page.evaluate(async (name) => {
    const have = ((await (await window.authFetch('/api/branches')).json()).branches || [])
      .some((b) => b.name === name);
    if (have) return;
    const r = await window.authFetch('/api/branches', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name }) });
    if (!(await r.json()).ok) throw new Error('branch create failed');
  }, BR);
  const urgent = await mk('urgent-message', 'const', BR);
  await bind({ 'fn-id': urgent, 'slot-id': valueSlot.id, value: JSON.stringify('🔴 On-call: new comment on your issue') }, BR);
  ents = await entities(page);
  const urlB = ents.bindings.find((b) => b['fn-id'] === notify && b['slot-id'] === urlSlot.id);
  const bodyB = ents.bindings.find((b) => b['fn-id'] === notify && b['slot-id'] === bodySlot.id);
  await form(page, 'PUT', '/api/entities/binding/' + urlB.id,
    { value: JSON.stringify('https://hooks.example.com/oncall') }, BR);
  await form(page, 'PUT', '/api/entities/binding/' + bodyB.id, { 'ref-fn-id': urgent }, BR);
}

async function diffShots(browser) {
  const page = await openDemo(browser, { width: 1400, height: 900 }, 2);
  await seedDiff(page);
  await page.evaluate((br) => gdEnterDiffMode(br), BR);
  await page.waitForSelector('#gd-diff-chip', { timeout: 30000 });
  // The "only changed" lens: the tree shows just the three rows that matter
  // (notify ±, urgent-message −, on-issue-comment ∿) with their digests.
  await page.evaluate(() => gdDiffSetLens({ changedOnly: true }));
  await sleep(1500);
  await page.evaluate(() => gdDiffExpandChangedGroups());
  await sleep(2500);
  await page.evaluate(() => selectFnByName('notify'));
  await sleep(4000);
  // Canvas: the sidebar's Δ chip + lens bar + per-row digests, the ringed
  // card with its args — and (UX-v4) the compared branch's side of the
  // replaced :body ref, drawn as a ghost subtree ABOVE :render-message.
  // Wait for it, zoom out until everything drawn sits inside the canvas,
  // then clip to what is drawn.
  await page.waitForSelector('.gd-ghost-cluster', { timeout: 30000 });
  await sleep(1000);
  const drawnBox = () => page.evaluate(() => {
    const canvas = document.getElementById('graph-container').getBoundingClientRect();
    const els = document.querySelectorAll('.node-overlay, .gd-ghost-cluster, .edge-label-overlay');
    let top = Infinity; let bottom = -Infinity; let right = -Infinity;
    els.forEach((e) => {
      const r = e.getBoundingClientRect();
      if (!r.width || e.hidden) return;
      top = Math.min(top, r.top); bottom = Math.max(bottom, r.bottom); right = Math.max(right, r.right);
    });
    return { top, bottom, right, canvasTop: canvas.top, canvasBottom: canvas.bottom };
  });
  for (let i = 0; i < 5; i++) {
    const b = await drawnBox();
    if (b.top >= b.canvasTop + 8 && b.bottom <= b.canvasBottom - 8 && b.right <= 1000) break;
    await page.evaluate(() => {
      gv.setZoom(gv.zoom() * 0.85, { x: gv.width() / 2, y: gv.height() / 2 });
    });
    await sleep(600);
  }
  const box = await drawnBox();
  // From the Δ chip down to the lowest drawn thing.
  const y = 120;
  const h = Math.max(345, Math.min(900 - y, Math.ceil(box.bottom) + 16 - y));
  console.log('canvas clip', JSON.stringify({ y, h, box }));
  await page.screenshot({ path: path.join(OUT, 'landing-diff-canvas.png'),
    clip: { x: 0, y, width: 1012, height: h } });
  await page.evaluate((br) => showReviewDialog(br), BR);
  await page.waitForSelector('.branch-diff-modal .bd-review-changes', { timeout: 30000 });
  await page.addStyleTag({ content: '.branch-diff-modal{width:680px!important;max-width:680px!important}' });
  await sleep(1500);
  const list = await page.$('.bd-review-changes');
  await list.screenshot({ path: path.join(OUT, 'landing-diff-review.png') });
  console.log('diff:', await page.evaluate(() =>
    document.querySelector('.bd-review-changes').innerText.replace(/\n+/g, ' | ').slice(0, 300)));
  await page.context().close();
}

// --- the hero loop: select web-server, walk into its handler, inspect
// (Bindings, then Versions — the Runs pane needs a signed-in, non-demo
// session to mount its form)
async function heroGif(browser) {
  const page = await openDemo(browser, { width: 1120, height: 700 }, 1);
  const frames = [];
  const shot = async (name) => {
    const p = path.join(OUT, '.gif-frame-' + name + '.png');
    await page.screenshot({ path: p }); frames.push(p);
  };
  await page.fill('#search-input', 'web-server');
  await sleep(1500);
  await page.evaluate(() => selectFnByName('web-server'));
  await sleep(4000);
  await shot('0');
  await page.locator('text=app-error-bounded').first().click();
  await sleep(4000);
  await shot('1');
  await page.locator('[role=tab]:has-text("Bindings"), button:has-text("Bindings")').first().click();
  await sleep(2500);
  await shot('2');
  await page.locator('[role=tab]:has-text("Versions"), button:has-text("Versions")').first().click();
  await page.waitForFunction(() => !document.body.innerText.includes('Loading versions'), null, { timeout: 30000 });
  await sleep(1500);
  await shot('3');
  await page.context().close();
  execFileSync('python3', ['-c', `
import sys
from PIL import Image
frames=[Image.open(p).convert('RGB').quantize(colors=128, method=Image.Quantize.MEDIANCUT) for p in sys.argv[2:]]
frames[0].save(sys.argv[1], save_all=True, append_images=frames[1:], duration=[2400,2400,2200,2600], loop=0, optimize=True)
`, path.join(OUT, 'editor-demo.gif'), ...frames]);
  for (const f of frames) require('fs').unlinkSync(f);
}

(async () => {
  const browser = await chromium.launch({ args: ['--no-sandbox', '--no-zygote', '--in-process-gpu'] });
  await diffShots(browser);
  await heroGif(browser);
  await browser.close();
  console.log('wrote', OUT);
})().catch((e) => { console.error(e); process.exit(1); });
