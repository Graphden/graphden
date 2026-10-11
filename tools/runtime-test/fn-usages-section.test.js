'use strict';
// Actual ordinary Inspector graph and host adapter; server/DOM insertion are
// controlled boundaries. Browser tests additionally prove stable DOM identity.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument, MiniElement} = require('./mini-dom');
const {installInspectorGraph} = require('./inspector-graph-fixture');
const source = path.join(__dirname, '../../resources/packages/app/editor/editor-fn-usages.js');
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
const row = (id, name, extra = {}) => ({kind: 'ref-of', 'fn-id': id, 'fn-name': name, ...extra});
function fixture() {
  const document = createDocument();
  const requests = [];
  const navigations = [];
  let owned = true;
  let activeRead = null;
  let blurs = 0;
  const context = vm.createContext({console, document, Map, Set, WeakMap, Promise, AbortController,
    API: {api_fns_usages: '/api/fns/usages'},
    fetch(url, options) { return new Promise(resolve => requests.push({url, options, resolve})); },
    gdNavigateToFn(...args) { navigations.push(args); },
  });
  context.window = context;
  installInspectorGraph(context, document);
  MiniElement.prototype.matches = function(selector) { return this.className.split(' ').includes(selector.slice(1)); };
  MiniElement.prototype.blur = function() { blurs++; if (document.activeElement === this) document.activeElement = null; };
  const body = document.createElement('div'); document.body.appendChild(body);
  const host = document.createElement('div'); host.dataset.gdUsagesHost = ''; body.appendChild(host);
  vm.runInContext(fs.readFileSync(source, 'utf8'), context);
  const owns = () => owned;
  const captureRead = (target, retry) => {
    activeRead?.controller.abort();
    const read = {target, retry, controller: new AbortController()}; activeRead = read;
    return {start: owned, signal: read.controller.signal,
      current: () => owned && target.isConnected && activeRead === read && !read.controller.signal.aborted,
      finish() {}};
  };
  context.gdMountFnUsages(host, 'selected', {owns, captureRead});
  async function reply(payload, request = requests.at(-1)) {
    request.resolve({ok: true, json: async () => ({ok: true, count: payload.usages.length, 'truncated?': false, ...payload})});
    await flush();
  }
  return {context, document, body, host, requests, navigations, reply,
    blurs: () => blurs, rows: () => host.querySelectorAll('.gd-insp-usage-row'),
    refresh() { activeRead.retry(); },
    stop() { owned = false; context.gdDisposeFnUsages(body); }};
}
(async () => {
  {
    const f = fixture();
    await f.reply({usages: [row('a', 'add-10', {kind: 'parent-of'}), row('b', 'caller', {'slot-name': 'handler'}), row('c', 'other', {'slot-name': 'body'})]});
    assert.deepEqual(f.host.querySelectorAll('.gd-insp-usage-glabel').map(el => el.textContent), ['Extended by 1', 'Referenced by 2']);
    assert.equal(f.rows().length, 3);
    assert.equal(f.rows()[1].getAttribute('aria-label'), 'Referenced by: caller (slot handler)');
    f.host.dispatch('click', {target: f.rows()[1]});
    assert.deepEqual(f.navigations, [['b', 'caller']]);
    f.rows()[1].dataset.usageId = 'not-in-authorized-response';
    f.host.dispatch('click', {target: f.rows()[1]});
    assert.deepEqual(f.navigations, [['b', 'caller']], 'a forged DOM identity cannot navigate outside the authorized payload');
    assert.equal(f.requests[0].url, '/api/fns/usages');
    assert.equal(f.requests[0].options.body, '{"fn-id":"selected"}');
  }
  {
    const f = fixture();
    await f.reply({usages: [row('a', 'a', {'slot-name': 'x'}), row('a', 'a', {'slot-name': 'x'}), row('a', 'a', {'slot-name': 'y'}), row('anon', '(anonymous)', {anonymous: true})]});
    assert.equal(f.rows().length, 2, 'same fn in another slot remains visible');
    assert.equal(f.host.querySelector('.gd-insp-usage-glabel').textContent, 'Referenced by 3');
    assert.equal(f.host.querySelector('.gd-insp-usage-more').textContent, '… 1 anonymous');
  }
  {
    const f = fixture(); await f.reply({usages: []});
    assert.equal(f.rows().length, 0);
    assert.equal(f.host.querySelector('.gd-insp-usages').getAttribute('hidden'), 'true');
  }
  {
    const f = fixture();
    const usages = Array.from({length: 35}, (_, i) => row('id-' + i, 'caller-' + String(i).padStart(2, '0'), {'fn-namespace': 'app.logic'}));
    await f.reply({usages, count: 900, 'truncated?': true});
    assert.equal(f.rows().length, 20, 'existing cap applies before filtering');
    assert.equal(f.host.querySelector('.gd-insp-usages-count').textContent, '900');
    assert.deepEqual(f.host.querySelectorAll('.gd-insp-usage-more').map(el => el.textContent), ['Showing the first 35 of 900 usages.', '… and 15 more']);
    let input = f.host.querySelector('.gd-insp-usage-filter');
    input.value = 'CALLER-01'; f.host.dispatch('input', {target: input});
    assert.equal(f.rows().filter(el => el.getAttribute('hidden') === 'false').length, 1);
    input = f.host.querySelector('.gd-insp-usage-filter'); f.document.activeElement = input;
    let prevented = 0; let stopped = 0;
    f.host.dispatch('keydown', {target: input, key: 'Escape', preventDefault() { prevented++; }, stopPropagation() { stopped++; }});
    assert.equal(f.host.querySelector('.gd-insp-usage-filter').getAttribute('value'), '');
    assert.equal(prevented, 1); assert.equal(stopped, 1);
    input = f.host.querySelector('.gd-insp-usage-filter'); f.document.activeElement = input;
    f.host.dispatch('keydown', {target: input, key: 'Escape'});
    assert.equal(f.blurs(), 1, 'second Escape requests native filter blur');
    assert.equal(f.rows().length, 20);
  }
  {
    const f = fixture();
    await f.reply({usages: [row('z', '_a'), row('b', 'Beta'), row('a', 'alpha'), row('x', '_z')]});
    assert.deepEqual(f.host.querySelectorAll('.gd-insp-usage-name').map(el => el.textContent), ['alpha', 'Beta', '_a', '_z']);
  }
  {
    const f = fixture();
    const kinds = ['parent-of', 'ref-of', 'resolver-of', 'slot-of', 'binding-of', 'base-of', 'element-of', 'return-of', 'union-branch', 'variant-branch', 'fn-type-arg-or-return', 'other'];
    const usages = Array.from({length: 400}, (_, i) => row('id-' + i, 'caller-' + i, {kind: kinds[i % kinds.length]}));
    await f.reply({usages, count: 1000, 'truncated?': true});
    assert.equal(f.rows().length, 240, 'existing 400-row response fits fixed operation/DOM budgets');
    assert.equal(f.host.querySelectorAll('.gd-insp-usage-glabel').length, 12);
  }
  {
    const f = fixture(); const old = f.requests[0];
    f.refresh();
    await f.reply({usages: [row('new', 'new-context')]});
    await f.reply({usages: [row('old', 'old-context')]}, old);
    assert.equal(f.rows()[0].dataset.usageId, 'new', 'latest read wins on the same host');
  }
  {
    const f = fixture();
    const usages = Array.from({length: 31}, (_, i) => row('id-' + i, 'caller-' + i));
    await f.reply({usages});
    const input = f.host.querySelector('.gd-insp-usage-filter');
    input.value = 'caller-1'; f.host.dispatch('input', {target: input});
    f.refresh(); await f.reply({usages, count: 45});
    assert.equal(f.host.querySelector('.gd-insp-usage-filter').getAttribute('value'), 'caller-1', 'same-owner data refresh preserves authored query state');
    assert.equal(f.host.querySelector('.gd-insp-usages-count').textContent, '45');
  }
  {
    const f = fixture(); const request = f.requests[0];
    f.stop(); await f.reply({usages: [row('old', 'old-account')]}, request);
    assert.equal(f.host.children.length, 0, 'disposed/auth-old read cannot mount');
  }
  {
    const f = fixture(); await f.reply({usages: [row('a', 'alpha')]});
    let wholeFailures = 0;
    f.context.gdUIComponentFailed = () => { wholeFailures++; };
    const builtin = f.context.GraphdenBrowser.createRuntime(f.context.GraphdenBuiltinPlans.plans.inspector, {operationLimit: 150000});
    f.context.gdInspectorGraph.subscribeRuntime = listener => {
      listener({run(entry, supplied) {
        const k = f.context.GraphdenBrowser.keyword;
        const inputs = new Map(supplied.inputs); inputs.set(k('surface'), 'chrome');
        return builtin.run(entry, {...supplied, inputs});
      }});
      return () => {};
    };
    f.context.gdMountFnUsages(f.host, 'selected', {owns: () => true, captureRead: () => ({start: false})});
    assert.equal(f.host.dataset.gdUiFallback, 'missing-usages-contract');
    assert.equal(wholeFailures, 0, 'legacy section fallback retains custom chrome identity');
  }
  console.log('PASS: Used-by grouping, dedup, cap, filtering, Escape, navigation, disposal and legacy fallback');
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
