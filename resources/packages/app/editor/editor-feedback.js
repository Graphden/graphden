// Editor — Feedback ("Report a problem").
//
// Three responsibilities:
//   1. A small ring buffer of uncaught JS errors (window.onerror /
//      unhandledrejection) so a report can carry "what just broke".
//   2. The report form popover — graph-presented category / text / email + a
//      transparent "what will be sent" checkbox list. Opened from the shell menu
//      (editor-auth.js).
//   3. Delivery: POST to the intake URL announced by THIS instance's
//      GET /api/feedback/config (env-controlled; empty string = feature
//      hidden), falling back to a "download the report / open GitHub"
//      path when the intake is unreachable.
//
// graph-first-exception: the pure form view runs from the shipped browser
// graph, not /partials/* — it must render even when this instance's
// backend is broken, which is exactly when a user wants to report. For
// the same reason the send goes straight from the browser to the intake
// (cross-origin; the intake answers with Access-Control-Allow-Origin).
// The body is sent as text/plain so the POST stays a CORS "simple
// request" (no preflight); the intake parses it as JSON regardless.
//
// Globals consumed: BUILD_HASH, gdToast, isAuthenticated, authFetch,
// getCurrentBranchName (bundle-lexical), window.gdAccount, window.API.

// Baked twin of the server-side default in app/feedback/fns.edn
// (:_fb-config-url) — used only when the local config probe is
// unreachable, i.e. the dead-backend case the form exists for.
const FEEDBACK_DEFAULT_URL = 'https://app.graphden.dev/api/feedback';
const FEEDBACK_GITHUB_ISSUES = 'https://github.com/Graphden/graphden/issues';

// ============================================================================
// UNCAUGHT-ERROR RING BUFFER
// ============================================================================

const _fbRecentErrors = [];

function _fbRecordError(line) {
  _fbRecentErrors.push(`${new Date().toISOString()} ${line}`.slice(0, 500));
  if (_fbRecentErrors.length > 20) _fbRecentErrors.shift();
}

window.addEventListener('error', (e) => {
  _fbRecordError(`${e.message || 'error'} @ ${e.filename || '?'}:${e.lineno || 0}`);
});
window.addEventListener('unhandledrejection', (e) => {
  const r = e.reason;
  _fbRecordError(`unhandled rejection: ${(r && (r.stack || r.message)) || String(r)}`);
});

// ============================================================================
// INTAKE URL — resolved once at boot from the local config probe
// ============================================================================

let _fbIntakeUrl = FEEDBACK_DEFAULT_URL;

(async function _fbResolveIntakeUrl() {
  try {
    if (window.API && API.api_feedback_config) {
      const r = await fetch(API.api_feedback_config);
      if (r.ok) {
        const j = await r.json();
        // '' is the operator's explicit "hide the feedback button".
        if (typeof j.url === 'string') _fbIntakeUrl = j.url;
      }
    }
  } catch (_) { /* dead backend — keep the baked default */ }
})();

function feedbackEnabled() {
  return _fbIntakeUrl !== '';
}
window.feedbackEnabled = feedbackEnabled;

// ============================================================================
// CONTEXT COLLECTION
// ============================================================================

async function _fbBuildContext(include) {
  const ctx = {};
  if (include.env) {
    ctx.build = { frontend: typeof BUILD_HASH === 'string' ? BUILD_HASH : null };
    try {
      const ac = new AbortController();
      const t = setTimeout(() => ac.abort(), 2000);
      const r = await fetch('/version', { signal: ac.signal });
      clearTimeout(t);
      if (r.ok) ctx.build.server = await r.json();
    } catch (_) { ctx.build.server = 'unreachable'; }
    ctx.env = {
      userAgent: navigator.userAgent,
      language: navigator.language,
      viewport: `${window.innerWidth}x${window.innerHeight}`,
      theme: document.body.classList.contains('theme-dark') ? 'dark' : 'light',
      instance: document.body.classList.contains('gd-tenancy') ? 'cloud' : 'self-host',
    };
  }
  if (include.consoleErrors && _fbRecentErrors.length) {
    ctx.consoleErrors = _fbRecentErrors.slice();
  }
  if (include.location) {
    ctx.location = {
      origin: location.origin,
      hash: location.hash,
      branch: typeof getCurrentBranchName === 'function' ? getCurrentBranchName() : null,
    };
  }
  if (include.failures && window.API && API.api_failures) {
    // The failed-runs lens's own read (GET /api/failures): which fns
    // failed on this branch view over the last 7 days, and how often.
    // Names + counts only — no error text leaves the instance.
    try {
      const r = await authFetch(API.api_failures);
      if (r.ok) {
        const rows = await r.json();
        if (Array.isArray(rows)) {
          ctx.failures = rows.slice(0, 50).map((f) => ({ fn: f['fn-name'], count: f.count }));
        }
      }
    } catch (_) { /* failures unreachable — send without them */ }
  }
  return ctx;
}

// ============================================================================
// THE FORM POPOVER
// ============================================================================

// Where the keyboard was before the form opened (the account menu item).
let _fbTrigger = null;

// Installed once — it reads the live backdrop each keystroke and is inert
// while the form is closed. Per-open installation would leak a document
// listener on every open.
installTabTrap({
  getEl: () => document.getElementById('feedback-backdrop'),
  isVisible: () => !!document.getElementById('feedback-backdrop'),
});

function closeFeedbackForm() {
  const el = document.getElementById('feedback-backdrop');
  const hadFocus = !!el && el.contains(document.activeElement);
  if (el) setSiblingsInert(el, false);
  if (el) window.gdFeedbackFormRelease?.(el.querySelector('.feedback-popover'));
  if (el) el.remove();
  document.removeEventListener('keydown', _fbEscHandler, true);
  if (hadFocus) returnFocusTo(_fbTrigger);
  _fbTrigger = null;
}

function _fbEscHandler(e) {
  if (e.key === 'Escape') { closeFeedbackForm(); e.stopPropagation(); }
}

// opts: {prefillText: string}
function openFeedbackForm(opts = {}) {
  if (!feedbackEnabled()) {
    if (typeof gdToast === 'function') gdToast('Feedback is disabled on this instance.');
    return;
  }
  closeFeedbackForm();
  _fbTrigger = document.activeElement;

  const backdrop = document.createElement('div');
  backdrop.id = 'feedback-backdrop';
  backdrop.addEventListener('mousedown', (e) => {
    if (e.target === backdrop) closeFeedbackForm();
  });

  const card = window.gdFeedbackFormNode(_fbRecentErrors.length,
    typeof isAuthenticated === 'function' && isAuthenticated());
  const category = card.querySelector('.feedback-category');
  const text = card.querySelector('.feedback-description');
  const email = card.querySelector('.feedback-email');
  const honeypot = card.querySelector('.feedback-honeypot input');
  const cEnv = card.querySelector('.feedback-env');
  const cErrs = card.querySelector('.feedback-errors');
  const cLoc = card.querySelector('.feedback-location');
  const cFails = card.querySelector('.feedback-failures');
  const status = card.querySelector('.feedback-status');
  const send = card.querySelector('.feedback-send');
  const cancel = card.querySelector('.feedback-cancel');
  if (opts.prefillText) text.value = opts.prefillText;
  if (window.gdAccount?.email) email.value = window.gdAccount.email;
  cancel.addEventListener('click', closeFeedbackForm);

  send.addEventListener('click', async () => {
    if (!text.value.trim()) {
      status.textContent = 'Please describe the problem first.';
      return;
    }
    send.disabled = true;
    status.textContent = 'Sending…';
    const payload = {
      category: category.value,
      text: text.value.trim(),
      email: email.value.trim(),
      website: honeypot.value,
      context: await _fbBuildContext({
        env: cEnv.checked,
        consoleErrors: cErrs.checked,
        location: cLoc.checked,
        failures: !!cFails?.checked,
      }),
    };
    try {
      // text/plain keeps this a CORS simple request (no preflight);
      // the intake parses the raw body as JSON regardless.
      const r = await fetch(_fbIntakeUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
        body: JSON.stringify(payload),
      });
      const j = await r.json();
      if (j.ok) {
        if (typeof gdToast === 'function') gdToast('Feedback sent — thank you!');
        closeFeedbackForm();
        return;
      }
      status.textContent = j.error === 'rate-limited'
        ? 'Rate limit reached — please try again later.'
        : 'The intake rejected the report (' + (j.error || 'unknown') + ').';
      send.disabled = false;
    } catch (_) {
      _fbShowFallback(status, payload);
      send.disabled = false;
    }
  });

  backdrop.appendChild(card);
  document.body.appendChild(backdrop);
  setSiblingsInert(backdrop, true);
  document.addEventListener('keydown', _fbEscHandler, true);
  text.focus();
}
window.openFeedbackForm = openFeedbackForm;

// Delivery failed (offline / intake down): keep the report on the user's
// side — download as JSON to attach to a GitHub issue or an email.
function _fbShowFallback(status, payload) {
  status.textContent = 'Could not reach the feedback service. ';
  const dl = document.createElement('a');
  dl.href = URL.createObjectURL(
    new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }));
  dl.download = 'graphden-feedback.json';
  dl.textContent = 'Download the report';
  const sep = document.createTextNode(' and attach it to a ');
  const gh = document.createElement('a');
  gh.href = FEEDBACK_GITHUB_ISSUES;
  gh.target = '_blank';
  gh.rel = 'noopener';
  gh.textContent = 'GitHub issue';
  status.appendChild(dl);
  status.appendChild(sep);
  status.appendChild(gh);
  status.appendChild(document.createTextNode('.'));
}
