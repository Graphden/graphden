// One causal boundary for browser gestures used by more than one scenario.
// Callers own the semantic selector and expected mutation; this runner reports
// which phase failed instead of leaving a late locator timeout ambiguous.

class ActionFailure extends Error {
  constructor(action, phase, detail, cause) {
    super(`${action}: ${phase}${detail ? ` — ${detail}` : ''}`);
    this.name = 'ActionFailure';
    this.action = action;
    this.phase = phase;
    if (cause) this.cause = cause;
  }
}

function matchingRequest(request, expected) {
  if (expected.method) {
    const methods = Array.isArray(expected.method) ? expected.method : [expected.method];
    if (!methods.includes(request.method())) return false;
  }
  if (expected.path) {
    const path = new URL(request.url()).pathname;
    if (expected.path instanceof RegExp ? !expected.path.test(path) : path !== expected.path) {
      return false;
    }
  }
  return !expected.match || expected.match(request);
}

async function responseWithin(request, timeoutMs) {
  let timer;
  try {
    return await Promise.race([
      request.response(),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`no response within ${timeoutMs} ms`)), timeoutMs);
      })
    ]);
  } finally {
    clearTimeout(timer);
  }
}

async function checkedAction(page, {name, ready, gesture, request, requests, settled, timeoutMs = 15000}) {
  if (!name || typeof gesture !== 'function') throw new Error('checkedAction needs name and gesture');
  if (request && requests) throw new Error('checkedAction accepts request or requests');
  try {
    if (ready) await ready();
  } catch (error) {
    throw new ActionFailure(name, 'element-absent', error.message, error);
  }

  // Arm the listener before the gesture so even a fast response is correlated
  // with this action. Turn its rejection into data to avoid an unhandled
  // timeout if the click itself fails first.
  const expectedRequests = requests || (request ? [request] : []);
  const requestWaits = expectedRequests.map((expected) =>
    page.waitForRequest((candidate) => matchingRequest(candidate, expected),
      {timeout: timeoutMs}).then((value) => ({value}), (error) => ({error})));
  try {
    await gesture();
  } catch (error) {
    throw new ActionFailure(name, 'click-failed', error.message, error);
  }

  const responses = [];
  for (let i = 0; i < requestWaits.length; i++) {
    const observed = await requestWaits[i];
    const label = expectedRequests[i].name || `request ${i + 1}`;
    if (observed.error) {
      throw new ActionFailure(name, 'request-not-sent', `${label}: ${observed.error.message}`, observed.error);
    }
    let response;
    try {
      response = await responseWithin(observed.value, timeoutMs);
    } catch (error) {
      throw new ActionFailure(name, 'response-timeout', `${label}: ${error.message}`, error);
    }
    if (!response) {
      throw new ActionFailure(name, 'response-missing', `${label}: ${observed.value.failure()?.errorText || 'network failure'}`);
    }
    if (!response.ok()) {
      throw new ActionFailure(name, 'http-error', `${label}: HTTP ${response.status()} ${response.url()}`);
    }
    responses.push(response);
  }

  try {
    if (settled) await settled();
  } catch (error) {
    throw new ActionFailure(name, 'ui-not-settled', error.message, error);
  }
  return requests ? responses : responses[0];
}

module.exports = {ActionFailure, checkedAction};
