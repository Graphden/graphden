// Inline secret binding e2e — the card's side of a `[:secret T]` slot
// (editor-edit-modes.js `enterSecretBindingEditMode`, layout `secretRef`,
// `PUT /api/secret-bindings/:binding-id`).
//
// Coverage:
//   • A child of :sql-exec gets an inline secret on :password through the
//     API (the same POST the Bind secret form makes).
//   • The card shows the binding as `🔒 <path>` — the path, never the value.
//   • Clicking it opens the SAME form in rotate mode: path read-only, a new
//     value; Save → PUT 200 with a new KV version; the binding still points
//     at the path.
//   • Delete in that form drops the binding — the `+` is back.
//
// Skips gracefully when the stand has no vault (POST answers a vault error):
// the form-only assertions are covered by the lesson 16 walk.
//
// Run from this directory:  node edit-secret-inline.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, nodeApi, BASE} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const FN = 'secret-inline-probe' + RUN_ID;
const PATH = 'tutorial/inline' + RUN_ID;


const SECRET_INITIAL = 'synthetic-inline-secret-initial-' + require('node:crypto').randomBytes(16).toString('hex');
const SECRET_ROTATED = 'synthetic-inline-secret-rotated-' + require('node:crypto').randomBytes(16).toString('hex');
assert(![FN, PATH].some(text => [SECRET_INITIAL, SECRET_ROTATED].some(value => text.includes(value))),
  'secret sentinels are disjoint from public fixture labels');
const owned = [];
const headers = {'X-Graphden-Branch': 'main'};
const saveOwned = () => require('node:fs').writeFileSync('/tmp/graphden-inline-owned' + RUN_ID + '.json',
  JSON.stringify(owned), {mode: 0o600});
async function cleanup(page) {
  for (const receipt of owned) {
    if (receipt.removed) continue;
    assert(receipt.confirmed, 'unconfirmed creation retained');
    const row = (await getEntities(page, receipt.id)).fns.find(fn => fn.id === receipt.id);
    if (row) {
      assert(row.name === receipt.name && row['namespace-id'] === receipt['namespace-id'], 'exact cleanup identity unchanged');
      assert((await nodeApi('DELETE', '/api/entities/fn/' + receipt.id, undefined, headers)).ok, 'owned delete succeeded');
    }
    assert(!(await getEntities(page, receipt.id)).fns.some(fn => fn.id === receipt.id), 'owned function absent');
    receipt.removed = true; saveOwned();
  }
}


(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  page.on('dialog', (d) => { d.accept(); });
  console.log('edit-secret-inline — 🔒 on the card, rotate in place, delete');

  try {
    await page.goto(BASE + '/?branch=main');
    await page.waitForSelector('#auth-lock-btn', {timeout: 10000});

    const sqlExec = (await getEntities(page, 'sql-exec')).fns.find((f) => f.name === 'sql-exec');
    assert(sqlExec, 'sql-exec found');
    const created = await nodeApi('POST', '/api/entities/fn', new URLSearchParams({name: FN, 'parent-ids': sqlExec.id}).toString(), headers);
    const id = created.headers.get('X-Graphden-Created-Id');
    assert(created.ok && /^[a-f0-9-]{36}$/.test(id || ''), 'canonical creation UUID');
    const receipt = {id, name: FN, confirmed: false};
    owned.push(receipt); saveOwned();
    const fn = (await getEntities(page, id)).fns.find((f) => f.id === id && f.name === FN);
    assert(fn, 'canonical created function identity');
    Object.assign(receipt, {'namespace-id': fn['namespace-id'], confirmed: true}); saveOwned();
    assert(fn, FN + ' created');
    const sub = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + fn.id);
    const pwSlot = (sub.slots || []).find((s) => s.name === 'password');
    assert(pwSlot, 'the :password slot is in the subtree');

    const bound = await api(page, 'POST', '/api/secret-bindings',
      {'fn-id': fn.id, 'slot-id': pwSlot.id, path: PATH, value: SECRET_INITIAL});
    if (!bound || bound.ok !== true) {
      console.log('  SKIP — no vault on this stand');
      await cleanup(page);
      await browser.close();
      process.exit(0);
    }
    const bindingId = bound.binding.id;
    console.log('  inline secret bound: ' + bindingId);

    // ---- the card: 🔒 + path, never the value ----
    await page.goto(BASE + '/?branch=main&t=' + Date.now().toString(36) + '#' + FN);
    await page.waitForSelector('.arg-value-text[data-secret-ref="true"]', {timeout: 60000});
    const card = await page.evaluate(() => {
      const el = document.querySelector('.arg-value-text[data-secret-ref="true"]');
      return {text: el.textContent, title: el.title, editable: el.classList.contains('arg-value-editable')};
    });
    assert(/^🔒 /.test(card.text) && card.text.includes(PATH.slice(0, 20)),
      'the value node reads 🔒 + the path (got: ' + card.text + ')');
    assert(![card.text, card.title].some(text => text.includes(SECRET_INITIAL)), 'the value itself is not on the card');
    assert(card.editable && /rotate/i.test(card.title), 'the node is clickable and says it rotates (got: ' + card.title + ')');

    assert(await page.evaluate(value => !document.body.textContent.includes(value)
      && !Array.from(document.querySelectorAll('[title]')).some(el => el.title.includes(value)), SECRET_INITIAL),
      'initial secret is absent from rendered text and titles');

    // ---- rotate ----
    await page.click('.arg-value-text[data-secret-ref="true"]');
    await page.waitForSelector('.arg-value-edit-popover .arg-value-edit-secret-form-rotate', {timeout: 15000});
    const form = await page.evaluate(() => {
      const pop = document.querySelector('.arg-value-edit-popover');
      const path = pop.querySelector('[data-secret-field="path"]');
      return {path: path.value, readOnly: path.readOnly,
              del: !!pop.querySelector('.arg-value-edit-btn-danger')};
    });
    assert(form.path === PATH && form.readOnly, 'the path is shown read-only: ' + JSON.stringify(form));
    assert(form.del, 'the rotate form carries Delete');
    const rotated = page.waitForResponse((r) => /\/api\/secret-bindings\//.test(r.url()) && r.request().method() === 'PUT', {timeout: 30000});
    await page.fill('.arg-value-edit-popover [data-secret-field="value"]', SECRET_ROTATED);
    await page.click('.arg-value-edit-popover .arg-value-edit-btn:not(.arg-value-edit-btn-secondary):not(.arg-value-edit-btn-danger)');
    const resp = await rotated;
    const body = await resp.json().catch(() => null);
    assert(resp.status() === 200 && body && body.ok === true && body.version >= 2,
      'PUT rotated to a new KV version (status: ' + resp.status() + ')');
    await page.waitForFunction(() => !document.querySelector('.arg-value-edit-popover'), null, {timeout: 15000});
    assert(await page.evaluate(values => values.every(value => !document.body.textContent.includes(value)
      && !Array.from(document.querySelectorAll('[title]')).some(el => el.title.includes(value))), [SECRET_INITIAL, SECRET_ROTATED]),
      'both secret versions are absent from rendered text and titles');
    const after = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + fn.id);
    const bnd = (after.bindings || []).find((b) => b.id === bindingId);
    assert(bnd && bnd.value === PATH && bnd['resolver-fn-id'], 'the binding still points at the path with its resolver');
    console.log('  rotate: PUT ok, version ' + body.version + ', binding unchanged');

    // ---- rejections over the API ----
    const plain = await api(page, 'PUT', '/api/secret-bindings/' + bindingId, {});
    assert(plain && plain.ok === false && /value/.test(plain.error || ''),
      'a rotate without a value is refused');

    // ---- delete from the form ----
    await page.click('.arg-value-text[data-secret-ref="true"]');
    await page.waitForSelector('.arg-value-edit-popover .arg-value-edit-btn-danger', {timeout: 15000});
    await page.click('.arg-value-edit-popover .arg-value-edit-btn-danger');
    await page.waitForFunction(() => !document.querySelector('.arg-value-text[data-secret-ref="true"]')
      && !!document.querySelector('.placeholder-binder[data-arg-name="password"]'), null, {timeout: 60000});
    const gone = await api(page, 'GET', '/api/graph/entities?scope=subtree&root-id=' + fn.id);
    assert(!(gone.bindings || []).some((b) => b.id === bindingId), 'the binding is deleted; the + is back');
    console.log('  delete: binding gone, slot free again');

    await cleanup(page);
    console.log('PASS');
    await browser.close();
    process.exit(0);
  } catch (e) {
    console.error('FAIL', e && e.stack || e);
    try { await cleanup(page); } catch (_) { console.error('exact owned cleanup failed; confirmation retained'); }
    await browser.close();
    process.exit(1);
  }
})();
