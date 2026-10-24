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
  if (expected.method && request.method() !== expected.method) return false;
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

async function checkedAction(page, {name, ready, gesture, request, settled, timeoutMs = 15000}) {
  if (!name || typeof gesture !== 'function') throw new Error('checkedAction needs name and gesture');
  try {
    if (ready) await ready();
  } catch (error) {
    throw new ActionFailure(name, 'element-absent', error.message, error);
  }

  // Arm the listener before the gesture so even a fast response is correlated
  // with this action. Turn its rejection into data to avoid an unhandled
  // timeout if the click itself fails first.
  const requestWait = request
    ? page.waitForRequest((candidate) => matchingRequest(candidate, request),
      {timeout: timeoutMs}).then((value) => ({value}), (error) => ({error}))
    : null;
  try {
    await gesture();
  } catch (error) {
    throw new ActionFailure(name, 'click-failed', error.message, error);
  }

  let response;
  if (requestWait) {
    const observed = await requestWait;
    if (observed.error) {
      throw new ActionFailure(name, 'request-not-sent', observed.error.message, observed.error);
    }
    try {
      response = await responseWithin(observed.value, timeoutMs);
    } catch (error) {
      throw new ActionFailure(name, 'response-timeout', error.message, error);
    }
    if (!response) {
      throw new ActionFailure(name, 'response-missing', observed.value.failure()?.errorText);
    }
    if (!response.ok()) {
      throw new ActionFailure(name, 'http-error', `HTTP ${response.status()} ${response.url()}`);
    }
  }

  try {
    if (settled) await settled();
  } catch (error) {
    throw new ActionFailure(name, 'ui-not-settled', error.message, error);
  }
  return response;
}

module.exports = {ActionFailure, checkedAction};
