'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const dir = path.join(__dirname, '../../resources/packages/app/editor');
const source = ['editor-inheritance-api.js', 'editor-edit-reparent.js']
  .map(file => fs.readFileSync(path.join(dir, file), 'utf8')).join('\n');

function harness({ orphans = [], accepted = true, stale = false, allowed = true, applyResult = { ok: true } } = {}) {
  const calls = [];
  const confirmations = [];
  const alerts = [];
  const toasts = [];
  const ctx = vm.createContext({
    console, JSON, Set, Map,
    API: { api_inheritance_preview: '/api/inheritance/preview', api_inheritance_apply: '/api/inheritance/apply' },
    lookups: { bindingMap: new Map(), slotMap: new Map() },
    confirm: text => { confirmations.push(text); return accepted; },
    alert: text => alerts.push(text),
    gdToast: text => toasts.push(text),
    safeTypeWarningSummary: warning => warning.message,
    authFetch: async (url, opts) => {
      const command = JSON.parse(opts.body);
      calls.push({ url, command, method: opts.method });
      if (url.endsWith('/preview')) {
        return { ok: true, status: 200, json: async () => ({
          ok: true, allowed, reason: 'sealed composition',
          request: { ...command, normalized: true },
          'expected-state': 'snapshot-A', 'orphan-binding-ids': orphans,
        }) };
      }
      return { ok: !stale, status: stale ? 409 : 200,
        json: async () => stale ? { ok: false, reason: 'Preview is stale.' } : applyResult };
    },
  });
  vm.runInContext(source, ctx);
  return { ctx, calls, confirmations, alerts, toasts };
}

(async () => {
  for (const parents of [[], ['parent-A'], ['parent-A', 'parent-B']]) {
    const h = harness();
    assert.equal(await h.ctx.performReparentCascade('F', parents), true);
    assert.equal(h.calls.length, 2);
    assert.deepEqual(h.calls[0].command['parent-ids'], parents);
    assert.equal(h.calls[0].command['target-fn-id'], 'F');
    assert.equal(h.calls[1].command.normalized, true);
    assert.equal(h.calls[1].command['expected-state'], 'snapshot-A');
    assert.deepEqual(h.calls[1].command['accepted-orphan-binding-ids'], []);
    assert(h.calls.every(call => call.method === 'POST'));
  }

  const consent = harness({ orphans: ['binding-A', 'binding-B'] });
  assert.equal(await consent.ctx.performReparentCascade('F', ['parent-C']), true);
  assert.equal(consent.confirmations.length, 1);
  assert(consent.confirmations[0].includes('binding-A'));
  assert(consent.confirmations[0].includes('binding-B'));
  assert.deepEqual(consent.calls[1].command['accepted-orphan-binding-ids'], ['binding-A', 'binding-B']);

  const cancel = harness({ orphans: ['binding-A'], accepted: false });
  assert.equal(await cancel.ctx._runCascadeWithBusy({ id: 'F' }, [], 'Removing parent from'), false);
  assert.equal(cancel.calls.length, 1);
  assert.equal(cancel.alerts.length, 0);
  let staged = 0;
  const cancelledPreview = await cancel.ctx.inheritanceRequest('preview', { action: 'variation' });
  await cancel.ctx.applyInheritancePreview(cancelledPreview, () => { staged++; });
  assert.equal(staged, 0, 'cancelled consent never registers an uncreated variation');

  const acceptedPreview = await consent.ctx.inheritanceRequest('preview', { action: 'variation' });
  await consent.ctx.applyInheritancePreview(acceptedPreview, () => {
    assert(consent.calls.at(-1).url.endsWith('/preview'), 'creation identity is staged before APPLY');
    staged++;
  });
  assert.equal(staged, 1);

  const stale = harness({ orphans: ['binding-A'], stale: true });
  assert.equal(await stale.ctx._runCascadeWithBusy({ id: 'F' }, [], 'Removing parent from'), false);
  assert.equal(stale.calls.length, 2, 'stale apply must not fetch or accept a new preview');
  assert.equal(stale.confirmations.length, 1);
  assert(stale.alerts[0].includes('Preview is stale.'));
  assert(!stale.alerts[0].includes('partial'));

  const published = harness({ applyResult: {
    ok: true, committed: true, 'created-fn-id': 'created-UUID',
    'publication-warnings': [{ stage: 'notify', reason: 'Derived state needs refresh.' }],
  } });
  const committed = await published.ctx.runInheritanceCommand({ action: 'variation' });
  assert.equal(committed['created-fn-id'], 'created-UUID', 'warning preserves the UUID used for navigation');
  assert.equal(committed.committed, true);
  assert.equal(published.alerts.length, 0, 'committed publication failure must not report failed save');
  assert.deepEqual(published.toasts, ['Change saved. Refresh the editor to reload derived state.']);
  const typed = harness({ applyResult: { ok: true, committed: true,
    'type-warnings': [{ message: 'Check the return type.' }] } });
  assert.equal(await typed.ctx.performReparentCascade('F', ['P']), true);
  assert.deepEqual(typed.toasts, ['Change saved with type warnings. Check the return type.']);

  const rejected = harness({ allowed: false });
  assert.equal(await rejected.ctx.performReparentCascade('F', ['P']), false);
  assert.equal(rejected.calls.length, 1);
  assert.equal(vm.runInContext('_lastCascadeError', rejected.ctx), 'sealed composition');

  console.log('✓ atomic inheritance client: initial, MI, clear, exact consent, cancel, stale, rejection');
})().catch(error => { console.error(error); process.exitCode = 1; });
