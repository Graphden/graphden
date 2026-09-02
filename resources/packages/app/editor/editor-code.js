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
    // Own wrapper DIV — CodeMirror rewrites view.dom's className on
    // every update (theme classes), silently dropping any class added
    // from outside, so the styling/probe hook must live on OUR node.
    const wrap = document.createElement('div');
    wrap.className = 'gd-code-editor';
    const rows = Number.parseInt(ta.getAttribute('rows') || '8', 10);
    wrap.style.minHeight = Math.min(rows, 30) * 1.4 + 'em';
    wrap.appendChild(view.dom);
    ta.dataset.cmEnhanced = '1';
    ta.style.display = 'none';
    ta.insertAdjacentElement('afterend', wrap);
    VIEWS.set(ta, view);
    return view;
  }

  function enhanceWithin(root) {
    if (!root?.querySelectorAll) return;
    root.querySelectorAll('textarea[data-lang]:not([data-cm-enhanced])').forEach(enhance);
  }

  // CM6 views hold document/window observers — an htmx swap that replaces
  // the textarea's subtree must destroy them or they leak (the Assets
  // panel re-renders on every save/revert). Tear down any enhanced
  // textarea in the OUTGOING subtree before the swap.
  function destroyWithin(root) {
    if (!root?.querySelectorAll) return;
    for (const ta of root.querySelectorAll('textarea[data-cm-enhanced]')) {
      const v = VIEWS.get(ta);
      if (v) { try { v.destroy(); } catch (_) {} VIEWS.delete(ta); }
    }
  }

  // Test/debug seam + programmatic writes after enhancement.
  function viewOf(ta) { return VIEWS.get(ta) || null; }
  function get(ta) { const v = viewOf(ta); return v ? v.state.doc.toString() : ta.value; }
  function set(ta, text) {
    const v = viewOf(ta);
    if (v) v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: text } });
    // Write ta.value directly too: the updateListener mirrors it on the
    // enhanced path, but the direct assignment guarantees the hidden
    // textarea is current for form serialization / the save gate the
    // instant set() returns, regardless of listener timing.
    ta.value = text;
  }

  window.gdCode = { enhance, enhanceWithin, viewOf, get, set };

  // Server-rendered fragments (Assets panel, future partials) arrive via
  // htmx swaps — destroy outgoing views, then upgrade the incoming
  // textareas. One enhance listener (afterSettle), not two.
  document.addEventListener('htmx:beforeSwap', (e) => destroyWithin(e.target));
  document.addEventListener('htmx:afterSettle', (e) => enhanceWithin(e.target));
})();
