// Editor — Moderation section (Platform surface). docs/MARKETPLACE.md § 8.
//
// Server-rendered via GET /partials/moderation-queue: every PUBLIC listing
// awaiting the operator's decision, across organizations, with Approve /
// Reject (+ note) forms that are pure HTMX declared in the partial's hiccup
// and swap the refreshed queue. This module only decides WHETHER to mount
// (client gate: platform-admin) and lazy-loads via hx-get; the base-fns
// behind the partial and the decision route refuse anyone else.
//
// Globals consumed: isAuthenticated, graphdenHasCap, htmx.

function buildModerationSection() {
  if (!isAuthenticated()) return null;
  if (typeof window.graphdenHasCap !== 'function' || !window.graphdenHasCap('platform-admin')) return null;
  // Only with the optional registry package (its routes are in window.API).
  if (!(typeof window.API === 'object' && window.API && typeof window.API.api_marketplace_moderation !== 'undefined')) return null;
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-moderation';
  wrap.innerHTML = ''
    + '<p class="gd-set-hint">Public listings an organization published wait here before other organizations see them. '
    + 'Approve lists it; Reject keeps it the publisher\'s own and shows them your note.</p>'
    + '<div class="ns-children" hx-get="/partials/moderation-queue" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  // Built imperatively; the caller (mountAdminSection) runs htmx.process after
  // appending to the connected DOM so hx-trigger="load" fires.
  return wrap;
}
