// Feedback form presentation stays available from a shipped browser graph
// when the local server is broken. Delivery, consent and focus remain native.
(() => {
  const api = window.GraphdenBrowser;
  const keyword = api.keyword;
  const builtin = api.createRuntime(window.GraphdenBuiltinPlans.plans.feedback,
    {operationLimit: 20000});
  let selected = null;
  let rejected = false;
  const sheets = new WeakMap();

  function form(runtime, errorCount, authenticated) {
    const inputs = new Map([[keyword('error-count'), errorCount],
      [keyword('authenticated'), authenticated]]);
    const result = runtime.run('view', {inputs});
    if (!(result instanceof Map) || result.size !== 2 || !result.has(keyword('tree'))
      || !result.has(keyword('styles'))) {
      throw new Error('Invalid feedback graph view');
    }
    const rules = window.GraphdenStyles.normalize(result.get(keyword('styles')));
    const vnode = window.GraphdenRenderer.vnode(result.get(keyword('tree')), null, 100);
    const host = document.createElement('div');
    window.GraphdenPreact.render(vnode, host);
    if (host.childElementCount !== 1) throw new Error('Invalid feedback form');
    const card = host.firstElementChild;
    if (!card.matches('.feedback-popover[role="dialog"][aria-modal="true"]')) {
      throw new Error('Invalid feedback dialog');
    }
    const required = [
      ['.feedback-category', 'SELECT'], ['.feedback-description', 'TEXTAREA'],
      ['.feedback-email', 'INPUT'], ['.feedback-honeypot input', 'INPUT'],
      ['.feedback-env', 'INPUT'], ['.feedback-errors', 'INPUT'],
      ['.feedback-location', 'INPUT'], ['.feedback-send', 'BUTTON'],
      ['.feedback-cancel', 'BUTTON'], ['.feedback-status', 'DIV'],
    ];
    for (const [selector, tag] of required) {
      const matches = card.querySelectorAll(selector);
      if (matches.length !== 1 || matches[0].tagName !== tag) throw new Error('Invalid feedback control');
    }
    if (card.querySelectorAll('.feedback-failures').length !== Number(authenticated)) {
      throw new Error('Invalid feedback consent controls');
    }
    return {card, rules};
  }

  window.gdFeedbackFormNode = (errorCount, authenticated) => {
    if (!selected && !rejected) {
      try { selected = window.gdEditorRoot?.componentNow('feedback') || null; }
      catch (_) { rejected = true; window.gdUIComponentFailed?.('feedback'); }
    }
    if (selected) {
      try {
        const rendered = form(selected, errorCount, authenticated);
        if (!window.gdEditorRoot.acceptComponent(selected)) throw new Error('Editor root changed');
        const sheet = window.GraphdenStyles.acquire(rendered.rules);
        rendered.card.dataset.gdUiStyle = sheet.id;
        sheets.set(rendered.card, sheet);
        return rendered.card;
      } catch (_) {
        window.gdEditorRoot?.releaseComponent(selected);
        selected = null;
        rejected = true;
        window.gdUIComponentFailed?.('feedback');
      }
    }
    const rendered = form(builtin, errorCount, authenticated);
    const sheet = window.GraphdenStyles.acquire(rendered.rules);
    rendered.card.dataset.gdUiStyle = sheet.id;
    sheets.set(rendered.card, sheet);
    return rendered.card;
  };
  window.gdFeedbackFormRelease = card => {
    const sheet = sheets.get(card);
    if (sheet) {
      window.GraphdenStyles.release(sheet);
      sheets.delete(card);
    }
  };
  window.addEventListener('gd-editor-root-changing', () => { selected = null; rejected = false; });
})();
