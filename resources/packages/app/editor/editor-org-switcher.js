// Editor Org-switcher — top-bar chip for Slack-style multi-org (Track B).
//
// Fetches GET /api/memberships ({current, orgs}). When the user belongs to
// MORE THAN ONE org, mounts a chip in #org-mount showing the current org and
// a dropdown of the others; picking one POSTs /api/switch-org?org=<org>,
// stores the fresh session token it returns as the new bearer, and reloads so
// every /api/* call runs in the chosen org. A single-org user (or an
// unauthenticated / single-tenant instance) gets no chip — nothing to switch.
//
// Globals consumed: window.API (route URLs), authFetch, setAuthPassword.

async function initOrgSwitcher() {
  const mount = document.getElementById('org-mount');
  if (!mount || typeof authFetch !== 'function') return;
  // No memberships route (single-tenant / no tenancy addon) → nothing to
  // switch; skip the fetch so we don't hit /undefined.
  if (!window.API || !window.API.api_memberships) return;
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

async function switchToOrg(org) {
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
