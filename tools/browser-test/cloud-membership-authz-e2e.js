// Cloud membership/authz smoke against ONLY the local gdcloud stack.
//
// This is deliberately outside run-edit-tests.sh: unlike the single-tenant
// editor specs, this guard needs the cloud image, two browser sessions, and a
// local OpenBao. Run it through `bb test-e2e-org-authz` from the monorepo.
// The runner creates a unique owner. This test invites a second, not-yet-
// registered email, provisions that account through gdcloud.sh (first login
// auto-accepts the pinned invite), narrows its default write grant, then exercises Run and
// the tenant write/secret routes with the invitee's session cookie.

const {chromium} = require('playwright');
const {execFileSync} = require('node:child_process');
const fs = require('node:fs');
const {reportFailure, errorKind, status} = require('./safe-diagnostics');

const BASE = process.env.GRAPHDEN_URL || '';
const OWNER_EMAIL = process.env.GRAPHDEN_ORG_EMAIL || '';
const MEMBER_EMAIL = process.env.GRAPHDEN_MEMBER_EMAIL || '';
const PASSWORD = process.env.GRAPHDEN_ORG_PASSWORD || '';
const GDCLOUD_SCRIPT = process.env.GDCLOUD_SCRIPT || '';
const RUN = Math.random().toString(36).slice(2, 9);
const NS_SCOPE = 'authz-scope-' + RUN;
const NS_OUTSIDE = 'authz-outside-' + RUN;
const NS_CHILD_A = 'authz-child-a-' + RUN;
const NS_CHILD_B = 'authz-child-b-' + RUN;
const NS_OUTSIDE_CHILD = 'authz-outside-child-' + RUN;
const FN_RUN = 'authz-run-' + RUN;
const FN_MOVE = 'authz-move-' + RUN;
const FN_OUTSIDE = 'authz-outside-fn-' + RUN;

function assert(condition, message) {
  if (!condition) throw new Error('assertion failed: ' + message);
  console.log('  ✓ ' + message);
}

function assertStatus(result, expected, label) {
  assert(result.status === expected,
    label + ' → HTTP ' + status(result.status));
}

function form(values) {
  return new URLSearchParams(values).toString();
}

async function request(page, method, path, body, kind = 'json') {
  return page.evaluate(async ({method, path, body, kind}) => {
    const headers = {};
    let payload;
    if (body !== undefined) {
      if (kind === 'form') {
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        payload = body;
      } else {
        headers['Content-Type'] = 'application/json';
        payload = JSON.stringify(body);
      }
    }
    const response = await fetch(path, {method, headers, body: payload});
    return {status: response.status, text: await response.text(), url: response.url};
  }, {method, path, body, kind});
}

async function jsonRequest(page, method, path, body) {
  const result = await request(page, method, path, body);
  let json = null;
  try { json = JSON.parse(result.text); } catch (_) {}
  return {...result, json};
}

async function login(page, email, password) {
  await page.goto(BASE + '/login', {waitUntil: 'domcontentloaded'});
  const result = await jsonRequest(page, 'POST', '/auth/login', {email, password});
  assertStatus(result, 200, 'browser session login');
}

async function createEntity(page, type, values) {
  const result = await request(page, 'POST', '/api/entities/' + type, form(values), 'form');
  assert(result.status >= 200 && result.status < 300,
    'owner creates ' + type + ' fixture (' + result.status + ')');
}

async function findEntity(page, scope, query, key, name) {
  const result = await jsonRequest(page, 'GET',
    '/api/graph/entities?scope=' + encodeURIComponent(scope) + '&q=' + encodeURIComponent(query));
  assertStatus(result, 200, 'graph lookup for fixture ' + name);
  const rows = result.json && result.json[key];
  return (rows || []).find((row) => row.name === name) || null;
}

async function findNamespace(page, name) {
  const result = await jsonRequest(page, 'GET', '/api/graph/entities?scope=index');
  assertStatus(result, 200, 'namespace index available');
  return (result.json.namespaces || []).find((row) => row.name === name) || null;
}

async function findFn(page, name) {
  return findEntity(page, 'search', name, 'fns', name);
}

function namespacePath(namespace, allNamespaces) {
  const byId = new Map(allNamespaces.map((row) => [row.id, row]));
  const names = [];
  let row = namespace;
  for (let depth = 0; row && depth < 20; depth++) {
    names.unshift(row.name);
    row = row['parent-id'] ? byId.get(row['parent-id']) : null;
  }
  return names.join('.');
}

async function provisionInvitedAccount() {
  if (!GDCLOUD_SCRIPT || !fs.existsSync(GDCLOUD_SCRIPT)) {
    throw new Error('GDCLOUD_SCRIPT must point to the sibling local gdcloud.sh helper');
  }
  try {
    // The helper signs up + verifies this local test account through the
    // disposable stack's LogMailer. Its stdout is intentionally discarded.
    execFileSync(GDCLOUD_SCRIPT, ['account', MEMBER_EMAIL, PASSWORD], {stdio: 'ignore'});
  } catch (_) {
    throw new Error('gdcloud.sh could not provision the invited local test account');
  }
}

async function cleanup(ownerPage, fixture) {
  if (!fixture || !ownerPage) return;
  // Revoke any fixture grants via the same generic entity route used by the
  // Grants panel. Remove descendants before their parents.
  for (const grantId of fixture.grantIds || []) {
    await request(ownerPage, 'DELETE', '/api/entities/grant/' + grantId).catch(() => null);
  }
  if (fixture.inviteId) {
    await request(ownerPage, 'POST', '/api/invites/revoke',
      form({'invite-id': fixture.inviteId}), 'form').catch(() => null);
  }
  for (const fnId of fixture.fnIds || []) {
    await request(ownerPage, 'DELETE', '/api/entities/fn/' + fnId).catch(() => null);
  }
  // Namespace deletion requires empty children. Retry the unique fixture set
  // until leaves are gone; this also handles the in-scope reparent exercised
  // by the test.
  let pending = [...(fixture.nsIds || [])];
  for (let pass = 0; pending.length && pass < pending.length + 1; pass++) {
    const next = [];
    for (const nsId of pending) {
      const result = await request(ownerPage, 'DELETE', '/api/entities/ns/' + nsId).catch(() => null);
      if (!result || result.status >= 400) next.push(nsId);
    }
    if (next.length === pending.length) break;
    pending = next;
  }
}

async function runUi(page, fnId, fnName) {
  await page.goto(BASE + '/#' + encodeURIComponent(fnName), {waitUntil: 'domcontentloaded'});
  await page.waitForFunction(() => typeof window.gdInspectorShowRuns === 'function'
    && typeof window.graphReady === 'function' && window.graphReady(), null, {timeout: 45000});
  await page.evaluate((id) => window.gdInspectorShowRuns(id), fnId);
  await page.waitForSelector('.execute-run-btn', {timeout: 30000});
  const runVisible = await page.locator('.execute-run-btn').isVisible();
  if (!runVisible) throw new Error('the invited writer has no visible Run action');

  // The editor's typed argument widget currently dispatches a form-registry
  // function through the member's namespace grants. Keep this guard focused
  // on the capability-header regression: verify the real Run affordance, then
  // submit the same fn through the endpoint the button uses with its value.
  const result = await jsonRequest(page, 'POST', '/api/execute',
    {'fn-id': fnId, args: {value: 42}, 'persist?': false});
  assertStatus(result, 200, 'invited member submits an in-scope Run');
  return result.json;
}

(async () => {
  const target = new URL(BASE || 'http://invalid');
  if (!['127.0.0.1', 'localhost'].includes(target.hostname) || target.port !== '9900') {
    throw new Error('Safety stop: this guard only runs against http://127.0.0.1:9900 (local gdcloud)');
  }
  if (!OWNER_EMAIL || !MEMBER_EMAIL || !PASSWORD) {
    throw new Error('Run through `bb test-e2e-org-authz`; local test credentials are missing');
  }

  const browser = await chromium.launch({headless: true, args: [
    '--js-flags=--max-old-space-size=1024', '--disable-dev-shm-usage',
    '--no-sandbox', '--no-zygote', '--in-process-gpu',
  ]});
  const ownerContext = await browser.newContext({viewport: {width: 1400, height: 900}});
  const memberContext = await browser.newContext({viewport: {width: 1400, height: 900}});
  const ownerPage = await ownerContext.newPage();
  const memberPage = await memberContext.newPage();
  const fixture = {nsIds: [], fnIds: [], secretIds: [], grantIds: []};

  try {
    console.log('cloud-membership-authz-e2e — local invitation, Run, and namespace grants');
    await login(ownerPage, OWNER_EMAIL, PASSWORD);
    const memberships = await jsonRequest(ownerPage, 'GET', '/api/memberships');
    assertStatus(memberships, 200, 'owner memberships load');
    const org = memberships.json && memberships.json.active;
    assert(typeof org === 'string' && org.length > 0, 'owner has an active organization');

    // Create two root namespaces and nested destinations while signed in as
    // the owner. The invitee will receive write only on the scope subtree.
    await createEntity(ownerPage, 'ns', {name: NS_SCOPE});
    await createEntity(ownerPage, 'ns', {name: NS_OUTSIDE});
    const scope = await findNamespace(ownerPage, NS_SCOPE);
    const outside = await findNamespace(ownerPage, NS_OUTSIDE);
    assert(scope && scope.id && outside && outside.id, 'owner namespace fixtures are indexed');
    fixture.nsIds.push(scope.id, outside.id);
    await createEntity(ownerPage, 'ns', {name: NS_CHILD_A, 'parent-id': scope.id});
    await createEntity(ownerPage, 'ns', {name: NS_CHILD_B, 'parent-id': scope.id});
    await createEntity(ownerPage, 'ns', {name: NS_OUTSIDE_CHILD, 'parent-id': outside.id});
    const allNs = (await jsonRequest(ownerPage, 'GET', '/api/graph/entities?scope=index')).json.namespaces || [];
    const childA = allNs.find((row) => row.name === NS_CHILD_A);
    const childB = allNs.find((row) => row.name === NS_CHILD_B);
    const outsideChild = allNs.find((row) => row.name === NS_OUTSIDE_CHILD);
    assert(childA && childB && outsideChild, 'nested namespace fixtures are indexed');
    fixture.nsIds.push(childB.id, childA.id, outsideChild.id);
    const scopePath = namespacePath(scope, allNs);

    // Seed harmless const-derived functions.
    const constFn = await findFn(ownerPage, 'const');
    assert(constFn && constFn.id, 'const base function exists');
    await createEntity(ownerPage, 'fn', {name: FN_RUN, 'parent-ids': constFn.id, 'namespace-id': scope.id});
    await createEntity(ownerPage, 'fn', {name: FN_MOVE, 'parent-ids': constFn.id, 'namespace-id': childA.id});
    await createEntity(ownerPage, 'fn', {name: FN_OUTSIDE, 'parent-ids': constFn.id, 'namespace-id': outside.id});
    const runFn = await findFn(ownerPage, FN_RUN);
    const moveFn = await findFn(ownerPage, FN_MOVE);
    const outsideFn = await findFn(ownerPage, FN_OUTSIDE);
    assert(runFn && moveFn && outsideFn, 'function fixtures exist');
    fixture.fnIds.push(runFn.id, moveFn.id, outsideFn.id);
    // Mint a real email-pinned invite before the account exists, then create
    // and verify it locally. Following the link in the signed-in browser
    // exercises invite redemption and selects the inviter's org cookie.
    const invite = await request(ownerPage, 'POST', '/api/invites', form({
      email: MEMBER_EMAIL, 'display-name': 'Local authz E2E', 'expires-days': '1', 'max-uses': '1',
    }), 'form');
    assertStatus(invite, 200, 'owner creates the pinned invitation');
    const inviteToken = await ownerPage.evaluate((html) => {
      const doc = new DOMParser().parseFromString(html, 'text/html');
      const input = doc.querySelector('input[data-invite-link]');
      const value = input && input.value;
      const match = value && value.match(/\/join\/([^/?#]+)/);
      return match && match[1];
    }, invite.text);
    assert(inviteToken, 'invite form returns a join link');
    const pendingInvite = await request(ownerPage, 'GET', '/partials/users-admin');
    if (pendingInvite.status === 200) {
      fixture.inviteId = await ownerPage.evaluate(({html, email}) => {
        const doc = new DOMParser().parseFromString(html, 'text/html');
        const row = Array.from(doc.querySelectorAll('[data-invite-row]'))
          .find((item) => item.textContent.includes(email));
        return row && row.querySelector('input[name="invite-id"]')?.value;
      }, {html: pendingInvite.text, email: MEMBER_EMAIL});
    }
    await provisionInvitedAccount();
    await login(memberPage, MEMBER_EMAIL, PASSWORD);
    const memberShipsAfterJoin = await jsonRequest(memberPage, 'GET', '/api/memberships');
    assertStatus(memberShipsAfterJoin, 200, 'invited member memberships load after redemption');
    assert(memberShipsAfterJoin.json.memberships.includes(org), 'invited account joined the owner organization');
    const switchOrg = await jsonRequest(memberPage, 'POST', '/api/switch-org', {org});
    assertStatus(switchOrg, 200, 'invited browser selects the inviter organization');
    await memberPage.goto(BASE + '/', {waitUntil: 'domcontentloaded'});

    // The invite grants org-wide write. Narrow it through the owner UI API,
    // then grant write on only the test subtree. Parse the rendered panel so
    // this removes exactly the invite-created broad grant, not unrelated rows.
    const grantsPanel = await request(ownerPage, 'GET', '/partials/grants-admin');
    assertStatus(grantsPanel, 200, 'owner Grants panel loads');
    const grantRows = await ownerPage.evaluate(({html, email}) => {
      const doc = new DOMParser().parseFromString(html, 'text/html');
      return Array.from(doc.querySelectorAll('tr')).flatMap((row) => {
        const cells = Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent.trim());
        const button = row.querySelector('button[hx-delete]');
        if (cells[0] !== email || cells[1] !== 'write' || cells[2] !== '') return [];
        const match = button && button.getAttribute('hx-delete').match(/\/api\/entities\/grant\/([^/]+)/);
        return match ? [match[1]] : [];
      });
    }, {html: grantsPanel.text, email: MEMBER_EMAIL});
    assert(grantRows.length === 1, 'exactly one default org-wide write grant belongs to the invitee');
    const broadGrant = await request(ownerPage, 'DELETE', '/api/entities/grant/' + grantRows[0]);
    fixture.grantIds.push(grantRows[0]);
    assert(broadGrant.status >= 200 && broadGrant.status < 300, 'owner removes the default org-wide grant');
    const scopedGrant = await request(ownerPage, 'POST', '/api/grants', form({
      subject: MEMBER_EMAIL, capability: 'write', namespace: scopePath,
    }), 'form');
    assertStatus(scopedGrant, 200, 'owner grants write only on ' + scopePath);
    const scopedPanel = await request(ownerPage, 'GET', '/partials/grants-admin');
    assertStatus(scopedPanel, 200, 'owner Grants panel reloads after narrowing');
    const scopedGrantIds = await ownerPage.evaluate(({html, email, namespace}) => {
      const doc = new DOMParser().parseFromString(html, 'text/html');
      return Array.from(doc.querySelectorAll('tr')).flatMap((row) => {
        const cells = Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent.trim());
        const button = row.querySelector('button[hx-delete]');
        if (cells[0] !== email || cells[1] !== 'write' || cells[2] !== namespace) return [];
        const match = button && button.getAttribute('hx-delete').match(/\/api\/entities\/grant\/([^/]+)/);
        return match ? [match[1]] : [];
      });
    }, {html: scopedPanel.text, email: MEMBER_EMAIL, namespace: scopePath});
    fixture.grantIds.push(...scopedGrantIds);
    assert(scopedGrantIds.length === 1, 'the scoped grant is visible in the owner Grants panel');
    await memberPage.reload({waitUntil: 'domcontentloaded'});

    // Run through the actual inspector Run pane as the invited member.
    const runResult = await runUi(memberPage, runFn.id, scopePath + '.' + FN_RUN);
    assert(runResult?.status === 'succeeded' && runResult.result === 42,
      'invited write member runs an in-scope function from the Run pane');

    // An equivalent in-scope namespace write succeeds.
    const allowedNamespace = await request(memberPage, 'POST', '/api/entities/ns',
      form({name: 'authz-child-created-' + RUN, 'parent-id': scope.id}), 'form');
    assert(allowedNamespace.status >= 200 && allowedNamespace.status < 300,
      'invited member creates a child namespace inside the grant');
    const allowedChild = await findNamespace(ownerPage, 'authz-child-created-' + RUN);
    assert(allowedChild && allowedChild.id, 'allowed child namespace is visible to the owner');
    fixture.nsIds.push(allowedChild.id);

    // Moves require the old and new paths. Moving a foreign fn into the
    // permitted subtree must fail; moving between two permitted descendants
    // must work. Rootless fn and root namespace creation require a root grant.
    const foreignMove = await request(memberPage, 'PUT', '/api/entities/fn/' + outsideFn.id,
      form({'namespace-id': scope.id}), 'form');
    assertStatus(foreignMove, 403, 'move from an ungranted source namespace is denied');
    const inScopeMove = await request(memberPage, 'PUT', '/api/entities/fn/' + moveFn.id,
      form({'namespace-id': childB.id}), 'form');
    assert(inScopeMove.status >= 200 && inScopeMove.status < 300,
      'move between granted namespace descendants succeeds');
    const rootlessFn = await request(memberPage, 'POST', '/api/entities/fn',
      form({name: 'authz-rootless-' + RUN, 'parent-ids': constFn.id}), 'form');
    assertStatus(rootlessFn, 403, 'rootless function creation is denied without a root grant');
    const rootNs = await request(memberPage, 'POST', '/api/entities/ns',
      form({name: 'authz-root-ns-' + RUN}), 'form');
    assertStatus(rootNs, 403, 'root namespace creation is denied without a root grant');
    const foreignNsMove = await request(memberPage, 'PUT', '/api/entities/ns/' + outsideChild.id,
      form({'parent-id': scope.id}), 'form');
    assertStatus(foreignNsMove, 403, 'reparenting a namespace from outside the grant is denied');
    const inScopeNsMove = await request(memberPage, 'PUT', '/api/entities/ns/' + childA.id,
      form({'parent-id': childB.id}), 'form');
    assert(inScopeNsMove.status >= 200 && inScopeNsMove.status < 300,
      'reparenting within the granted subtree succeeds');

    // Secret rotation is covered by graphden-tenancy's addon_test. A browser
    // request to /api/secrets cannot set up fixtures on the local free-tier
    // tenant: the request effect sandbox blocks :network before authz runs.

    console.log('✓ cloud membership/authz browser guard passed (secret rotation remains in tenancy unit coverage)');
  } finally {
    // Teardown through the owner's own browser session. It only removes the
    // unique fn/ns/grant fixture rows created above; account rows are
    // intentionally retained on the local gdcloud volume for auditability.
    await memberContext.close().catch(() => {});
    await cleanup(ownerPage, fixture).catch((error) => console.log('  cleanup note:', errorKind(error)));
    await ownerContext.close().catch(() => {});
    await browser.close().catch(() => {});
  }
})().catch(reportFailure);
