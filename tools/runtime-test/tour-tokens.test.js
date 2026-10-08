const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = name => fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/' + name), 'utf8');
const response = (status, body) => ({status, ok: status < 400, json: async () => body});

function context() {
  const principal = {accountId: 'alice', orgId: 'org'};
  const saved = [];
  const ctx = vm.createContext({console, URLSearchParams, Date,
    window: {crypto: {randomUUID: () => 'new-token-id'}},
    _tourState: {lessonId: '42', principal: {...principal}, created: []},
    _tourStep: () => ({creates: {type: 'api-token', name: 'tutorial-token'}}),
    _tourPrincipalMatches: state => state?.principal.accountId === principal.accountId
      && state?.principal.orgId === principal.orgId,
    _tourSaveState: () => saved.push(JSON.stringify(ctx._tourState)),
    selectedFnId: 'readable-id', lookups: {fnMap: new Map([['readable-id', {}]])},
    API: {api_execute: '/api/execute'},
  });
  vm.runInContext(source('editor-tour-receipts.js'), ctx);
  vm.runInContext(source('editor-tour-tokens.js'), ctx);
  return {ctx, principal, saved};
}

const receipt = {id: 'new-token-id', label: 'tutorial-token', scopes: 'write',
  'expires-at': new Date(Date.now() + 7 * 86400000).toISOString()};

(async () => {
  {
    const {ctx, saved} = context();
    const ticket = ctx.gdTourBeginTokenCreation('tutorial-token', 'write', '7');
    assert.equal(ctx._tourState.created[0].id, 'new-token-id');
    assert.ok(saved.length, 'persist the exact ID before the POST, including an ambiguous reply');
    ctx.gdTourRejectTokenCreation(ticket, 500);
    assert.equal(ctx._tourState.created.length, 1);
    assert.equal(ctx.gdTourRecordTokenCreation(ticket, {...receipt, id: 'pre-existing'}), false);
    assert.equal(ctx.gdTourRecordTokenCreation(ticket, {...receipt, token: 'one-time-secret'}), true);
    assert.ok(!saved.some(value => value.includes('one-time-secret')), 'never serialize the raw bearer');
    assert.equal(ctx._tourTokenMatches(ctx._tourState.created[0], {...receipt, 'token-hash': 'hash'}), false);
    let rows = [{...receipt, id: 'other-token'}];
    ctx.fetch = async () => response(200, rows);
    const check = {kind: 'token-created', name: 'tutorial-token', scopes: 'write', 'ttl-days': 7};
    assert.equal(ctx._tourTokenCheck(check), false, 'a matching label with another ID cannot pass');
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(ctx._tourTokenCheck(check), false);
    rows = [receipt];
    vm.runInContext('_tourTokenListings.get(_tourState.created[0]).at = 0', ctx);
    ctx._tourTokenCheck(check);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(ctx._tourTokenCheck(check), true);
    assert.equal(ctx._tourTokenCheck({...check, scopes: 'write execute'}), false);
  }
  {
    const {ctx} = context();
    const ticket = ctx.gdTourBeginTokenCreation('tutorial-token', 'write', '7');
    ctx.gdTourRejectTokenCreation(ticket, 409);
    assert.equal(ctx._tourState.created.length, 0, 'definite rejection claims no existing identity');
  }
  {
    const {ctx, principal} = context();
    const ticket = ctx.gdTourBeginTokenCreation('tutorial-token', 'write', '7');
    ctx.gdTourRecordTokenCreation(ticket, receipt);
    let calls = [];
    ctx.fetch = async (url, options) => {
      calls.push([url, options]);
      return url === '/auth/me' ? response(200, {account: {id: 'alice'}})
        : response(403, {error: 'forbidden'});
    };
    assert.equal(await ctx.gdTourProbeTokenAccess(ticket.id, 'local-only-bearer'), false,
      'an unrelated authorization refusal is not proof of a scope ceiling');
    ctx.fetch = async url => url === '/auth/me' ? response(200, {account: {id: 'alice'}})
      : response(403, {error: 'token-scope'});
    assert.equal(await ctx.gdTourProbeTokenAccess(ticket.id, 'local-only-bearer'), true);
    assert.equal(ctx._tourTokenCheck({kind: 'token-execute-denied', name: 'tutorial-token'}), true);
    assert.equal(calls[0][1].headers.Authorization, 'Bearer local-only-bearer');
    assert.ok(!JSON.stringify(ctx._tourState).includes('local-only-bearer'));
    ctx.fetch = async () => response(200, {});
    assert.equal(await ctx.gdTourProbeTokenAccess(ticket.id, 'local-only-bearer', true), false,
      'an empty response is not proof of revocation');
    ctx.fetch = async () => response(401, {error: 'unauthenticated'});
    assert.equal(await ctx.gdTourProbeTokenAccess(ticket.id, 'local-only-bearer', true), true);
    ctx.fetch = async () => response(200, []);
    await ctx.gdTourRecordTokenRevocation(ticket.id);
    assert.equal(ctx._tourTokenCheck({kind: 'token-revoked', name: 'tutorial-token'}), true);
    principal.accountId = 'bob';
    assert.equal(ctx._tourTokenCheck({kind: 'token-revoked', name: 'tutorial-token'}), false);
  }
  {
    const {ctx, principal} = context();
    ctx.gdTourBeginTokenCreation('tutorial-token', 'write', '7');
    let writes = 0;
    ctx.fetch = async (url) => {
      if (url.endsWith('/list')) { principal.accountId = 'bob'; return response(200, [receipt]); }
      writes++; return response(200, {});
    };
    assert.equal((await ctx._tourDeleteTokens(ctx._tourState.created)).length, 1);
    assert.equal(writes, 0, 'principal changes during listing must prevent any revoke');
  }
  {
    const {ctx} = context();
    ctx.gdTourBeginTokenCreation('tutorial-token', 'write', '7');
    let rows = [receipt, {...receipt, id: 'somebody-elses-token'}];
    const revoked = [];
    ctx.fetch = async (url, options) => {
      if (url.endsWith('/list')) return response(200, rows);
      const id = new URLSearchParams(options.body).get('id');
      revoked.push(id); rows = rows.filter(row => row.id !== id); return response(200, {});
    };
    assert.equal((await ctx._tourDeleteTokens(ctx._tourState.created)).length, 0);
    assert.deepEqual(revoked, ['new-token-id'], 'lost creation response cleanup revokes only its staged ID');
    assert.equal(rows[0].id, 'somebody-elses-token');
  }
  {
    const {ctx, principal} = context();
    const reveal = {innerHTML: '', dataset: {}};
    const controls = {
      'gd-acct-tok-label': {value: 'ordinary-token'},
      'gd-acct-tok-ttl': {value: '7'},
      'gd-acct-tok-reveal': reveal,
      'gd-acct-mint-form': {hidden: true},
    };
    ctx.document = {getElementById: id => controls[id],
      querySelectorAll: () => [{value: 'write'}], querySelector: () => null};
    ctx._tourSessionPrincipal = () => ({...principal});
    ctx.gdEscapeHtml = value => value;
    vm.runInContext(source('editor-account.js'), ctx);
    ctx.gdAcctSay = () => {};
    ctx.gdAcctLoadTokens = () => {};
    ctx.gdTourBeginTokenCreation = () => null;
    const pending = [];
    ctx.gdAcctPostForm = () => new Promise(resolve => pending.push(resolve));
    const first = ctx.gdAcctMintToken();
    const second = ctx.gdAcctMintToken();
    pending[1]([200, {...receipt, id: 'second', token: 'second-one-time-value'}]);
    await second;
    pending[0]([200, {...receipt, id: 'first', token: 'first-one-time-value'}]);
    await first;
    assert.equal(reveal.dataset.tokenId, 'second', 'an older response cannot replace the current reveal');
    assert.ok(!reveal.innerHTML.includes('first-one-time-value'));
    const third = ctx.gdAcctMintToken();
    principal.accountId = 'bob';
    pending[2]([200, {...receipt, token: 'previous-principal-value'}]);
    await third;
    assert.equal(reveal.innerHTML, '', 'a late response cannot reveal a previous principal’s token');
    assert.equal(reveal.dataset.tokenId, undefined);
  }
  console.log('✓ token tour: exact receipts, scope refusal, revocation and principal-safe cleanup');
})().catch(error => { console.error(error); process.exitCode = 1; });
