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

// `tag.a.b[attr][attr="v"]` — the shapes the runtime's builders use.
const SELECTOR_RE = /^([a-zA-Z][\w-]*)?((?:\.[\w-]+)*)((?:\[[^\]]+\])*)$/;
const ATTR_RE = /\[([\w-]+)(?:=(?:"([^"]*)"|'([^']*)'|([^\]]*)))?\]/g;

function parseSimple(sel) {
  const m = SELECTOR_RE.exec(sel);
  if (!m) throw new Error('mini-dom: unsupported selector fragment "' + sel + '"');
  const attrs = [];
  let a;
  ATTR_RE.lastIndex = 0;
  while ((a = ATTR_RE.exec(m[3] || '')) !== null) {
    const value = a[2] !== undefined ? a[2] : (a[3] !== undefined ? a[3] : a[4]);
    attrs.push({ name: a[1], value: value === undefined ? null : value });
  }
  return {
    tag: m[1] ? m[1].toLowerCase() : null,
    classes: (m[2] || '').split('.').filter(Boolean),
    attrs,
  };
}

function matches(node, part) {
  if (part.tag && node.tagName.toLowerCase() !== part.tag) return false;
  if (!part.classes.every((c) => node.classList.contains(c))) return false;
  return part.attrs.every(({ name, value }) => {
    const got = node.getAttribute(name);
    if (got === null) return false;
    return value === null ? true : got === value;
  });
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

  // Real-DOM semantics: appending a node that already has a parent MOVES
  // it (the old parent loses it), and appending a fragment moves the
  // fragment's children in. Code under test that stashes/restores form
  // controls (the raw-value toggle) relies on both.
  appendChild(child) {
    if (child.tagName === '#FRAGMENT') {
      for (const c of [...child.children]) this.appendChild(c);
      return child;
    }
    if (child.parentNode) child.parentNode.removeChild(child);
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  removeChild(child) {
    const i = this.children.indexOf(child);
    if (i >= 0) { this.children.splice(i, 1); child.parentNode = null; }
    return child;
  }

  insertBefore(child, ref) {
    if (ref == null) return this.appendChild(child);
    this.appendChild(child);
    this.children.pop();
    const i = this.children.indexOf(ref);
    this.children.splice(i < 0 ? this.children.length : i, 0, child);
    return child;
  }

  get firstChild() { return this.children[0] || null; }

  setAttribute(k, v) { this.attributes[k] = String(v); }

  getAttribute(k) {
    return Object.prototype.hasOwnProperty.call(this.attributes, k)
      ? this.attributes[k] : null;
  }

  removeAttribute(k) { delete this.attributes[k]; }

  // `hidden` is a reflected property in the real DOM: setting it must make
  // `[hidden]` match, which is exactly what the form collector relies on to
  // skip inactive union branches.
  get hidden() { return this.getAttribute('hidden') !== null; }

  set hidden(v) {
    if (v) this.setAttribute('hidden', '');
    else this.removeAttribute('hidden');
  }

  closest(selector) {
    const parts = String(selector).trim().split(/\s+/).map(parseSimple);
    if (parts.length !== 1) {
      throw new Error('mini-dom: closest() takes one selector fragment');
    }
    let node = this;
    while (node) {
      if (node instanceof MiniElement && matches(node, parts[0])) return node;
      node = node.parentNode;
    }
    return null;
  }

  contains(other) {
    let node = other;
    while (node) {
      if (node === this) return true;
      node = node.parentNode;
    }
    return false;
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

  // Enough of dispatchEvent for code that fires a synthetic event at the
  // node holding the listener (no bubbling — same scope as dispatch).
  dispatchEvent(e) { return this.dispatch(e.type, e); }

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
    createDocumentFragment: () => new MiniElement('#fragment'),
  };
}

module.exports = { createDocument, MiniElement, MiniText };
