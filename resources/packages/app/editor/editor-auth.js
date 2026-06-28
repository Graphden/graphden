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

// Multi-tenant deployments authenticate with username + password against
// POST /api/login (the §4.1 user model) and store the returned session token
// as the bearer. Single-tenant uses a bare admin password. The body carries
// `gd-tenancy` once the first response reports tenancy capabilities.
function loginIsTenant() {
  return document.body.classList.contains('gd-tenancy');
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
  if (pw) headers.Authorization = 'Bearer ' + pw;
  const merged = Object.assign({}, opts, { headers });
  const response = await fetch(url, merged);
  if (response.status === 401 && pw) {
    clearAuthPassword();
    openAuthPopover('Password rejected — please re-enter.');
  }
  return response;
}

// Build a user-facing error message for a non-OK `authFetch` response.
// 401 is the load-bearing case: a bare "HTTP 401" tells the user
// nothing actionable — every call site needs to point at the toolbar
// lock icon. `opts.authExpired` lets the caller supply context-specific
// recovery guidance (e.g. "the run continues in the background");
// `opts.fallback` overrides the generic "HTTP <status>" for non-401s,
// either as a string or a (response) => string.
function authFetchErrorMessage(response, opts) {
  const o = opts || {};
  if (response && response.status === 401) {
    return o.authExpired
      || 'Sign-in expired. Click the lock icon in the toolbar to re-authenticate.';
  }
  if (typeof o.fallback === 'function') return o.fallback(response);
  if (o.fallback) return o.fallback;
  return 'HTTP ' + (response ? response.status : '?');
}

// Like authFetchErrorMessage but for write-side handlers that need to
// READ the response body — the backend wraps write-rejection reasons in
// `<p class="error">…</p>`, and the popover shows the bare reason. Uses
// the DOM parser (not a regex) so HTML entities decode correctly.
//
// `opts.authExpired` short-circuits to a recovery message for 401
// without reading the body. `opts.fallback` overrides the generic
// "HTTP <status>" when the body is empty or unparseable.
async function extractResponseError(response, opts) {
  const o = opts || {};
  if (response && response.status === 401 && o.authExpired) {
    return o.authExpired;
  }
  if (response && response.status === 401) {
    return 'Sign-in expired. Click the lock icon in the toolbar to re-authenticate.';
  }
  let raw = '';
  try { raw = await response.text(); } catch (_) {}
  if (raw) {
    const tmp = document.createElement('div');
    tmp.innerHTML = raw;
    const text = (tmp.textContent || '').trim();
    if (text) return text;
  }
  return o.fallback || ('HTTP ' + (response ? response.status : '?'));
}

// Convenience wrapper for the typical mutating call shape: form-urlencoded
// body, server returns 2xx/4xx. `fields` is either an object that gets
// URL-encoded (skipping nullish/empty values), a pre-encoded string, or
// undefined for body-less requests (DELETE).
async function authMutate(method, url, fields) {
  const opts = { method };
  if (fields !== undefined && fields !== null) {
    let body;
    if (typeof fields === 'string') {
      body = fields;
    } else {
      const params = new URLSearchParams();
      for (const [k, v] of Object.entries(fields)) {
        if (v !== undefined && v !== null && v !== '') params.set(k, v);
      }
      body = params.toString();
    }
    opts.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    opts.body = body;
  }
  return authFetch(url, opts);
}

// Mount the lock icon + popover skeleton into #auth-mount.
function initAuthLock() {
  const mount = document.getElementById('auth-mount');
  if (!mount) return;
  mount.innerHTML =
    '<button id="auth-lock-btn" class="auth-lock-btn" title="Admin login"></button>' +
    '<div id="auth-popover" class="auth-popover hidden">' +
      // Username — shown only in multi-tenant (body.gd-tenancy); single-tenant
      // is a bare admin password, so it stays hidden there.
      '<input id="auth-username-input" type="text" placeholder="Username" autocomplete="username" class="auth-username-input hidden">' +
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
  document.getElementById('auth-username-input').addEventListener('keydown', (e) => {
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
  btn.title = authed ? 'Sign out' : (loginIsTenant() ? 'Sign in' : 'Admin login');
}

// Click on the lock toggles between login (when closed) and logout
// (when open). Logout asks for confirmation since it loses the
// in-progress edit session.
async function toggleAuthAction() {
  if (isAuthenticated()) {
    if (!confirm('Sign out?')) return;
    // Multi-tenant: invalidate the session token server-side (POST /api/logout
    // deletes the :token, so a leaked bearer can't be replayed) before clearing
    // local storage, then reload to drop back to the anonymous view. Single-
    // tenant has no server session — just clear the stored admin password.
    if (loginIsTenant()) {
      try { await authFetch(API.api_logout, { method: 'POST' }); } catch (_) {}
      clearAuthPassword();
      window.location.reload();
    } else {
      clearAuthPassword();
    }
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
  const tenant = loginIsTenant();
  const userInput = document.getElementById('auth-username-input');
  const pwInput = document.getElementById('auth-password-input');
  if (userInput) {
    userInput.value = '';
    userInput.classList.toggle('hidden', !tenant);
  }
  if (pwInput) {
    pwInput.value = '';
    pwInput.placeholder = tenant ? 'Password' : 'Admin password';
  }
  // Focus the first field: username in multi-tenant, password otherwise.
  const focusEl = tenant ? userInput : pwInput;
  if (focusEl) focusEl.focus();
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
  if (loginIsTenant()) { await submitLogin(); return; }
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
    const response = await fetch(API.api_auth_check, {
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

// Multi-tenant login (§4.1): exchange username + password at POST /api/login
// for a session token, store it as the bearer, then reload so every /api/*
// call re-runs as the logged-in user (org context + capabilities + workspace
// come from the request scope, so a reload is the clean way to pick them up).
async function submitLogin() {
  const userInput = document.getElementById('auth-username-input');
  const pwInput = document.getElementById('auth-password-input');
  const err = document.getElementById('auth-error');
  const username = userInput ? userInput.value.trim() : '';
  const password = pwInput ? pwInput.value : '';
  if (!username || !password) {
    if (err) { err.textContent = 'Username and password required.'; err.classList.remove('hidden'); }
    return;
  }
  try {
    const body = 'username=' + encodeURIComponent(username) +
                 '&password=' + encodeURIComponent(password);
    const response = await fetch(API.api_login, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    });
    const token = response.ok ? (await response.text()).trim() : '';
    if (token) {
      setAuthPassword(token);
      window.location.reload();
    } else {
      if (err) { err.textContent = 'Invalid username or password.'; err.classList.remove('hidden'); }
      if (pwInput) { pwInput.focus(); pwInput.select(); }
    }
  } catch (e) {
    if (err) { err.textContent = 'Network error: ' + e.message; err.classList.remove('hidden'); }
  }
}

// Bootstrapped from editor-main.js after DOMContentLoaded.
window.initAuthLock = initAuthLock;
window.authFetch = authFetch;

// HTMX bridge — HTMX makes its own `fetch()` calls (`hx-get`/`hx-post`),
// bypassing authFetch. Without this hook, every HTMX-driven partial
// on an auth-required route would 401. The `htmx:configRequest`
// event fires before each request; we attach the Bearer token from
// the same localStorage entry authFetch reads, so HTMX inherits the
// session login automatically.
document.body.addEventListener('htmx:configRequest', (evt) => {
  const pw = getAuthPassword();
  if (pw) evt.detail.headers.Authorization = 'Bearer ' + pw;
});
window.authMutate = authMutate;
window.isAuthenticated = isAuthenticated;
