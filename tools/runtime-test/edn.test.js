// Unit tests for graphden-edn.js — the hiccup-oriented EDN
// reader/printer behind the value-form `edn` field kind. Pure JS,
// node vm sandbox, no DOM at all.
//
// Run:  node tools/runtime-test/edn.test.js
// Exit: 0 on pass, 1 on failure (prints the failing case).

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ednSource = fs.readFileSync(
  path.join(__dirname, '..', '..', 'resources', 'packages', 'web',
            'runtime', 'graphden-edn.js'),
  'utf8');

const ctx = vm.createContext({ console });
vm.runInContext(ednSource, ctx);
const { parseEdn, printEdn } = ctx;

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes++; return; }
  failures++;
  console.error('  ✗ ' + msg);
}

function eq(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

function test(name, fn) {
  console.log(' ' + name);
  try { fn(); }
  catch (e) { failures++; console.error('  ✗ threw: ' + e.message); }
}

function parseError(text) {
  try { parseEdn(text); return null; }
  catch (e) { return e.message; }
}

// =============================================================================
// READER
// =============================================================================

test('scalars', () => {
  assert(parseEdn('nil') === null, 'nil → null');
  assert(parseEdn('true') === true, 'true');
  assert(parseEdn('false') === false, 'false');
  assert(parseEdn('42') === 42, 'int');
  assert(parseEdn('-3.5') === -3.5, 'negative float');
  assert(parseEdn('1e3') === 1000, 'exponent');
  assert(parseEdn('"hi"') === 'hi', 'string');
  assert(parseEdn('"a\\n\\"b\\""') === 'a\n"b"', 'string escapes');
  assert(parseEdn('"\\u0041"') === 'A', 'unicode escape');
});

test('keywords normalize to their name string (stored form)', () => {
  assert(parseEdn(':div') === 'div', ':div → "div"');
  assert(parseEdn(':my.ns/thing') === 'my.ns/thing', 'namespaced keyword');
});

test('collections', () => {
  assert(eq(parseEdn('[1 2 3]'), [1, 2, 3]), 'vector');
  assert(eq(parseEdn('(1 2)'), [1, 2]), 'list reads as array');
  assert(eq(parseEdn('{:a 1 "b" 2}'), { a: 1, b: 2 }),
         'map with keyword + string keys');
  assert(eq(parseEdn('[1, 2,, 3]'), [1, 2, 3]), 'commas are whitespace');
  assert(eq(parseEdn('[1 ;; comment\n 2]'), [1, 2]), 'line comments skipped');
});

test('hiccup round shape', () => {
  assert(eq(parseEdn('[:div {:class "x"} "hi" [:b 1]]'),
            ['div', { class: 'x' }, 'hi', ['b', 1]]),
         'hiccup normalizes to string tags / attr keys');
});

test('errors are located and phrased for humans', () => {
  assert(/symbol 'foo'/.test(parseError('foo')), 'bare symbol rejected');
  assert(/line 1:/.test(parseError('foo')), 'error carries line:col');
  assert(/closing/.test(parseError('[1 2')), 'unclosed vector');
  assert(/unterminated/.test(parseError('"abc')), 'unclosed string');
  assert(/key with no value/.test(parseError('{:a}')), 'odd map');
  assert(/keys must be/.test(parseError('{1 2}')), 'non-string map key');
  assert(/trailing/.test(parseError('1 2')), 'trailing content');
  assert(/tagged literals/.test(parseError('#{1}')), 'sets rejected');
  assert(/auto-resolved/.test(parseError('::kw')), ':: keywords rejected');
  assert(/char literals/.test(parseError('\\a')), 'char literals rejected');
  assert(/bad number/.test(parseError('12abc')), 'malformed number');
  assert(parseError('[]') === null, 'empty vector fine');
});

// =============================================================================
// PRINTER
// =============================================================================

test('atoms print as EDN', () => {
  assert(printEdn(null) === 'nil', 'null → nil');
  assert(printEdn(true) === 'true', 'true');
  assert(printEdn(5) === '5', 'number');
  assert(printEdn('hi') === '"hi"', 'top-level string stays quoted');
});

test('tag / key positions print as keywords', () => {
  assert(printEdn(['div', { class: 'x' }, 'hi'])
         === '[:div {:class "x"} "hi"]',
         'short hiccup prints inline with keyword sugar');
  assert(printEdn({ 'not a kw': 1 }) === '{"not a kw" 1}',
         'a non-keywordable key stays a quoted string');
  assert(printEdn(['some text', 'b']) === '["some text" "b"]',
         'a tag-position string with spaces stays quoted');
});

test('long values break into hiccup-style lines', () => {
  const v = ['div', { class: 'container wide' },
             ['h1', { class: 'title' }, 'A heading long enough to wrap'],
             ['p', 'body text that pushes the total width past the limit']];
  const printed = printEdn(v);
  assert(printed.includes('\n'), 'multi-line for long trees');
  assert(printed.startsWith('[:div {:class "container wide"}\n'),
         'tag + attrs stay on the opening line');
  assert(printed.split('\n').slice(1).every((l) => l.startsWith(' ')),
         'continuation lines are indented');
});

test('print → parse round-trips the stored value', () => {
  const values = [
    ['div', { class: 'x', 'data-n': 3 }, 'hi', ['b', 'bold'], null, true],
    { a: [1, 2, { b: 'c' }] },
    ['some text', 'b'],
    [],
    {},
    'plain',
    3.25,
  ];
  for (const v of values) {
    assert(eq(parseEdn(printEdn(v)), v),
           'round-trip: ' + JSON.stringify(v));
  }
});

console.log(failures === 0
  ? `PASS — ${passes} assertions`
  : `FAIL — ${failures} of ${passes + failures} assertions failed`);
process.exit(failures === 0 ? 0 : 1);
