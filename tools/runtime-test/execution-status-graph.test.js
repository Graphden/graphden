'use strict';

// The shipped status graph, personal root and native execute callers share one
// passive rendering boundary. Run without a server so submitting and failures
// remain visible when the editor backend is unavailable.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const packages = path.join(__dirname, '../../resources/packages');
const listeners = new Map();
const context = vm.createContext({Map, Set, WeakSet, Symbol, console,
  document: {createElement() { return {childNodes: [], firstElementChild: null}; }},
  addEventListener(name, callback) { listeners.set(name, callback); },
});
context.window = context;
function load(file) {
  vm.runInContext(fs.readFileSync(path.join(packages, file), 'utf8'), context, {filename: file});
}
load('web/vendor/preact.min.js');
context.GraphdenPreact = {...context.GraphdenPreact,
  render(node, host) { host.childNodes = [node]; host.firstElementChild = node; }};
load('app/ui-preview/browser-runtime.js');
load('app/ui-preview/builtin-plans.js');
load('app/ui-preview/graph-styles.js');
load('app/ui-preview/graph-renderer.js');
let candidate = null;
let accepted = 0;
let failed = 0;
context.gdEditorRoot = {
  componentNow() { return candidate; },
  acceptComponent() { accepted++; return true; },
  releaseComponent() {},
};
context.gdUIComponentFailed = () => { failed++; };
load('app/editor/editor-execution-status-graph.js');
load('app/editor/editor-execute-result.js');

function text(node) {
  if (node == null) return '';
  if (typeof node !== 'object') return String(node);
  return [node.props?.children].flat(Infinity).map(text).join('');
}
assert.equal(context.renderSubmitSpinner('Submitting…').props.className, 'execute-submit-spinner');
const pending = context.renderPendingPane('12345678-1234-1234-1234-123456789abc');
assert.equal(pending.props.className, 'execute-pending-pane');
assert.match(text(pending), /12345678/);
assert.doesNotMatch(text(pending), /123456789abc/);
const error = context.renderErrorPane('Request failed', {code: 'bad'});
assert.equal(error.props.className, 'execute-error-pane');
assert.match(text(error), /Request failed/);
assert.match(text(error), /"code": "bad"/);

const api = context.GraphdenBrowser;
const k = api.keyword;
candidate = {run() { return new Map([[k('tree'), [k('div'), new Map([[k('class'), 'personal-status']]), 'Personal']]]); }};
listeners.get('gd-editor-root-changing')();
assert.equal(context.renderSubmitSpinner('Submitting…').props.className, 'personal-status');
assert.equal(accepted, 1);
candidate = {run() { return new Map([[k('tree'), [k('button'), 'unsafe']]]); }};
listeners.get('gd-editor-root-changing')();
assert.equal(context.renderSubmitSpinner('Submitting…').props.className, 'execute-submit-spinner');
assert.equal(failed, 1);
assert.equal(context.renderSubmitSpinner('Submitting…').props.className, 'execute-submit-spinner');
assert.equal(failed, 1, 'one rejected personal root is not retried on every spinner');
console.log('PASS execution status graph: offline builtin, root selection and passive fallback');
