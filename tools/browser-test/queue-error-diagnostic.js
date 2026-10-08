// Opt-in self-host diagnostic. SQL returns classifications only, never payloads
// or exception text. Both identifiers must belong to this test's fresh fixture.
const {spawnSync} = require('node:child_process');
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function readQueueError(messageId, queue) {
  const container = process.env.GRAPHDEN_QUEUE_DIAGNOSTIC_DB_CONTAINER;
  if (!container) return null;
  if (!UUID.test(messageId) || !UUID.test(queue)) throw new Error('Diagnostic needs owned UUIDs');
  const sql = `SELECT json_build_object('attempts', attempts, 'errorPresent', error IS NOT NULL,
    'category', CASE
      WHEN error IS NULL THEN 'none'
      WHEN error ~* 'JSON|Unrecognized token|Unexpected character' THEN 'json-parse'
      WHEN error ~* 'heartbeat|lease.every' THEN 'heartbeat-input'
      WHEN error ~* 'interrupted|cancel' THEN 'cancelled'
      WHEN error ~* 'lambda|HOF|callable' THEN 'hof'
      WHEN error ~* 'missing|required|free.arg' THEN 'missing-input'
      WHEN error ~* 'cannot invoke.*Number|longCast|NullPointer' THEN 'nil-number'
      WHEN error ~* 'arity|Wrong number' THEN 'arity'
      WHEN error ~* 'effect' THEN 'effect-denied'
      WHEN error ~* 'cast|ClassCast' THEN 'cast'
      WHEN error ~* 'UUID' THEN 'uuid'
      WHEN error ~* 'not.*function|IFn' THEN 'not-callable'
      ELSE 'unknown' END)
    FROM queue_message WHERE id = '${messageId}'::uuid AND queue = '${queue}';`;
  const result = spawnSync('docker', ['exec', container, 'psql', '-XAt', '-v',
    'ON_ERROR_STOP=1', '-U', 'graphden', '-d', 'graphden', '-c', sql],
  {encoding: 'utf8', timeout: 5000});
  if (result.status !== 0) throw new Error('Scoped queue diagnostic read failed');
  return result.stdout.trim() ? JSON.parse(result.stdout.trim()) : {absent: true};
}

module.exports = {readQueueError};
