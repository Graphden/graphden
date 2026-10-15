// The catalogue tests run the shipped graph runtime against mini-dom. Mirror
// the renderer's host ownership (including empty-rule scope clearing) while
// keeping native controls as real mini-dom elements for lifecycle assertions.
function catalogRenderer(document, api) {
  const isMap = item => Object.prototype.toString.call(item) === '[object Map]';
  function nodes(raw) {
    if (raw == null || raw === false) return [];
    if (!Array.isArray(raw) && api.isSequence(raw)) return [...raw].flatMap(nodes);
    if (!Array.isArray(raw)) return [raw];
    const tag = raw[0] instanceof api.Keyword ? raw[0].name : raw[0];
    const attrs = isMap(raw[1]) ? raw[1] : new Map();
    const props = {};
    for (const [key, item] of attrs) {
      const name = key instanceof api.Keyword ? key.name : key;
      props[name === 'class' ? 'className' : name] = item;
    }
    props.children = raw.slice(attrs.size ? 2 : 1).flatMap(nodes).map(vnode);
    return [{type: tag, props}];
  }
  function vnode(raw) { return nodes(raw)[0] ?? null; }
  function element(node) {
    if (node == null || node === false) return null;
    if (typeof node !== 'object') return document.createTextNode(node);
    const el = document.createElement(node.type);
    for (const [name, item] of Object.entries(node.props)) {
      if (name === 'className') el.className = item;
      else if (name !== 'children' && name !== 'key') el.setAttribute(name, item);
    }
    for (const child of node.props.children || []) {
      const rendered = element(child);
      if (rendered) el.appendChild(rendered);
    }
    return el;
  }
  return {vnode, mount(host) {
    return {render(tree) {
      host.replaceChildren();
      delete host.dataset.gdUiStyle;
      const node = element(vnode(tree));
      if (node) host.appendChild(node);
    }, dispose() { host.replaceChildren(); delete host.dataset.gdUiStyle; }};
  }};
}

module.exports = catalogRenderer;
