// `:js-source` slot renders a multi-line `<textarea>`, not a single-line
// `<input>`. Regression coverage for the `:_form-js-source` form-fn
// (resources/packages/app/forms/fns.edn): JS-body slots (`:custom-button`
// `:body` via `:dispatch-custom`, `:custom-script` `:content`) get a
// textarea so users can paste multi-line snippets without JSON-encoding.
//
// Tests the backend contract directly via `/api/value-form`. The editor
// already renders whatever hiccup the API returns (covered by
// `edit-arg-value*` tests); this file pins the type → form-fn mapping.
//
// Run from this directory:  node edit-form-js-source.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext, api, getEntities} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('form-js-source — :js-source slot renders <textarea>');
  try {
    // Resolve :dispatch-custom to its id (scoped getEntities returns
    // its subtree). For the :body slot row, fetch scope=subtree rooted
    // at the fn: the subtree includes every slot the root or its
    // ancestors reference; the :body slot lives among them (its owning
    // fn-slot link is elided from subtree's fn-slots list, so look up
    // by name within the subtree's slots).
    const ents = await getEntities(page, 'dispatch-custom');
    const dc = ents.fns.find(f => f.name === 'dispatch-custom');
    assert(dc, ':dispatch-custom baseline resolved');
    const subtree = await api(
      page, 'GET',
      '/api/graph/entities?scope=subtree&root-id=' + dc.id);
    const bodySlot = (subtree.slots || []).find(s => s.name === 'body');
    assert(bodySlot, ':dispatch-custom subtree exposes a :body slot');

    // Pass body as an object (not a JSON-stringified string) so
    // `nodeApi` sets `Content-Type: application/json` — strings are
    // sent as `application/x-www-form-urlencoded` (different
    // parser).
    const resp = await api(page, 'POST', '/api/value-form',
                           {'fn-id': dc.id, 'slot-id': bodySlot.id});
    assert(resp.ok, '/api/value-form succeeded: '
                    + JSON.stringify(resp).slice(0, 160));
    assert(Array.isArray(resp.form), 'response has :form hiccup');

    // Walk the hiccup tree (a list of nested vectors); find the first
    // tag-keyword element of any kind.
    const findTag = (node) => {
      if (!Array.isArray(node)) return null;
      const tag = node[0];
      if (typeof tag === 'string' || (tag && tag.tag)) return node;
      return null;
    };
    // resp.form is ["div", {attrs}, [<inner>, ...]]; the textarea
    // lives at resp.form[2] (the first child after the attrs map).
    const inner = resp.form[2];
    const innerTag = findTag(inner);
    assert(innerTag && innerTag[0] === 'textarea',
           ':js-source slot rendered as <textarea>, got: '
           + JSON.stringify(inner).slice(0, 200));

    const attrs = innerTag[1] || {};
    assert(attrs['data-field-kind'] === 'text',
           'data-field-kind="text" (round-trips as plain string, not JSON): '
           + JSON.stringify(attrs));
    assert(attrs.rows && parseInt(attrs.rows, 10) >= 4,
           'textarea has multi-line rows (>=4): ' + JSON.stringify(attrs));
    assert(attrs.spellcheck === 'false',
           'spellcheck disabled (JS source, not prose): '
           + JSON.stringify(attrs));
  } finally {
    await browser.close();
  }
  console.log('form-js-source — PASS');
})().catch(e => {
  console.error('form-js-source — FAIL:', e.message);
  process.exit(1);
});
