'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');
const {MiniElement} = require('./mini-dom');
const root = path.join(__dirname, '../../resources/packages/app');

// Real graph evaluator, component lifecycle and vnode validation. Only the DOM
// painting/style-sheet insertion boundary is replaced in this small Node DOM.
function installInspectorGraph(ctx, doc) {
  Object.defineProperties(MiniElement.prototype, {
    childNodes: {configurable: true, get() { return this.children; }},
    parentElement: {configurable: true, get() { return this.parentNode; }},
  });
  MiniElement.prototype.removeEventListener = function(type, fn) {
    this.listeners[type] = (this.listeners[type] || []).filter(item => item !== fn);
  };
  ctx.GraphdenBrowser = graph;
  ctx.HTMLElement = MiniElement;
  ctx.AbortController = AbortController;
  ctx.inspectorNarrow = {matches: false, addEventListener(_type, callback) { this.change = callback; }};
  ctx.matchMedia = () => ctx.inspectorNarrow;
  const listeners = new Map();
  ctx.addEventListener = (type, fn) => {
    listeners.set(type, [...(listeners.get(type) || []), fn]);
  };
  ctx.dispatchEvent = event => { for (const fn of listeners.get(event.type) || []) fn(event); };
  ctx.focusSafely = element => { doc.activeElement = element; };
  ctx.CSS = {supports: () => true};
  ctx.GraphdenPreact = {
    h(type, props, ...children) { return {type, props: {...props, children}}; },
    render(tree, host) {
      function build(node) {
        if (node == null) return null;
        if (typeof node !== 'object') return doc.createTextNode(String(node));
        const element = doc.createElement(node.type);
        for (const [key, value] of Object.entries(node.props)) {
          if (key === 'children') continue;
          if (key === 'className') element.className = value;
          else element.setAttribute(key === 'tabIndex' ? 'tabindex' : key, String(value));
        }
        for (const child of node.props.children.flat(Infinity)) {
          const built = build(child); if (built) element.appendChild(built);
        }
        return element;
      }
      const built = build(tree);
      host.replaceChildren(...(built ? [built] : []));
    },
  };
  for (const file of ['graph-styles.js', 'graph-renderer.js', 'graph-component.js', 'builtin-plans.js']) {
    vm.runInContext(fs.readFileSync(path.join(root, 'ui-preview', file), 'utf8'), ctx);
    if (file === 'graph-styles.js') {
      ctx.GraphdenStyles.acquire = () => ({id: 'fixture'});
      ctx.GraphdenStyles.release = () => {};
    }
  }
  vm.runInContext(fs.readFileSync(path.join(root, 'editor/editor-inspector-graph.js'), 'utf8'), ctx);
}
function installHtmlParser(doc) {
  Object.defineProperty(MiniElement.prototype, 'innerHTML', {
    configurable: true,
    get() { return this._fixtureHtml || ''; },
    set(html) {
      this.replaceChildren(); this._fixtureHtml = String(html);
      const stack = [this];
      for (const match of String(html).matchAll(/<(\/?)([a-zA-Z][\w-]*)([^>]*)>/g)) {
        if (match[1]) { stack.pop(); continue; }
        const element = doc.createElement(match[2]);
        for (const attr of match[3].matchAll(/([\w-]+)="([^"]*)"/g)) element.setAttribute(attr[1], attr[2]);
        stack[stack.length - 1].appendChild(element); stack.push(element);
      }
    },
  });
}
module.exports = {installInspectorGraph, installHtmlParser};
