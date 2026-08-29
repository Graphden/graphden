// Editor — Feedback ("Report a problem").
//
// Three responsibilities:
//   1. A small ring buffer of uncaught JS errors (window.onerror /
//      unhandledrejection) so a report can carry "what just broke".
//   2. The report form popover — category / text / email + a transparent
//      "what will be sent" checkbox list. Opened from the shell menu
//      (editor-auth.js) and from the Errors panel (editor-errors.js).
//   3. Delivery: POST to the intake URL announced by THIS instance's
//      GET /api/feedback/config (env-controlled; empty string = feature
//      hidden), falling back to a "download the report / open GitHub"
//      path when the intake is unreachable.
//
// graph-first-exception: the form is built client-side, not fetched from
// /partials/* — it must render and submit even when this instance's
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
  if (include.errorLog) {
    try {
      const r = await authFetch('/partials/error-log');
      if (r.ok) {
        const doc = new DOMParser().parseFromString(await r.text(), 'text/html');
        ctx.errorLog = (doc.body.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 4000);
      }
    } catch (_) { /* panel unreachable — send without it */ }
  }
  return ctx;
}

// ============================================================================
// THE FORM POPOVER
// ============================================================================

function closeFeedbackForm() {
  const el = document.getElementById('feedback-backdrop');
  if (el) el.remove();
  document.removeEventListener('keydown', _fbEscHandler, true);
}

function _fbEscHandler(e) {
  if (e.key === 'Escape') { closeFeedbackForm(); e.stopPropagation(); }
}

function _fbRow(labelText, input) {
  const row = document.createElement('label');
  row.className = 'feedback-row';
  const span = document.createElement('span');
  span.textContent = labelText;
  row.appendChild(span);
  row.appendChild(input);
  return row;
}

function _fbCheck(labelText, checked) {
  const label = document.createElement('label');
  label.className = 'feedback-check';
  const box = document.createElement('input');
  box.type = 'checkbox';
  box.checked = !!checked;
  label.appendChild(box);
  label.appendChild(document.createTextNode(' ' + labelText));
  return { el: label, box };
}

// opts: {includeErrorLog: bool, prefillText: string}
function openFeedbackForm(opts = {}) {
  if (!feedbackEnabled()) {
    if (typeof gdToast === 'function') gdToast('Feedback is disabled on this instance.');
    return;
  }
  closeFeedbackForm();

  const backdrop = document.createElement('div');
  backdrop.id = 'feedback-backdrop';
  backdrop.addEventListener('mousedown', (e) => {
    if (e.target === backdrop) closeFeedbackForm();
  });

  const card = document.createElement('div');
  card.className = 'feedback-popover';
  card.setAttribute('role', 'dialog');
  card.setAttribute('aria-label', 'Report a problem');

  const title = document.createElement('div');
  title.className = 'feedback-title';
  title.textContent = 'Report a problem';
  card.appendChild(title);

  const category = document.createElement('select');
  for (const [v, label] of [['bug', 'Bug'], ['idea', 'Idea'], ['question', 'Question']]) {
    const o = document.createElement('option');
    o.value = v;
    o.textContent = label;
    category.appendChild(o);
  }
  card.appendChild(_fbRow('Category', category));

  const text = document.createElement('textarea');
  text.rows = 5;
  text.maxLength = 10000;
  text.placeholder = 'What happened? What did you expect?';
  if (opts.prefillText) text.value = opts.prefillText;
  card.appendChild(_fbRow('Description', text));

  const email = document.createElement('input');
  email.type = 'email';
  email.maxLength = 200;
  email.placeholder = 'you@example.com (optional, for follow-up)';
  if (window.gdAccount?.email) email.value = window.gdAccount.email;
  card.appendChild(_fbRow('Email', email));

  // Honeypot — visually absent; bots that fill every field reveal
  // themselves. The intake pretends success when it arrives non-empty.
  const honeypotWrap = document.createElement('div');
  honeypotWrap.className = 'feedback-honeypot';
  honeypotWrap.setAttribute('aria-hidden', 'true');
  const honeypot = document.createElement('input');
  honeypot.name = 'website';
  honeypot.tabIndex = -1;
  honeypot.autocomplete = 'off';
  honeypotWrap.appendChild(honeypot);
  card.appendChild(honeypotWrap);

  const attach = document.createElement('div');
  attach.className = 'feedback-attach';
  const attachTitle = document.createElement('div');
  attachTitle.className = 'feedback-attach-title';
  attachTitle.textContent = 'Attach (sent only on submit):';
  attach.appendChild(attachTitle);
  const cEnv = _fbCheck('Build & environment (versions, browser)', true);
  const cErrs = _fbCheck(`Recent console errors (${_fbRecentErrors.length})`, true);
  const cLoc = _fbCheck('Current location (instance URL, branch, open fn)', false);
  attach.appendChild(cEnv.el);
  attach.appendChild(cErrs.el);
  attach.appendChild(cLoc.el);
  let cLog = null;
  if (typeof isAuthenticated === 'function' && isAuthenticated()) {
    cLog = _fbCheck('Recent failed executions (error log, already redacted)', !!opts.includeErrorLog);
    attach.appendChild(cLog.el);
  }
  card.appendChild(attach);

  const status = document.createElement('div');
  status.className = 'feedback-status';
  const actions = document.createElement('div');
  actions.className = 'feedback-actions';
  const send = document.createElement('button');
  send.className = 'feedback-send';
  send.textContent = 'Send';
  const cancel = document.createElement('button');
  cancel.className = 'feedback-cancel';
  cancel.textContent = 'Cancel';
  cancel.addEventListener('click', closeFeedbackForm);
  actions.appendChild(send);
  actions.appendChild(cancel);
  card.appendChild(actions);
  card.appendChild(status);

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
        env: cEnv.box.checked,
        consoleErrors: cErrs.box.checked,
        location: cLoc.box.checked,
        errorLog: !!cLog?.box.checked,
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
