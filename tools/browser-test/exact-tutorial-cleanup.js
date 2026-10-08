'use strict';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const check = (condition, category) => { if (!condition) throw new Error(category); };
const samePrincipal = (a, b) => a?.accountId && a?.orgId
  && a.accountId === b?.accountId && a.orgId === b?.orgId;

async function browserContext(page, base) {
  return page.evaluate(async origin => {
    if (location.origin !== origin || typeof _tourSessionPrincipal !== 'function') throw Error('Cleanup context unavailable');
    const principal = _tourSessionPrincipal();
    const name = getCurrentBranchName();
    const response = await authFetch(API.api_branches);
    if (!response.ok) throw Error('Cleanup branch unavailable');
    const payload = await response.json();
    const branch = (Array.isArray(payload) ? payload : payload.branches)?.find(row => row.name === name);
    if (!branch?.id) throw Error('Cleanup branch identity unavailable');
    return {origin, principal, branch: {id: branch.id, name: branch.name}};
  }, new URL(base).origin);
}

function browserTransport(page, base) {
  return {
    context: () => browserContext(page, base),
    async read(id, branch) {
      return page.evaluate(async ({id, branch}) => {
        const response = await authFetch(API.api_graph_entities + '?scope=subtree&root-id=' + encodeURIComponent(id),
          {headers: {'X-Graphden-Branch': branch.id}});
        if (!response.ok) throw Error('Owned cleanup lookup refused');
        return response.json();
      }, {id, branch});
    },
    async remove(id, branch) {
      return page.evaluate(async ({id, branch}) => {
        const response = await authFetch(API.api_entities_type_id('fn', id),
          {method: 'DELETE', headers: {'X-Graphden-Branch': branch.id}});
        return {status: response.status, ok: response.ok};
      }, {id, branch});
    },
  };
}

// HTTP lessons create fn rows only. A success header from this run is the
// authority; names, loaded graph rows and earlier tutorial branches are not.
function trackExactTutorialFunctions(page, {base, persist = () => {}, transport = browserTransport(page, base)} = {}) {
  const origin = new URL(base).origin;
  const receipts = [];
  const requests = new WeakMap();
  const tasks = [];
  let failures = [];
  const observe = promise => {
    // Observe failures immediately; cleanup must surface every retained error.
    tasks.push(promise.catch(error => { failures.push(error); }));
  };
  const save = () => persist({origin, receipts});
  const request = request => {
    if (request.method() !== 'POST') return;
    const url = new URL(request.url());
    if (url.origin !== origin) return;
    if (/^\/api\/(entities\/(ns|service)$|branches(?:\/|$))/.test(url.pathname)) {
      const error = new Error('Unexpected candidate entity creation');
      failures.push(error);
      receipts.push({receipt: 'unsupported', path: url.pathname});
      save();
      return;
    }
    if (url.pathname !== '/api/entities/fn') return;
    const form = new URLSearchParams(request.postData());
    const row = {type: 'fn', name: form.get('name'), 'namespace-id': form.get('namespace-id') || null,
      receipt: 'pending'};
    receipts.push(row);
    save();
    const pending = (async () => {
      check(row.name, 'Creation name unavailable');
      const context = await transport.context();
      const header = request.headers()['x-graphden-branch'];
      check(context.origin === origin && context.principal?.accountId && context.principal?.orgId,
        'Creation principal unavailable');
      check(header ? [context.branch.id, context.branch.name].includes(header) : context.branch.name === 'main',
        'Creation branch changed');
      Object.assign(row, {principal: context.principal, branch: context.branch});
      save();
      return row;
    })();
    requests.set(request, pending);
    observe(pending);
  };
  const response = response => {
    const pending = requests.get(response.request());
    if (!pending) return;
    observe((async () => {
      const row = await pending;
      check(response.ok(), 'Creation response refused');
      const id = response.headers()['x-graphden-created-id'];
      check(uuid.test(id || ''), 'Creation receipt unavailable');
      const current = await transport.context();
      check(current.origin === origin && samePrincipal(row.principal, current.principal)
        && row.branch.id === current.branch.id && row.branch.name === current.branch.name, 'Creation context changed');
      check(!receipts.some(other => other !== row && other.id === id), 'Duplicate creation receipt');
      Object.assign(row, {id, receipt: 'created'});
      save();
    })());
  };
  page.on('request', request);
  page.on('response', response);
  return async () => {
    await Promise.all(tasks);
    check(!failures.length, 'Owned creation tracking failed; receipts retained');
    check(receipts.every(row => row.receipt === 'created' || row.receipt === 'removed'),
      'Unconfirmed creation retained');
    // HTTP lessons create handlers before their callers. Reverse receipts
    // delete those dependents first; a refusal retries only after progress.
    let pending = receipts.filter(row => row.receipt === 'created').reverse();
    while (pending.length) {
      const failed = [];
      for (const row of pending) {
        const context = await transport.context();
        check(context.origin === origin && samePrincipal(row.principal, context.principal)
          && row.branch.id === context.branch.id && row.branch.name === context.branch.name, 'Cleanup context changed');
        const graph = await transport.read(row.id, row.branch);
        check(Array.isArray(graph.fns), 'Cleanup graph unavailable');
        const fn = graph.fns.find(fn => fn.id === row.id);
        if (fn) {
          check(fn.name === row.name && (fn['namespace-id'] || null) === row['namespace-id'],
            'Created function identity changed');
          const beforeDelete = await transport.context();
          check(beforeDelete.origin === origin && samePrincipal(row.principal, beforeDelete.principal)
            && row.branch.id === beforeDelete.branch.id && row.branch.name === beforeDelete.branch.name,
            'Cleanup context changed');
          const removed = await transport.remove(row.id, row.branch);
          if (!removed.ok) {
            check(removed.status === 409, 'Owned deletion refused');
            failed.push(row);
            continue;
          }
          const after = await transport.read(row.id, row.branch);
          check(Array.isArray(after.fns) && !after.fns.some(fn => fn.id === row.id), 'Owned deletion not confirmed');
        }
        row.receipt = 'removed';
        save();
      }
      check(failed.length < pending.length, 'Owned dependencies retained');
      pending = failed;
    }
    failures = [];
    save();
  };
}

module.exports = {trackExactTutorialFunctions};
