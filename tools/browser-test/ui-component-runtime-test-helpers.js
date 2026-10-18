'use strict';
const {components, builtinFallback} = require('./selective-copy-errors');

// Observe the actual loader invocation, not a later configuration with the
// same root. Its retries share an AbortSignal; a reload gets a fresh signal.
// Only finite response metadata crosses this test-only observer.
async function observeUIPlanLoads(page) {
  const requests = [], replies = [];
  const bySequence = new Map();
  await page.exposeFunction('__gdTestPlanReceipt', receipt => {
    if (receipt.kind === 'request') {
      const request = {...receipt, status: 'pending'};
      requests.push(request); bySequence.set(receipt.sequence, request);
    } else {
      const request = bySequence.get(receipt.sequence);
      if (!request) throw Error('Plan response has no observed request');
      request.status = receipt.status;
      if (receipt.kind === 'plan') replies.push(receipt);
    }
  });
  await page.addInitScript(names => {
    const components = new Set(names), loads = new WeakMap(), pending = new Set();
    let sequence = 0, nextLoad = 0;
    const track = promise => {
      pending.add(promise);
      promise.finally(() => pending.delete(promise));
      return promise;
    };
    const publish = receipt => track(window.__gdTestPlanReceipt(receipt));
    const original = window.fetch;
    window.fetch = function(input, options) {
      const url = new URL(typeof input === 'string' ? input : input.url, location.href);
      if (url.pathname !== '/api/ui/configuration/plan' || options?.method !== 'POST')
        return original.apply(this, arguments);
      const component = JSON.parse(options.body)?.component;
      if (!components.has(component)) throw Error('Unknown observed UI component');
      const signal = options.signal;
      if (signal && !loads.has(signal)) loads.set(signal, ++nextLoad);
      const receipt = {sequence: ++sequence, component, loadId: signal ? loads.get(signal) : null,
        url: url.origin + url.pathname};
      publish({...receipt, kind: 'request'});
      const response = original.apply(this, arguments);
      void response.then(result => result.clone().json().catch(() => null).then(body => publish({
        ...receipt, kind: 'plan', status: result.status, ok: body?.ok === true,
        code: ['policy-refresh-required', 'component-missing'].includes(body?.code) ? body.code : null,
        fallback: body?.fallback === 'builtin' ? 'builtin' : null, retryable: body?.retryable === true,
      })), () => publish({...receipt, kind: 'failed', status: 'failed'}));
      return response;
    };
    window.__gdTestFlushPlanReceipts = async () => {
      while (pending.size) await Promise.all([...pending]);
    };
  }, [...components]);
  return {requests, replies, flush: () => page.evaluate(() => window.__gdTestFlushPlanReceipts())};
}

async function captureLoadedUIComponents(page) {
  return page.evaluate(names => {
    const configuration = gdUIConfigurationState()?.selection?.['fn-id'];
    if (!configuration) throw Error('UI configuration identity unavailable');
    return {configuration, names: names.filter(name => gdUIComponentRuntimeIdentity(name) === configuration)};
  }, [...components]);
}

async function waitForLoadedUIComponents(page, snapshot) {
  await page.waitForFunction(({names, configuration}) =>
    gdUIConfigurationState()?.selection?.['fn-id'] === configuration
    && names.every(name => gdUIComponentRuntimeIdentity(name) === configuration), snapshot, {timeout: 60000});
}

async function drainUIPlanReplies(requests, replies, pending, requireLoadIdentity = false) {
  const deadline = Date.now() + 60000;
  // Runtime identity above proves the loaded generation has finished its
  // retries. Also account for every actually started request before auditing
  // responses; unused lazy components need no request or fabricated runtime.
  while (Date.now() < deadline) {
    if (requests.some(request => request.status === 'failed')) throw Error('UI plan request failed before receipt drain');
    const received = new Map(replies.map(reply => [reply.sequence, reply]));
    for (const reply of received.values()) {
      if (reply.status === 200 && reply.ok === true || builtinFallback(reply)) continue;
      if (reply.status !== 422 || reply.code !== 'policy-refresh-required' || reply.retryable !== true
        || !components.has(reply.component)
        || requireLoadIdentity && !(Number.isSafeInteger(reply.loadId) && reply.loadId > 0))
        throw Error('UI plan has no known terminal receipt');
    }
    if (requests.every(request => received.has(request.sequence))) {
      const latest = new Map(requests.map(request => [requireLoadIdentity
        ? request.component + ':' + request.loadId : request.component, received.get(request.sequence)]));
      let retrying = false;
      for (const reply of latest.values()) {
        if (reply.status === 200 && reply.ok === true || builtinFallback(reply)) continue;
        if (reply.status === 422 && reply.code === 'policy-refresh-required' && reply.retryable === true
          && components.has(reply.component)) retrying = true;
        else throw Error('UI plan has no known terminal receipt');
      }
      if (!retrying) {
        await Promise.all(pending);
        return;
      }
    }
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  throw Error('UI plan response receipts did not drain');
}

module.exports = {observeUIPlanLoads, captureLoadedUIComponents, waitForLoadedUIComponents, drainUIPlanReplies};
