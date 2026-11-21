'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require('playwright');

const [origin, rootId] = process.argv.slice(2);
const token = process.env.GD_LESSON_TOKEN;
assert.equal(new URL(origin).hostname, '127.0.0.1');
assert.ok(rootId && token);

const resources = path.join(__dirname, '..', '..', '..', 'resources', 'packages');
const editor = 'app/editor/';
const runtime = 'web/runtime/';

async function installLesson(page) {
  await page.setContent(`<!doctype html><html lang="en"><meta charset="utf-8"><title>Fill a structured input</title>
    <main><h1>Fill a structured input</h1><p>Bind the name, then supply the remaining fields and run.</p>
    <div id="root-card"></div><form id="exercise" aria-label="Run inputs"></form>
    <output id="result" aria-label="Run result" aria-live="polite"></output></main>`);
  await page.addStyleTag({path: path.join(resources, editor, 'editor-styles.css')});
  await page.evaluate(id => { window.lessonRootId = id; }, rootId);
  await page.addScriptTag({content: `
    const API = {api_structured_input_preview: '/api/structured-input/preview', api_structured_input_apply: '/api/structured-input/apply'};
    const gv = {ready: () => false, onViewportChange() {}};
    const richTypes = {};
    const passive = {bind() {}, disposeWithin() {}, format(type) { return {short: String(type), human: String(type)}; }};
    window.gdCanvasTypeStructureGraph = passive;
    window.gdCanvasBindingsGraph = passive;
    window.gdMountTypeStructureBody = (_, el, role, {label}) => {
      const text = document.createElement('span'); text.textContent = label; el.appendChild(text);
      return {update({label}) { text.textContent = label; }};
    };
    window.gdAppendTypeControlMark = (host, label) => {
      const mark = document.createElement('span'); mark.className = 'canvas-type-structure-control-mark';
      mark.setAttribute('aria-hidden', 'true'); mark.textContent = label; host.appendChild(mark); return mark;
    };
    const authFetch = window.fetch.bind(window);
    const card = document.querySelector('#root-card');
    const form = document.querySelector('#exercise');
    const output = document.querySelector('#result');
    let descriptor;
    window.gdStructuredInputFor = id => id === window.lessonRootId ? descriptor : null;
    async function loadGraphData() {
      const url = '/api/graph/entities?scope=subtree&root-id=' + encodeURIComponent(window.lessonRootId);
      const response = await fetch(url);
      if (!response.ok) throw new Error('Could not load the lesson graph');
      const {fns} = await response.json();
      descriptor = fns.find(row => row.id === window.lessonRootId)?.['structured-input'];
      if (descriptor?.status !== 'available') throw new Error('The lesson declaration is missing');
      hideAllInlineHosts();
      card.replaceChildren();
      appendStructuredInputStrip(card, window.lessonRootId);
      form.replaceChildren();
      const fields = descriptor.fields.find(field => field.name === 'profile')?.fields || [];
      for (const {name} of fields) {
        const label = document.createElement('label'); label.textContent = name + ' ';
        const input = document.createElement('input'); input.name = name; input.required = true;
        label.appendChild(input); form.appendChild(label);
      }
      const run = document.createElement('button'); run.textContent = 'Run'; form.appendChild(run);
    }
    form.addEventListener('submit', async event => {
      event.preventDefault();
      const response = await fetch('/fixture/run', {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({'fn-id': window.lessonRootId, args: {profile: Object.fromEntries(new FormData(form))}})});
      const result = await response.json();
      output.textContent = response.ok && result.ok ? JSON.stringify(result.value) : 'Run refused';
    });
  `});
  for (const file of [runtime + 'graphden-popover.js', runtime + 'graphden-forms.js', editor + 'editor-passive-canvas.js',
    editor + 'editor-literal-types.js', editor + 'editor-type-format.js', editor + 'editor-edit-modes.js',
    editor + 'editor-type-expand-render.js', editor + 'editor-overlay-type-expand.js', editor + 'editor-overlay-strips.js']) {
    await page.addScriptTag({path: path.join(resources, file)});
  }
  await page.evaluate(() => loadGraphData());
}

async function exercise() {
  const browser = await chromium.launch({
    executablePath: process.env.GD_LESSON_CHROMIUM || undefined,
    args: ['--no-sandbox'],
  });
  try {
    const page = await browser.newPage({extraHTTPHeaders: {Authorization: 'Bearer ' + token}});
    page.setDefaultTimeout(9000);
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    await page.goto(origin);
    await installLesson(page);
    const form = page.getByRole('form', {name: 'Run inputs'});
    assert.equal(await form.getByRole('textbox', {name: 'name', exact: true}).count(), 1);
    await page.getByRole('button', {name: 'Remaining inputs', exact: true}).press('Enter');
    await page.getByRole('button', {name: /^profile — Type:/}).press('Space');
    await page.getByRole('button', {name: 'Bind profile.name'}).press('Enter');
    const dialog = page.getByRole('dialog', {name: 'Bind profile.name'});
    await dialog.getByRole('textbox', {name: 'Value for profile.name'}).fill('Ada');
    await dialog.getByRole('button', {name: 'Bind', exact: true}).click();
    await page.getByRole('button', {name: 'Structured inputs', exact: true}).waitFor();
    const fields = await form.getByRole('textbox').evaluateAll(items => items.map(item => item.name));
    assert.deepEqual(fields, ['id', 'email']);
    await form.getByRole('textbox', {name: 'id'}).fill('42');
    await form.getByRole('textbox', {name: 'email'}).fill('ada@example.test');
    const [response] = await Promise.all([
      page.waitForResponse(result => new URL(result.url()).pathname === '/fixture/run'),
      form.getByRole('button', {name: 'Run'}).press('Enter'),
    ]);
    const expected = {id: '42', name: 'Ada', email: 'ada@example.test'};
    assert.equal(response.status(), 200);
    assert.deepEqual(await response.json(), {ok: true, value: expected});
    await page.waitForFunction(() => document.querySelector('#result').textContent !== '');
    const output = await page.getByRole('status', {name: 'Run result'}).textContent();
    assert.deepEqual(JSON.parse(output), expected);
    assert.deepEqual(pageErrors, []);
    console.log('Checked structured input lesson passed in Chromium');
  } finally {
    await browser.close();
  }
}

exercise().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
