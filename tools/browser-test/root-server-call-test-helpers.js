'use strict';
const assert = require('node:assert/strict');

// A stale response is admissible only for an actually superseded root consumer.
// A later unrelated 200 (or a still-current error) never establishes recovery.
function recoveredStaleCalls(events) {
  const calls = events.filter(event => event.kind === 'request');
  const responses = events.filter(event => event.kind === 'response');
  const sameCall = (left, right) => left.document === right.document
    && left.root === right.root && left.lifetime === right.lifetime && left.invocation === right.invocation;
  const failed = responses.filter(event => event.status >= 400);
  for (const response of failed) {
    const request = calls.find(call => sameCall(call, response));
    assert(request?.owned && response.status === 422 && response.code === 'server-call-stale',
      'server refusal has no exact finite stale receipt');
    assert(!events.some(state => sameCall(request, state) && state.kind === 'state' && state.phase === 'error'),
      'current server consumer handled the refusal before supersession');
    const replacement = calls.find(call => call.document === request.document && call.owned
      && call.root > request.root
      && events.some(event => event.kind === 'changing' && event.document === call.document
        && event.root > request.root && event.order > request.order && event.order < call.order)
      && events.some(event => event.kind === 'installed' && event.document === call.document
        && event.root === call.root && event.order < call.order)
      && responses.some(result => sameCall(call, result) && result.status === 200 && result.ok)
      && events.some(state => sameCall(call, state) && state.kind === 'state' && state.phase === 'ready'
        && state.root === call.root && state.owned && state.hostReady));
    assert(replacement, 'stale server request lacks a superseding installed root and successful typed query');
  }
  return failed.length;
}

function withoutRecoveredServerErrors(errors, events) {
  const count = recoveredStaleCalls(events);
  const serverErrors = errors.filter(error => error.kind === 'console'
    && /status of 422\b/.test(error.text || '')
    && error.url && new URL(error.url).pathname === '/api/ui/server-call');
  assert.equal(serverErrors.length, count, 'every stale console error has exactly one correlated response');
  return errors.filter(error => !serverErrors.includes(error));
}

function rootServerDiagnostic(error, events) {
  const reasons = [
    ['server refusal has no exact finite stale receipt', 'unknown-refusal'],
    ['current server consumer handled the refusal before supersession', 'current-consumer-error'],
    ['stale server request lacks a superseding installed root and successful typed query', 'missing-replacement'],
    ['every stale console error has exactly one correlated response', 'console-receipt-count'],
  ];
  const documents = new Map();
  const calls = events.filter(event => event.kind === 'request').map(call => {
    if (!documents.has(call.document)) documents.set(call.document, documents.size);
    const same = event => event.document === call.document && event.root === call.root
      && event.lifetime === call.lifetime && event.invocation === call.invocation;
    const response = events.find(event => event.kind === 'response' && same(event));
    return {document: documents.get(call.document), root: call.root, owned: call.owned,
      status: response?.status, code: response?.code,
      states: events.filter(event => event.kind === 'state' && same(event))
        .map(({phase, owned, hostReady}) => ({phase, owned, hostReady}))};
  });
  return {reason: reasons.find(([message]) => typeof error?.message === 'string' && error.message.startsWith(message))?.[1] || 'other', calls};
}

async function observeRootServerCalls(page) {
  const events = [];
  await page.exposeFunction('__gdRootServerReceipt', event => { events.push(event); });
  await page.addInitScript(() => {
    const document = crypto.randomUUID();
    let order = 0, root = 0, installed = null, nextLifetime = 0;
    const pending = new Set(), signals = new WeakMap();
    const track = promise => { pending.add(promise); promise.finally(() => pending.delete(promise)); return promise; };
    const publish = event => track(window.__gdRootServerReceipt({...event, document, order: ++order}));
    addEventListener('gd-editor-root-changing', () => { installed = null; window.__gdRootServerState = null; publish({kind: 'changing', root: ++root}); });
    addEventListener('gd-editor-root-ready', () => { installed = root; publish({kind: 'installed', root}); });
    let server;
    Object.defineProperty(window, 'GraphdenServer', {configurable: true, get: () => server,
      set(api) {
        server = Object.freeze({...api, createLifetime(options) {
          const lifetime = ++nextLifetime, owner = installed, facade = window.gdShellMenuGraph?.runtime;
          let invocation = 0;
          return api.createLifetime({...options,
            transport(payload, settings) {
              const receipt = {lifetime, invocation, root: owner,
                owned: owner !== null && owner === installed && facade === window.gdShellMenuGraph?.runtime};
              signals.set(settings.signal, receipt);
              return options.transport(payload, settings);
            },
            onState(next) {
              if (next.phase === 'loading') invocation++;
              options.onState?.(next);
              const owned = owner !== null && owner === installed && facade === window.gdShellMenuGraph?.runtime;
              window.__gdRootServerState = {root: owner, installed, phase: next.phase, owned,
                hostReady: window.gdShellMenuGraph?.ready === true};
              publish({kind: 'state', lifetime, invocation, ...window.__gdRootServerState});
            },
          });
        }});
      },
    });
    const fetch = window.fetch;
    window.fetch = function(input, options) {
      const url = new URL(typeof input === 'string' ? input : input.url, location.href);
      if (url.pathname !== '/api/ui/server-call' || options?.method !== 'POST') return fetch.apply(this, arguments);
      const receipt = signals.get(options.signal) || {owned: false};
      publish({kind: 'request', ...receipt});
      const response = fetch.apply(this, arguments);
      track(response.then(async result => {
        const body = await result.clone().json().catch(() => null);
        await publish({kind: 'response', ...receipt, status: result.status, ok: body?.ok === true,
          code: body?.code === 'server-call-stale' ? body.code : null});
      }, error => publish({kind: 'failed', ...receipt, aborted: error?.name === 'AbortError'})));
      return response;
    };
    window.__gdFlushRootServerReceipts = async () => { while (pending.size) await Promise.all([...pending]); };
  });
  return {events, async settle() {
    await page.waitForFunction(() => {
      const state = window.__gdRootServerState;
      return state?.phase === 'ready' && state.owned && state.root === state.installed
        && window.gdShellMenuGraph?.ready === true;
    }, null, {timeout: 60000});
    await page.evaluate(() => window.__gdFlushRootServerReceipts());
  }};
}
module.exports = {observeRootServerCalls, recoveredStaleCalls, withoutRecoveredServerErrors, rootServerDiagnostic};
