'use strict';
const {unexpectedPolicyRetryErrors} = require('./policy-retry-errors');
// Keep the finite console-recovery contract in sync with the shipped graph
// component catalog. The runtime test checks these exact names and root keys.
const componentRoots = Object.freeze({'account-menu': 'menu-id', 'fn-picker': 'picker-id',
  recents: 'recents-id', inspector: 'inspector-id', explorer: 'explorer-id',
  'tutorial-catalog': 'tutorial-catalog-id', canvas: 'canvas-id', 'canvas-values': 'canvas-values-id', 'canvas-inheritance': 'canvas-inheritance-id', 'canvas-metadata': 'canvas-metadata-id'});
const components = new Set(Object.keys(componentRoots));
const builtinFallback = reply => reply.kind === 'plan' && reply.status === 422
  && reply.code === 'component-missing' && reply.fallback === 'builtin' && components.has(reply.component);
const fallbackRecovery = (reply, replies) => reply.kind === 'plan' && reply.status === 422
  && reply.code === 'policy-refresh-required' && reply.retryable === true && components.has(reply.component)
  && replies.some(success => builtinFallback(success) && success.sequence > reply.sequence
    && success.url === reply.url && success.component === reply.component);

// These refusals are expected only after the matching real host behavior
// has been verified: builtin fallback or stale-selection preservation.
function unexpectedSelectiveCopyErrors(errors, replies, behaviorVerified) {
  if (replies.some(reply => reply.kind === 'plan' && reply.status === 422
    && !builtinFallback(reply) && !fallbackRecovery(reply, replies)
    && !(reply.code === 'policy-refresh-required' && reply.retryable === true && components.has(reply.component)
      && replies.some(success => success.kind === 'plan' && success.sequence > reply.sequence
        && success.url === reply.url && success.component === reply.component
        && success.status === 200 && success.ok === true)))) return errors;
  const remaining = unexpectedPolicyRetryErrors(errors, replies.filter(reply => reply.kind === 'plan'
    && (reply.status === 200 || reply.code === 'policy-refresh-required') && !fallbackRecovery(reply, replies)), behaviorVerified);
  if (!behaviorVerified) return remaining;
  const allowed = new Map();
  for (const reply of replies) {
    const fallback = builtinFallback(reply) || fallbackRecovery(reply, replies);
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
module.exports = {unexpectedSelectiveCopyErrors, componentRoots, components};
