// The refinement-constraint builder (editor-create-type-fields.js) —
// the operator dropdown + value rows behind "New refinement".
//
// What it must get right is the WIRE spelling of the operators: the
// server's `base-allowed-ops` (types/check/literals.clj) knows `:not=`,
// and the builder used to offer `!=` — every inequality the form
// produced was rejected as "not legal on base type", and an existing
// `[:not= …]` row opened for editing with no operator selected. Both
// directions are pinned here: what `collect()` serialises, and which
// option a prefilled constraint lands on.
//
// Run:  node tools/runtime-test/refinement-builder.test.js
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

function test(name, fn) {
  console.log(' ' + name);
  try { fn(); } catch (e) { failures += 1; console.error('  ✗ threw: ' + e.message); }
}

function builderCtx() {
  const document = createDocument();
  const ctx = vm.createContext({ console, document });
  vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-create-type-fields.js'), 'utf8'),
                  ctx, { filename: 'editor-create-type-fields.js' });
  return ctx;
}

function optionValues(sel) {
  return sel.children.map((o) => o.value);
}

test('every base offers the server spelling `not=`, never `!=`', () => {
  const ctx = builderCtx();
  for (const base of ['int', 'numeric', 'float', 'text', 'bool', 'keyword',
                      'uuid', 'timestamptz', 'null']) {
    const ops = ctx.refinementOpsFor(base);
    assert(ops.includes('not='), base + ' offers not=');
    assert(!ops.includes('!='), base + ' does not offer !=');
  }
});

test('the inequality row serialises as the checker op `:not=`', () => {
  const ctx = builderCtx();
  const f = ctx.buildRefinementFields('dl', null);
  f.el.querySelector('input.type-create-input').value = 'text';
  const opSel = f.el.querySelector('.refinement-op');
  assert(optionValues(opSel).includes(':not='), 'an option carries :not=');
  assert(!optionValues(opSel).includes(':!='), 'no option carries :!=');
  const neq = opSel.children.find((o) => o.value === ':not=');
  assert(neq && neq.textContent === '≠', 'the option reads ≠ (display only)');
  opSel.value = ':not=';
  f.el.querySelector('.refinement-val').value = '';
  f.el.querySelector('.refinement-val').value = 'x';
  const out = f.collect();
  assert(out.kind === 'refinement', 'kind is refinement');
  assert(out.body.constraint === '[":not=","x"]',
         'constraint is [":not=","x"], got ' + out.body.constraint);
});

test('an existing [:not= ""] row prefills onto the not= option', () => {
  const ctx = builderCtx();
  const f = ctx.buildRefinementFields('dl', { base: 'text', constraint: '["not=", ""]' });
  const opSel = f.el.querySelector('.refinement-op');
  assert(opSel !== null, 'a builder row was created (not the raw-JSON fallback)');
  assert(opSel.value === ':not=', 'the not= option is selected, got ' + opSel.value);
  assert(f.collect().body.constraint === '[":not=",""]',
         'round-trips unchanged, got ' + f.collect().body.constraint);
});

test('a combined constraint prefills each row onto an existing option', () => {
  // The `:and` / `:or` arm passed the stored op through WITHOUT the
  // leading colon the option values carry, so no row was selected.
  const ctx = builderCtx();
  const f = ctx.buildRefinementFields('dl', {
    base: 'int', constraint: '["and", [">=", 1], ["not=", 5]]',
  });
  const rows = f.el.querySelectorAll('.refinement-op');
  assert(rows.length === 2, 'two rows, got ' + rows.length);
  assert(rows[0].value === ':>=', 'first row on >=, got ' + rows[0].value);
  assert(rows[1].value === ':not=', 'second row on not=, got ' + rows[1].value);
  assert(f.collect().body.constraint === '[":and",[":>=",1],[":not=",5]]',
         'round-trips, got ' + f.collect().body.constraint);
});

test('a hand-typed `!=` (raw JSON of an older row) lands on not= too', () => {
  const ctx = builderCtx();
  const f = ctx.buildRefinementFields('dl', { base: 'int', constraint: '["!=", 0]' });
  const opSel = f.el.querySelector('.refinement-op');
  assert(opSel !== null && opSel.value === ':not=',
         'legacy != prefills onto not=, got ' + (opSel && opSel.value));
});

console.log('\n' + passes + ' passed, ' + failures + ' failed');
process.exit(failures ? 1 : 0);
