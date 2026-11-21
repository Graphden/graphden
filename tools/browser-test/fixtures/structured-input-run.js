'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require('playwright');

const [origin, structuredId, slotId] = process.argv.slice(2);
const token = process.env.GD_BROWSER_FIXTURE_TOKEN;
assert.equal(new URL(origin).hostname, '127.0.0.1');
assert.ok(structuredId && slotId && token);

const resources = path.join(__dirname, '..', '..', '..', 'resources', 'packages');
const editor = 'app/editor/';

async function installRunSurface(page) {
  await page.setContent('<!doctype html><html lang="en"><title>Run</title><main id="gd-insp-run-host"></main></html>');
  await page.addStyleTag({path: path.join(resources, editor, 'editor-styles.css')});
  await page.addScriptTag({content: `
    const API = {api_execute: '/api/execute', api_value_form: '/api/value-form'};
    const authFetch = window.fetch.bind(window);
    const lookups = {fnMap: new Map()};
    const richTypes = {};
    const isAuthenticated = () => true;
    let input;
    window.ensureSubtreeFor = async id => {
      const response = await fetch('/api/graph/entities?scope=subtree&root-id=' + encodeURIComponent(id));
      if (!response.ok) throw new Error('Could not load subtree');
      const {fns} = await response.json();
      const root = fns.find(fn => fn.id === id);
      if (!root) return false;
      lookups.fnMap.set(id, root);
      input = {id, descriptor: root['structured-input']};
      return true;
    };
    window.gdStructuredInputFor = id => input?.id === id ? input.descriptor : null;
    window.gdExecutionStatusNode = (_, message) => {
      const status = document.createElement('div');
      status.textContent = message;
      return status;
    };
    window.openRun = async id => {
      lookups.fnMap.set(id, {id});
      await window.gdMountRunPane(id);
    };
  `});
  for (const file of ['web/runtime/graphden-popover.js', 'web/runtime/graphden-forms.js', editor + 'editor-literal-types.js',
    editor + 'editor-value-form.js', editor + 'editor-execute-result.js', editor + 'editor-execute-inputs.js',
    editor + 'editor-execute.js']) {
    await page.addScriptTag({path: path.join(resources, file)});
  }
}

async function verifyRun(page, form, args, output) {
  const [response] = await Promise.all([
    page.waitForResponse(result => new URL(result.url()).pathname === '/api/execute'),
    form.getByRole('button', {name: 'Run', exact: true}).press('Enter'),
  ]);
  assert.equal(response.status(), 200);
  assert.deepEqual(response.request().postDataJSON().args, args);
  assert.equal((await response.json()).result, output);
  const rendered = form.locator('.execute-result-scalar');
  assert.equal(await rendered.textContent(), String(output));
}

async function exercise() {
  const browser = await chromium.launch({
    executablePath: process.env.GD_LESSON_CHROMIUM || undefined, args: ['--no-sandbox'],
  });
  try {
    const page = await browser.newPage({extraHTTPHeaders: {Authorization: 'Bearer ' + token}});
    page.setDefaultTimeout(9000);
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    await page.goto(origin);
    await installRunSurface(page);

    await page.evaluate(id => window.openRun(id), structuredId);
    const form = page.getByRole('form', {name: 'Run function'});
    const field = form.getByRole('group', {name: 'config'}).getByLabel('ref', {exact: true});
    assert.equal(await form.locator('[data-slot-id]').count(), 0);
    await form.getByRole('button', {name: 'Run', exact: true}).click();
    assert.match(await form.getByRole('status').textContent(), /config\.ref: Enter a value/);
    assert.equal(await field.getAttribute('aria-invalid'), 'true');
    assert.equal(await field.evaluate(input => input === document.activeElement), true);
    await field.fill('-7.6');
    await verifyRun(page, form, {config: {ref: -7.6}}, -8);

    await page.evaluate(id => window.openRun(id), slotId);
    const legacyField = form.locator('.execute-arg-form[data-slot-id] [data-form-field]');
    await legacyField.fill('-2.3');
    await verifyRun(page, form, {number: -2.3}, -2);
    assert.deepEqual(pageErrors, []);
    console.log('Ordinary structured and slot Run passed in Chromium');
  } finally {
    await browser.close();
  }
}

exercise().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
