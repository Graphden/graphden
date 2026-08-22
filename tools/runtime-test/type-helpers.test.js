// The editor's pure type helpers — refinement walks, kind labels,
// closed-enum detection, human-readable rendering, compact labels.
//
// Every function here maps a type shape to a value. Nothing touches
// the network, the DOM or the graph, so the test loads the three
// editor modules into a node `vm` and calls them directly.
//
// This used to be `tools/browser-test/type-system-ui-types.test.js`,
// which drove a chromium at a LIVE editor to assert that
// `shortTypeLabel(['list','text'])` is `'[text]'`. That cost a built
// image and a database per assertion, it ran in no CI (bb test-js
// deliberately excluded it; run-edit-tests.sh's glob excluded it too),
// and its inputs came from whatever types the server happened to ship
// — `refinementChain('user-port')` asserted a shape defined in
// packages, not in the test. Here the type registry is three lines of
// fixture, so the assertions say what they mean.
//
// Run:  node tools/runtime-test/type-helpers.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');
const MODULES = [
  'editor-type-expand-render.js',   // refinementChain, typeKindLabel
  'editor-type-format.js',          // formatTypeHumanReadable, shortTypeLabel
  'editor-literal-types.js',        // closedEnumOf
];

// The registry the editor gets from the server, written down instead of
// fetched. `user-port` is the shipped `[:refine :int [:and [:>= 1024]
// [:<= 65535]]]`; keeping a copy here is the point — a change to the
// package's definition must not silently change what this test proves.
const richTypes = {
  'user-port': { return: ['refine', 'int', ['and', ['>=', 1024], ['<=', 65535]]] },
  'positive-int': { return: ['refine', 'int', ['>', 0]] },
};

const ctx = vm.createContext({
  console,
  richTypes,
  lookups: { fnByName: new Map(), fnMap: new Map() },
  document: createDocument(),
});
for (const f of MODULES) {
  vm.runInContext(fs.readFileSync(path.join(EDITOR, f), 'utf8'), ctx, { filename: f });
}

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  failures += 1;
  console.error('  ✗ ' + msg);
}

function test(name, fn) {
  console.log(' ' + name);
  try { fn(); } catch (e) { failures += 1; console.error('  ✗ threw: ' + e.message); }
}


test('refinementChain walks refine links, then one primitive super', () => {
  const userPort = ctx.refinementChain('user-port');
  assert(userPort.steps.length === 3,
         'user-port → 3 steps (user-port, int, numeric), got ' + userPort.steps.length);
  assert(userPort.steps[0].name === 'user-port', 'first step is the type itself');
  assert(userPort.steps[1].name === 'int', 'second step is the refine base');
  assert(userPort.steps[2].name === 'numeric', 'third step is the primitive super');
  assert(JSON.stringify(userPort.steps[0].constraint)
         === JSON.stringify(['and', ['>=', 1024], ['<=', 65535]]),
         'the constraint is stamped on the step it narrows');
  assert(userPort.steps[2].constraint === null, 'the last step has no outgoing constraint');

  assert(ctx.refinementChain('int').steps.length === 2, ':int → (int, numeric)');
  assert(ctx.refinementChain('bool').steps.length === 1,
         ':bool is single-step — no super, no refine');
});


test('refinementChain terminates on a circular alias', () => {
  // A registry can be wrong; a walk that loops locks the editor's
  // popover thread. The implementation caps hops — pin that.
  const loopy = vm.createContext({
    console,
    richTypes: { a: { return: ['refine', 'b', ['>', 0]] },
                 b: { return: ['refine', 'a', ['>', 0]] } },
    lookups: { fnByName: new Map(), fnMap: new Map() },
    document: createDocument(),
  });
  for (const f of MODULES) {
    vm.runInContext(fs.readFileSync(path.join(EDITOR, f), 'utf8'), loopy, { filename: f });
  }
  const chain = loopy.refinementChain('a');
  assert(chain.steps.length <= 64, 'a cyclic alias must not walk forever');
});


test('typeKindLabel names structural kinds and stays quiet on primitives', () => {
  const cases = [
    [['map', 'keyword', 'text'], 'Map'],
    [['list', 'int'], 'List'],
    [['tuple', 'int', 'text'], 'Tuple'],
    [['fn', { a: 'int' }, 'bool'], 'Function'],
    [['refine', 'int', ['>', 0]], 'Refinement'],
    [['union', 'int', 'null'], 'Union'],
    [{ name: 'text', age: 'int' }, 'Record'],
    ['int', null],
    [null, null],
  ];
  for (const [input, expected] of cases) {
    const got = ctx.typeKindLabel(input);
    assert(got === expected,
           'typeKindLabel(' + JSON.stringify(input) + ') → '
           + JSON.stringify(expected) + ', got ' + JSON.stringify(got));
  }
});


test('closedEnumOf recognises only [:refine base [:in [...]]]', () => {
  const kw = ctx.closedEnumOf(['refine', 'keyword', ['in', ['ok', 'err']]]);
  assert(kw && kw.members.length === 2, 'two members found');
  assert(kw.members[0].label.startsWith(':'),
         'a :keyword-based enum renders members with a leading colon');
  assert(ctx.closedEnumOf(['refine', 'int', ['>', 0]]) === null,
         'a range refinement is not a closed enum');
  assert(ctx.closedEnumOf(['refine', 'text', ['not=', '']]) === null,
         'a [:not= …] refinement is not a closed enum');
  assert(ctx.closedEnumOf(['list', 'int']) === null,
         'a non-refinement is not a closed enum');
  assert(ctx.closedEnumOf(null) === null, 'null is not a closed enum');
});


test('formatTypeHumanReadable verbalises constraints', () => {
  assert(ctx.formatTypeHumanReadable(['refine', 'int', ['>', 0]]) === 'positive integer',
         ':int (> 0) short-circuits to the named phrase');
  const generic = ctx.formatTypeHumanReadable(
    ['refine', 'int', ['and', ['>=', 1024], ['<=', 65535]]]);
  assert(generic.includes('1024') && generic.includes('65535') && generic.includes('and'),
         'a compound refinement keeps both literals visible: ' + generic);
  assert(ctx.formatTypeHumanReadable(['refine', 'keyword', ['in', ['get', 'post']]])
           .includes('one of'),
         'a closed enum reads as "one of …"');
});


test('shortTypeLabel is the one compact notation every row uses', () => {
  const cases = [
    [null, 'any'],
    ['int', 'int'],
    [['refine', 'int', ['>', 0]], 'int'],
    [['list', 'text'], '[text]'],
    [['map', 'keyword', 'int'], '{keyword→int}'],
    [['tuple', 'int', 'text'], '(int,text)'],
    [['union', 'int', 'null'], 'union'],
    [['fn', { x: 'int' }, 'bool', []], 'fn'],
    [{ name: 'text' }, 'record'],
  ];
  for (const [input, expected] of cases) {
    const got = ctx.shortTypeLabel(input);
    assert(got === expected,
           'shortTypeLabel(' + JSON.stringify(input) + ') → "' + expected
           + '", got "' + got + '"');
  }
});


console.log(failures === 0
  ? '✓ type-helpers — ' + passes + ' assertions'
  : '✗ type-helpers — ' + failures + ' failed of ' + (passes + failures));
process.exit(failures === 0 ? 0 : 1);
