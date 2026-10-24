const {checkedAction} = require('./checked-action');

// Ordinary lesson entries share this readiness and completion boundary.
// Startup and stale-owner regressions intentionally exercise earlier states.
async function openCatalog(page, timeoutMs = 30000) {
  await checkedAction(page, {
    name: 'open-catalog', timeoutMs,
    ready: () => page.waitForFunction(
      () => !!window.gdEditorRoot?.componentNow('tutorial-catalog'),
      null, {timeout: timeoutMs}),
    gesture: () => page.evaluate(() => window.openTutorialMenu()),
    settled: () => page.waitForSelector('.gd-tour-lesson-list', {timeout: timeoutMs})
  });
}

// Every inline Save in the lesson walks uses the same causal boundary. The
// caller describes the mutation endpoint and any form-specific fields; the
// helper owns button readiness, a real pointer click, HTTP and dismissal.
async function saveInlinePopover(page, {name, request, requests, prepare,
  settled, timeoutMs = 30000}) {
  const popover = page.locator('.arg-value-edit-popover').last();
  const save = popover.getByRole('button', {name: 'Save', exact: true});
  return checkedAction(page, {
    name, request, requests, timeoutMs,
    ready: async () => {
      await popover.waitFor({state: 'visible', timeout: timeoutMs});
      if (prepare) await prepare();
      await save.waitFor({state: 'visible', timeout: timeoutMs});
    },
    gesture: () => save.click({timeout: timeoutMs}),
    settled: async () => {
      await popover.waitFor({state: 'hidden', timeout: timeoutMs});
      if (settled) await settled();
    }
  });
}

async function fillInlineField(page, value, {selector = '[data-form-field], .arg-value-edit-input',
  code = false} = {}) {
  await page.waitForFunction((fieldSelector) => {
    const popovers = document.querySelectorAll('.arg-value-edit-popover');
    return !!popovers[popovers.length - 1]?.querySelector(fieldSelector);
  }, selector, {timeout: 30000, polling: 100});
  await page.evaluate(({text, fieldSelector, codeField}) => {
    const popovers = document.querySelectorAll('.arg-value-edit-popover');
    const field = popovers[popovers.length - 1].querySelector(fieldSelector);
    if (codeField && window.gdCode) {
      window.gdCode.set(field, text);
      return;
    }
    if (field.type === 'checkbox') field.checked = String(text) === 'true';
    else field.value = text;
    field.dispatchEvent(new Event('input', {bubbles: true}));
    field.dispatchEvent(new Event('change', {bubbles: true}));
  }, {text: value, fieldSelector: selector, codeField: code});
}

async function appendOrBindLiteralFromChooser(page, literalText, {code = false} = {}) {
  const choice = page.getByRole('button', {name: /^(Bind|Append|Insert) literal$/}).first();
  let kind;
  await checkedAction(page, {
    name: 'choose-literal', timeoutMs: 10000,
    ready: () => choice.waitFor({state: 'visible', timeout: 8000}),
    gesture: async () => {
      kind = (await choice.textContent()).trim();
      await choice.click();
    },
    settled: () => page.waitForFunction(() => {
      const popovers = document.querySelectorAll('.arg-value-edit-popover');
      return !!popovers[popovers.length - 1]?.querySelector(
        '.arg-value-edit-input, [data-form-field]');
    }, null, {timeout: 10000, polling: 100})
  });
  await saveInlinePopover(page, {
    name: kind === 'Bind literal' ? 'bind-literal' : 'append-literal',
    timeoutMs: 60000,
    prepare: () => fillInlineField(page, literalText,
      {selector: '.arg-value-edit-input, [data-form-field]', code}),
    request: {method: 'POST', path: kind === 'Bind literal'
      ? '/api/entities/binding' : /^\/api\/sequence\/append\/[^/]+$/}
  });
}

async function saveDescriptionTooltip(page, fnId, value) {
  const textarea = page.locator('.description-tooltip-textarea');
  const save = page.locator('.description-tooltip').getByRole('button',
    {name: 'Save', exact: true});
  await checkedAction(page, {
    name: 'save-description', timeoutMs: 30000,
    ready: async () => {
      await textarea.waitFor({state: 'visible', timeout: 10000});
      await textarea.fill(value);
      await save.waitFor({state: 'visible', timeout: 10000});
    },
    gesture: () => save.click(),
    request: {method: 'PUT', path: `/api/entities/fn/${fnId}`},
    settled: () => textarea.waitFor({state: 'hidden', timeout: 30000})
  });
}

module.exports = {openCatalog, saveInlinePopover, fillInlineField,
  appendOrBindLiteralFromChooser, saveDescriptionTooltip};
