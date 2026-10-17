'use strict';

// Actual plan evaluator, scoped-style validator and passive adapter over the
// small DOM. Only Preact's DOM writing is replaced here; real Preact/CSS/hit
// geometry and asynchronous persisted plans are exercised in Chromium.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {performance} = require('node:perf_hooks');
const {MiniElement} = require('./mini-dom');
const root = path.join(__dirname, '../../resources/packages');

function installPassiveGraphs(ctx) {
  ctx.window ||= ctx;
  const window = ctx.window;
  ctx.AbortController ||= AbortController;
  ctx.queueMicrotask ||= queueMicrotask;
  ctx.performance ||= performance;
  window.performance ||= performance;
  window.addEventListener ||= () => {};
  window.gdLoadUIComponentRuntime ||= async () => null;
  ctx.requestAnimationFrame ||= () => 1;
  ctx.cancelAnimationFrame ||= () => {};
  ctx.CSS ||= {supports: () => true};
  ctx.HTMLElement = MiniElement;
  ctx.document.head ||= ctx.document.createElement('head');
  for (const [name, getter] of Object.entries({childNodes() { return this.children; },
    parentElement() { return this.parentNode; }, firstElementChild() { return this.children[0] || null; }})) {
    if (!Object.getOwnPropertyDescriptor(MiniElement.prototype, name)) {
      Object.defineProperty(MiniElement.prototype, name, {get: getter});
    }
  }
  window.GraphdenPreact = {
    h(type, props, ...children) { return {type, key: props.key, props: {...props, children}}; },
    render(node, host) {
      function append(value, parent) {
        if (value == null || value === false) return;
        if (Array.isArray(value)) { value.forEach(child => append(child, parent)); return; }
        if (typeof value !== 'object') { parent.appendChild(ctx.document.createTextNode(String(value))); return; }
        const element = ctx.document.createElement(value.type);
        for (const [key, item] of Object.entries(value.props)) {
          if (key === 'children' || key === 'key') continue;
          element.setAttribute(key === 'className' ? 'class' : key, String(item));
        }
        append(value.props.children, element);
        parent.appendChild(element);
      }
      host.textContent = '';
      append(node, host);
    },
  };
  for (const file of ['app/ui-preview/browser-runtime.js', 'app/ui-preview/graph-styles.js',
    'app/ui-preview/graph-renderer.js', 'app/ui-preview/builtin-plans.js',
    'app/editor/editor-passive-canvas.js', 'app/editor/editor-canvas-values-graph.js',
    'app/editor/editor-canvas-type-structure-graph.js', 'app/editor/editor-canvas-bindings-graph.js',
    'app/editor/editor-canvas-metadata-graph.js']) {
    vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), ctx, {filename: file});
  }
}
module.exports = {installPassiveGraphs};
