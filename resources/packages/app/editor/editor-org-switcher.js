// Editor Org-switcher — the top-bar ORG chip: where you are, and the way to
// somewhere else (Slack-style multi-org, Track B/C).
//
// Fetches GET /api/memberships ({ok, memberships, orgs, active} — served by
// the tenancy auth-routes Ring router; the session cookie authenticates it,
// so no window.API key is involved) and mounts the chip in #org-mount for
// EVERY member, single-org accounts included: the org is the outermost
// context you write into (org → branch → workspace) and it has to be readable
// at a glance, not only when there is something to switch to. The popover
// lists the account's orgs (owner badge, plan), and ends with
// "New organization…" — self-serve creation through POST /api/my-orgs (the
// caller becomes owner + admin), then a switch into the new org.
//
// Track C model A: each org's editor is its own subdomain ORIGIN
// (<org>.graphden.dev), and the request-scope resolves the org from the Host
// when you're a member — so picking another org NAVIGATES to that org's
// subdomain (the accounts session cookie is origin-scoped like any cookie).
// On a single-host dev instance (localhost / no derivable base domain) the
// fallback is POST /api/switch-org, which sets the gd_org selector cookie
// and reloads — sessions are org-agnostic, no re-mint involved.
//
// Globals consumed: authFetch (bearer/cookie-transparent fetch),
// installPopoverDismiss / focusIntoDialog (graphden-popover).

// Builds the popover's contents from a plain model — pure DOM, so it runs
// under tools/runtime-test/mini-dom too.
//   model:    {orgs: [{name, 'owner?', plan}], current}  (the /api/memberships rows)
//   handlers: {onSwitch(name), onCreate(name) → Promise<{ok, org?, message?}>}
// Returns {showForm, hideForm} so the owner can reset the inline form on close.
function gdRenderOrgPopover(popover, model, handlers) {
  popover.replaceChildren();
  const orgs = Array.isArray(model?.orgs) ? model.orgs : [];
  const current = model?.current;

  const head = document.createElement('div');
  head.className = 'org-popover-head';
  head.textContent = 'Organizations';
  popover.appendChild(head);

  const list = document.createElement('div');
  list.className = 'org-popover-list';
  for (const org of orgs) {
    const isCurrent = org.name === current;
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'org-popover-item' + (isCurrent ? ' org-popover-item-current' : '');
    if (isCurrent) item.setAttribute('aria-current', 'true');
    item.disabled = isCurrent;
    item.title = isCurrent ? 'You are here' : 'Switch to ' + org.name;
    const name = document.createElement('span');
    name.className = 'org-popover-name';
    name.textContent = org.name;
    item.appendChild(name);
    // The wire key is `owner?` (cheshire keeps the keyword's `?`).
    if (org['owner?'] ?? org.owner) {
      const badge = document.createElement('span');
      badge.className = 'org-popover-badge';
      badge.textContent = 'owner';
      item.appendChild(badge);
    }
    if (org.plan) {
      const plan = document.createElement('span');
      plan.className = 'org-popover-plan';
      plan.textContent = org.plan;
      item.appendChild(plan);
    }
    if (!isCurrent) item.addEventListener('click', () => handlers.onSwitch(org.name));
    list.appendChild(item);
  }
  popover.appendChild(list);

  // --- New organization… → an inline form, not a second dialog ------------
  const newBtn = document.createElement('button');
  newBtn.type = 'button';
  newBtn.className = 'org-popover-item org-popover-new';
  newBtn.textContent = '＋ New organization…';
  popover.appendChild(newBtn);

  const form = document.createElement('div');
  form.className = 'org-popover-form';
  form.hidden = true;
  const label = document.createElement('label');
  label.className = 'org-popover-form-label';
  label.textContent = 'Name — it becomes the org\'s editor address';
  label.setAttribute('for', 'org-new-name');
  form.appendChild(label);
  const input = document.createElement('input');
  input.type = 'text';
  input.id = 'org-new-name';
  input.className = 'org-popover-input';
  input.placeholder = 'acme';
  input.setAttribute('autocomplete', 'off');
  input.setAttribute('spellcheck', 'false');
  input.setAttribute('maxlength', '40');
  input.setAttribute('aria-describedby', 'org-new-hint');
  form.appendChild(input);
  const hint = document.createElement('div');
  hint.id = 'org-new-hint';
  hint.className = 'org-popover-hint';
  hint.textContent = '3–40 lowercase letters, digits or hyphens. You become its owner.';
  form.appendChild(hint);
  const err = document.createElement('div');
  err.className = 'org-popover-error';
  err.setAttribute('role', 'alert');
  err.hidden = true;
  form.appendChild(err);
  const row = document.createElement('div');
  row.className = 'org-popover-form-row';
  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.className = 'org-popover-btn org-popover-btn-secondary';
  cancel.textContent = 'Cancel';
  const create = document.createElement('button');
  create.type = 'button';
  create.className = 'org-popover-btn';
  create.textContent = 'Create';
  row.appendChild(cancel);
  row.appendChild(create);
  form.appendChild(row);
  popover.appendChild(form);

  const showForm = () => {
    newBtn.hidden = true;
    form.hidden = false;
    err.hidden = true;
    if (typeof input.focus === 'function') input.focus();
  };
  const hideForm = () => {
    form.hidden = true;
    newBtn.hidden = false;
    input.value = '';
    err.hidden = true;
  };
  const submit = async () => {
    const wanted = String(input.value || '').trim().toLowerCase();
    if (!wanted) { err.textContent = 'Type a name.'; err.hidden = false; return; }
    create.disabled = true;
    cancel.disabled = true;
    err.hidden = true;
    let res;
    try {
      res = await handlers.onCreate(wanted);
    } catch (_) {
      res = {ok: false, message: 'The request did not complete.'};
    }
    create.disabled = false;
    cancel.disabled = false;
    if (res?.ok) {
      handlers.onSwitch(res.org || wanted);
      return;
    }
    err.textContent = res?.message || 'Could not create the organization.';
    err.hidden = false;
  };
  newBtn.addEventListener('click', showForm);
  cancel.addEventListener('click', () => {
    hideForm();
    if (typeof newBtn.focus === 'function') newBtn.focus();
  });
  create.addEventListener('click', submit);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); submit(); return; }
    // Escape inside the form backs out of the form, not the popover.
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      hideForm();
      if (typeof newBtn.focus === 'function') newBtn.focus();
    }
  });
  return {showForm, hideForm};
}

// POST /api/my-orgs {name} → {ok, org} | {ok:false, error, message}. The
// server's refusals (taken / reserved / shape / limit / unverified email)
// carry a human-readable message; put THAT in front of the reader.
async function gdCreateOrg(name) {
  // Fixed route-collection endpoint (tenancy auth-routes), not a graph route.
  const resp = await authFetch('/api/my-orgs', { // api-url-drift-allow: route-collection
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({name}),
  });
  let body = null;
  try { body = await resp.json(); } catch (_) { /* non-JSON refusal */ }
  if (resp.ok && body?.ok) return {ok: true, org: body.org};
  let fallback = 'Could not create the organization (' + resp.status + ').';
  if (resp.status === 429) fallback = 'Too many attempts — try again later.';
  if (resp.status === 401) fallback = 'Sign in to create an organization.';
  return {ok: false, error: body?.error || String(resp.status), message: body?.message || fallback};
}

async function initOrgSwitcher() {
  const mount = document.getElementById('org-mount');
  if (!mount || typeof authFetch !== 'function') return;
  // Orgs exist only under the accounts addon — wait for its boot probe and
  // skip entirely when it's absent (no wasted fetch on single-tenant boots).
  if (!(await window.gdAccountsReady)) return;
  // /auth/me told us this deployment has no org surface — don't fire a
  // doomed /api/memberships probe (a guaranteed console 404 per load).
  if (window.gdOrgsAvailable === false) return;
  let data;
  try {
    // Fixed route-collection endpoint (tenancy auth-routes), not a graph route.
    const resp = await authFetch('/api/memberships'); // api-url-drift-allow: route-collection
    if (!resp.ok) return; // unauthenticated / no addon → no chip
    const ct = resp.headers.get('content-type') || '';
    if (!ct.includes('application/json')) return; // graph fall-through page
    data = await resp.json();
  } catch (_) {
    return;
  }
  // `orgs` carries owner/plan per row; an older server answers names only.
  const orgs = Array.isArray(data?.orgs) && data.orgs.length
    ? data.orgs
    : (Array.isArray(data?.memberships) ? data.memberships : []).map((name) => ({name}));
  if (orgs.length < 1) return; // a platform / org-less principal: nothing to show
  const current = data.active || orgs[0].name;
  mount.innerHTML = ''
    + '<button type="button" id="org-chip-btn" class="org-chip-btn"'
    +   ' title="Organization — switch or create" aria-haspopup="dialog" aria-expanded="false">'
    +   '<span class="org-chip-k">org</span><b id="org-chip-name"></b>'
    + '</button>'
    + '<div id="org-popover" class="org-popover hidden" role="dialog" aria-label="Organizations"></div>';
  document.getElementById('org-chip-name').textContent = current;
  const btn = document.getElementById('org-chip-btn');
  const popover = document.getElementById('org-popover');
  const form = gdRenderOrgPopover(popover, {orgs, current},
                                  {onSwitch: switchToOrg, onCreate: gdCreateOrg});
  const isOpen = () => !popover.classList.contains('hidden');
  const close = () => {
    if (!isOpen()) return;
    popover.classList.add('hidden');
    btn.setAttribute('aria-expanded', 'false');
    form.hideForm();
  };
  const open = () => {
    popover.classList.remove('hidden');
    btn.setAttribute('aria-expanded', 'true');
    if (typeof focusIntoDialog === 'function') focusIntoDialog(popover);
  };
  btn.addEventListener('click', () => {
    if (isOpen()) { close(); return; }
    open();
  });
  if (typeof installPopoverDismiss === 'function') {
    installPopoverDismiss({
      getEl: () => popover,
      getAnchor: () => btn,
      isVisible: isOpen,
      onDismiss: close,
      getReturnFocus: () => btn,
    });
  }
}

// Derive the base domain by dropping the current host's leftmost label (its
// org / `app` subdomain): acme.graphden.dev → graphden.dev, app.graphden.dev
// → graphden.dev. Returns null when there's no derivable base (the apex,
// localhost, an IP, a 2-label host) — a single-host dev instance, where we
// re-mint in place instead of navigating.
function orgBaseDomain() {
  const host = window.location.hostname;
  if (/^[0-9.]+$/.test(host)) return null; // IPv4 literal
  const labels = host.split('.');
  if (labels.length < 3) return null; // apex / localhost / 2-label
  return labels.slice(1).join('.');
}

async function switchToOrg(org) {
  const base = orgBaseDomain();
  if (base) {
    // Per-org subdomain model: navigate to that org's editor origin (its own
    // session). Never re-mint across origins — the target host's cross-org
    // guard would reject a token minted here.
    const port = window.location.port ? ':' + window.location.port : '';
    window.location.href =
      window.location.protocol + '//' + org + '.' + base + port + '/';
    return;
  }
  // Single-host dev fallback: set the gd_org selector cookie server-side
  // (validated against memberships), then reload — the session itself is
  // org-agnostic, so there is nothing to re-mint.
  try {
    // Fixed route-collection endpoint (tenancy auth-routes), not a graph route.
    const resp = await authFetch('/api/switch-org', { // api-url-drift-allow: route-collection
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ org }),
    });
    if (!resp.ok) return;
    // Reload so the whole editor re-fetches under the newly selected org.
    window.location.reload();
  } catch (_) {
    // Network / auth error — leave the current selection untouched.
  }
}
