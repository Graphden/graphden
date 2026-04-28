// Editor Auth — admin password popover + authFetch wrapper.
//
// The backend uses static-bearer-token middleware: a single `AUTH_TOKEN`
// env var on the server is the admin password. Mutating routes
// (POST /api/entities/..., DELETE /api/sequence/item/..., etc.) reject
// requests whose `Authorization` header doesn't match. Read routes
// (GET /, /api/graph/entities, /api/graph/layout) are open.
//
// UX:
//   1. Lock icon in the sidebar header (closed = anonymous, open = signed in).
//   2. Click → popover with a password input.
//   3. Save → hits GET /api/auth/check; on 200 stores the password in
//      localStorage and switches to "open" icon. On 401 shows an error.
//   4. Click "open" lock → confirm logout → clear localStorage,
//      back to "closed" icon.
//
// All mutating fetches go through `authFetch(url, opts)` which reads
// the password from storage and adds `Authorization: Bearer <pw>`.
// A 401 from any authFetch call automatically clears the stored
// password and re-opens the popover with an error so the user notices
// when their session was rejected.

const AUTH_STORAGE_KEY = 'graphden.auth.password';

const LOCK_CLOSED_SVG = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>';
const LOCK_OPEN_SVG   = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 7.5-2"/></svg>';
const EYE_SVG     = '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
const EYE_OFF_SVG = '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';

function getAuthPassword() {
  try { return localStorage.getItem(AUTH_STORAGE_KEY); }
  catch (_) { return null; }
}

function setAuthPassword(pw) {
  try { localStorage.setItem(AUTH_STORAGE_KEY, pw); } catch (_) {}
  renderAuthLock();
}

function clearAuthPassword() {
  try { localStorage.removeItem(AUTH_STORAGE_KEY); } catch (_) {}
  renderAuthLock();
}

function isAuthenticated() {
  return !!getAuthPassword();
}

// Wraps fetch() — adds Authorization header from storage when present;
// auto-clears on 401 + opens the popover with an error so a stale
// password can be re-entered immediately.
async function authFetch(url, opts = {}) {
  const pw = getAuthPassword();
  const headers = Object.assign({}, opts.headers || {});
  if (pw) headers['Authorization'] = 'Bearer ' + pw;
  const merged = Object.assign({}, opts, { headers });
  const response = await fetch(url, merged);
  if (response.status === 401 && pw) {
    clearAuthPassword();
    openAuthPopover('Password rejected — please re-enter.');
  }
  return response;
}

// Mount the lock icon + popover skeleton into #auth-mount.
function initAuthLock() {
  const mount = document.getElementById('auth-mount');
  if (!mount) return;
  mount.innerHTML =
    '<button id="auth-lock-btn" class="auth-lock-btn" title="Admin login"></button>' +
    '<div id="auth-popover" class="auth-popover hidden">' +
      '<div class="auth-input-wrap">' +
        '<input id="auth-password-input" type="password" placeholder="Admin password" autocomplete="off">' +
        '<button id="auth-toggle-visibility-btn" type="button" class="auth-toggle-visibility" tabindex="-1" title="Show password">' + EYE_SVG + '</button>' +
      '</div>' +
      '<div class="auth-popover-row">' +
        '<button id="auth-save-btn" class="auth-popover-btn">Save</button>' +
        '<button id="auth-cancel-btn" class="auth-popover-btn auth-popover-btn-secondary">Cancel</button>' +
      '</div>' +
      '<div id="auth-error" class="auth-error hidden"></div>' +
    '</div>';

  document.getElementById('auth-lock-btn').addEventListener('click', toggleAuthAction);
  document.getElementById('auth-save-btn').addEventListener('click', submitAuth);
  document.getElementById('auth-cancel-btn').addEventListener('click', () => closeAuthPopover());
  document.getElementById('auth-toggle-visibility-btn').addEventListener('click', (e) => {
    // The toggle replaces this button's innerHTML, which detaches the
    // SVG that was the click target. If the event bubbled to the
    // document-level "click outside the popover" handler afterwards,
    // its `popover.contains(e.target)` check would see the now-orphan
    // SVG and close the popover. Keep the click local.
    e.stopPropagation();
    togglePasswordVisibility();
  });
  document.getElementById('auth-password-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') submitAuth();
    if (e.key === 'Escape') closeAuthPopover();
  });

  // Close popover on outside click.
  document.addEventListener('click', (e) => {
    const popover = document.getElementById('auth-popover');
    const btn = document.getElementById('auth-lock-btn');
    if (!popover || popover.classList.contains('hidden')) return;
    if (popover.contains(e.target) || btn.contains(e.target)) return;
    closeAuthPopover();
  });

  renderAuthLock();
}

// Eye-toggle in the password input — flip between `type="password"`
// and `type="text"` so the user can sanity-check what they typed
// before submitting. Reset back to "password" whenever the popover
// closes so the field is masked again on next open.
function togglePasswordVisibility() {
  const input = document.getElementById('auth-password-input');
  const btn   = document.getElementById('auth-toggle-visibility-btn');
  if (!input || !btn) return;
  const showing = input.type === 'text';
  input.type = showing ? 'password' : 'text';
  btn.innerHTML = showing ? EYE_SVG : EYE_OFF_SVG;
  btn.title     = showing ? 'Show password' : 'Hide password';
  // Keep the cursor in the input so the toggle doesn't steal focus
  // mid-typing.
  input.focus();
}

function renderAuthLock() {
  const btn = document.getElementById('auth-lock-btn');
  if (!btn) return;
  const authed = isAuthenticated();
  btn.innerHTML = authed ? LOCK_OPEN_SVG : LOCK_CLOSED_SVG;
  btn.classList.toggle('auth-lock-open', authed);
  btn.title = authed ? 'Sign out' : 'Admin login';
}

// Click on the lock toggles between login (when closed) and logout
// (when open). Logout asks for confirmation since it loses the
// in-progress edit session.
function toggleAuthAction() {
  if (isAuthenticated()) {
    if (confirm('Sign out?')) clearAuthPassword();
  } else {
    openAuthPopover();
  }
}

function openAuthPopover(errorMsg) {
  const popover = document.getElementById('auth-popover');
  if (!popover) return;
  popover.classList.remove('hidden');
  // Place at viewport coords BEFORE focusing the input so the
  // browser doesn't try to scroll the sidebar to reveal an
  // off-screen field (which would drag the popover with it).
  positionAuthPopover();
  const input = document.getElementById('auth-password-input');
  if (input) {
    input.value = '';
    input.focus();
  }
  const err = document.getElementById('auth-error');
  if (err) {
    if (errorMsg) {
      err.textContent = errorMsg;
      err.classList.remove('hidden');
    } else {
      err.textContent = '';
      err.classList.add('hidden');
    }
  }
}

// Position the popover via `position: fixed` viewport coordinates,
// dropping below the lock-button and right-aligned to its right edge,
// clamped so the left edge stays at least 8 px inside the viewport.
//
// We also REPARENT the popover under <body> on first open. Otherwise
// it sits inside #side-menu, which has `transform: translateX(0)` for
// the collapse-slide animation — and any ancestor with a transform
// becomes the containing block for `position: fixed` descendants
// (per spec), defeating the point of fixed-positioning. Living
// directly under <body> keeps the popover anchored to the viewport.
function positionAuthPopover() {
  const popover = document.getElementById('auth-popover');
  const lock = document.getElementById('auth-lock-btn');
  if (!popover || !lock) return;
  if (popover.parentElement !== document.body) {
    document.body.appendChild(popover);
  }
  const lockRect = lock.getBoundingClientRect();
  // Measure popover with a neutral position so we get its natural size.
  popover.style.top = '0px';
  popover.style.left = '0px';
  const popRect = popover.getBoundingClientRect();
  const margin = 8;
  const top = lockRect.bottom + 6;
  // Right-align with the lock when there's room; clamp left to >= margin.
  const left = Math.max(margin, lockRect.right - popRect.width);
  popover.style.top = top + 'px';
  popover.style.left = left + 'px';
}

function closeAuthPopover() {
  const popover = document.getElementById('auth-popover');
  if (!popover) return;
  popover.classList.add('hidden');
  const err = document.getElementById('auth-error');
  if (err) { err.textContent = ''; err.classList.add('hidden'); }
  // Reset the eye-toggle so the next open starts masked.
  const input = document.getElementById('auth-password-input');
  const eye   = document.getElementById('auth-toggle-visibility-btn');
  if (input) input.type = 'password';
  if (eye)   { eye.innerHTML = EYE_SVG; eye.title = 'Show password'; }
}

async function submitAuth() {
  const input = document.getElementById('auth-password-input');
  const err = document.getElementById('auth-error');
  if (!input) return;
  const pw = input.value;
  if (!pw) {
    if (err) { err.textContent = 'Password required.'; err.classList.remove('hidden'); }
    return;
  }
  // Validate against the auth-required ping endpoint BEFORE storing.
  try {
    const response = await fetch('/api/auth/check', {
      headers: { 'Authorization': 'Bearer ' + pw }
    });
    if (response.status === 200) {
      setAuthPassword(pw);
      closeAuthPopover();
    } else if (response.status === 401) {
      if (err) { err.textContent = 'Wrong password.'; err.classList.remove('hidden'); }
      input.focus();
      input.select();
    } else {
      if (err) {
        err.textContent = 'Auth check failed (status ' + response.status + ').';
        err.classList.remove('hidden');
      }
    }
  } catch (e) {
    if (err) {
      err.textContent = 'Network error: ' + e.message;
      err.classList.remove('hidden');
    }
  }
}

// Bootstrapped from editor-main.js after DOMContentLoaded.
window.initAuthLock = initAuthLock;
window.authFetch = authFetch;
window.isAuthenticated = isAuthenticated;
