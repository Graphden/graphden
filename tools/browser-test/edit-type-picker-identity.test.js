// Legal local private type twins must never break the compatible picker or
// change which native type row a saved option targets (root and @ included).
const {chromium} = require('playwright');
const {assert, newContext, api, getEntities, deleteFnByName, waitFor} =
  require('./edit-test-helpers');

const suffix = '-' + process.pid + '-' + Date.now().toString(36);
const typeName = '_picker-local' + suffix;
const probeName = 'picker-owner' + suffix;
const namespaceNames = ['picker-a', 'picker-b', 'picker@1-0-0'].map(n => n + suffix);
const namespaceIds = [];

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  try {
    for (const name of namespaceNames) {
      await api(page, 'POST', '/api/entities/ns', new URLSearchParams({name}).toString());
    }
    const index = await api(page, 'GET', '/api/graph/entities?scope=index');
    for (const name of namespaceNames) {
      const ns = index.namespaces.find(n => n.name === name);
      assert(ns, 'native namespace exists');
      namespaceIds.push(ns.id);
    }
    const intFn = index.fns.find(f => f.name === 'int' && !f['namespace-id']);
    const constFn = index.fns.find(f => f.name === 'const');
    assert(intFn && constFn, 'primitive int and const resolved');
    for (const nsId of [null, ...namespaceIds]) {
      const body = new URLSearchParams({name: typeName, 'base-fn-id': intFn.id,
                                        constraint: JSON.stringify(['>=', 0])});
      if (nsId) body.set('namespace-id', nsId);
      await api(page, 'POST', '/api/entities/fn', body.toString());
    }
    const found = await api(page, 'GET', '/api/graph/entities?scope=search&q=' + typeName);
    const twins = found.fns.filter(f => f.name === typeName);
    assert(twins.length === 4, 'four legal private type twins exist');
    const spellings = ['', ...namespaceNames].map(n => n + '/' + typeName);
    const expectedIds = [null, ...namespaceIds].map(nsId =>
      twins.find(f => (f['namespace-id'] || null) === nsId).id);
    const compatible = await api(page, 'GET', '/partials/compatible-type-options?expected=' +
      encodeURIComponent(JSON.stringify('int')) + '&primitives=true');
    assert(compatible.status === 200 && compatible.body.includes('value="int"'),
           'compatible int request succeeds with private twins');
    for (const spelling of spellings) {
      assert(compatible.body.includes('value="' + spelling + '"'),
             'compatible options retain exact qualified owner');
    }
    await api(page, 'POST', '/api/entities/fn',
              new URLSearchParams({name: probeName, 'parent-ids': constFn.id}).toString());
    const probe = (await getEntities(page, probeName)).fns.find(f => f.name === probeName);
    assert(probe, 'native probe exists');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002') + '/#' + probeName);
    await page.waitForFunction(() => graphReady() && !graph.animating);
    for (let i = 0; i < spellings.length; i++) {
      assert(await waitFor(() => page.evaluate(() => {
        if (document.querySelector('.arg-value-edit-popover select')) return true;
        document.querySelector('.arg-type-chip')?.click();
        return false;
      }), 20000), 'native type picker opens');
      const select = page.locator('.arg-value-edit-popover select.arg-value-edit-input');
      await page.waitForFunction(spelling => Array.from(document.querySelector(
        '.arg-value-edit-popover select')?.options || []).some(o => o.value === spelling),
        spellings[i], {timeout: 20000});
      await select.selectOption(spellings[i]);
      await page.locator('.arg-value-edit-popover .arg-value-edit-btn:not(' +
        '.arg-value-edit-btn-secondary):not(.arg-value-edit-btn-danger)').click();
      await page.waitForFunction(() => !document.querySelector('.arg-value-edit-popover'));
      assert(await waitFor(async () => {
        const graph = await getEntities(page, probe.id);
        return graph.bindings.some(b => b['fn-id'] === probe.id &&
          b['type-override-fn-id'] === expectedIds[i]);
      }, 15000), 'saved option targets the exact authorized type UUID');
      await page.waitForFunction(() => graphReady() && !graph.animating &&
        !!document.querySelector('.arg-type-chip'));
    }
    console.log('PASS: canonical type picker identities and native writes');
  } catch (error) {
    process.exitCode = 1;
    console.error('FAIL:', error.message);
  } finally {
    await page.close();
    await deleteFnByName(null, probeName);
    await deleteFnByName(null, typeName);
    for (const id of namespaceIds.reverse()) await api(null, 'DELETE', '/api/entities/ns/' + id);
    await browser.close();
  }
})();
