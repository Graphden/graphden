// Editor Account — the /account page merged into Settings.
//
// Fills the #gd-set-account card on the Settings surface with the account
// sections the standalone page used to own: identity + verify-email banner,
// sign-in methods (linked identities, unlink, provider link buttons), 2FA
// enrollment, and self-serve API tokens (shown only where the tenancy addon
// serves /api/my-tokens). The card stays hidden unless the open accounts
// addon answered the boot probe (editor-auth.js's accountsMode); the
// standalone /account page remains as the headless-deployment fallback.
//
// The sign-in methods view is a typed browser graph over the caller's
// session-scoped /auth/* responses. Fetches, credentials and writes stay here.

// Attach a delegated click handler to `host` exactly once (guarded via a
// dataset flag so repeated list re-renders don't stack listeners). Reading
// the datum from `dataset` keeps dynamic values out of inline onclick
// JS-strings — gdEscapeHtml escapes for HTML attributes, not JS-string context.
function gdAcctDelegate(host, selector, handler) {
  if (!host || host.dataset.gdDelegated) return;
  host.dataset.gdDelegated = '1';
  host.addEventListener('click', (e) => {
    const el = e.target.closest(selector);
    if (el && host.contains(el)) handler(el, e);
  });
}

// YYYY-MM-DD for a date string, or 'unknown' for an unparseable one — a bad
// row must not throw out of the whole `.map(...)` and freeze the panel.
function gdAcctFmtDate(s) {
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? 'unknown' : d.toISOString().slice(0, 10);
}

function gdAcctSay(text, ok) {
  const m = document.getElementById('gd-acct-msg');
  if (!m) return;
  m.textContent = text;
  m.className = 'gd-acct-msg ' + (ok ? 'ok' : 'err');
}

async function gdAcctGet(url) {
  const r = await fetch(url);
  return [r.status, await r.json().catch(() => ({}))];
}

async function gdAcctPost(url, body) {
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  });
  return [r.status, await r.json().catch(() => ({}))];
}

async function gdAcctPostForm(url, fields) {
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(fields).toString(),
  });
  return [r.status, await r.json().catch(() => ({}))];
}

// ---- Sign-in methods -------------------------------------------------------

let _gdAcctMethodsEpoch = 0;

function gdAcctGraphMethods(identities, links) {
  const api = window.GraphdenBrowser;
  const key = api.keyword;
  const record = value => new Map(Object.entries(value).map(([name, item]) => [key(name), item]));
  const inputs = record({identities: identities.map(record), links});
  const builtin = api.createRuntime(window.GraphdenBuiltinPlans.plans.accountMethods,
    {operationLimit: 100000});
  let selected = null;
  try { selected = window.gdEditorRoot?.componentNow('account-methods') || null; }
  catch (_) { window.gdUIComponentFailed?.('account-methods'); }
  function render(runtime) {
    const output = runtime.run('view', {inputs});
    if (!(output instanceof Map) || output.size !== 1 || !output.has(key('tree'))) {
      throw new Error('Invalid account methods graph view');
    }
    const vnode = window.GraphdenRenderer.vnode(output.get(key('tree')), 'gd-acct', 3000);
    const scratch = document.createElement('div');
    window.GraphdenPreact.render(vnode, scratch);
    const tree = scratch.firstElementChild || scratch.firstChild;
    const rows = tree?.querySelectorAll('#gd-acct-idents .gd-acct-row') || [];
    const unlink = tree?.querySelectorAll('[data-unlink-provider]') || [];
    const linkButtons = tree?.querySelectorAll('[data-link-provider]') || [];
    const providers = new Set(identities.map(i => i.provider));
    const unlinkTargets = new Set([...unlink].map(el => el.dataset.unlinkProvider));
    const allowedLinks = new Set(links);
    if (!tree?.classList.contains('gd-acct-methods')
        || tree.querySelectorAll('#gd-acct-idents').length !== 1
        || tree.querySelectorAll('#gd-acct-links').length !== 1
        || rows.length !== identities.length
        || unlink.length !== (identities.length > 1 ? identities.length : 0)
        || unlinkTargets.size !== unlink.length
        || (identities.length > 1 && [...providers].some(provider => !unlinkTargets.has(provider)))
        || linkButtons.length !== links.length
        || [...linkButtons].some(el => el.tagName !== 'BUTTON' || !allowedLinks.has(el.dataset.linkProvider))
        || new Set([...linkButtons].map(el => el.dataset.linkProvider)).size !== links.length) {
      throw new Error('Invalid account methods graph rows');
    }
    for (const button of unlink) {
      const provider = button.dataset.unlinkProvider;
      button.addEventListener('click', () => gdAcctUnlink(provider));
    }
    for (const button of linkButtons) {
      const provider = button.dataset.linkProvider;
      button.addEventListener('click', () => {
        window.location.href = '/auth/' + provider + '/start';
      });
    }
    return tree;
  }
  if (selected) {
    try {
      const tree = render(selected);
      if (!window.gdEditorRoot.acceptComponent(selected)) throw new Error('Editor root changed');
      return tree;
    } catch (_) {
      window.gdEditorRoot?.releaseComponent(selected);
      window.gdUIComponentFailed?.('account-methods');
    }
  }
  return render(builtin);
}

async function gdAcctLoadMethods() {
  const host = document.getElementById('gd-acct-methods-mount');
  if (!host) return;
  const epoch = ++_gdAcctMethodsEpoch;
  const principal = gdAcctTokenPrincipal();
  let identityResponse;
  let providerResponse;
  try {
    [identityResponse, providerResponse] = await Promise.all([
      gdAcctGet('/auth/identities'), gdAcctGet('/auth/providers').catch(() => [0, {}]),
    ]);
  } catch (_) {
    if (epoch === _gdAcctMethodsEpoch && gdAcctTokenPrincipalMatches(principal)) host.textContent = '—';
    return;
  }
  if (epoch !== _gdAcctMethodsEpoch || !gdAcctTokenPrincipalMatches(principal)
      || document.getElementById('gd-acct-methods-mount') !== host) return;
  const [identityStatus, identityData] = identityResponse;
  const [providerStatus, providerData] = providerResponse;
  if (identityStatus !== 200 || !Array.isArray(identityData.identities)) {
    host.textContent = '—';
    return;
  }
  if (!identityData.identities.every(i => i && typeof i === 'object')) {
    host.textContent = '—';
    return;
  }
  const count = identityData.identities.length;
  const identities = identityData.identities.map(i => ({
    provider: String(i.provider || ''), email: String(i.email || ''),
    verified: !!i['email-verified?'], 'can-unlink': count > 1,
  }));
  const links = providerStatus === 200
    ? ['github', 'google'].filter(provider => providerData.providers?.[provider]) : [];
  host.replaceChildren(gdAcctGraphMethods(identities, links));
}

async function gdAcctUnlink(provider) {
  const principal = gdAcctTokenPrincipal();
  const [s] = await gdAcctPost('/auth/unlink', { provider });
  if (!gdAcctTokenPrincipalMatches(principal)) return;
  if (s === 200) { gdAcctLoadMethods(); gdAcctSay('Unlinked ' + provider + '.', true); }
  else gdAcctSay('Could not unlink.');
}

// ---- Two-factor ------------------------------------------------------------

let _gdAcctTfaEpoch = 0;

function gdAcctGraphSecurity(kind, enabled, tokens) {
  const api = window.GraphdenBrowser;
  const key = api.keyword;
  const record = value => new Map(Object.entries(value).map(([name, item]) => [key(name), item]));
  const inputs = record({kind, enabled, tokens: tokens.map(record)});
  const builtin = api.createRuntime(window.GraphdenBuiltinPlans.plans.accountSecurity,
    {operationLimit: 100000});
  let selected = null;
  try { selected = window.gdEditorRoot?.componentNow('account-security') || null; }
  catch (_) { window.gdUIComponentFailed?.('account-security'); }
  function render(runtime) {
    const output = runtime.run('view', {inputs});
    if (!(output instanceof Map) || output.size !== 1 || !output.has(key('tree'))) {
      throw new Error('Invalid account security graph view');
    }
    const vnode = window.GraphdenRenderer.vnode(output.get(key('tree')), 'gd-acct', 3000);
    const scratch = document.createElement('div');
    window.GraphdenPreact.render(vnode, scratch);
    const tree = scratch.firstElementChild || scratch.firstChild;
    if (kind === 'tfa') {
      const buttons = tree?.querySelectorAll('[data-tfa-action]') || [];
      if (!tree?.classList.contains('gd-acct-tfa-state') || buttons.length !== 1
          || tree.querySelectorAll('button').length !== 1
          || buttons[0].tagName !== 'BUTTON' || buttons[0].getAttribute('type') !== 'button'
          || buttons[0].dataset.tfaAction !== (enabled ? 'disable' : 'enroll')) {
        throw new Error('Invalid account security 2FA controls');
      }
      buttons[0].addEventListener('click', enabled ? gdAcctTotpDisable : gdAcctTotpEnroll);
    } else {
      const rows = tree?.querySelectorAll('.gd-acct-row') || [];
      const buttons = tree?.querySelectorAll('[data-revoke-token]') || [];
      if (!tree?.classList.contains('gd-acct-token-list') || rows.length !== tokens.length
          || buttons.length !== tokens.length
          || tree.querySelectorAll('button').length !== tokens.length
          || tokens.some((token, index) => rows[index].dataset.tokenId !== token.id
            || buttons[index].tagName !== 'BUTTON' || buttons[index].getAttribute('type') !== 'button'
            || buttons[index].dataset.revokeToken !== token.id
            || !rows[index].contains(buttons[index]))) {
        throw new Error('Invalid account security token controls');
      }
    }
    return tree;
  }
  if (selected) {
    try {
      const tree = render(selected);
      if (!window.gdEditorRoot.acceptComponent(selected)) throw new Error('Editor root changed');
      return tree;
    } catch (_) {
      window.gdEditorRoot?.releaseComponent(selected);
      window.gdUIComponentFailed?.('account-security');
    }
  }
  return render(builtin);
}

async function gdAcctLoadTfa() {
  const host = document.getElementById('gd-acct-tfa');
  if (!host) return;
  const epoch = ++_gdAcctTfaEpoch;
  const principal = gdAcctTokenPrincipal();
  let status;
  let state;
  try { [status, state] = await gdAcctGet('/auth/tfa-state'); }
  catch (_) { status = 0; }
  if (epoch !== _gdAcctTfaEpoch || !gdAcctTokenPrincipalMatches(principal)
      || document.getElementById('gd-acct-tfa') !== host) return;
  if (status !== 200 || typeof state?.enabled !== 'boolean') { host.textContent = '—'; return; }
  host.replaceChildren(gdAcctGraphSecurity('tfa', state.enabled, []));
}

async function gdAcctTotpEnroll() {
  const host = document.getElementById('gd-acct-tfa');
  const epoch = ++_gdAcctTfaEpoch;
  const principal = gdAcctTokenPrincipal();
  const [s, j] = await gdAcctPost('/auth/totp/enroll');
  if (epoch !== _gdAcctTfaEpoch || !gdAcctTokenPrincipalMatches(principal)
      || document.getElementById('gd-acct-tfa') !== host) return;
  if (s !== 200 || !host) { gdAcctSay('Could not start enrollment.'); return; }
  host.innerHTML =
    "<div class='gd-set-hint'>Add this key to your authenticator app, then enter the code.</div>"
    + "<code class='gd-set-code gd-acct-secret'>" + gdEscapeHtml(j.secret) + '</code>'
    + "<input type='text' id='gd-acct-ecode' class='gd-acct-input' inputmode='numeric' placeholder='123456'>"
    + "<button type='button' class='gd-set-btn' onclick='gdAcctTotpConfirm()'>Confirm &amp; enable</button>";
}

async function gdAcctTotpConfirm() {
  const code = document.getElementById('gd-acct-ecode')?.value;
  const [s] = await gdAcctPost('/auth/totp/confirm', { code });
  if (s === 200) { gdAcctSay('Two-factor enabled.', true); gdAcctLoadTfa(); }
  else gdAcctSay('That code did not match.');
}

async function gdAcctTotpDisable() {
  const code = prompt('Enter a current authenticator code to disable 2FA:');
  if (!code) return;
  const [s] = await gdAcctPost('/auth/totp/disable', { code });
  if (s === 200) { gdAcctSay('Two-factor disabled.', true); gdAcctLoadTfa(); }
  else gdAcctSay('That code did not match.');
}

// ---- API tokens (tenancy addon only — probe reveals the section) -----------

let _gdAcctTokenEpoch = 0;
let _gdAcctTokenListEpoch = 0;

function gdAcctTokenPrincipal() {
  return typeof _tourSessionPrincipal === 'function' ? _tourSessionPrincipal()
    : {accountId: window.gdAccount?.id || null, orgId: null};
}

function gdAcctTokenPrincipalMatches(principal) {
  const current = gdAcctTokenPrincipal();
  return current.accountId === principal.accountId && current.orgId === principal.orgId;
}

async function gdAcctLoadTokens() {
  const sec = document.getElementById('gd-acct-tok-sec');
  const host = document.getElementById('gd-acct-toks');
  if (!sec || !host) return;
  const epoch = ++_gdAcctTokenListEpoch;
  const principal = gdAcctTokenPrincipal();
  const [s, j] = await gdAcctGet('/api/my-tokens/list'); // api-url-drift-allow: route-collection
  if (epoch !== _gdAcctTokenListEpoch || !gdAcctTokenPrincipalMatches(principal)
      || document.getElementById('gd-acct-toks') !== host) return;
  if (s !== 200 || !Array.isArray(j)) return; // addon absent → section stays hidden
  if (!j.every(t => t && typeof t.id === 'string' && t.id && typeof t === 'object')
      || new Set(j.map(t => t.id)).size !== j.length) { host.textContent = '—'; return; }
  const tokens = j.map(t => ({id: t.id, label: String(t.label || ''),
    scopes: String(t.scopes || ''),
    expiry: t['expires-at'] ? gdAcctFmtDate(t['expires-at']) : ''}));
  const tree = gdAcctGraphSecurity('tokens', false, tokens);
  sec.hidden = false;
  host.replaceChildren(tree);
  if (typeof _tourTokenEntry === 'function') {
    const rows = tree.querySelectorAll('.gd-acct-row');
    j.forEach((token, index) => {
      if (_tourTokenEntry(token.label)?.id === token.id) rows[index].dataset.tourToken = '';
    });
  }
  gdAcctDelegate(host, '[data-revoke-token]', (el) => gdAcctRevokeToken(el.dataset.revokeToken));
}

// Show/hide the mint form (disclosure pattern — aria-expanded mirrors state).
function gdAcctToggleMint() {
  const btn = document.getElementById('gd-acct-tok-new');
  const form = document.getElementById('gd-acct-mint-form');
  if (!btn || !form) return;
  form.hidden = !form.hidden;
  btn.setAttribute('aria-expanded', String(!form.hidden));
}

async function gdAcctMintToken() {
  const label = document.getElementById('gd-acct-tok-label')?.value.trim();
  const scopes = [...document.querySelectorAll('#gd-acct-tok-scopes input:checked')]
    .map((c) => c.value).join(' ');
  if (!scopes) { gdAcctSay('Pick at least one scope.'); return; }
  const ttl = document.getElementById('gd-acct-tok-ttl')?.value;
  const epoch = ++_gdAcctTokenEpoch;
  const principal = gdAcctTokenPrincipal();
  const reveal = document.getElementById('gd-acct-tok-reveal');
  if (reveal) { reveal.innerHTML = ''; delete reveal.dataset.tokenId; }
  const ticket = typeof gdTourBeginTokenCreation === 'function'
    ? gdTourBeginTokenCreation(label, scopes, ttl) : null;
  const fields = {label, scopes, 'ttl-days': ttl, ...(ticket ? {id: ticket.id} : {})};
  let s;
  let j;
  try {
    [s, j] = await gdAcctPostForm('/api/my-tokens', fields); // api-url-drift-allow: route-collection
  } catch (_) {
    if (epoch === _gdAcctTokenEpoch && gdAcctTokenPrincipalMatches(principal)) {
      gdAcctSay(ticket ? 'Creation reply unavailable. Lessons retains this token’s exact ID for cleanup.'
        : 'Creation reply unavailable. Refresh the token list before retrying.');
    }
    return;
  }
  if (ticket) {
    if (s === 200) gdTourRecordTokenCreation(ticket, j);
    else gdTourRejectTokenCreation(ticket, s);
  }
  if (epoch !== _gdAcctTokenEpoch || !gdAcctTokenPrincipalMatches(principal)
      || document.getElementById('gd-acct-tok-reveal') !== reveal) return;
  if (s !== 200 || !j.token) { gdAcctSay('Could not create a token.'); return; }
  if (reveal) {
    reveal.dataset.tokenId = j.id;
    reveal.innerHTML =
      "<div class='gd-set-hint'>Copy your new token now — it will not be shown again:</div>"
      + "<code class='gd-set-code gd-acct-secret'>" + gdEscapeHtml(j.token) + '</code>'
      + "<button type='button' class='gd-set-btn' onclick='gdAcctCopyToken(this)'>Copy</button>"
      + (ticket ? "<button type='button' class='gd-set-btn' data-token-check onclick='gdTourCheckRevealedToken()'>Check restricted access</button>" : '');
  }
  const labelInput = document.getElementById('gd-acct-tok-label');
  if (labelInput) labelInput.value = '';
  // Fold the form away and land the keyboard on Copy — the one-time reveal
  // is the only thing that matters right now.
  const form = document.getElementById('gd-acct-mint-form');
  if (form && !form.hidden) gdAcctToggleMint();
  const copyBtn = document.querySelector('#gd-acct-tok-reveal button');
  if (copyBtn && typeof focusSafely === 'function') focusSafely(copyBtn);
  gdAcctLoadTokens();
  gdAcctSay('Token created.', true);
}

function gdAcctCopyToken(btn) {
  const code = document.querySelector('#gd-acct-tok-reveal code');
  if (!code) return;
  navigator.clipboard.writeText(code.textContent).then(() => { btn.textContent = 'Copied'; });
}

async function gdAcctRevokeToken(id) {
  if (!confirm('Revoke this token? Anything still using it will stop working.')) return;
  const principal = gdAcctTokenPrincipal();
  const reveal = document.getElementById('gd-acct-tok-reveal');
  const bearer = reveal?.dataset.tokenId === id ? reveal.querySelector('code')?.textContent : null;
  const [s] = await gdAcctPostForm('/api/my-tokens/revoke', { id }); // api-url-drift-allow: route-collection
  if (!gdAcctTokenPrincipalMatches(principal)) return;
  if (s === 200) {
    ++_gdAcctTokenEpoch;
    if (reveal) { reveal.innerHTML = ''; delete reveal.dataset.tokenId; }
    if (typeof gdTourRecordTokenRevocation === 'function') {
      try {
        await gdTourRecordTokenRevocation(id);
        if (bearer) await gdTourProbeTokenAccess(id, bearer, true);
      } catch (_) { /* Listing/verification can retry; the revoke was already committed. */ }
    }
    if (!gdAcctTokenPrincipalMatches(principal)) return;
    gdAcctLoadTokens();
    gdAcctSay('Token revoked.', true);
  } else {
    gdAcctSay('Could not revoke that token.');
  }
}

// ---- Card render (called by gdRenderSettings on every Settings open) -------

let _gdAcctCardEpoch = 0;

async function gdRenderAccountCard() {
  const epoch = ++_gdAcctCardEpoch;
  ++_gdAcctMethodsEpoch;
  ++_gdAcctTfaEpoch;
  ++_gdAcctTokenEpoch;
  ++_gdAcctTokenListEpoch;
  const card = document.getElementById('gd-set-account');
  const navBtn = document.querySelector('#gd-settings-nav [data-section="account"]');
  const root = document.getElementById('gd-acct-root');
  if (!card || !root) return;
  // Await the boot probe rather than sampling accountsMode: a deep-linked
  // `@settings/account` routes before the probe answers, and the nav entry's
  // visibility must reflect the settled answer, not the race.
  const accounts = window.gdAccountsReady
    ? await window.gdAccountsReady
    : ((typeof window.graphdenAccountsMode === 'function') && window.graphdenAccountsMode());
  if (epoch !== _gdAcctCardEpoch) return;
  // Visibility belongs to the NAV entry; the pane itself is shown/hidden by
  // the section switcher (activateOpSection via gdActivateSettingsSection).
  if (navBtn) navBtn.hidden = !accounts;
  // Re-assert a remembered 'account' choice now that availability is known —
  // upgrades a deep link out of its Appearance fallback (or confirms it).
  if (typeof window.gdSettingsSection === 'function' && window.gdSettingsSection() === 'account'
      && typeof window.gdActivateSettingsSection === 'function') {
    window.gdActivateSettingsSection('account');
  }
  if (!accounts) return;

  const [s, j] = await gdAcctGet('/auth/me');
  if (epoch !== _gdAcctCardEpoch || document.getElementById('gd-acct-root') !== root) return;
  if (s !== 200 || !j.account) {
    root.innerHTML =
      "<div class='gd-set-hint'>Sign in to manage your sign-in methods, two-factor auth and API tokens.</div>"
      + "<a class='gd-set-btn' href='/login'>Sign in</a>";
    return;
  }

  root.innerHTML =
    "<div class='gd-set-hint' id='gd-acct-who'></div>"
    + "<div id='gd-acct-verify'></div>"
    + "<h3 class='gd-acct-h'>Sign-in methods</h3>"
    + "<div id='gd-acct-methods-mount' class='gd-set-hint'>Loading…</div>"
    + "<h3 class='gd-acct-h'>Two-factor authentication</h3>"
    + "<div id='gd-acct-tfa' class='gd-set-hint'>Loading…</div>"
    + "<div id='gd-acct-tok-sec' hidden>"
    +   "<h3 class='gd-acct-h'>API tokens</h3>"
    +   "<div class='gd-set-hint'>Long-lived keys for MCP and API clients, sent as <code>Authorization: Bearer</code>. A token can only narrow your access: pick its scopes and lifetime.</div>"
    +   "<div id='gd-acct-tok-reveal'></div>"
    +   "<div id='gd-acct-toks' class='gd-set-hint'>Loading…</div>"
    // The mint form sits behind a disclosure: reading what tokens exist is
    // the common visit, composing a new one is the rare act — an always-open
    // six-checkbox form only pulled attention from everything else.
    +   "<button type='button' class='gd-set-btn' id='gd-acct-tok-new' aria-expanded='false'"
    +     " aria-controls='gd-acct-mint-form' onclick='gdAcctToggleMint()'>Create token…</button>"
    +   "<div id='gd-acct-mint-form' hidden>"
    +     "<div class='gd-acct-scopes' id='gd-acct-tok-scopes'>"
    +       "<label><input type='checkbox' value='write' checked> Edit graph &amp; branches</label>"
    +       "<label><input type='checkbox' value='execute' checked> Execute functions</label>"
    +       "<label><input type='checkbox' value='merge'> Merge branches</label>"
    +       "<label><input type='checkbox' value='services'> Manage services</label>"
    +       "<label><input type='checkbox' value='secrets'> Write secrets</label>"
    +       "<label><input type='checkbox' value='packages'> Publish packages</label>"
    +     '</div>'
    +     "<div class='gd-acct-mint'>"
    +       "<select id='gd-acct-tok-ttl' aria-label='Token lifetime'>"
    +         "<option value='7'>7 days</option><option value='30'>30 days</option>"
    +         "<option value='90' selected>90 days</option><option value='365'>1 year</option>"
    +         "<option value=''>Never</option>"
    +       '</select>'
    +       "<input type='text' id='gd-acct-tok-label' class='gd-acct-input' placeholder='Label (e.g. laptop MCP)'>"
    +       "<button type='button' class='gd-set-btn' onclick='gdAcctMintToken()'>Create</button>"
    +     '</div>'
    +   '</div>'
    + '</div>'
    + "<div id='gd-acct-msg' class='gd-acct-msg'></div>";

  const who = document.getElementById('gd-acct-who');
  if (who) who.textContent = j.account.email || '(email not verified yet)';
  if (!j.account.email) {
    const banner = document.getElementById('gd-acct-verify');
    if (banner) {
      banner.innerHTML =
        "<div class='gd-acct-banner'><b>Verify your email.</b> Check your inbox for the link. Didn't get it? "
        + "<button type='button' class='gd-set-btn' onclick='gdAcctResendVerify()'>Resend verification email</button></div>";
    }
  }
  gdAcctLoadMethods();
  gdAcctLoadTfa();
  gdAcctLoadTokens();
}

async function gdAcctResendVerify() {
  await gdAcctPost('/auth/resend-verification');
  gdAcctSay('If your email is unverified, a new link is on its way.', true);
}

window.gdRenderAccountCard = gdRenderAccountCard;
if (typeof window.addEventListener === 'function') {
  window.addEventListener('gd-auth-changed', () => {
    ++_gdAcctCardEpoch;
    ++_gdAcctMethodsEpoch;
    ++_gdAcctTfaEpoch;
    ++_gdAcctTokenEpoch;
    ++_gdAcctTokenListEpoch;
    document.getElementById('gd-acct-root')?.replaceChildren();
    if (document.getElementById('gd-set-account')?.hidden === false) gdRenderAccountCard();
  });
}
