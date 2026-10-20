'use strict';

// A refused plan is never usable. Only the explicit policy publication
// envelope permits a later, independent no-store GET by the test harness.
function storedRootPlan({status, ok, body}) {
  if (status === 409) return {pending: true, delayMs: 0};
  if (status === 422 && body?.ok === false
    && body.code === 'policy-refresh-required' && body.retryable === true)
    return {pending: true, delayMs: 1000};
  if (!ok || body?.ok !== true) {
    const finiteStatus = Number.isInteger(status) && status >= 100 && status <= 599 ? status : 0;
    const code = body?.code === 'policy-refresh-required' ? body.code : 'unclassified';
    throw new Error('Stored root export refused: HTTP ' + finiteStatus + '; code=' + code);
  }
  return {plan: body.plan};
}

module.exports = {storedRootPlan};
