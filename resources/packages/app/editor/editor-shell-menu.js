// Editor Shell MENU — the account chip's menu.
//
// Split out of editor-auth.js (2026-09-13). `openShellMenu` is the single
// entry to Settings / Organization / Platform (capability-gated), the
// interactive tutorial, feedback, and the session actions for every auth mode
// (sign in / out, sign out everywhere — `logoutEverywhere`). A real ARIA menu:
// focus enters on open, ↑ ↓ Home End walk the `menuitem`s, Escape / Tab close
// and return focus to the chip, which carries `aria-haspopup` /
// `aria-expanded`. Reads the auth mode flags (`accountsMode`, `authServedMode`,
// …) from editor-auth.js at click time; loads right after it.

// The account chip's menu — the SETTINGS HUB (redesign 2026-08-15, the rail
// is retired). One menu for every auth mode: identity head (when known), the
// management destinations (Settings always — appearance works signed-out too;
// Organization always; Platform behind the platform right), then the session
// actions for the current mode. Sections the principal has no rights to
// simply don't appear. Rendered into the shared #auth-popover.
function openShellMenu() {
  const pop = document.getElementById('auth-popover');
  if (!pop) return;
  const menu = document.createElement('div');
  menu.className = 'auth-menu';
  // A real ARIA menu, not a styled div: focus moves IN on open, arrows walk
  // the items (roving tabindex — Tab is not the navigator here), Escape and
  // Tab close and hand focus back to the chip. Without this the items sat at
  // the very END of the page's tab order — ~50 presses from the trigger.
  menu.setAttribute('role', 'menu');
  menu.setAttribute('aria-label', 'Account and editor');

  // Identity head — accounts identity, or the bearer-session kinds.
  const isOp = (typeof window.graphdenHasCap === 'function') && window.graphdenHasCap('platform-admin');
  let who = null;
  if (accountsMode && accountsAuthed) {
    const a = window.gdAccount || {};
    who = a.email || a['display-name'] || a.id || 'signed in';
  } else if (isAuthenticated()) {
    who = loginIsTenant() ? 'Signed in' : 'Signed in as admin';
  }
  if (who) {
    const head = document.createElement('div');
    head.className = 'auth-menu-head';
    const whoEl = document.createElement('div');
    whoEl.className = 'auth-menu-who';
    whoEl.textContent = who;
    head.appendChild(whoEl);
    if (isOp) {
      const badge = document.createElement('span');
      badge.className = 'auth-menu-badge';
      badge.textContent = 'operator';
      head.appendChild(badge);
    }
    menu.appendChild(head);
  }

  const item = (label, onClick) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'auth-menu-item';
    b.setAttribute('role', 'menuitem');
    b.setAttribute('tabindex', '-1');
    // Stable hook for the tutorial spotlight: which destination this row is.
    b.dataset.item = label;
    b.textContent = label;
    b.addEventListener('click', onClick);
    menu.appendChild(b);
  };
  const divider = () => {
    const d = document.createElement('div');
    d.className = 'auth-menu-div';
    menu.appendChild(d);
  };
  const goSurface = (name) => {
    closeAuthPopover();
    if (typeof gdShellSurface === 'function') gdShellSurface(name);
  };

  // Management destinations — rare places, one click deep by design.
  item('Settings', () => goSurface('settings'));
  item('Organization', () => goSurface('operate'));
  // Marketplace — only with the optional registry package (docs/MARKETPLACE.md).
  if (typeof window.API === 'object' && window.API && typeof window.API.api_marketplace !== 'undefined') {
    item('Marketplace', () => goSurface('market'));
  }
  if (document.body.classList.contains('gd-platform') || isOp) {
    item('Platform', () => goSurface('platform'));
  }
  // Interactive tutorial — guided in-editor lessons (editor-tour.js).
  if (typeof window.openTutorialMenu === 'function') {
    item('Interactive tutorial', () => {
      closeAuthPopover();
      window.openTutorialMenu();
    });
  }
  divider();

  // Session actions per auth mode.
  if (accountsMode) {
    if (accountsAuthed) {
      item('Sign out', async () => {
        try { await fetch('/auth/logout', { method: 'POST' }); } catch (_) {}
        window.location.reload();
      });
      item('Sign out everywhere', async () => {
        try { await fetch('/auth/logout-all', { method: 'POST' }); } catch (_) {}
        window.location.reload();
      });
    } else {
      item('Sign in', () => { window.location.href = '/login'; });
    }
  } else if (isAuthenticated()) {
    item('Sign out', async () => {
      if (!confirm('Sign out?')) return;
      if (loginIsTenant()) {
        try { await authFetch(API.api_logout, { method: 'POST' }); } catch (_) {}
        clearAuthPassword();
        window.location.reload();
      } else {
        clearAuthPassword();
        closeAuthPopover();
      }
    });
    if (loginIsTenant()) item('Sign out everywhere', logoutEverywhere);
  } else {
    // Bearer modes sign in via the popover form — swap the menu for it.
    item('Sign in', () => {
      pop.classList.add('hidden');
      pop.dataset.gdContent = '';
      pop.innerHTML = '';
      void openAuthPopover();
    });
  }

  // Community footer — the menu's quiet last block, the in-editor pointer at
  // the project's channels (URLs mirror README/landing).
  divider();
  // Feedback — present unless the operator explicitly disabled it
  // (GRAPHDEN_FEEDBACK_URL=off → editor-feedback.js hides the affordance).
  if (typeof window.feedbackEnabled !== 'function' || window.feedbackEnabled()) {
    item('Report a problem', () => {
      closeAuthPopover();
      if (typeof window.openFeedbackForm === 'function') window.openFeedbackForm();
    });
  }
  // Everywhere, cloud included. It was gated off tenancy deployments for a
  // while on the theory that paying customers should not be asked to
  // donate — the project's owner disagrees, and next to the socials it
  // reads as a community link, not a collection plate.
  {
    const support = document.createElement('a');
    support.className = 'auth-menu-item auth-menu-support';
    support.setAttribute('role', 'menuitem');
    support.setAttribute('tabindex', '-1');
    support.href = 'https://boosty.to/graphden';
    support.target = '_blank';
    support.rel = 'noopener';
    support.textContent = 'Support the project ♥';
    menu.appendChild(support);
  }
  const social = document.createElement('div');
  social.className = 'auth-menu-social';
  for (const [label, href] of [
    ['Website', 'https://graphden.dev'],
    ['GitHub', 'https://github.com/Graphden/graphden'],
    ['Discord', 'https://discord.gg/UDC4pZFvp'],
    ['Telegram', 'https://t.me/graphden'],
    ['X', 'https://x.com/graphdendev'],
    ['YouTube', 'https://www.youtube.com/@Graphdendev'],
  ]) {
    const a = document.createElement('a');
    a.setAttribute('role', 'menuitem');
    a.setAttribute('tabindex', '-1');
    a.href = href;
    a.target = '_blank';
    a.rel = 'noopener';
    a.textContent = label;
    social.appendChild(a);
  }
  menu.appendChild(social);

  // Keyboard: arrows walk (and wrap over) every menuitem, Home/End jump,
  // Escape/Tab close and put focus back on the chip. `preventDefault` on
  // Escape marks it consumed (see graphden-popover.js) so the tour and the
  // surface-level Escape handler don't also act on it.
  const menuItems = () => [...menu.querySelectorAll('[role="menuitem"]')];
  menu.addEventListener('keydown', (e) => {
    const items = menuItems();
    if (!items.length) return;
    const at = items.indexOf(document.activeElement);
    const go = (i) => { const t = items[(i + items.length) % items.length]; if (t) t.focus(); };
    if (e.key === 'ArrowDown') { e.preventDefault(); go(at + 1); } else
    if (e.key === 'ArrowUp') { e.preventDefault(); go(at - 1); } else
    if (e.key === 'Home') { e.preventDefault(); go(0); } else
    if (e.key === 'End') { e.preventDefault(); go(items.length - 1); } else
    if (e.key === 'Escape' || e.key === 'Tab') { e.preventDefault(); closeAuthPopover(); }
  });

  pop.replaceChildren(menu);
  pop.dataset.gdContent = 'menu';
  pop.classList.remove('hidden');
  positionAuthPopover();
  document.getElementById('auth-lock-btn')?.setAttribute('aria-expanded', 'true');
  const first = menuItems()[0];
  if (first) {
    if (typeof focusSafely === 'function') focusSafely(first);
    else first.focus();
  }
}

// Sign out of ALL sessions (server-side: POST /api/logout-all deletes every
// :token for this user), then clear local + reload.
async function logoutEverywhere() {
  if (!confirm('Sign out of all your sessions, on every device?')) return;
  if (accountsMode) {
    // Accounts addon: revoke every session for this account server-side.
    try { await fetch('/auth/logout-all', { method: 'POST' }); } catch (_) {}
    window.location.reload();
    return;
  }
  // Tenancy auth routes — only reached in multi-tenant mode (loginIsTenant).
  // The tenancy-admin addon registers its routes in window.API at boot (same
  // routing-graph codegen as core routes), so we address them by key — no
  // hardcoded path. Single-tenant never reaches this branch, so the key being
  // absent there is harmless.
  try { await authFetch(API.api_logout_all, { method: 'POST' }); } catch (_) {}
  clearAuthPassword();
  window.location.reload();
}
