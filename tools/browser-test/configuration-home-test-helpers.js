'use strict';
const {assert, api, nodeApi} = require('./edit-test-helpers');

// Only a successful explicit prepare can establish creation ownership. Names
// and the selected configuration's copy receipts cannot prove home ownership.
async function trackHomeSetup(page) {
  const before = await api(page, 'GET', '/api/graph/entities?scope=index');
  const baseline = new Set(before.namespaces.map(row => row.id));
  const setups = [], pending = [];
  const record = (body, ok) => setups.push({
    ok: ok && body.ok === true && body.setup === true,
    id: body.home?.id, created: body['changed-namespaces'] || [],
  });
  const observe = response => {
    if (response.request().method() !== 'POST'
      || new URL(response.url()).pathname !== '/api/ui/configuration/prepare'
      || response.request().postDataJSON()?.home !== true) return;
    pending.push(response.json().then(body => record(body, response.ok()),
      () => setups.push({ok: false})));
  };
  page.on('response', observe);
  return {
    async prepare() {
      // api() uses Node fetch, so the page response listener cannot see it.
      const result = await api(page, 'POST', '/api/ui/configuration/prepare', {home: true});
      record(result, result.ok === true);
      return result;
    },
    async cleanup() {
      page.off('response', observe);
      await Promise.all(pending);
      const created = new Set();
      for (const setup of setups) {
        assert(setup.ok && Array.isArray(setup.created), 'explicit home setup returned its cleanup receipt');
        for (const id of setup.created) {
          assert(id === setup.id && !baseline.has(id), 'only an exact newly created home belongs to this fixture');
          created.add(id);
        }
      }
      for (const id of created) {
        const result = await nodeApi('DELETE', '/api/entities/ns/' + encodeURIComponent(id));
        await result.text();
        assert(result.ok, 'empty home created by this fixture is removed through authorized CRUD');
      }
      const after = new Set((await api(page, 'GET', '/api/graph/entities?scope=index')).namespaces.map(row => row.id));
      assert(after.size === baseline.size && [...baseline].every(id => after.has(id)),
        'namespace identities return to baseline, preserving every pre-existing home');
    },
  };
}

module.exports = {trackHomeSetup};
