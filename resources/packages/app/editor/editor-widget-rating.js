// Form widget: rating — a 1-5 slider. The reference Tier-2 custom
// widget for the type-aware value-form system.
//
// A "widget" is a JS module that registers itself on
// `window.GraphdenFormWidgets`. A form-fn emits a `data-form-widget`
// mount node (see `app/forms` `:_form-rating`); the editor's
// `hydrateWidgets` (editor-value-form.js) calls `mount(el, value)` for
// each such node. The widget owns a hidden `[data-form-field]` input
// it keeps in sync, so `collectFormValue` / `saveFormValue` need no
// widget-specific code — the widget is purely a nicer INPUT over the
// same collected field.
//
// To add a widget: ship its JS (registering on GraphdenFormWidgets),
// add it to `_editor-script-paths`, and add a `:const` form-fn that
// emits `data-form-widget="<name>"` plus a `:_value-form-registry`
// entry mapping a type to that form-fn.

window.GraphdenFormWidgets = window.GraphdenFormWidgets || {};

window.GraphdenFormWidgets.rating = {
  // `el` is the `[data-form-widget]` node; `value` is the current
  // value at this field's path.
  mount(el, value) {
    el.textContent = '';

    // The hidden field carries the actual value into collectFormValue.
    const hidden = document.createElement('input');
    hidden.type = 'hidden';
    hidden.setAttribute('data-form-field', '');
    hidden.setAttribute('data-field-kind', 'number');
    const path = el.getAttribute('data-field-path');
    if (path) hidden.setAttribute('data-field-path', path);

    const range = document.createElement('input');
    range.type = 'range';
    range.min = '1';
    range.max = '5';
    range.step = '1';
    range.className = 'value-form-widget-range';

    const readout = document.createElement('span');
    readout.className = 'value-form-widget-readout';

    const sync = (v) => {
      const n = Math.max(1, Math.min(5, Math.round(Number(v)) || 1));
      range.value = String(n);
      hidden.value = String(n);
      readout.textContent = n + ' / 5';
    };
    range.addEventListener('input', () => sync(range.value));
    sync(value);

    el.appendChild(hidden);
    el.appendChild(range);
    el.appendChild(readout);
  }
};
