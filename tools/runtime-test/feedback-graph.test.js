'use strict';

// The shipped form must stay renderable without an HTTP request. Consent for
// recent failed executions appears only for an authenticated editor session.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '../../resources/packages/app/ui-preview');
const context = vm.createContext({Map, Set, WeakSet, WeakMap, Symbol,
  CSS: {supports: () => true}});
context.window = context;
for (const file of ['browser-runtime.js', 'builtin-plans.js', 'graph-styles.js']) {
  vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), context, {filename: file});
}
const api = context.GraphdenBrowser;
const key = api.keyword;
const runtime = api.createRuntime(context.GraphdenBuiltinPlans.plans.feedback);
function tree(authenticated) {
  const inputs = new Map([[key('error-count'), 3], [key('authenticated'), authenticated]]);
  const result = runtime.run('view', {inputs});
  assert.equal(context.GraphdenStyles.normalize(result.get(key('styles'))).length, 5);
  return result.get(key('tree'));
}
function nodes(value, output = []) {
  if (Array.isArray(value) || api.isSequence(value)) {
    if (Array.isArray(value) && value[0] instanceof api.Keyword) output.push(value);
    for (const child of value) nodes(child, output);
  }
  return output;
}
function classes(value) {
  return nodes(value).map(node => node[1] instanceof Map ? node[1].get(key('class')) : null);
}
function texts(value, output = []) {
  if (typeof value === 'string') output.push(value);
  else if (Array.isArray(value) || api.isSequence(value)) for (const child of value) texts(child, output);
  return output;
}
const guest = tree(false);
const signedIn = tree(true);
assert(classes(guest).includes('feedback-send'));
assert(classes(guest).includes('feedback-env'));
assert(classes(guest).includes('feedback-errors'));
assert(!classes(guest).includes('feedback-failures'));
assert(classes(signedIn).includes('feedback-failures'));
assert(texts(signedIn).includes(' Recent console errors (3)'));
console.log('PASS feedback graph: offline form and authenticated consent');
