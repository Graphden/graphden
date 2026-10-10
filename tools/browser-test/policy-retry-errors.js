'use strict';
// A classified transient refusal can emit a browser network-console error even
// when the host retries successfully. Never waive a terminal or unknown reply.
function unexpectedPolicyRetryErrors(errors, replies, behaviorVerified) {
  if (!behaviorVerified) return errors;
  const relevant = replies.filter(reply => reply.status === 422);
  if (relevant.some(reply => reply.code !== 'policy-refresh-required' || reply.retryable !== true
    || typeof reply.component !== 'string' || !reply.component || !replies.some(success => success.sequence > reply.sequence
      && success.url === reply.url && success.component === reply.component
      && success.status === 200 && success.ok === true))) return errors;
  const allowed = new Map();
  for (const reply of relevant) allowed.set(reply.url, (allowed.get(reply.url) || 0) + 1);
  return errors.filter(error => {
    const count = allowed.get(error.url) || 0;
    if (error.kind !== 'console' || !count
      || !/^Failed to load resource: the server responded with a status of 422 \((?:Unprocessable Entity|Unprocessable Content)\)$/.test(error.text)) return true;
    allowed.set(error.url, count - 1);
    return false;
  });
}
module.exports = {unexpectedPolicyRetryErrors};
