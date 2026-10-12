'use strict';
const {unexpectedPolicyRetryErrors} = require('./policy-retry-errors');

// These refusals are successful selective-copy outcomes only after the real
// host fallback and stale-selection preservation have both been verified.
function unexpectedSelectiveCopyErrors(errors, replies, behaviorVerified) {
  const remaining = unexpectedPolicyRetryErrors(errors, replies.filter(reply => reply.kind === 'plan' && (reply.status === 200 || reply.code === 'policy-refresh-required')), behaviorVerified);
  if (!behaviorVerified) return remaining;
  const allowed = new Map();
  for (const reply of replies) {
    const fallback = reply.kind === 'plan' && reply.status === 422 && reply.code === 'component-missing'
      && reply.fallback === 'builtin' && ['account-menu', 'fn-picker', 'recents', 'inspector', 'explorer'].includes(reply.component);
    const conflict = reply.kind === 'activation' && reply.status === 409 && reply.code === 'stale-preference'
      && reply.noValue === true;
    if (fallback || conflict) {
      const key = reply.url + ':' + reply.status;
      allowed.set(key, (allowed.get(key) || 0) + 1);
    }
  }
  return remaining.filter(error => {
    const match = error.text?.match(/^Failed to load resource: the server responded with a status of (422) \((?:Unprocessable Entity|Unprocessable Content)\)$|^Failed to load resource: the server responded with a status of (409) \(Conflict\)$/);
    const status = match?.[1] || match?.[2];
    const key = error.url + ':' + status;
    const count = allowed.get(key) || 0;
    if (error.kind !== 'console' || !count) return true;
    allowed.set(key, count - 1);
    return false;
  });
}
module.exports = {unexpectedSelectiveCopyErrors};
