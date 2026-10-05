// A DOM renderer for live graph values. State, behavior and styles are graphs.
(() => {
  const api = window.GraphdenBrowser;
  const renderer = window.GraphdenPreact;
  const styles = window.GraphdenStyles;
  const name = (value) => value instanceof api.Keyword ? (value.namespace ? value.namespace + '/' : '') + value.name : value;
  const owners = new WeakSet();
  const tags = new Set(['div', 'span', 'button', 'input', 'label', 'ul', 'li', 'p', 'strong', 'small', 'section']);
  const textAttributes = new Set(['class', 'role', 'type', 'title', 'name', 'value', 'placeholder', 'autocomplete']);
  const booleanAttributes = new Set(['disabled', 'hidden', 'readonly', 'checked']);

  function attributes(value, idPrefix) {
    if (!(value instanceof Map)) throw new Error('Graph element attributes must be a map');
    const result = Object.create(null);
    for (const [raw, item] of value) {
      const attr = name(raw);
      if (attr === 'id') {
        if (!idPrefix || typeof item !== 'string' || !item.startsWith(idPrefix + '-')
          || !/^[a-z][a-z0-9_-]{1,150}$/.test(item)) throw new Error('Graph element ID must belong to this component');
        result.id = item;
      } else if (attr === 'key') {
        if (typeof item !== 'string' && !Number.isSafeInteger(item)) throw new Error('Invalid graph element key');
        result.key = String(item);
      } else if (attr === 'tabindex') {
        if (!Number.isSafeInteger(item) || item < -1 || item > 0) throw new Error('Invalid graph tabindex');
        result.tabIndex = item;
      } else if (booleanAttributes.has(attr)) {
        if (typeof item !== 'boolean') throw new Error('Invalid graph boolean attribute');
        result[attr === 'readonly' ? 'readOnly' : attr] = item;
      } else if (textAttributes.has(attr) || (typeof attr === 'string' && /^(?:aria|data)-[a-z][a-z0-9-]*$/.test(attr) && !attr.startsWith('data-gd-ui-'))) {
        if (typeof item !== 'string' || item.length > 4096) throw new Error('Invalid graph text attribute');
        result[attr === 'class' ? 'className' : attr] = item;
      } else throw new Error('Unsupported graph element attribute');
    }
    return result;
  }

  function vnode(tree, idPrefix = null, nodeLimit = 5000) {
    if (!Number.isSafeInteger(nodeLimit) || nodeLimit < 1 || nodeLimit > 10000) throw new Error('Invalid graph element budget');
    let remaining = nodeLimit;
    function children(values, depth) {
      const result = [];
      const keys = new Set();
      function append(value, level) {
        if (api.isSequence(value)) {
          if (--remaining < 0 || level > 64) throw new Error('Graph element limit exceeded');
          for (const item of value) append(item, level + 1);
        } else {
          const node = visit(value, level);
          if (node && typeof node === 'object' && node.key != null) {
            if (keys.has(node.key)) throw new Error('Duplicate graph sibling key');
            keys.add(node.key);
          }
          result.push(node);
        }
      }
      for (const value of values) append(value, depth);
      return result;
    }
    function visit(value, depth) {
      if (--remaining < 0 || depth > 64) throw new Error('Graph element limit exceeded');
      if (value === null || value === false) return null;
      if (typeof value === 'string' || Number.isSafeInteger(value)) return value;
      if (!Array.isArray(value) || !tags.has(name(value[0]))) throw new Error('Unsupported graph element');
      const hasAttrs = value[1] instanceof Map;
      const props = attributes(hasAttrs ? value[1] : new Map(), idPrefix);
      const content = children(value.slice(hasAttrs ? 2 : 1), depth + 1);
      if (name(value[0]) === 'input' && content.some((item) => item !== null)) throw new Error('Graph input cannot have children');
      return renderer.h(name(value[0]), props, ...content);
    }
    return visit(tree, 0);
  }

  function mount(host, {idPrefix = null, nodeLimit = 5000} = {}) {
    if (!Number.isSafeInteger(nodeLimit) || nodeLimit < 1 || nodeLimit > 10000) throw new Error('Invalid graph element budget');
    if (idPrefix !== null && (typeof idPrefix !== 'string' || !/^gd-[a-z0-9_-]{1,100}$/.test(idPrefix))) throw new Error('Invalid graph component ID prefix');
    if (!(host instanceof HTMLElement) || owners.has(host) || host.childNodes.length) throw new Error('Graph component requires its own empty DOM host');
    for (let parent = host.parentElement; parent; parent = parent.parentElement) {
      if (owners.has(parent)) throw new Error('Graph component hosts cannot overlap');
    }
    owners.add(host);
    let sheet = null;
    let disposed = false;
    return {
      render(tree, rules = []) {
        if (disposed) throw new Error('Graph component is disposed');
        // Validate the complete next output before changing the current DOM.
        const next = vnode(tree, idPrefix, nodeLimit);
        const normalized = styles.normalize(rules);
        const nextSheet = normalized.length ? styles.acquire(normalized) : null;
        try {
          renderer.render(next, host);
          if (nextSheet) host.dataset.gdUiStyle = nextSheet.id;
          else delete host.dataset.gdUiStyle;
        } catch (error) { styles.release(nextSheet); throw error; }
        styles.release(sheet);
        sheet = nextSheet;
        return host.firstElementChild;
      },
      dispose() {
        if (disposed) return;
        disposed = true;
        try { renderer.render(null, host); }
        finally {
          styles.release(sheet);
          sheet = null;
          delete host.dataset.gdUiStyle;
          owners.delete(host);
        }
      },
    };
  }
  window.GraphdenRenderer = {vnode, mount};
})();
