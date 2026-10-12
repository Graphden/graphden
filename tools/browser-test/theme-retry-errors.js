'use strict';
// Editing an ordinary theme can invalidate an in-flight checked result. Only
// exact selected-source recovery and verified rendered colors waive its error.
function unexpectedThemeRetryErrors(errors, replies, behaviorVerified) {
  if (!behaviorVerified) return errors;
  const refused = replies.filter(reply => reply.status === 422);
  if (refused.some(reply => reply.code !== 'graph-changed' || reply.reason !== 'result-unavailable'
    || reply.retryable !== true || !reply.identity || !replies.some(success => success.url === reply.url
      && success.identity === reply.identity && success.sequence > reply.sequence
      && success.status === 200 && success.ok === true && success.payload === true))) return errors;
  const allowed = new Map();
  for (const reply of refused) allowed.set(reply.url, (allowed.get(reply.url) || 0) + 1);
  return errors.filter(error => {
    const count = allowed.get(error.url) || 0;
    if (error.kind !== 'console' || !count
      || !/^Failed to load resource: the server responded with a status of 422 \((?:Unprocessable Entity|Unprocessable Content)\)$/.test(error.text)) return true;
    allowed.set(error.url, count - 1);
    return false;
  });
}
module.exports = {unexpectedThemeRetryErrors};
