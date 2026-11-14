'use strict';
const {components, builtinFallback} = require('./selective-copy-errors');
const {createComponentPlanDecoder} = require('./component-plan-replies');

// Loader invocation identities outlive transport retries and never conflate
// sibling consumers just because they share a batch AbortSignal.
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
  await page.addInitScript(({names, decoderSource}) => {
    // Only checked-in helper source is evaluated; no server data is executable.
    const {componentsForRequest, decode} = Function('return (' + decoderSource + ')()')();
    const components = new Set(names), loads = new Map(), pending = new Set(), failures = [];
    const documentId = crypto.randomUUID();
    let sequence = 0, nextLoad = 0;
    const track = promise => {
      pending.add(promise);
      promise.then(() => pending.delete(promise), error => { failures.push(error); pending.delete(promise); });
      return promise;
    };
    const publish = receipt => track(window.__gdTestPlanReceipt(receipt));
    for (const [name, componentAt, signalAt] of [
      ['gdLoadUIComponentRuntime', 0, 3], ['gdCaptureUIConfiguration', null, 0],
    ]) {
      let wrapped;
      Object.defineProperty(window, name, {configurable: true, get: () => wrapped, set(fn) {
        wrapped = function(...args) {
          const component = componentAt === null ? 'account-menu' : args[componentAt];
          if (!components.has(component)) throw Error('Unknown observed UI component');
          loads.set(component, {id: ++nextLoad, signal: args[signalAt]});
          return fn.apply(this, args);
        };
      }});
    }
    const original = window.fetch;
    window.fetch = function(input, options) {
      const url = new URL(typeof input === 'string' ? input : input.url, location.href);
      if (url.pathname !== '/api/ui/configuration/plan' || options?.method !== 'POST')
        return original.apply(this, arguments);
      const request = JSON.parse(options.body);
      const receipts = componentsForRequest(request).map(component => {
        if (!components.has(component)) throw Error('Unknown observed UI component');
        const load = loads.get(component);
        return {receipt: {documentId, sequence: ++sequence, component, loadId: load?.id ?? null,
          url: url.origin + url.pathname}, signal: load?.signal || options.signal};
      });
      for (const {receipt} of receipts) publish({...receipt, kind: 'request'});
      const response = original.apply(this, arguments);
      track(response.then(async result => {
        const body = await result.clone().json().catch(() => null);
        const items = decode(request, result.status, body);
        await Promise.all(items.map((item, index) => publish({
          ...receipts[index].receipt, kind: 'plan', transportId: receipts[0].receipt.sequence, transportStatus: result.status,
          status: item.status, ok: item.body?.ok === true,
          code: ['policy-refresh-required', 'component-missing'].includes(item.body?.code) ? item.body.code : null,
          fallback: item.body?.fallback === 'builtin' ? 'builtin' : null, retryable: item.body?.retryable === true,
        })));
      }, async error => {
        // Capture consumer and transport facts at rejection, before later aborts.
        await Promise.all(receipts.map(({receipt, signal}) => publish({...receipt, kind: 'failed', status: 'failed',
          abortError: error?.name === 'AbortError', signalAborted: signal?.aborted === true})));
      }));
      return response;
    };
    window.__gdTestFlushPlanReceipts = async () => {
      while (pending.size) await Promise.allSettled([...pending]);
      if (failures.length) throw Error('Invalid UI plan observer receipt');
    };
  }, {names: [...components], decoderSource: createComponentPlanDecoder.toString()});
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
