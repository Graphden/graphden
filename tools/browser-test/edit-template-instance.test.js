// Template-instance palette e2e — the append chooser's "New from
// template…" path: pick a type-compatible template, name the
// instance, and it is created (template as parent) + ref-appended.
//
// Coverage:
//   • Seed an owner fn with parent :stack (a hiccup :children chain).
//   • The append chooser offers the "New from template…" button.
//   • /api/types/candidates with the chain's elem type (hiccup-node)
//     lists the component library (type-filtered palette).
//   • createTemplateInstanceAndAppend(owner, :button, name) creates
//     the instance with parent-ids=[button] and appends a ref item.
//   • Executing the owner renders the composed hiccup.
//
// Run from this directory:  node edit-template-instance.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const OWNER_FN = 'tpl-inst-owner' + RUN_ID;
const INST_FN = 'tpl-inst-btn' + RUN_ID;


async function cleanup(page) {
  // Item first (ref blocks fn delete), then instance, then owner.
  try {
    const ents = await getEntities(page, OWNER_FN);
    const owner = ents.fns.find((f) => f.name === OWNER_FN);
    if (owner) {
      const bindIds = ents.bindings
        .filter((b) => b['fn-id'] === owner.id).map((b) => b.id);
      for (const it of (ents['list-items'] || [])) {
        if (bindIds.includes(it['binding-id'])) {
          await api(page, 'DELETE', '/api/sequence/item/' + it.id);
        }
      }
    }
  } catch (_) {}
  try { await deleteFnByName(page, INST_FN); } catch (_) {}
  try { await deleteFnByName(page, OWNER_FN); } catch (_) {}
}


(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('edit-template-instance — palette pick → named instance → ref item');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: owner with parent :stack (children chain typed hiccup-node).
    // ===================================================================
    const ents = await getEntities(page, 'stack');
    const stackFn = ents.fns.find((f) => f.name === 'stack');
    assert(stackFn, ':stack template resolved');
    await api(page, 'POST', '/api/entities/fn',
              'name=' + OWNER_FN + '&parent-ids=' + stackFn.id);
    const ownerEnts = await getEntities(page, OWNER_FN);
    const owner = ownerEnts.fns.find((f) => f.name === OWNER_FN);
    assert(owner, 'owner fn created');

    // ===================================================================
    // The type-filtered palette lists the component library.
    // ===================================================================
    const candidates = await page.evaluate(async () => {
      const r = await authFetch(API.api_types_candidates, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({expected: 'hiccup-node'})
      });
      const d = await r.json();
      return (d.candidates || []).map((c) => c.name);
    });
    for (const comp of ['button', 'card', 'heading', 'paragraph']) {
      assert(candidates.includes(comp),
             'palette (hiccup-node candidates) offers :' + comp);
    }

    // ===================================================================
    // The chooser surfaces the palette entry point.
    // ===================================================================
    const chooserLabels = await page.evaluate(async (ownerName) => {
      const ownerFn = await resolveFnByName(ownerName);
      appendSequenceItem(ownerFn.id, document.body, undefined, {});
      await new Promise((res) => setTimeout(res, 300));
      const labels = [...document.querySelectorAll(
        '.free-arg-bind-chooser button')].map((b) => b.textContent);
      if (typeof closeInlineEdit === 'function') closeInlineEdit();
      return labels;
    }, OWNER_FN);
    assert(chooserLabels.some((l) => l.includes('New from template')),
           'append chooser offers "New from template…": '
           + JSON.stringify(chooserLabels));

    // ===================================================================
    // Create-and-append (the palette pick's programmatic core).
    // ===================================================================
    const created = await page.evaluate(async ({ownerName, instName}) => {
      const ownerFn = await resolveFnByName(ownerName);
      const button = await resolveFnByName('button');
      return await createTemplateInstanceAndAppend(
        ownerFn.id, {id: button.id, name: 'button'}, instName, null);
    }, {ownerName: OWNER_FN, instName: INST_FN});
    assert(created && created.ok === true,
           'createTemplateInstanceAndAppend succeeded: '
           + JSON.stringify(created));

    const after = await getEntities(page, OWNER_FN);
    const inst = (await getEntities(page, INST_FN)).fns
      .find((f) => f.name === INST_FN);
    assert(inst, 'instance fn exists');
    const buttonFn = (await getEntities(page, 'button')).fns
      .find((f) => f.name === 'button');
    assert((inst['parent-ids'] || []).includes(buttonFn.id),
           'instance is parented to :button');
    const owner2 = after.fns.find((f) => f.name === OWNER_FN);
    const bindIds = after.bindings
      .filter((b) => b['fn-id'] === owner2.id).map((b) => b.id);
    const refItem = (after['list-items'] || []).find(
      (it) => bindIds.includes(it['binding-id'])
              && it['ref-fn-id'] === inst.id);
    assert(refItem, 'ref item appended onto the owner chain');

    // ===================================================================
    // The composed page executes.
    // ===================================================================
    const exec = await page.evaluate(async (ownerName) => {
      const r = await authFetch(API.api_execute, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({'fn-name': ownerName,
                              args: {label: 'Hi'}})
      });
      return await r.json();
    }, OWNER_FN);
    assert(exec.status === 'succeeded',
           'owner executes: ' + JSON.stringify(exec).slice(0, 120));
    assert(JSON.stringify(exec.result).includes('"button","Hi"')
           || JSON.stringify(exec.result).includes('["button","Hi"]'),
           'rendered hiccup contains the button instance: '
           + JSON.stringify(exec.result).slice(0, 120));

    await cleanup(page);
    console.log('PASS');
    await browser.close();
    process.exit(0);
  } catch (e) {
    console.error('FAIL:', e.message);
    try { await cleanup(page); } catch (_) {}
    await browser.close();
    process.exit(1);
  }
})();
