// editor-code.js — CodeMirror 6 upgrade for code-editing textareas.
//
// Any `<textarea data-lang="js|css|json|clojure">` (value-form js-source /
// css-source / json / hiccup-EDN fields, the Assets panel editor) becomes a
// real editor: highlighting, line numbers, search, bracket match/close,
// indent-with-tab, undo history. The textarea stays in the DOM (hidden) and
// receives every document change, so form serialization — htmx forms AND
// graphden-forms.js collectFormValue — keeps reading `textarea.value`
// exactly as before. Progressive enhancement: without `window.CM` (bundle
// missing / user page) the textarea just keeps working.
//
// Globals consumed: window.CM (vendored codemirror.min.js).
// Globals exposed: window.gdCode {enhance, enhanceWithin, viewOf, get, set}.

(() => {
  const VIEWS = new WeakMap(); // textarea → EditorView

  function langExtension(name) {
    const langs = window.CM?.langs;
    if (!langs) return null;
    const l = { js: langs.javascript, css: langs.css, json: langs.json, clojure: langs.clojure }[name];
    return l ? l() : null;
  }

  function baseExtensions(ta) {
    const CM = window.CM;
    return [
      CM.lineNumbers(),
      CM.history(),
      CM.bracketMatching(),
      CM.closeBrackets(),
      CM.indentOnInput(),
      CM.highlightActiveLine(),
      CM.highlightSelectionMatches(),
      CM.search(),
      CM.syntaxHighlighting(CM.defaultHighlightStyle, { fallback: true }),
      CM.keymap.of([...CM.defaultKeymap, ...CM.historyKeymap, ...CM.searchKeymap, CM.indentWithTab]),
      CM.EditorView.updateListener.of((u) => {
        if (u.docChanged) ta.value = u.state.doc.toString();
      }),
    ];
  }

  function enhance(ta) {
    if (!window.CM || !ta || ta.dataset.cmEnhanced) return null;
    const lang = langExtension(ta.dataset.lang);
    const exts = baseExtensions(ta);
    if (lang) exts.push(lang);
    const view = new window.CM.EditorView({
      state: window.CM.EditorState.create({ doc: ta.value, extensions: exts }),
    });
    view.dom.classList.add('gd-code-editor');
    // Rough height parity with the textarea it replaces.
    const rows = parseInt(ta.getAttribute('rows') || '8', 10);
    view.dom.style.minHeight = Math.min(rows, 30) * 1.4 + 'em';
    ta.dataset.cmEnhanced = '1';
    ta.style.display = 'none';
    ta.insertAdjacentElement('afterend', view.dom);
    VIEWS.set(ta, view);
    return view;
  }

  function enhanceWithin(root) {
    if (!root?.querySelectorAll) return;
    root.querySelectorAll('textarea[data-lang]:not([data-cm-enhanced])').forEach(enhance);
  }

  // Test/debug seam + programmatic writes after enhancement.
  function viewOf(ta) { return VIEWS.get(ta) || null; }
  function get(ta) { const v = viewOf(ta); return v ? v.state.doc.toString() : ta.value; }
  function set(ta, text) {
    const v = viewOf(ta);
    if (v) v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: text } });
    else ta.value = text;
    ta.value = text;
  }

  window.gdCode = { enhance, enhanceWithin, viewOf, get, set };

  // Server-rendered fragments (Assets panel, future partials) arrive via
  // htmx swaps — upgrade any code textarea they carry.
  document.addEventListener('htmx:afterSwap', (e) => enhanceWithin(e.target));
  document.addEventListener('htmx:afterSettle', (e) => enhanceWithin(e.target));
})();
