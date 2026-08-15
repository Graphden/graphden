// Editor Account — the /account page merged into Settings (2026-08-15).
//
// Fills the #gd-set-account card on the Settings surface with the account
// sections the standalone page used to own: identity + verify-email banner,
// sign-in methods (linked identities, unlink, provider link buttons), 2FA
// enrollment, and self-serve API tokens (shown only where the tenancy addon
// serves /api/my-tokens). The card stays hidden unless the open accounts
// addon answered the boot probe (editor-auth.js's accountsMode); the
// standalone /account page remains as the headless-deployment fallback.
//
// graph-first-exception: everything here is the CALLER's session state read
// from the accounts addon's /auth/* JSON API (cookie-authenticated) — a graph
// partial can't render another principal's session, so the card body is
// client-built from the same endpoints the standalone page used.

function gdAcctEsc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
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

async function gdAcctLoadIdents() {
  const host = document.getElementById('gd-acct-idents');
  if (!host) return;
  const [s, j] = await gdAcctGet('/auth/identities');
  if (s !== 200 || !Array.isArray(j.identities)) { host.textContent = '—'; return; }
  const n = j.identities.length;
  host.innerHTML = j.identities.map((i) =>
    "<div class='gd-acct-row'><div class='gd-set-copy'>"
    + "<div class='gd-set-label'>" + gdAcctEsc(i.provider) + '</div>'
    + "<div class='gd-set-hint'>" + gdAcctEsc(i.email || '')
    + (i.provider === 'password' && !i['email-verified?'] ? ' · unverified' : '') + '</div></div>'
    + (n > 1
      ? "<button type='button' class='gd-set-btn' onclick=\"gdAcctUnlink('" + gdAcctEsc(i.provider) + "')\">Unlink</button>"
      : '')
    + '</div>').join('');
}

async function gdAcctUnlink(provider) {
  const [s] = await gdAcctPost('/auth/unlink', { provider });
  if (s === 200) { gdAcctLoadIdents(); gdAcctSay('Unlinked ' + provider + '.', true); }
  else gdAcctSay('Could not unlink.');
}

// Provider LINK buttons — same /auth/<p>/start flow as sign-in; while a
// session cookie is live the callback links the identity to this account.
async function gdAcctRenderLinks() {
  const host = document.getElementById('gd-acct-links');
  if (!host) return;
  const [s, j] = await gdAcctGet('/auth/providers');
  if (s !== 200 || !j.providers) return;
  let html = '';
  if (j.providers.github) html += "<a class='gd-set-btn' href='/auth/github/start'>Link GitHub</a>";
  if (j.providers.google) html += "<a class='gd-set-btn' href='/auth/google/start'>Link Google</a>";
  host.innerHTML = html;
}

// ---- Two-factor ------------------------------------------------------------

async function gdAcctLoadTfa() {
  const host = document.getElementById('gd-acct-tfa');
  if (!host) return;
  const [, j] = await gdAcctGet('/auth/tfa-state');
  host.innerHTML = j?.enabled
    ? "<div class='gd-set-hint'>Two-factor is <b>on</b>.</div>"
      + "<button type='button' class='gd-set-btn' onclick='gdAcctTotpDisable()'>Disable 2FA</button>"
    : "<div class='gd-set-hint'>Add a second factor with an authenticator app.</div>"
      + "<button type='button' class='gd-set-btn' onclick='gdAcctTotpEnroll()'>Enable 2FA</button>";
}

async function gdAcctTotpEnroll() {
  const host = document.getElementById('gd-acct-tfa');
  const [s, j] = await gdAcctPost('/auth/totp/enroll');
  if (s !== 200 || !host) { gdAcctSay('Could not start enrollment.'); return; }
  host.innerHTML =
    "<div class='gd-set-hint'>Add this key to your authenticator app, then enter the code.</div>"
    + "<code class='gd-set-code gd-acct-secret'>" + gdAcctEsc(j.secret) + '</code>'
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

async function gdAcctLoadTokens() {
  const sec = document.getElementById('gd-acct-tok-sec');
  const host = document.getElementById('gd-acct-toks');
  if (!sec || !host) return;
  const [s, j] = await gdAcctGet('/api/my-tokens/list'); // api-url-drift-allow: route-collection
  if (s !== 200 || !Array.isArray(j)) return; // addon absent → section stays hidden
  sec.hidden = false;
  host.innerHTML = j.length
    ? j.map((t) =>
      "<div class='gd-acct-row'><div class='gd-set-copy'>"
      + "<div class='gd-set-label'>" + (gdAcctEsc(t.label) || '(unlabeled)') + '</div>'
      + "<div class='gd-set-hint'>" + gdAcctEsc(t.scopes || 'unscoped') + ' · '
      + (t['expires-at'] ? 'expires ' + new Date(t['expires-at']).toISOString().slice(0, 10) : 'no expiry')
      + '</div></div>'
      + "<button type='button' class='gd-set-btn' onclick=\"gdAcctRevokeToken('" + gdAcctEsc(t.id) + "')\">Revoke</button></div>").join('')
    : "<div class='gd-set-hint'>No API tokens yet.</div>";
}

async function gdAcctMintToken() {
  const label = document.getElementById('gd-acct-tok-label')?.value.trim();
  const scopes = [...document.querySelectorAll('#gd-acct-tok-scopes input:checked')]
    .map((c) => c.value).join(' ');
  if (!scopes) { gdAcctSay('Pick at least one scope.'); return; }
  const ttl = document.getElementById('gd-acct-tok-ttl')?.value;
  const [s, j] = await gdAcctPostForm('/api/my-tokens', { label, scopes, 'ttl-days': ttl }); // api-url-drift-allow: route-collection
  if (s !== 200 || !j.token) { gdAcctSay('Could not create a token.'); return; }
  const reveal = document.getElementById('gd-acct-tok-reveal');
  if (reveal) {
    reveal.innerHTML =
      "<div class='gd-set-hint'>Copy your new token now — it will not be shown again:</div>"
      + "<code class='gd-set-code gd-acct-secret'>" + gdAcctEsc(j.token) + '</code>'
      + "<button type='button' class='gd-set-btn' onclick='gdAcctCopyToken(this)'>Copy</button>";
  }
  const labelInput = document.getElementById('gd-acct-tok-label');
  if (labelInput) labelInput.value = '';
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
  const [s] = await gdAcctPostForm('/api/my-tokens/revoke', { id }); // api-url-drift-allow: route-collection
  if (s === 200) {
    const reveal = document.getElementById('gd-acct-tok-reveal');
    if (reveal) reveal.innerHTML = '';
    gdAcctLoadTokens();
    gdAcctSay('Token revoked.', true);
  } else {
    gdAcctSay('Could not revoke that token.');
  }
}

// ---- Card render (called by gdRenderSettings on every Settings open) -------

async function gdRenderAccountCard() {
  const card = document.getElementById('gd-set-account');
  const root = document.getElementById('gd-acct-root');
  if (!card || !root) return;
  const accounts = (typeof window.graphdenAccountsMode === 'function') && window.graphdenAccountsMode();
  card.hidden = !accounts;
  if (!accounts) return;

  const [s, j] = await gdAcctGet('/auth/me');
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
    + "<div id='gd-acct-idents' class='gd-set-hint'>Loading…</div>"
    + "<div id='gd-acct-links' class='gd-acct-btns'></div>"
    + "<h3 class='gd-acct-h'>Two-factor authentication</h3>"
    + "<div id='gd-acct-tfa' class='gd-set-hint'>Loading…</div>"
    + "<div id='gd-acct-tok-sec' hidden>"
    +   "<h3 class='gd-acct-h'>API tokens</h3>"
    +   "<div class='gd-set-hint'>Long-lived keys for MCP and API clients, sent as <code>Authorization: Bearer</code>. A token can only narrow your access: pick its scopes and lifetime.</div>"
    +   "<div id='gd-acct-tok-reveal'></div>"
    +   "<div id='gd-acct-toks' class='gd-set-hint'>Loading…</div>"
    +   "<div class='gd-acct-scopes' id='gd-acct-tok-scopes'>"
    +     "<label><input type='checkbox' value='write' checked> Edit graph &amp; branches</label>"
    +     "<label><input type='checkbox' value='execute' checked> Execute functions</label>"
    +     "<label><input type='checkbox' value='merge'> Merge branches</label>"
    +     "<label><input type='checkbox' value='services'> Manage services</label>"
    +     "<label><input type='checkbox' value='secrets'> Write secrets</label>"
    +     "<label><input type='checkbox' value='packages'> Publish packages</label>"
    +   '</div>'
    +   "<div class='gd-acct-mint'>"
    +     "<select id='gd-acct-tok-ttl' aria-label='Token lifetime'>"
    +       "<option value='7'>7 days</option><option value='30'>30 days</option>"
    +       "<option value='90' selected>90 days</option><option value='365'>1 year</option>"
    +       "<option value=''>Never</option>"
    +     '</select>'
    +     "<input type='text' id='gd-acct-tok-label' class='gd-acct-input' placeholder='Label (e.g. laptop MCP)'>"
    +     "<button type='button' class='gd-set-btn' onclick='gdAcctMintToken()'>Create</button>"
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
  gdAcctLoadIdents();
  gdAcctRenderLinks();
  gdAcctLoadTfa();
  gdAcctLoadTokens();
}

async function gdAcctResendVerify() {
  await gdAcctPost('/auth/resend-verification');
  gdAcctSay('If your email is unverified, a new link is on its way.', true);
}

window.gdRenderAccountCard = gdRenderAccountCard;
