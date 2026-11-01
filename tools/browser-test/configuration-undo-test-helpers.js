'use strict';
const {api} = require('./edit-test-helpers');

function undoRequest(receipt) {
  const command = {'current-id': receipt.id,
    'expected-configuration-revision': receipt['configuration-revision'],
    'cohort-revision': receipt['cohort-revision'], previous: receipt.previous,
    functions: receipt['function-receipts']};
  if (receipt.root) command.root = {'current-id': receipt.root.id,
    'cohort-revision': receipt.root['cohort-revision'],
    'restore-revision': receipt.root['restore-revision'],
    previous: receipt.root.previous, functions: receipt.root['function-receipts']};
  return command;
}

async function undoConfiguration(page, proposal, command = undoRequest(proposal.receipt)) {
  const headers = {'X-Graphden-Branch': proposal.request.scope['branch-id']};
  let result;
  for (let attempt = 0; attempt < 3; attempt++) {
    result = await api(page, 'POST', '/api/ui/configuration/undo', command, headers);
    if (result.ok) break;
    let refusal;
    try { refusal = JSON.parse(result.body); } catch (_) { refusal = null; }
    // The server rolls this classified refusal back. Retry only that exact
    // receipt after the policy settles; transport loss or other refusals need review.
    if (attempt === 2 || result.status !== 422 || refusal?.code !== 'policy-refresh-required'
      || refusal?.retryable !== true) break;
    await page.evaluate(() => gdWaitForGraphPolicy());
  }
  return result;
}

module.exports = {undoConfiguration, undoRequest};
