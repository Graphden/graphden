// editor-quota.js — proactive per-org plan-usage badge (task #8-frontend).
//
// Fetches the current org's plan usage (GET /api/orgs/quota → { plan,
// fns:{used,max}, list-items:{used,max} }) and shows a small "fns: N / cap"
// badge above the sidebar's entity list. Tenancy-only: hidden when the plan is
// uncapped, single-tenant, unauthenticated, or on any error — the on-hit 429
// (over-limit UX) still handles the exact-cap moment; this is the heads-up
// BEFORE you get there. Refreshed by initGraph() after every graph mutation.
//
// Globals consumed: isAuthenticated, graphdenTenancyActive, authFetch, API.

function quotaBadgeEl() {
  let el = document.getElementById('quota-usage-badge');
  if (!el) {
    const list = document.getElementById('entity-list');
    if (!list?.parentNode) return null;
    el = document.createElement('div');
    el.id = 'quota-usage-badge';
    el.className = 'quota-badge';
    el.hidden = true;
    // Above the entity list — a stable host that the list rebuild never wipes.
    list.parentNode.insertBefore(el, list);
  }
  return el;
}

async function refreshQuotaBadge() {
  const el = quotaBadgeEl();
  if (!el) return;
  // Gate: authenticated tenant only, and only when window.API carries the
  // (addon-installed) route — no hardcoded path, so the drift validator stays
  // green and a single-tenant editor simply never shows the badge.
  const url = typeof API === 'object' ? API.api_orgs_quota : undefined;
  if (!url
      || !isAuthenticated()
      || (typeof graphdenTenancyActive === 'function' && !graphdenTenancyActive())) {
    el.hidden = true;
    return;
  }
  try {
    const resp = await authFetch(url);
    if (!resp?.ok) { el.hidden = true; return; }
    const q = await resp.json();
    const fns = q?.fns;
    if (!fns || fns.max == null) { el.hidden = true; return; } // uncapped plan
    const used = fns.used || 0;
    const max = fns.max;
    const ratio = max > 0 ? used / max : 0;
    el.textContent = 'fns: ' + used + ' / ' + max;
    el.classList.toggle('quota-badge-warn', ratio >= 0.8 && ratio < 1);
    el.classList.toggle('quota-badge-full', ratio >= 1);
    el.title = 'Your ' + (q.plan || 'plan') + ' plan allows ' + max
             + ' functions (' + used + ' used). Others (list items) tracked server-side.';
    el.hidden = false;
  } catch (_e) {
    el.hidden = true;
  }
}

window.refreshQuotaBadge = refreshQuotaBadge;
