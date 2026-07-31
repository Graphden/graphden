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
// as the bearer. Single-tenant uses a bare admin password.
//
// The MODE ARRIVES WITH THE FORM: the served /partials/auth-form variant
// (core = admin-password only; the tenancy addon shadows the path with the
// username/org variant) stamps `data-auth-mode` on its error div, captured at
// mount. Before the fields have mounted (the lock tooltip renders at boot)
// fall back to the `gd-tenancy` capability class.
let authServedMode = null; // 'admin' | 'tenant' — read off the mounted partial
function loginIsTenant() {
  if (authServedMode) return authServedMode === 'tenant';
  return document.body.classList.contains('gd-tenancy');
}

// Multi-tenant popover mode: false = log in (username + password), true = sign
// up (also an org field; POST /api/signup creates a new org + user). Reset to
// false every time the popover opens.
let authSignupMode = false;

// Reflect tenant + signup mode in the popover fields: username (tenant), org
// (tenant + signup), the submit-button label, and the login⇄signup toggle.
// Does NOT clear values — toggling preserves what's typed.
function applyAuthMode() {
  const tenant = loginIsTenant();
  const userInput = document.getElementById('auth-username-input');
  const pwInput = document.getElementById('auth-password-input');
  const orgInput = document.getElementById('auth-org-input');
  const toggle = document.getElementById('auth-mode-toggle');
  const saveBtn = document.getElementById('auth-save-btn');
  if (userInput) userInput.classList.toggle('hidden', !tenant);
  if (pwInput) pwInput.placeholder = tenant ? 'Password' : 'Admin password';
  if (orgInput) orgInput.classList.toggle('hidden', !(tenant && authSignupMode));
  if (toggle) {
    toggle.classList.toggle('hidden', !tenant);
    toggle.textContent = authSignupMode ? 'Have an account? Sign in' : 'Create account';
  }
  if (saveBtn) saveBtn.textContent = tenant ? (authSignupMode ? 'Sign up' : 'Sign in') : 'Save';
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
    void openAuthPopover('Password rejected — please re-enter.'); // fire-and-forget; fields mount async
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

// Mount the lock icon + (empty) popover shell into #auth-mount. The lock
// button is built synchronously so the icon appears instantly (§6.4); the
// popover FIELDS are a graph partial (GET /partials/auth-form) mounted lazily
// on first open — see mountAuthPopoverFields().
function initAuthLock() {
  const mount = document.getElementById('auth-mount');
  if (!mount) return;
  mount.innerHTML =
    '<button id="auth-lock-btn" class="auth-lock-btn" title="Admin login"></button>' +
    // Sign out of ALL sessions — multi-tenant + authenticated only.
    '<button id="auth-logout-all-btn" class="auth-logout-all-btn hidden" title="Sign out everywhere">⎋</button>' +
    '<div id="auth-popover" class="auth-popover hidden"></div>';

  document.getElementById('auth-lock-btn').addEventListener('click', toggleAuthAction);
  document.getElementById('auth-logout-all-btn').addEventListener('click', logoutEverywhere);

  // Close popover on outside click (works whether or not the fields are mounted).
  document.addEventListener('click', (e) => {
    const popover = document.getElementById('auth-popover');
    const btn = document.getElementById('auth-lock-btn');
    if (!popover || popover.classList.contains('hidden')) return;
    if (popover.contains(e.target) || btn.contains(e.target)) return;
    closeAuthPopover();
  });

  renderAuthLock();
}

// Lazily fetch the popover FIELDS (graph partial, GET /partials/auth-form) into
// #auth-popover on first open, then wire their handlers. Idempotent. Returns
// true when the fields are mounted + wired, false on fetch failure (the popover
// then holds an inline error). The form is a fragment so the fields stay direct
// flex children of `.auth-popover`.
let _authFieldsMounted = false;
async function mountAuthPopoverFields() {
  if (_authFieldsMounted) return true;
  const popover = document.getElementById('auth-popover');
  if (!popover) return false;
  try {
    // Unauthenticated — this IS the login form; authFetch works with or
    // without a bearer.
    const r = await authFetch('/partials/auth-form');
    if (!r.ok) throw new Error('HTTP ' + r.status);
    popover.innerHTML = await r.text();
  } catch (_) {
    popover.innerHTML =
      '<div class="auth-error">Couldn\'t load the login form — reload the page.</div>';
    return false;
  }
  _authFieldsMounted = true;

  // The served variant declares its submit mode (core = "admin"; the tenancy
  // addon's shadowing partial = "tenant") — see loginIsTenant().
  const modeEl = popover.querySelector('[data-auth-mode]');
  authServedMode = modeEl ? modeEl.getAttribute('data-auth-mode') : 'admin';

  // The eye button ships empty from the partial — fill its (toggling) SVG here.
  const eyeBtn = document.getElementById('auth-toggle-visibility-btn');
  if (eyeBtn) eyeBtn.innerHTML = EYE_SVG;

  document.getElementById('auth-save-btn').addEventListener('click', submitAuth);
  document.getElementById('auth-cancel-btn').addEventListener('click', () => closeAuthPopover());
  eyeBtn.addEventListener('click', (e) => {
    // The toggle replaces this button's innerHTML, which detaches the SVG that
    // was the click target. If the event bubbled to the document-level "click
    // outside the popover" handler afterwards, its `popover.contains(e.target)`
    // check would see the now-orphan SVG and close the popover. Keep it local.
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
  document.getElementById('auth-org-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') submitAuth();
    if (e.key === 'Escape') closeAuthPopover();
  });
  document.getElementById('auth-mode-toggle').addEventListener('click', (e) => {
    e.stopPropagation();
    authSignupMode = !authSignupMode;
    applyAuthMode();
    document.getElementById('auth-username-input')?.focus();
  });
  return true;
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
  // "Sign out everywhere" only makes sense for a real (multi-tenant) session.
  const allBtn = document.getElementById('auth-logout-all-btn');
  if (allBtn) allBtn.classList.toggle('hidden', !(authed && loginIsTenant()));
}

// Sign out of ALL sessions (server-side: POST /api/logout-all deletes every
// :token for this user), then clear local + reload.
async function logoutEverywhere() {
  if (!confirm('Sign out of all your sessions, on every device?')) return;
  // Tenancy auth routes — only reached in multi-tenant mode (loginIsTenant).
  // The tenancy-admin addon registers its routes in window.API at boot (same
  // routing-graph codegen as core routes), so we address them by key — no
  // hardcoded path. Single-tenant never reaches this branch, so the key being
  // absent there is harmless.
  try { await authFetch(API.api_logout_all, { method: 'POST' }); } catch (_) {}
  clearAuthPassword();
  window.location.reload();
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
    await openAuthPopover();
  }
}

async function openAuthPopover(errorMsg) {
  const popover = document.getElementById('auth-popover');
  if (!popover) return;
  // Fields are a graph partial mounted on first open; if the fetch fails, show
  // the popover anyway so its inline error is visible.
  if (!(await mountAuthPopoverFields())) {
    popover.classList.remove('hidden');
    positionAuthPopover();
    return;
  }
  popover.classList.remove('hidden');
  // Place at viewport coords BEFORE focusing the input so the
  // browser doesn't try to scroll the sidebar to reveal an
  // off-screen field (which would drag the popover with it).
  positionAuthPopover();
  authSignupMode = false;
  const userInput = document.getElementById('auth-username-input');
  const pwInput = document.getElementById('auth-password-input');
  const orgInput = document.getElementById('auth-org-input');
  if (userInput) userInput.value = '';
  if (pwInput) pwInput.value = '';
  if (orgInput) orgInput.value = '';
  applyAuthMode();
  // Focus the first field: username in multi-tenant, password otherwise.
  const focusEl = loginIsTenant() ? userInput : pwInput;
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
  if (loginIsTenant()) { await (authSignupMode ? submitSignup() : submitLogin()); return; }
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

// Multi-tenant self-serve signup (§4.1): POST username + password + a NEW org
// to /api/signup, which creates the org + user and returns a session token
// (auto-login). Empty body → username/org taken. Same store-token + reload as
// login.
async function submitSignup() {
  const username = document.getElementById('auth-username-input')?.value.trim();
  const password = document.getElementById('auth-password-input')?.value;
  const org = document.getElementById('auth-org-input')?.value.trim();
  const err = document.getElementById('auth-error');
  if (!username || !password || !org) {
    if (err) { err.textContent = 'Username, password and org are required.'; err.classList.remove('hidden'); }
    return;
  }
  try {
    const body = 'username=' + encodeURIComponent(username) +
                 '&password=' + encodeURIComponent(password) +
                 '&org=' + encodeURIComponent(org);
    const response = await fetch(API.api_signup, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    });
    const token = response.ok ? (await response.text()).trim() : '';
    if (token) {
      setAuthPassword(token);
      window.location.reload();
    } else {
      if (err) { err.textContent = 'Signup failed — that username or org may be taken.'; err.classList.remove('hidden'); }
    }
  } catch (e) {
    if (err) { err.textContent = 'Network error: ' + e.message; err.classList.remove('hidden'); }
  }
}


// Landing demo entry (?demo=1): the landing page can't set this origin's
// localStorage (cross-origin), so it links to the editor with ?demo=1 and the
// editor itself mints an ephemeral anonymous-tier org via the PUBLIC
// POST /api/demo/start (present only when a deploy enables it), stores the
// returned bearer exactly like a login, and reloads on a clean URL. Returns
// true when a reload was triggered (caller should stop booting). Fails soft:
// endpoint absent (404 — self-hosted / demo off) or network error → strip the
// param and boot signed-out as usual.
async function maybeStartLandingDemo() {
  const params = new URLSearchParams(window.location.search);
  if (!params.has('demo') || getAuthPassword()) return false;
  const cleanUrl = () => {
    params.delete('demo');
    const qs = params.toString();
    window.history.replaceState(null, '', window.location.pathname + (qs ? '?' + qs : ''));
  };
  // Route key exists only when the deployment enables the demo endpoint
  // (the tenancy addon merges its routes into window.API). No literal
  // fallback — the api-url drift guard scans /api/* string literals against
  // the LIVE router, and a core-only boot doesn't serve this route.
  const url = window.API && API.api_demo_start;
  if (!url) { cleanUrl(); return false; }
  try {
    const response = await fetch(url, { method: 'POST' });
    if (response.ok) {
      const data = await response.json();
      if (data?.token) {
        setAuthPassword(data.token);
        cleanUrl();
        window.location.reload();
        return true;
      }
    }
  } catch (_) { /* fall through to signed-out boot */ }
  cleanUrl();
  return false;
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
