// Editor — Diagnostics drawer (Build surface): the collapsible strip under
// the canvas hosting the live code-facing lists (Tests / Debug — the
// problem lists became Explorer lenses + Inspector sections). These used to sit on the Organization surface, which hid
// the editor while you read them; in the drawer a fn link in a row navigates
// the canvas and the list stays open. editor-sidebar.js mounts the section
// panes + bar tabs (mountOpsSections → #gd-diag-nav / #gd-diag-panels); this
// module owns the drawer lifecycle:
//   * open / collapse (persisted in localStorage), the ▴ toggle button;
//   * click-to-open on the bar tabs, click-the-active-tab-again to collapse;
//   * re-fetching the live panels each time the drawer OPENS
//     (reloadDiagnosticsSections — same staleness reason Operate re-fetches
//     its Assets panel on open);
//   * the badge counts on the bar tabs (errors / type errors from the
//     loaded panel rows, tests from the /api/tests/status cache).
//
// Globals consumed: reloadDiagnosticsSections (editor-sidebar.js),
// gdTestStatusSummary (editor-tests.js), htmx (badge recount on afterSettle).

const DIAG_OPEN_KEY = 'graphden.diagDrawer';

function _diagDrawerEl() { return document.getElementById('gd-diag-drawer'); }

function gdDiagIsOpen() {
  return _diagDrawerEl()?.getAttribute('data-open') === '1';
}

function _diagSetOpen(open) {
  const drawer = _diagDrawerEl();
  if (!drawer) return;
  drawer.setAttribute('data-open', open ? '1' : '0');
  const pane = document.getElementById('gd-diag-panels');
  if (pane) pane.hidden = !open;
  const toggle = document.getElementById('gd-diag-toggle');
  if (toggle) toggle.setAttribute('aria-expanded', String(open));
  try { localStorage.setItem(DIAG_OPEN_KEY, open ? '1' : '0'); } catch (_) { /* private mode */ }
  // Opening is when the user starts READING these lists — re-fetch so a
  // diagnostic recorded since the last open (or the boot mount) shows up.
  if (open && typeof window.reloadDiagnosticsSections === 'function') {
    window.reloadDiagnosticsSections();
  }
}

function gdDiagToggle() { _diagSetOpen(!gdDiagIsOpen()); }
window.gdDiagToggle = gdDiagToggle;

// Badge counts on the bar tabs. The problem lists (failed runs / type
// errors / lint) moved to the Explorer's lenses and the Inspector, so the
// only badge left is tests — the /api/tests/status cache (a failed COUNT
// beats a row count: it also reflects auto-runs that landed while the
// panel was stale). Debug carries no badge — the trap is short-lived
// state with its own panel.
function gdDiagUpdateBadges() {
  const nav = document.getElementById('gd-diag-nav');
  if (!nav) return;
  const setBadge = (key, text, cls) => {
    const btn = nav.querySelector('.gd-op-nav-btn[data-section="' + key + '"]');
    if (!btn) return;
    let badge = btn.querySelector('.gd-diag-badge');
    if (!badge) {
      badge = document.createElement('span');
      badge.className = 'gd-diag-badge';
      btn.appendChild(badge);
    }
    badge.classList.remove('diag-bad', 'diag-ok');
    if (!text) { badge.hidden = true; badge.textContent = ''; return; }
    badge.hidden = false;
    badge.textContent = text;
    if (cls) badge.classList.add(cls);
  };
  const tst = typeof gdTestStatusSummary === 'function' ? gdTestStatusSummary() : null;
  if (tst && tst.total > 0) {
    setBadge('tests',
      tst.failed > 0 ? '✗' + tst.failed : '✓',
      tst.failed > 0 ? 'diag-bad' : 'diag-ok');
  } else {
    setBadge('tests', null);
  }
}
window.gdDiagUpdateBadges = gdDiagUpdateBadges;

// The panel partials land asynchronously (hx-get at mount + the per-open
// refresh) — recount whenever a swap settles inside the drawer.
document.addEventListener('htmx:afterSettle', (ev) => {
  if (ev.target?.closest?.('#gd-diag-panels')) gdDiagUpdateBadges();
});

document.addEventListener('DOMContentLoaded', () => {
  const nav = document.getElementById('gd-diag-nav');
  if (!nav) return;
  // Capture phase: the active-tab check must run BEFORE the tab's own
  // activateOpSection listener flips aria-current to the clicked button.
  nav.addEventListener('click', (ev) => {
    const btn = ev.target.closest('.gd-op-nav-btn');
    if (!btn) return;
    if (!gdDiagIsOpen()) { _diagSetOpen(true); return; }
    if (btn.getAttribute('aria-current') === 'page') _diagSetOpen(false);
  }, true);
  // Restore last session's state. The sections mount (and lazy-load their
  // panels) either way, so restoring open costs no extra fetch beyond the
  // usual open-refresh.
  try {
    if (localStorage.getItem(DIAG_OPEN_KEY) === '1') _diagSetOpen(true);
  } catch (_) { /* private mode — start collapsed */ }
});
