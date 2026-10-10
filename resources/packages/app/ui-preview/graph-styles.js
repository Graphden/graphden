// Structured graph rules become scoped CSS; no author-supplied stylesheet blob.
(() => {
  const api = window.GraphdenBrowser;
  const fields = (value) => {
    if (!(value instanceof Map)) throw new Error('Graph style must be a map');
    return Object.fromEntries([...value].map(([key, item]) => [key instanceof api.Keyword ? (key.namespace ? key.namespace + '/' : '') + key.name : key, item]));
  };
  const properties = new Set([
    'display', 'flex', 'flex-direction', 'align-items', 'justify-content', 'gap',
    'padding', 'padding-inline', 'padding-block', 'margin', 'margin-inline',
    'margin-block', 'width', 'min-width', 'max-width', 'height', 'max-height',
    'overflow', 'overflow-x', 'overflow-y', 'color', 'background-color',
    'border', 'border-color', 'border-width', 'border-style', 'border-radius',
    'outline', 'outline-color', 'outline-offset', 'font-size', 'font-weight',
    'line-height', 'font-family', 'letter-spacing', 'text-transform', 'text-align', 'white-space', 'text-overflow', 'cursor',
    'opacity', 'box-shadow',
  ]);
  const hasControl = (value) => [...value].some((char) => char.charCodeAt(0) < 32);
  const sheets = new Map();
  let nextId = 0;

  function selector(value) {
    // Only this component and its descendants; no sibling or global selectors.
    if (typeof value !== 'string' || value.length > 256 || !value.startsWith('&')
      || /[{},;@\\+~]/.test(value) || hasControl(value) || value.slice(1).includes('&')
      || !/^&(?::(?:hover|focus-visible|disabled))?(?:(?:\s+|\s*>\s*)[a-zA-Z.\[][a-zA-Z0-9_[\]=".: ()-]*)?$/.test(value)) {
      throw new Error('Unsupported component selector');
    }
    if (!CSS.supports('selector([data-gd-ui-style]' + value.slice(1) + ')')) throw new Error('Invalid component selector');
    return value;
  }

  function declarations(value) {
    const entries = Object.entries(fields(value));
    if (entries.length > 64) throw new Error('Graph style declaration limit exceeded');
    return entries.sort(([a], [b]) => a.localeCompare(b)).map(([property, item]) => {
      if (!properties.has(property) || typeof item !== 'string' || item.length > 256
        || /[{};\\]|url\s*\(|@/i.test(item) || hasControl(item) || !CSS.supports(property, item)) {
        throw new Error('Unsupported component declaration');
      }
      return [property, item];
    });
  }

  function normalize(rules) {
    if (!Array.isArray(rules) && !api.isSequence(rules)) throw new Error('Graph styles must be a sequence');
    const result = [];
    for (const raw of rules) {
      if (result.length >= 128) throw new Error('Graph style rule limit exceeded');
      const rule = fields(raw);
      if (Object.keys(rule).some((key) => key !== 'selector' && key !== 'declarations')) throw new Error('Unknown graph style field');
      result.push({selector: selector(rule.selector), declarations: declarations(rule.declarations)});
    }
    return result;
  }

  function acquire(normalized) {
    const signature = JSON.stringify(normalized);
    let sheet = sheets.get(signature);
    if (!sheet) {
      const id = 'gd-style-' + (++nextId);
      const scope = '[data-gd-ui-style="' + id + '"]';
      const element = document.createElement('style');
      element.dataset.gdUiStyles = id;
      element.textContent = normalized.map((rule) => scope + rule.selector.slice(1) + '{'
        + rule.declarations.map(([property, value]) => property + ':' + value).join(';') + '}').join('\n');
      document.head.appendChild(element);
      sheet = {id, signature, element, users: 0};
      sheets.set(signature, sheet);
    }
    sheet.users++;
    return sheet;
  }

  function release(sheet) {
    if (!sheet || --sheet.users > 0) return;
    sheet.element.remove();
    sheets.delete(sheet.signature);
  }

  window.GraphdenStyles = {normalize, acquire, release};
})();
