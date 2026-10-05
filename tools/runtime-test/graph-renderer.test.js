'use strict';

// The graph-to-DOM boundary rejects executable markup before DOM mutation.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const packages = path.join(__dirname, '../../resources/packages');
const ctx = vm.createContext({Map, Set, WeakSet, Symbol, console, CSS: {supports: () => true}});
ctx.window = ctx;
for (const file of ['web/vendor/preact.min.js', 'app/ui-preview/browser-runtime.js',
  'app/ui-preview/graph-styles.js', 'app/ui-preview/graph-renderer.js']) {
  vm.runInContext(fs.readFileSync(path.join(packages, file), 'utf8'), ctx, {filename: file});
}
const api = ctx.GraphdenBrowser;
const k = api.keyword;
const attrs = (value) => new Map(Object.entries(value).map(([name, item]) => [k(name), item]));
const button = (id) => [k('button'), attrs({key: id, type: 'button'}), id];
const tree = ctx.GraphdenRenderer.vnode([k('div'), attrs({class: 'rows'}), button('a'), button('b')]);
assert.equal(tree.type, 'div');
for (const limit of [0, 10001, NaN]) assert.throws(() => ctx.GraphdenRenderer.vnode([k('div')], null, limit));
assert.throws(() => ctx.GraphdenRenderer.vnode([k('div'), 'child'], null, 1));
assert.equal(ctx.GraphdenRenderer.vnode([k('div'), attrs({id: 'gd-test-option'})], 'gd-test').props.id, 'gd-test-option');
assert.throws(() => ctx.GraphdenRenderer.vnode([k('div'), attrs({id: 'auth-lock-btn'})], 'gd-test'));
assert.throws(() => ctx.GraphdenRenderer.vnode([k('div'), attrs({id: 'gd-test-option'})]));
assert.equal(tree.props.children[0].key, 'a');
assert.equal(tree.props.children[1].key, 'b');
for (const unsafe of [
  [k('script'), 'alert(1)'],
  [k('div'), attrs({onclick: 'alert(1)'})],
  [k('div'), attrs({style: 'position:fixed'})],
  [k('div'), attrs({'data-gd-ui-style': 'other-owner'})],
  [k('input'), attrs({type: 'text'}), 'unexpected child'],
  [k('div'), button('same'), button('same')],
  [k('div'), button(1), button('1')],
]) assert.throws(() => ctx.GraphdenRenderer.vnode(unsafe));
assert.throws(() => ctx.GraphdenRenderer.vnode([k('other/div'), 'namespaced tag']));
const rule = (selector, declarations) => attrs({selector, declarations: new Map(Object.entries(declarations))});
assert.equal(ctx.GraphdenStyles.normalize([rule('& .row:hover', {'background-color': 'var(--hover-bg)'})]).length, 1);
for (const unsafe of [
  rule('body', {color: 'red'}),
  rule('& + .neighbor', {color: 'red'}),
  rule('&', {'background-color': 'url(https://example.com/private)'}),
  rule('&', {color: 'red;display:none'}),
  rule('&', {position: 'fixed'}),
  rule('&', {cursor: String.raw`\75rl(https://example.com/private), pointer`}),
]) assert.throws(() => ctx.GraphdenStyles.normalize([unsafe]));
console.log('PASS graph renderer markup and scoped-style boundary');
