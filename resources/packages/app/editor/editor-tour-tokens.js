// Lesson-owned token receipts contain identity and policy, never the bearer.
// graph-first-exception: browser account APIs, one-time reveal and local
// lesson recovery. Creation/revocation remain the ordinary Account actions.

function _tourTokenScopes(value) {
  return String(value || '').trim().split(/\s+/).filter(Boolean).sort().join(' ');
}

const _tourTokenListings = new WeakMap();

function _tourTokenExpiry(value) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : Number.NaN;
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T/.test(value)) return Number.NaN;
  return Date.parse(value);
}

function gdTourBeginTokenCreation(label, scopes, ttl) {
  const ticket = _tourReceiptTicket('api-token', label, {
    id: window.crypto.randomUUID(), scopes: _tourTokenScopes(scopes), 'ttl-days': String(ttl || ''),
  });
  return ticket ? {...ticket, id: _tourReceiptEntry(ticket).id} : null;
}

function gdTourRecordTokenCreation(ticket, body) {
  const entry = _tourReceiptEntry(ticket);
  if (!entry || body?.id !== entry.id || body.label !== entry.name
      || _tourTokenScopes(body.scopes) !== entry.scopes
      || !Number.isFinite(_tourTokenExpiry(body['expires-at']))) return false;
  Object.assign(entry, {'expires-at': body['expires-at'], receipt: 'created'});
  _tourSaveState();
  return true;
}

function gdTourRejectTokenCreation(ticket, status) {
  const entry = _tourReceiptEntry(ticket);
  if (entry?.receipt !== 'pending' || status < 400 || status >= 500) return;
  _tourState.created = _tourState.created.filter(row => row !== entry);
  _tourSaveState();
}

function _tourTokenEntry(name) {
  if (!_tourState || !_tourPrincipalMatches(_tourState)) return null;
  return [..._tourState.created].reverse().find(row => row.type === 'api-token'
    && row.name === name && row.id) || null;
}

async function _tourReadTokens() {
  const response = await fetch('/api/my-tokens/list'); // api-url-drift-allow: route-collection
  if (!response.ok) throw new Error('Account tokens unavailable');
  const rows = await response.json();
  if (!Array.isArray(rows)) throw new Error('Invalid account token response');
  return rows;
}

function _tourTokenMatches(entry, row) {
  const expires = _tourTokenExpiry(row?.['expires-at']);
  const pending = entry.receipt === 'pending' && !Object.hasOwn(entry, 'expires-at');
  return row?.id === entry.id && row.label === entry.name
    && _tourTokenScopes(row.scopes) === entry.scopes && Number.isFinite(expires)
    && (pending || expires === _tourTokenExpiry(entry['expires-at']))
    && !Object.hasOwn(row, 'token') && !Object.hasOwn(row, 'token-hash');
}

function _tourTokenCheck(check) {
  const entry = _tourTokenEntry(check.name);
  if (!entry) return false;
  if (check.kind === 'token-execute-denied') return entry['execute-denied'] === true;
  if (check.kind === 'token-revoked') return entry.receipt === 'removed' && entry['revoked-auth'] === true;
  if (entry.receipt !== 'created' || entry.scopes !== _tourTokenScopes(check.scopes)
      || entry['ttl-days'] !== String(check['ttl-days'])) return false;
  const listing = _tourTokenListings.get(entry) || {pending: false, at: 0, listed: false};
  _tourTokenListings.set(entry, listing);
  if (!listing.pending && Date.now() - listing.at > 1000) {
    listing.pending = true;
    _tourReadTokens().then(rows => {
      if (_tourTokenEntry(check.name) === entry) {
        listing.listed = rows.some(row => _tourTokenMatches(entry, row));
        listing.at = Date.now();
      }
    }).catch(() => { listing.listed = false; }).finally(() => { listing.pending = false; });
  }
  return listing.listed === true && Date.now() - listing.at <= 2000;
}

async function gdTourProbeTokenAccess(id, bearer, revoked = false) {
  const state = _tourState;
  const entry = state?.created?.find(row => row.type === 'api-token' && row.id === id);
  const current = () => _tourState === state && _tourPrincipalMatches(state);
  if (!entry || !bearer || !current()) return false;
  const headers = {Authorization: 'Bearer ' + bearer, Accept: 'application/json'};
  // Verify authentication first: an unrelated 403 is not evidence of a scope.
  const auth = await fetch('/auth/me', {headers});
  const account = await auth.json();
  if (!current()) return false;
  if (revoked) {
    if (auth.status !== 401 || account?.error !== 'unauthenticated' || account?.account?.id) return false;
    entry['revoked-auth'] = true;
  } else {
    if (!auth.ok || account?.account?.id !== state.principal.accountId
        || !selectedFnId || !lookups?.fnMap?.has(selectedFnId)) return false;
    const execute = await fetch(API.api_execute, {method: 'POST',
      headers: {...headers, 'Content-Type': 'application/json'},
      body: JSON.stringify({'fn-id': selectedFnId, args: {}, 'persist?': false,
        'trace?': false, 'capture-values?': false})});
    const result = await execute.json();
    if (!current() || execute.status !== 403 || result?.error !== 'token-scope') return false;
    entry['execute-denied'] = true;
  }
  _tourSaveState();
  return true;
}

async function gdTourCheckRevealedToken() {
  const reveal = document.getElementById('gd-acct-tok-reveal');
  try {
    const passed = await gdTourProbeTokenAccess(reveal?.dataset.tokenId,
      reveal?.querySelector('code')?.textContent);
    gdAcctSay(passed ? 'Token authenticated; Execute refused without its scope.'
      : 'Keep this token’s one-time reveal open and select a readable function before retrying.', passed);
  } catch (_) { gdAcctSay('Could not verify token access. Retry while the reveal is available.'); }
}

async function gdTourRecordTokenRevocation(id) {
  const state = _tourState;
  const entry = state?.created?.find(row => row.type === 'api-token' && row.id === id);
  if (!entry || !_tourPrincipalMatches(state)) return;
  const rows = await _tourReadTokens();
  if (_tourState === state && _tourPrincipalMatches(state) && !rows.some(row => row.id === id)) {
    entry.receipt = 'removed';
    _tourSaveState();
  }
}

async function _tourDeleteTokens(created) {
  const state = _tourState;
  const current = () => _tourState === state && _tourPrincipalMatches(state);
  const failed = [];
  for (const entry of created.filter(row => row.type === 'api-token' && row.receipt !== 'removed')) {
    try {
      if (!entry.id || !current()) throw new Error('Token ownership unavailable');
      const row = (await _tourReadTokens()).find(row => row.id === entry.id);
      if (!current()) throw new Error('Token ownership changed');
      if (row) {
        if (!_tourTokenMatches(entry, row)) throw new Error('Token receipt changed');
        const response = await fetch('/api/my-tokens/revoke', {method: 'POST', // api-url-drift-allow: route-collection
          headers: {'Content-Type': 'application/x-www-form-urlencoded'},
          body: new URLSearchParams({id: entry.id}).toString()});
        if (!response.ok || !current() || (await _tourReadTokens()).some(token => token.id === entry.id)) {
          throw new Error('Token still exists');
        }
        if (!current()) throw new Error('Token ownership changed');
      }
      entry.receipt = 'removed';
    } catch (_) { failed.push(entry); }
  }
  return failed;
}
