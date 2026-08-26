// The raw-value escape hatch on the type-aware value form
// (editor-value-form.js `installRawToggle`).
//
// A typed form renders controls for the RESOLVED slot type — the
// toggle guarantees the author can always enter a value of a DIFFERENT
// shape: another JSON object, a bare number, a plain string. Asserted
// here as pure DOM+data transforms over mini-dom:
//   - the toggle mounts on a typed form, not on an already-raw one
//   - raw mode is ONE smart-parse textarea, prefilled from the typed
//     controls (or blank when nothing was typed)
//   - a number / a bare string / arbitrary JSON collect cleanly in
//     raw mode (the user-blocked case this feature removes)
//   - toggling back restores the typed controls and carries a
//     shape-matching raw value into them
//   - a string that LOOKS like JSON round-trips quoted
//
// Run:  node tools/runtime-test/value-form-raw-toggle.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const ROOT = path.join(__dirname, '..', '..', 'resources', 'packages');
const document = createDocument();

// Minimal event class — installRawToggle fires a synthetic 'change'
// at the host to refresh live validation.
class MiniEvent {
  constructor(type, opts) { this.type = type; Object.assign(this, opts || {}); }
}

const ctx = vm.createContext({
  console,
  document,
  window: {},
  Event: MiniEvent,
  // editor-value-form.js wires its read-only viewer's dismissal at
  // load time; the toggle under test never reaches it.
  installPopoverDismiss() {},
});

for (const f of [
  ['web', 'runtime', 'graphden-edn.js'],
  ['web', 'runtime', 'graphden-forms.js'],
  ['app', 'editor', 'editor-value-form.js'],
]) {
  const p = path.join(ROOT, ...f);
  vm.runInContext(fs.readFileSync(p, 'utf8'), ctx, { filename: f[f.length - 1] });
}

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  failures += 1;
  console.error('  ✗ ' + msg);
}

function eq(actual, expected, msg) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  assert(a === e, msg + ' — expected ' + e + ', got ' + a);
}

function test(name, fn) {
  console.log(' ' + name);
  try { fn(); } catch (e) { failures += 1; console.error('  ✗ threw: ' + e.message); }
}

// A typed RECORD form as the server renders it: root carries
// data-form-root; each field carries data-form-field + path + kind.
function recordFormHost() {
  const host = document.createElement('div');
  host.className = 'value-form-host';
  const root = document.createElement('div');
  root.setAttribute('data-form-root', '');
  for (const [p, kind] of [['status', 'number'], ['body', 'text']]) {
    const el = document.createElement('input');
    el.setAttribute('data-form-field', '');
    el.setAttribute('data-field-path', p);
    el.setAttribute('data-field-kind', kind);
    el.value = '';
    root.appendChild(el);
  }
  host.appendChild(root);
  return host;
}

function rawTextarea(host) {
  return host.querySelector('[data-form-root]')
    .querySelector('textarea[data-field-kind="any"]');
}

test('toggle mounts on a typed record form', () => {
  const host = recordFormHost();
  ctx.installRawToggle(host, { status: 200, body: 'Hello!' });
  assert(host.querySelector('.value-form-raw-toggle'), 'toggle button present');
});

test('no toggle on an already-raw (single json textarea) form', () => {
  const host = document.createElement('div');
  const root = document.createElement('div');
  root.setAttribute('data-form-root', '');
  const ta = document.createElement('textarea');
  ta.setAttribute('data-form-field', '');
  ta.setAttribute('data-field-kind', 'json');
  root.appendChild(ta);
  host.appendChild(root);
  ctx.installRawToggle(host, null);
  assert(!host.querySelector('.value-form-raw-toggle'),
         'raw fallback form gets no redundant toggle');
});

test('raw mode replaces the typed controls with one smart-parse textarea', () => {
  const host = recordFormHost();
  const root = host.querySelector('[data-form-root]');
  ctx.installRawToggle(host, { status: 200, body: 'Hello!' });
  host.querySelector('.value-form-raw-toggle').click();
  const ta = rawTextarea(host);
  assert(ta, 'textarea mounted inside the form root');
  eq(root.querySelectorAll('[data-form-field]').length, 1,
     'the textarea is the only collectable field');
  eq(JSON.parse(ta.value), { status: 200, body: 'Hello!' },
     'prefilled from the bound value when the typed controls are blank');
});

test('typed-in field values win over the bound value as raw prefill', () => {
  const host = recordFormHost();
  const root = host.querySelector('[data-form-root]');
  const fields = root.querySelectorAll('[data-form-field]');
  fields[0].value = '404';
  fields[1].value = 'edited';
  ctx.installRawToggle(host, { status: 200, body: 'Hello!' });
  host.querySelector('.value-form-raw-toggle').click();
  eq(JSON.parse(rawTextarea(host).value), { status: 404, body: 'edited' },
     'raw prefill reflects the live controls');
});

test('a bare number and a plain string collect cleanly in raw mode', () => {
  const host = recordFormHost();
  const root = host.querySelector('[data-form-root]');
  ctx.installRawToggle(host, null);
  host.querySelector('.value-form-raw-toggle').click();
  const ta = rawTextarea(host);
  eq(ta.value, '', 'blank form prefits an empty textarea');
  ta.value = '5';
  eq(ctx.collectFormValue(root), { ok: true, value: 5, errors: [] },
     'a number is a saveable value');
  ta.value = 'hello world';
  eq(ctx.collectFormValue(root), { ok: true, value: 'hello world', errors: [] },
     'non-JSON text smart-parses to a string, never an error');
  ta.value = '{"other": {"shape": true}}';
  eq(ctx.collectFormValue(root).value, { other: { shape: true } },
     'arbitrary JSON of a different shape is saveable');
});

test('toggling back restores the typed controls and carries the value', () => {
  const host = recordFormHost();
  const root = host.querySelector('[data-form-root]');
  ctx.installRawToggle(host, { status: 200, body: 'Hello!' });
  const btn = host.querySelector('.value-form-raw-toggle');
  btn.click();
  rawTextarea(host).value = '{"status": 404, "body": "gone"}';
  btn.click();
  const fields = root.querySelectorAll('[data-form-field]');
  eq(fields.length, 2, 'both typed controls are back');
  eq(fields[0].value, '404', 'matching raw value landed in the status field');
  eq(fields[1].value, 'gone', 'matching raw value landed in the body field');
  eq(btn.textContent, '{} raw', 'button label back to raw affordance');
});

test('a string that parses as JSON round-trips QUOTED through raw mode', () => {
  const host = document.createElement('div');
  const root = document.createElement('div');
  root.setAttribute('data-form-root', '');
  const el = document.createElement('input');
  el.setAttribute('data-form-field', '');
  el.setAttribute('data-field-kind', 'text');
  el.setAttribute('data-field-path', 'note');
  el.value = '{"a": 1}';           // a STRING whose text is JSON
  root.appendChild(el);
  host.appendChild(root);
  ctx.installRawToggle(host, null);
  host.querySelector('.value-form-raw-toggle').click();
  eq(ctx.collectFormValue(root).value, { note: '{"a": 1}' },
     'smart-parse returns the original string, not an object');
});

console.log('');
if (failures) {
  console.error('FAIL — ' + failures + ' failed, ' + passes + ' passed');
  process.exit(1);
}
console.log('PASS — ' + passes + ' assertions');
process.exit(0);
