// Editor Org-switcher — top-bar chip for Slack-style multi-org (Track B/C).
//
// Fetches GET /api/memberships ({ok, memberships, active} — served by the
// tenancy auth-routes Ring router; the session cookie authenticates it, so
// no window.API key is involved). When the account belongs to MORE THAN ONE
// org, mounts a chip in #org-mount showing the active org and a dropdown of
// the others.
//
// Track C model A: each org's editor is its own subdomain ORIGIN
// (<org>.graphden.dev), and the request-scope resolves the org from the Host
// when you're a member — so picking another org NAVIGATES to that org's
// subdomain (the accounts session cookie is origin-scoped like any cookie).
// On a single-host dev instance (localhost / no derivable base domain) the
// fallback is POST /api/switch-org, which sets the gd_org selector cookie
// and reloads — sessions are org-agnostic, no re-mint involved.
//
// Globals consumed: authFetch (bearer/cookie-transparent fetch).

async function initOrgSwitcher() {
  const mount = document.getElementById('org-mount');
  if (!mount || typeof authFetch !== 'function') return;
  // Orgs exist only under the accounts addon — wait for its boot probe and
  // skip entirely when it's absent (no wasted fetch on single-tenant boots).
  if (!(await window.gdAccountsReady)) return;
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
  const orgs = (data && Array.isArray(data.memberships)) ? data.memberships : [];
  if (orgs.length < 2) return; // nothing to switch between

  const current = data.active;
  mount.innerHTML = ''
    + '<button id="org-chip-btn" class="org-chip-btn" title="Switch organization">'
    +   '<span id="org-chip-name"></span>'
    + '</button>'
    + '<div id="org-popover" class="org-popover hidden" role="dialog" aria-label="Switch organization"></div>';
  document.getElementById('org-chip-name').textContent = current;

  const popover = document.getElementById('org-popover');
  popover.replaceChildren();
  for (const org of orgs) {
    const item = document.createElement('button');
    item.className = 'org-popover-item' + (org === current ? ' org-popover-item-current' : '');
    item.textContent = org;
    item.disabled = org === current;
    item.addEventListener('click', () => switchToOrg(org));
    popover.appendChild(item);
  }

  const btn = document.getElementById('org-chip-btn');
  btn.addEventListener('click', () => popover.classList.toggle('hidden'));
  document.addEventListener('click', (e) => {
    if (popover.classList.contains('hidden')) return;
    if (popover.contains(e.target) || btn.contains(e.target)) return;
    popover.classList.add('hidden');
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') popover.classList.add('hidden');
  });
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
