// `graphden-forms.js` — the value ⇄ control bridge behind every
// server-rendered value form.
//
// It ships in TWO bundles: the editor's, and the standalone
// `/assets/graphden-runtime.js` that graph-composed user pages load. That
// makes it public API, and until now the only coverage it had was whatever
// an e2e edit-flow happened to walk through — nothing pinned the coercion
// table itself, and nothing pinned the union-branch rule that decides which
// half of a form contributes to the submitted value.
//
// Everything asserted here is a data transform: text in, value out, and
// back. The DOM is `mini-dom` (attribute selectors + `closest`, which is
// all `collectFormValue` asks for).
//
// Run:  node tools/runtime-test/forms.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const RUNTIME = path.join(__dirname, '..', '..', 'resources', 'packages', 'web', 'runtime');

const document = createDocument();
const ctx = vm.createContext({ console, document, window: {} });
// forms.js delegates the `edn` kind to graphden-edn.js, so load both.
for (const f of ['graphden-edn.js', 'graphden-forms.js']) {
  vm.runInContext(fs.readFileSync(path.join(RUNTIME, f), 'utf8'), ctx, { filename: f });
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

// A control as the server renders it: `data-form-field` marks it, the path
// says where its value lands, the kind says how to coerce.
function field(kind, fieldPath, props) {
  const el = document.createElement(props && props.tag ? props.tag : 'input');
  el.setAttribute('data-form-field', '');
  el.setAttribute('data-field-path', fieldPath == null ? '' : fieldPath);
  el.setAttribute('data-field-kind', kind);
  Object.assign(el, props || {});
  return el;
}

function form(...fields) {
  const root = document.createElement('div');
  for (const f of fields) root.appendChild(f);
  return root;
}


test('readFieldValue coerces per kind', () => {
  const read = (kind, props) => ctx.readFieldValue(field(kind, '', props), kind);

  eq(read('text', { value: '  hi  ' }), { value: '  hi  ' },
     'text is passed through UNTRIMMED — leading space can be meaningful');
  eq(read('bool', { checked: true }), { value: true }, 'checked box is true');
  eq(read('bool', {}), { value: false }, 'unchecked box is false, not undefined');

  eq(read('number', { value: '42' }), { value: 42 }, 'number parses');
  eq(read('number', { value: '  7 ' }), { value: 7 }, 'number tolerates padding');
  eq(read('number', { value: '' }), { value: null }, 'empty number is null, not 0');
  const bad = read('number', { value: 'abc' });
  assert(bad.error === 'not a number' && bad.value === 'abc',
         'an unparseable number reports an error AND keeps the raw text so the '
         + 'user does not lose what they typed');

  eq(read('keyword', { value: 'get' }), { value: ':get' }, 'keyword gets its colon');
  eq(read('keyword', { value: ':get' }), { value: ':get' }, 'an existing colon is not doubled');
  eq(read('keyword', { value: '' }), { value: null }, 'empty keyword is null');

  eq(read('json', { value: '{"a":1}' }), { value: { a: 1 } }, 'json parses');
  eq(read('json', { value: '' }), { value: null }, 'empty json is null');
  const badJson = read('json', { value: '{a:1}' });
  assert(badJson.error === 'invalid JSON' && badJson.value === '{a:1}',
         'invalid JSON reports and preserves');

  // `any` is the fallback control used when /api/value-form is unreachable.
  // It must NEVER error — an error there blocks a save with no way forward.
  eq(read('any', { value: '[1,2]' }), { value: [1, 2] }, 'any prefers JSON');
  eq(read('any', { value: 'not json' }), { value: 'not json' }, 'any falls back to text');
  assert(read('any', { value: '{oops' }).error === undefined,
         'the fallback control never errors');
});


test('writeFieldValue is the inverse of readFieldValue', () => {
  const roundtrip = (kind, value, props) => {
    const el = field(kind, '', props);
    ctx.writeFieldValue(el, kind, value);
    return ctx.readFieldValue(el, kind).value;
  };
  eq(roundtrip('text', 'hello'), 'hello', 'text roundtrips');
  eq(roundtrip('number', 42), 42, 'number roundtrips');
  eq(roundtrip('keyword', ':post'), ':post', 'keyword roundtrips');
  eq(roundtrip('keyword', 'post'), ':post', 'a bare keyword is normalised on write');
  eq(roundtrip('json', { a: [1, 2] }), { a: [1, 2] }, 'json roundtrips');
  eq(roundtrip('bool', true), true, 'true roundtrips');
  eq(roundtrip('bool', false), false, 'false roundtrips');
});


test('writeFieldValue leaves a <select> on its default for a null value', () => {
  // A <select> has no blank option; `value = ''` sets selectedIndex -1 and
  // the dropdown renders blank, then fails validation on save.
  const sel = field('enum', '', { tag: 'select', value: 'get' });
  ctx.writeFieldValue(sel, 'enum', null);
  assert(sel.value === 'get', 'the enum keeps its first valid member, got ' + sel.value);

  const input = field('text', '', { value: 'x' });
  ctx.writeFieldValue(input, 'text', null);
  assert(input.value === '', 'a text input DOES clear, got ' + input.value);
});


test('setFormPath / getFormPath build and read nested paths', () => {
  const obj = {};
  ctx.setFormPath(obj, ['a', 'b', 'c'], 1);
  eq(obj, { a: { b: { c: 1 } } }, 'missing levels are created');
  ctx.setFormPath(obj, ['a', 'd'], 2);
  eq(obj, { a: { b: { c: 1 }, d: 2 } }, 'a sibling does not clobber the branch');

  assert(ctx.getFormPath(obj, ['a', 'b', 'c']) === 1, 'reads back');
  assert(ctx.getFormPath(obj, ['a', 'nope', 'deep']) === undefined,
         'a missing path is undefined, not a throw');
  assert(ctx.getFormPath(undefined, ['a']) === undefined, 'nil-safe at the root');
});


test('collectFormValue: one empty-path field IS the scalar value', () => {
  const root = form(field('number', '', { value: '5' }));
  eq(ctx.collectFormValue(root), { ok: true, value: 5, errors: [] },
     'a scalar form yields the scalar, not {"": 5}');
});


test('collectFormValue assembles a nested record', () => {
  const root = form(
    field('text', 'name', { value: 'ada' }),
    field('number', 'addr.port', { value: '8080' }),
    field('bool', 'addr.tls', { checked: true }));
  eq(ctx.collectFormValue(root),
     { ok: true, value: { name: 'ada', addr: { port: 8080, tls: true } }, errors: [] },
     'paths build the object');
});


test('collectFormValue reports every bad field, labelled by path', () => {
  const root = form(
    field('number', 'port', { value: 'nope' }),
    field('json', 'meta', { value: '{' }),
    field('text', 'name', { value: 'ok' }));
  const res = ctx.collectFormValue(root);
  assert(res.ok === false, 'a form with errors is not ok');
  eq(res.errors.sort(), ['meta: invalid JSON', 'port: not a number'],
     'both fields are named');
});


test('collectFormValue ignores controls inside a hidden union branch', () => {
  // This is the rule that decides what a union submits. A control in the
  // inactive branch still exists in the DOM — counting it would send the
  // other variant's value along with the chosen one.
  const active = document.createElement('div');
  active.appendChild(field('text', 'value', { value: 'chosen' }));
  const inactive = document.createElement('div');
  inactive.hidden = true;
  inactive.appendChild(field('number', 'value', { value: '999' }));

  const root = document.createElement('div');
  root.appendChild(active);
  root.appendChild(inactive);

  eq(ctx.collectFormValue(root), { ok: true, value: { value: 'chosen' }, errors: [] },
     'only the visible branch contributes');
});


test('a hidden branch cannot fail the form either', () => {
  const inactive = document.createElement('div');
  inactive.hidden = true;
  inactive.appendChild(field('number', 'n', { value: 'garbage' }));
  const root = document.createElement('div');
  root.appendChild(field('text', 'ok', { value: 'fine' }));
  root.appendChild(inactive);

  const res = ctx.collectFormValue(root);
  assert(res.ok === true,
         'an unparseable value in the branch nobody chose must not block the save: '
         + JSON.stringify(res.errors));
});


test('fillFormValue round-trips a whole nested form', () => {
  const fields = [
    field('text', 'name'),
    field('number', 'addr.port'),
    field('bool', 'addr.tls'),
  ];
  const root = form(...fields);
  const value = { name: 'ada', addr: { port: 8080, tls: true } };
  ctx.fillFormValue(root, value);
  eq(ctx.collectFormValue(root).value, value, 'fill then collect is identity');
});


test('fillFormValue skips hidden branches, so they cannot leak in', () => {
  const inactive = document.createElement('div');
  inactive.hidden = true;
  const hiddenField = field('text', 'name');
  hiddenField.value = 'stale';
  inactive.appendChild(hiddenField);
  const root = document.createElement('div');
  root.appendChild(inactive);

  ctx.fillFormValue(root, { name: 'fresh' });
  assert(hiddenField.value === 'stale',
         'the inactive branch is not written, got ' + hiddenField.value);
});


console.log(failures === 0
  ? '✓ forms — ' + passes + ' assertions'
  : '✗ forms — ' + failures + ' failed of ' + (passes + failures));
process.exit(failures === 0 ? 0 : 1);
