'use strict';
// A browser's network console may repeat a refused dependency DELETE. Accept
// it only after the same owned URL succeeded and exact absence was verified.
function unexpectedCleanupErrors(errors, responses, ownedIds, absenceVerified) {
  const refusals = new Map();
  const succeeded = new Set();
  for (const response of responses) {
    const match = new URL(response.url).pathname.match(/^\/api\/entities\/fn\/([^/]+)$/);
    if (!match || !ownedIds.has(match[1])) continue;
    if (response.status === 409) refusals.set(response.url, (refusals.get(response.url) || 0) + 1);
    if (response.status >= 200 && response.status < 300) succeeded.add(response.url);
  }
  return errors.filter(error => {
    const count = refusals.get(error.url) || 0;
    if (!absenceVerified || error.kind !== 'console' || !count || !succeeded.has(error.url)
      || error.text !== 'Failed to load resource: the server responded with a status of 409 (Conflict)') return true;
    refusals.set(error.url, count - 1);
    return false;
  });
}
module.exports = {unexpectedCleanupErrors};
