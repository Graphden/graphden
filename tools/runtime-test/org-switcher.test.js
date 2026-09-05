// `gdRenderOrgPopover` (editor-org-switcher.js) — the org chip's popover,
// built from a plain {orgs, current} model: one row per org (the current one
// inert and marked, owner badge + plan where known) and the inline
// "New organization…" form, whose submit hands the normalised name to the
// creator and either switches into the new org or shows the server's own
// refusal message. Pure DOM over mini-dom; no browser, no stack.
//
// Run:  node tools/runtime-test/org-switcher.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  failures += 1;
  console.error('  ✗ ' + msg);
}

async function test(name, fn) {
  console.log(' ' + name);
  try { await fn(); } catch (e) { failures += 1; console.error('  ✗ threw: ' + e.message); }
}

function load() {
  const document = createDocument();
  const ctx = vm.createContext({ console, document, window: { location: { hostname: 'localhost' } } });
  vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-org-switcher.js'), 'utf8'), ctx,
                  { filename: 'editor-org-switcher.js' });
  return { ctx, document };
}

const tick = () => new Promise((r) => setImmediate(r));

(async () => {
  await test('one row per org; the current one is inert and marked; owner badge + plan', () => {
    const { ctx, document } = load();
    const pop = document.createElement('div');
    const switched = [];
    ctx.gdRenderOrgPopover(pop,
      { orgs: [{ name: 'acme', 'owner?': true, plan: 'network' }, { name: 'beta', 'owner?': false, plan: 'free' }],
        current: 'acme' },
      { onSwitch: (n) => switched.push(n), onCreate: async () => ({ ok: true }) });
    const rows = pop.querySelectorAll('button.org-popover-item').filter((b) => !b.className.includes('org-popover-new'));
    assert(rows.length === 2, 'two org rows (got ' + rows.length + ')');
    assert(rows[0].disabled === true && rows[0].getAttribute('aria-current') === 'true',
           'the current org is inert and aria-current');
    assert(rows[0].querySelector('.org-popover-badge')?.textContent === 'owner', 'owner badge on the owned org (wire key owner?)');
    assert(!rows[1].querySelector('.org-popover-badge'), 'no badge on a plain membership');
    assert(rows[1].querySelector('.org-popover-plan')?.textContent === 'free', 'the plan is shown');
    rows[1].click();
    rows[0].click();
    assert(switched.length === 1 && switched[0] === 'beta', 'clicking another org switches to it; the current one does nothing');
  });

  await test('names-only memberships (older server) render without badge or plan', () => {
    const { ctx, document } = load();
    const pop = document.createElement('div');
    ctx.gdRenderOrgPopover(pop, { orgs: [{ name: 'solo' }], current: 'solo' },
                           { onSwitch: () => {}, onCreate: async () => ({ ok: true }) });
    assert(pop.querySelectorAll('.org-popover-badge').length === 0 && pop.querySelectorAll('.org-popover-plan').length === 0,
           'no badge, no plan');
    assert(pop.querySelector('.org-popover-new') != null, 'the New organization… entry is there even with one org');
  });

  await test('New organization…: the form opens, submit normalises the name, success switches', async () => {
    const { ctx, document } = load();
    const pop = document.createElement('div');
    const created = [];
    const switched = [];
    ctx.gdRenderOrgPopover(pop, { orgs: [{ name: 'acme' }], current: 'acme' },
      { onSwitch: (n) => switched.push(n),
        onCreate: async (n) => { created.push(n); return { ok: true, org: n }; } });
    const form = pop.querySelector('.org-popover-form');
    assert(form.hidden === true, 'the form starts hidden');
    pop.querySelector('.org-popover-new').click();
    assert(form.hidden === false, 'New organization… reveals the form');
    const input = pop.querySelector('.org-popover-input');
    input.value = '  Acme-Labs ';
    const btns = pop.querySelectorAll('.org-popover-btn');
    btns.find((b) => b.textContent === 'Create').click();
    await tick(); await tick();
    assert(created.length === 1 && created[0] === 'acme-labs', 'the name is trimmed + lower-cased before it leaves (got ' + JSON.stringify(created) + ')');
    assert(switched.length === 1 && switched[0] === 'acme-labs', 'success switches into the new org');
  });

  await test('a refusal shows the server message in the alert line and keeps the form', async () => {
    const { ctx, document } = load();
    const pop = document.createElement('div');
    const switched = [];
    ctx.gdRenderOrgPopover(pop, { orgs: [{ name: 'acme' }], current: 'acme' },
      { onSwitch: (n) => switched.push(n),
        onCreate: async () => ({ ok: false, error: 'org/name-taken', message: 'That name is taken.' }) });
    pop.querySelector('.org-popover-new').click();
    pop.querySelector('.org-popover-input').value = 'beta';
    pop.querySelectorAll('.org-popover-btn').find((b) => b.textContent === 'Create').click();
    await tick(); await tick();
    const err = pop.querySelector('.org-popover-error');
    assert(err.hidden === false && err.textContent === 'That name is taken.', 'the refusal message is shown verbatim');
    assert(err.getAttribute('role') === 'alert', 'the error line is an alert');
    assert(switched.length === 0, 'no switch on refusal');
    assert(pop.querySelector('.org-popover-form').hidden === false, 'the form stays open for a retry');
    assert(pop.querySelectorAll('.org-popover-btn').find((b) => b.textContent === 'Create').disabled === false,
           'the buttons are re-enabled');
  });

  await test('an empty name is refused locally; Cancel hides the form and clears it', () => {
    const { ctx, document } = load();
    const pop = document.createElement('div');
    const created = [];
    ctx.gdRenderOrgPopover(pop, { orgs: [{ name: 'acme' }], current: 'acme' },
      { onSwitch: () => {}, onCreate: async (n) => { created.push(n); return { ok: true }; } });
    pop.querySelector('.org-popover-new').click();
    pop.querySelector('.org-popover-input').value = '   ';
    pop.querySelectorAll('.org-popover-btn').find((b) => b.textContent === 'Create').click();
    assert(created.length === 0, 'nothing is sent for an empty name');
    assert(pop.querySelector('.org-popover-error').hidden === false, 'the empty-name hint is shown');
    pop.querySelector('.org-popover-input').value = 'draft';
    pop.querySelectorAll('.org-popover-btn').find((b) => b.textContent === 'Cancel').click();
    assert(pop.querySelector('.org-popover-form').hidden === true, 'Cancel hides the form');
    assert(pop.querySelector('.org-popover-input').value === '', 'Cancel clears the draft');
    assert(pop.querySelector('.org-popover-new').hidden === false, 'the entry comes back');
  });

  console.log(failures === 0 ? 'PASS (' + passes + ' assertions)' : 'FAIL (' + failures + ' failed)');
  process.exit(failures === 0 ? 0 : 1);
})();
