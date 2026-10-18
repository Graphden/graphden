'use strict';
const {components, builtinFallback} = require('./selective-copy-errors');

// Observe the actual loader invocation, not a later configuration with the
// same root. Its retries share an AbortSignal; a reload gets a fresh signal.
// Only finite response metadata crosses this test-only observer.
async function observeUIPlanLoads(page) {
  const requests = [], replies = [];
  const bySequence = new Map();
  await page.exposeFunction('__gdTestPlanReceipt', receipt => {
    const key = receipt.documentId + ':' + receipt.sequence;
    if (receipt.kind === 'request') {
      const request = {...receipt, status: 'pending'};
      requests.push(request); bySequence.set(key, request);
    } else {
      const request = bySequence.get(key);
      if (!request) throw Error('Plan response has no observed request');
      Object.assign(request, {status: receipt.status, abortError: receipt.abortError === true,
        signalAborted: receipt.signalAborted === true});
      if (receipt.kind === 'plan') replies.push(receipt);
    }
  });
  await page.addInitScript(names => {
    const components = new Set(names), loads = new WeakMap(), pending = new Set();
    const documentId = crypto.randomUUID();
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
      const receipt = {documentId, sequence: ++sequence, component, loadId: signal ? loads.get(signal) : null,
        url: url.origin + url.pathname};
      publish({...receipt, kind: 'request'});
      const response = original.apply(this, arguments);
      void response.then(result => result.clone().json().catch(() => null).then(body => publish({
        ...receipt, kind: 'plan', status: result.status, ok: body?.ok === true,
        code: ['policy-refresh-required', 'component-missing'].includes(body?.code) ? body.code : null,
        fallback: body?.fallback === 'builtin' ? 'builtin' : null, retryable: body?.retryable === true,
      })), error => {
        // Freeze these facts at rejection: a later abort cannot launder a
        // prior network error into intentional loader supersession.
        const abortError = error?.name === 'AbortError', signalAborted = signal?.aborted === true;
        return publish({...receipt, kind: 'failed', status: 'failed', abortError, signalAborted});
      });
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

async function drainUIPlanReplies(requests, replies, pending, requireLoadIdentity = false, acceptSuperseded = null) {
  if (acceptSuperseded && !requireLoadIdentity) throw Error('Superseded loads require exact load identity');
  const deadline = Date.now() + 60000;
  // Runtime identity above proves the loaded generation has finished its
  // retries. Also account for every actually started request before auditing
  // responses; unused lazy components need no request or fabricated runtime.
  while (Date.now() < deadline) {
    const requestKey = request => request.documentId === undefined ? request.sequence : request.documentId + ':' + request.sequence;
    const loadKey = request => request.documentId + ':' + request.component + ':' + request.loadId;
    const received = new Map(replies.map(reply => [requestKey(reply), reply]));
    for (const reply of received.values()) {
      if (reply.status === 200 && reply.ok === true || builtinFallback(reply)) continue;
      if (reply.status !== 422 || reply.code !== 'policy-refresh-required' || reply.retryable !== true
        || !components.has(reply.component)
        || requireLoadIdentity && !(Number.isSafeInteger(reply.loadId) && reply.loadId > 0))
        throw Error('UI plan has no known terminal receipt');
    }
    const superseded = new Map();
    let replacementPending = false;
    for (const request of requests.filter(request => request.status === 'failed')) {
      if (!acceptSuperseded || request.abortError !== true || request.signalAborted !== true
        || typeof request.documentId !== 'string' || !request.documentId
        || !Number.isSafeInteger(request.loadId) || request.loadId <= 0)
        throw Error('UI plan request failed before receipt drain');
      const newer = requests.filter(candidate => candidate.component === request.component
        && candidate.documentId === request.documentId && Number.isSafeInteger(candidate.loadId)
        && candidate.loadId > request.loadId);
      const latest = newer.reduce((last, candidate) => !last || candidate.loadId > last.loadId
        || candidate.loadId === last.loadId && candidate.sequence > last.sequence ? candidate : last, null);
      if (!latest || latest.status === 'failed') throw Error('UI plan request failed before receipt drain');
      const reply = received.get(requestKey(latest));
      if (!reply || reply.status === 422 && reply.code === 'policy-refresh-required' && reply.retryable === true) {
        replacementPending = true; continue;
      }
      if (reply.status !== 200 || reply.ok !== true) throw Error('UI plan request failed before receipt drain');
      superseded.set(loadKey(request), {component: request.component, documentId: request.documentId,
        loadId: request.loadId, replacementLoadId: latest.loadId});
    }
    const active = requests.filter(request => !superseded.has(loadKey(request)));
    if (!replacementPending && active.every(request => received.has(requestKey(request)))) {
      const latest = new Map(active.map(request => [requireLoadIdentity
        ? loadKey(request) : request.component, received.get(requestKey(request))]));
      let retrying = false;
      for (const reply of latest.values()) {
        if (reply.status === 200 && reply.ok === true || builtinFallback(reply)) continue;
        if (reply.status === 422 && reply.code === 'policy-refresh-required' && reply.retryable === true
          && components.has(reply.component)) retrying = true;
        else throw Error('UI plan has no known terminal receipt');
      }
      if (!retrying) {
        const replyCount = replies.length, requestCount = requests.length;
        if (superseded.size) await acceptSuperseded([...new Set([...superseded.values()].map(load => load.component))]);
        await Promise.all(pending);
        // Another response may finish parsing during this await.
        // Audit that completed response too before declaring the barrier done.
        if (replies.length !== replyCount || requests.length !== requestCount || requests.some(request =>
          request.status === 'failed' ? !superseded.has(loadKey(request)) : !received.has(requestKey(request)))) continue;
        return {superseded: [...superseded.values()]};
      }
    }
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  throw Error('UI plan response receipts did not drain');
}

module.exports = {observeUIPlanLoads, captureLoadedUIComponents, waitForLoadedUIComponents, drainUIPlanReplies};
