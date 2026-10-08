// Two graph occurrences joined as a replacement; exact source identity and
// literal presence come from its branch-scoped subtree, never the label ∅.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const {createDocument} = require('./mini-dom');
const document = createDocument();
const ctx = vm.createContext({console, document, localStorage: {getItem: () => null}});
ctx.window = ctx;
const source = name => fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/', name), 'utf8');
for (const name of ['editor-diff-mode.js', 'editor-diff-inspector.js', 'editor-diff-ghost.js']) {
  vm.runInContext(source(name), ctx);
}
const binding = (id, slot, text, change = 'modified') => ({'entity-name': 'binding',
  'entity-id': id, 'binding-id': id, 'slot-id': slot, change,
  fields: [{field: 'value', source: text, target: 'old'}]});
const item = (id, position, text) => ({'entity-name': 'binding-list-item',
  'entity-id': id, 'item-id': id, 'binding-id': 'list-binding', 'slot-id': 'list-slot',
  change: 'modified', position, fields: [{field: 'value', source: text, target: 'old'}]});
ctx.fixture = {branch: 'source', byFnId: new Map([['owner', {'fn-id': 'owner', __kind: 'modified', entries: [
  binding('one-binding', 'one-slot', '∅'), binding('two-binding', 'two-slot', 'false'),
  binding('added-binding', 'added-slot', 'added', 'added-in-source'),
  item('one-item', 0, 'first'), item('two-item', 1, 'second'),
]}]]), affected: new Map()};
vm.runInContext('_gdDiffMode = fixture;', ctx);
ctx.argRowFromNode = data => data.arg;
const arg = (slot, itemId) => ({'fn-id': 'owner', 'slot-id': slot, 'item-id': itemId, name: 'renamed'});
const node = (id, slot, itemId) => ({id: () => id,
  data: key => key ? (key === 'type' ? 'value' : false) : {arg: arg(slot, itemId)}});
ctx.gv = {nodes: () => [node('first-use', 'one-slot'), node('second-use', 'one-slot'),
  node('other-slot', 'two-slot'), node('first-item', 'list-slot', 'one-item'),
  node('second-item', 'list-slot', 'two-item'), node('added', 'added-slot')], edges: () => []};
const wants = ctx._gdGhostWants();
assert.equal(wants.length, 5, 'separate uses and list items keep separate counterparts; additions remain additions');
assert.deepEqual(Array.from(wants, want => want.entityId),
  ['one-binding', 'one-binding', 'two-binding', 'one-item', 'two-item']);
assert.deepEqual(Array.from(wants, want => want.text), ['∅', '∅', 'false', 'first', 'second']);
assert.equal(ctx.gdDiffLiteralChange(ctx.gdDiffSlotDetails('owner')['list-slot']), null,
  'the aggregate list never borrows its first changed item literal');
const one = {'id': 'one-binding', 'fn-id': 'owner', 'slot-id': 'one-slot', value: '∅'};
const list = {id: 'list-binding', 'fn-id': 'owner', 'slot-id': 'list-slot'};
const lk = {bindingsByFn: new Map([['owner', [one, list]]]),
  itemsByBinding: new Map([['list-binding', [{id: 'one-item', value: 'first'}, {id: 'two-item', value: 'second'}]]])};
const literal = ctx._gdGhostLiteralEl(lk, wants[0], 'source');
assert.ok(literal, 'a real literal string ∅ is not confused with absent value');
assert.equal(literal.dataset.entityId, 'one-binding');
assert.equal(literal.dataset.anchorId, 'first-use');
assert.equal(literal.dataset.branch, 'source');
assert.match(literal.textContent, /read-only/);
assert.equal(literal.querySelectorAll('button,input,textarea').length, 0, 'source literal has no edit controls');
assert.equal(ctx.gdDiffWasEl(ctx.gdDiffArgDetails(arg('one-slot'))), null,
  'the replacement is not repeated as an inline there value');
const itemLiteral = ctx._gdGhostLiteralEl(lk, wants[4], 'source');
assert.equal(itemLiteral.dataset.itemId, 'two-item');
assert.match(itemLiteral.textContent, /second/);
for (const value of [null, undefined]) {
  one.value = value;
  assert.equal(ctx._gdGhostLiteralEl(lk, wants[0], 'source'), null, 'missing source value has no fabricated node');
}
one.value = false;
assert.ok(ctx._gdGhostLiteralEl(lk, {...wants[0], text: 'false'}, 'source'), 'false is a present literal');
one['ref-fn-id'] = 'source-ref';
assert.equal(ctx._gdGhostLiteralEl(lk, wants[0], 'source'), null, 'a source fn-ref is not a literal counterpart');
delete one['ref-fn-id'];
one['slot-id'] = 'different-slot';
assert.equal(ctx._gdGhostLiteralEl(lk, wants[0], 'source'), null, 'changed source slot identity refuses the annotation');
const link = ctx._gdGhostLinkEl();
ctx._gdGhostReplacementLabel(link);
assert.equal(link.querySelector('[class="gd-ghost-replacement-label"]').textContent, 'replacement');
assert.equal(ctx.gdDiffLiteralChange({change: 'modified', entityId: 'binding', sourceRef: 'ref',
  fields: [{field: 'value', source: '∅'}]}), null, 'fn-ref counterparts remain source subtrees');
(async () => {
  // Finishing an old source read after a render with no counterparts cannot
  // repopulate the current graph. It also protects ordinary ref ghosts.
  let finishRead;
  delete one['ref-fn-id'];
  one['slot-id'] = 'one-slot';
  one.value = '∅';
  ctx.API = {api_graph_entities: '/api/graph/entities'};
  ctx.BRANCH_HEADER = 'X-Graphden-Branch';
  ctx.buildLookups = () => lk;
  ctx.fetch = () => new Promise(resolve => { finishRead = resolve; });
  ctx.gv.node = () => ({position: () => ({x: 0, y: 0}), width: () => 100, height: () => 40});
  ctx.gdDiffGhostsRender(document.body);
  ctx.gv.nodes = () => [];
  ctx.gdDiffGhostsRender(document.body);
  finishRead({ok: true, json: async () => ({fns: [{id: 'owner'}]})});
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(document.body.querySelectorAll('.gd-ghost-literal').length, 0,
    'old async counterpart never reappears after its occurrence is removed');
  console.log('diff literal counterparts: PASS');
})().catch(error => {console.error(error); process.exitCode = 1;});
