// A DOM small enough to read, big enough for the editor's element
// builders — so a function that only ever builds nodes can be tested
// with `node`, no browser and no graphden stack.
//
// Why this exists: the type-helper tests used to run `page.evaluate`
// against a LIVE editor, which meant they needed a built image and a
// database to assert that `shortTypeLabel(['list','text'])` is
// `'[text]'`. That is a pure function. It also meant the assertions
// were only as stable as whatever types the server happened to ship
// that day. Here the inputs are written down in the test.
//
// Supported, because the builders under test use exactly this much:
//   createElement / createTextNode, appendChild, textContent,
//   className + classList.add/contains, setAttribute/getAttribute,
//   title / href / style.cursor, addEventListener + click(),
//   querySelector / querySelectorAll over `tag.a.b` selectors with
//   descendant combinators (`.parent .child`).
//
// NOT supported, on purpose: innerHTML, layout, CSS, events that
// bubble to anything but their own listeners. A builder that needs
// those is not a pure builder and belongs in the e2e suite.

'use strict';

const SELECTOR_RE = /^([a-zA-Z][\w-]*)?((?:\.[\w-]+)*)$/;

function parseSimple(sel) {
  const m = SELECTOR_RE.exec(sel);
  if (!m) throw new Error('mini-dom: unsupported selector fragment "' + sel + '"');
  return {
    tag: m[1] ? m[1].toLowerCase() : null,
    classes: (m[2] || '').split('.').filter(Boolean),
  };
}

function matches(node, part) {
  if (part.tag && node.tagName.toLowerCase() !== part.tag) return false;
  return part.classes.every((c) => node.classList.contains(c));
}

function descendants(node, acc) {
  for (const child of node.children) {
    if (child.tagName !== '#text') { acc.push(child); descendants(child, acc); }
  }
  return acc;
}

class MiniElement {
  constructor(tag) {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.parentNode = null;
    this.attributes = {};
    this.style = {};
    this.listeners = {};
    this._className = '';
    this._text = '';
    const self = this;
    this.classList = {
      add(...names) {
        const have = new Set(self._classes());
        for (const n of names) have.add(n);
        self._className = Array.from(have).join(' ');
      },
      remove(...names) {
        const drop = new Set(names);
        self._className = self._classes().filter((c) => !drop.has(c)).join(' ');
      },
      contains(name) { return self._classes().includes(name); },
    };
  }

  _classes() {
    return String(this._className || '').split(/\s+/).filter(Boolean);
  }

  get className() { return this._className; }

  set className(v) { this._className = v == null ? '' : String(v); }

  get textContent() {
    if (this.children.length === 0) return this._text;
    return this.children.map((c) => c.textContent).join('');
  }

  set textContent(v) {
    this.children = [];
    this._text = v == null ? '' : String(v);
  }

  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  setAttribute(k, v) { this.attributes[k] = String(v); }

  getAttribute(k) {
    return Object.prototype.hasOwnProperty.call(this.attributes, k)
      ? this.attributes[k] : null;
  }

  addEventListener(type, fn) {
    (this.listeners[type] = this.listeners[type] || []).push(fn);
  }

  dispatch(type, event) {
    const e = Object.assign({ type, preventDefault() {}, stopPropagation() {} },
                            event || {});
    for (const fn of this.listeners[type] || []) fn(e);
    return e;
  }

  click() { return this.dispatch('click'); }

  // `.a .b` — descendant combinator; each fragment is `tag.a.b`.
  querySelectorAll(selector) {
    const parts = String(selector).trim().split(/\s+/).map(parseSimple);
    let scope = [this];
    for (const part of parts) {
      const next = [];
      const seen = new Set();
      for (const node of scope) {
        for (const cand of descendants(node, [])) {
          if (matches(cand, part) && !seen.has(cand)) { seen.add(cand); next.push(cand); }
        }
      }
      scope = next;
    }
    return scope;
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }
}

class MiniText {
  constructor(text) {
    this.tagName = '#text';
    this.textContent = text == null ? '' : String(text);
    this.children = [];
  }
}

function createDocument() {
  return {
    createElement: (tag) => new MiniElement(tag),
    createTextNode: (t) => new MiniText(t),
  };
}

module.exports = { createDocument, MiniElement, MiniText };
