'use strict';
const {components, builtinFallback} = require('./selective-copy-errors');

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

async function drainUIPlanReplies(requests, replies, pending) {
  const deadline = Date.now() + 60000;
  // Runtime identity above proves the loaded generation has finished its
  // retries. Also account for every actually started request before auditing
  // responses; unused lazy components need no request or fabricated runtime.
  while (Date.now() < deadline) {
    if (requests.some(request => request.status === 'failed')) throw Error('UI plan request failed before receipt drain');
    const received = new Map(replies.map(reply => [reply.sequence, reply]));
    if (requests.every(request => received.has(request.sequence))) {
      const latest = new Map(requests.map(request => [request.component, received.get(request.sequence)]));
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

module.exports = {captureLoadedUIComponents, waitForLoadedUIComponents, drainUIPlanReplies};
