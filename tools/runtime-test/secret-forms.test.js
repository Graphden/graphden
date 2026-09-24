'use strict';

// editor-secrets.js — the create / rotate secret popovers. Pinned:
//   * a form partial answering an error status is NOT mounted as the form
//     (its body is an error page — the handlers then hit null); the popover
//     says what failed and carries a visible ×, and focus moves into it;
//   * Save is disabled while its request is in flight — a double click
//     created the secret twice — and comes back when the request fails.
// Runs under node's vm over mini-dom; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument, MiniElement } = require('./mini-dom');

const SRC = fs.readFileSync(path.join(__dirname, '..', '..', 'resources', 'packages',
  'app', 'editor', 'editor-secrets.js'), 'utf8');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

MiniElement.prototype.focus = function focus() {};

function boot(formResponse) {
  const doc = createDocument();
  const seen = { posts: 0, focusedInto: 0, xs: 0 };
  let releasePost = null;
  const ctx = vm.createContext({
    console, Promise, JSON, URLSearchParams,
    document: doc,
    API: { api_secrets: '/api/secrets', api_secrets_fn_id_value: (id) => '/api/secrets/' + id + '/value' },
    installPopoverDismiss() {},
    anchorBelowClamped() {},
    returnFocusTo() {},
    focusIntoDialog: () => { seen.focusedInto += 1; },
    ensurePopoverClose: (el, onClose) => {
      seen.xs += 1;
      const b = doc.createElement('button');
      b.className = 'gd-pop-x';
      b.addEventListener('click', onClose);
      el.insertBefore(b, el.firstChild);
      return b;
    },
    authFetchErrorMessage: (r, o) => (r.status === 401 ? 'Sign-in expired.' : o.fallback),
    authFetch: async (url, opts) => {
      if (url.startsWith('/partials/')) return formResponse();
      seen.posts += 1;
      await new Promise((r) => { releasePost = r; });
      return { ok: false, status: 409, json: async () => ({ ok: false, error: 'exists' }) };
    },
  });
  ctx.window = ctx;
  // The create form, built by hand when its partial body lands.
  Object.defineProperty(MiniElement.prototype, 'innerHTML', {
    configurable: true,
    get() { return this.textContent; },
    set(v) {
      this.textContent = '';
      if (v !== 'FORM') return;
      for (const n of ['name', 'path', 'value', 'description']) {
        const i = doc.createElement('input');
        i.setAttribute('name', n);
        i.value = 'x';
        this.appendChild(i);
      }
      for (const a of ['pick-ns', 'cancel', 'submit']) {
        const b = doc.createElement('button');
        b.setAttribute('data-act', a);
        this.appendChild(b);
      }
      const err = doc.createElement('div');
      err.className = 'popover-error';
      this.appendChild(err);
    },
  });
  vm.runInContext(SRC, ctx);
  return { ctx, doc, seen, release: () => releasePost?.() };
}

(async () => {
  console.log(' an error status is shown as an error, with a ×, not mounted as the form');
  {
    const t = boot(() => ({ ok: false, status: 401, text: async () => '<html>login</html>' }));
    await t.ctx.openCreateSecretForm(t.doc.createElement('button'));
    const pop = t.doc.querySelector('.secrets-popover');
    assert(!!pop, 'popover mounted');
    assert(pop.textContent.includes('Sign-in expired.'), 'says what failed, got "' + pop?.textContent + '"');
    assert(!!pop.querySelector('.gd-pop-x'), 'visible ×');
    assert(t.seen.focusedInto === 1, 'focus moved into it');
    pop.querySelector('.gd-pop-x').click();
    assert(!t.doc.querySelector('.secrets-popover'), '× closes it');
  }

  console.log(' a double click on Save posts once; Save comes back after a refusal');
  {
    const t = boot(() => ({ ok: true, status: 200, text: async () => 'FORM' }));
    await t.ctx.openCreateSecretForm(t.doc.createElement('button'));
    const submit = t.doc.querySelector('[data-act="submit"]');
    submit.onclick();
    submit.onclick();
    await flush();
    assert(t.seen.posts === 1, 'one POST, got ' + t.seen.posts);
    assert(submit.disabled === true, 'disabled in flight');
    t.release();
    await flush();
    assert(submit.disabled === false, 're-enabled after the refusal');
    assert(t.doc.querySelector('.popover-error').textContent === 'exists', 'refusal shown');
  }

  if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
  console.log(`✓ ${passes} passed`);
})();
