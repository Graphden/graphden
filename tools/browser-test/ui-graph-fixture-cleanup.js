const {api, assert} = require('./edit-test-helpers');

// Namespace identities outlive branch deletion. Remove only the exact leaf
// created by this fixture; the ordinary API still checks cross-branch liveness.
async function captureFixtureNamespaces(page) {
  const index = await api(page, 'GET', '/api/graph/entities?scope=index');
  return new Set((index.namespaces || []).map((row) => row.id));
}

async function cleanupGraphFixture(page, prepared, baseline) {
  const removed = await api(page, 'DELETE', '/api/branches/' + encodeURIComponent(prepared.branch));
  assert(removed.ok, 'temporary graph branch removed');
  const index = await api(page, 'GET', '/api/graph/entities?scope=index');
  const namespaces = new Map((index.namespaces || []).map((row) => [row.id, row]));
  const fullName = (row) => row['parent-id'] && namespaces.has(row['parent-id'])
    ? fullName(namespaces.get(row['parent-id'])) + '.' + row.name : row.name;
  const leaf = [...namespaces.values()].find((row) => fullName(row) === prepared.namespace);
  assert(!!leaf, 'temporary graph namespace located by full path');
  for (let row = leaf; row && !baseline.has(row.id); row = namespaces.get(row['parent-id'])) {
    const deleted = await api(page, 'DELETE', '/api/entities/ns/' + row.id);
    assert(deleted.ok || deleted.status === 200, 'temporary graph namespace removed: ' + fullName(row));
  }
}

module.exports = {captureFixtureNamespaces, cleanupGraphFixture};
