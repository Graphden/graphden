// Editor Org-switcher — top-bar chip for Slack-style multi-org (Track B/C).
//
// Fetches GET /api/memberships ({current, orgs}). When the user belongs to
// MORE THAN ONE org, mounts a chip in #org-mount showing the current org and
// a dropdown of the others.
//
// Track C model A: each org's editor is its own subdomain ORIGIN
// (<org>.graphden.dev) with its own session (per-origin localStorage). So
// picking another org NAVIGATES to that org's subdomain — you sign in there;
// a token minted here would be rejected on that origin's cross-org host guard.
// On a single-host dev instance (localhost / no derivable base domain) there
// are no per-org origins, so we fall back to the in-place re-mint via
// POST /api/switch-org.
//
// Globals consumed: window.API (route URLs), authFetch, setAuthPassword.

async function initOrgSwitcher() {
  const mount = document.getElementById('org-mount');
  if (!mount || typeof authFetch !== 'function') return;
  // No memberships route (single-tenant / no tenancy addon) → nothing to
  // switch; skip the fetch so we don't hit /undefined.
  if (!window.API?.api_memberships) return;
  let data;
  try {
    const resp = await authFetch(window.API.api_memberships);
    if (!resp.ok) return; // unauthenticated / no addon → no chip
    data = await resp.json();
  } catch (_) {
    return;
  }
  const orgs = (data && Array.isArray(data.orgs)) ? data.orgs : [];
  if (orgs.length < 2) return; // nothing to switch between

  const current = data.current;
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
  // Single-host dev fallback: re-mint the session in place.
  try {
    const resp = await authFetch(window.API.api_switch_org + '?org=' + encodeURIComponent(org), {
      method: 'POST',
    });
    if (!resp.ok) return;
    const token = (await resp.text()).trim();
    if (token && typeof setAuthPassword === 'function') {
      setAuthPassword(token);
      // Reload so the whole editor re-fetches under the new org's session.
      window.location.reload();
    }
  } catch (_) {
    // Network / auth error — leave the current session untouched.
  }
}
