// Built-in action handlers — Block 2.4 of the user-sites plan
// (docs/USER_SITES_PLAN.md). Two platform-provided handlers that
// the component library's `:button` / `:link` etc. can target via
// `:dispatch-action`:
//
//   data-action="navigate"     → read `data-href`, set location
//   data-action="submit-form"  → find nearest <form>, POST via fetch,
//                                swap response into `data-target`
//                                (selector) or back into the form
//
// Loaded immediately after editor-runtime.js so the registrations
// are visible to every later module.


// =============================================================================
// navigate — link-like buttons
// =============================================================================
//
// Useful when the visible affordance is a <button> (e.g. styled as
// a card-action) but the behaviour is "go to URL". Composition:
//
//   {:name :open-docs-button
//    :parent :button
//    :args {:label "Open docs"
//           :attrs {:parent :merge
//                   :args {:maps [{:parent :dispatch-action
//                                  :args {:action "navigate"}}
//                                 {:value {:data-href "/docs"}}]}}}}

registerActionHandler('navigate', (btn, e) => {
  const href = btn.dataset.href;
  if (!href) return;
  e.preventDefault();
  window.location.href = href;
});


// =============================================================================
// submit-form — graph-built forms with response swap
// =============================================================================
//
// Targets the nearest enclosing <form>; serializes its fields as
// FormData; POSTs to `form.action` (or current path); swaps the
// response HTML into `btn.dataset.target` (a CSS selector) — or
// back into the form if no target was specified.
//
// After swap, re-runs `bindActionDispatch` on the swapped element
// so any returned buttons (e.g. a "try again" CTA) become live.
//
// Composition example:
//
//   {:name :contact-submit
//    :parent :button
//    :args {:label "Send"
//           :attrs {:parent :merge
//                   :args {:maps [{:parent :dispatch-action
//                                  :args {:action "submit-form"}}
//                                 {:value {:type "submit"
//                                          :data-target "#contact-result"}}]}}}}

async function _runSubmitForm(btn, e) {
  e.preventDefault();
  const form = btn.closest('form');
  if (!form) return;
  const formData = new FormData(form);
  const action = form.getAttribute('action') || window.location.pathname;
  const method = (form.getAttribute('method') || 'POST').toUpperCase();
  let res;
  try {
    res = await fetch(action, {
      method,
      body: method === 'GET' ? null : formData,
      credentials: 'same-origin',
    });
  } catch (err) {
    console.error('submit-form fetch failed:', err?.message);
    return;
  }
  if (!res.ok) {
    console.error('submit-form: server returned', res.status);
    return;
  }
  const html = await res.text();
  const targetSel = btn.dataset.target;
  const target = targetSel ? document.querySelector(targetSel) : form;
  if (!target) return;
  target.innerHTML = html;
  if (typeof bindActionDispatch === 'function') bindActionDispatch(target);
}

registerActionHandler('submit-form', _runSubmitForm);
