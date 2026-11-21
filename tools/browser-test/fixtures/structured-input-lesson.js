'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require('playwright');

const [origin, rootId] = process.argv.slice(2);
const token = process.env.GD_BROWSER_FIXTURE_TOKEN;
assert.equal(new URL(origin).hostname, '127.0.0.1');
assert.ok(rootId && token);

const resources = path.join(__dirname, '..', '..', '..', 'resources', 'packages');
const editor = 'app/editor/';
const runtime = 'web/runtime/';

async function installLesson(page) {
  await page.setContent(`<!doctype html><html lang="en"><meta charset="utf-8"><title>Fill a structured input</title>
    <main><h1>Fill a structured input</h1><p>Bind the name, then supply the remaining fields and run.</p>
    <div id="root-card" class="node-overlay">
      <div class="ancestor-line" data-level="0"><button class="fn-run-trigger" type="button">Run sample</button></div>
      <div id="input-strip"></div>
    </div>
    <section id="run-pane" class="execute-popover gd-insp-run" hidden>
      <form id="exercise" aria-label="Run inputs"></form>
      <div class="execute-result-host" hidden>
        <div class="execute-result-pane">
          <div class="execute-result-raw"><pre role="status" aria-label="Run result" aria-live="polite"></pre></div>
        </div>
      </div>
    </section></main>`);
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
    const strip = document.querySelector('#input-strip');
    const pane = document.querySelector('#run-pane');
    const form = document.querySelector('#exercise');
    const resultHost = pane.querySelector('.execute-result-host');
    const output = resultHost.querySelector('pre');
    const lookups = {fnMap: new Map()};
    let authored;
    let _tourState;
    function _tourLesson() { return authored; }
    function _tourOwnedBranch(state) { return state.sandboxBranch; }
    function _tourSessionBranch() { return 'fixture-lesson'; }
    function _tourPrincipalMatches() { return true; }
    window.installFixtureReceipt = lesson => {
      authored = lesson;
      // This fixture begins after sample setup. The real Extend/branch
      // receipt lifecycle is checked separately.
      _tourState = {lessonId: '43', sandboxBranch: 'fixture-lesson',
        sandboxBranchId: 'fixture-branch-id', sandboxBaseBranchId: 'fixture-base-id', created: [
          {type: 'branch', id: 'fixture-branch-id', name: 'fixture-lesson',
            'base-branch-id': 'fixture-base-id', receipt: 'created'},
          {type: 'fn', id: window.lessonRootId, name: 'tutorial-structured-profile',
            'parent-id': lesson['template-id'], 'namespace-id': null, 'branch-id': 'fixture-branch-id',
            receipt: 'created'},
        ]};
    };
    let selectedFnId;
    let descriptor;
    window.gdStructuredInputFor = id => id === window.lessonRootId ? descriptor : null;
    async function loadGraphData() {
      const url = '/api/graph/entities?scope=subtree&root-id=' + encodeURIComponent(window.lessonRootId);
      const response = await fetch(url);
      if (!response.ok) throw new Error('Could not load the lesson graph');
      const {fns} = await response.json();
      const root = fns.find(row => row.id === window.lessonRootId);
      descriptor = root?.['structured-input'];
      if (descriptor?.status !== 'available') throw new Error('The lesson declaration is missing');
      selectedFnId = root.id;
      lookups.fnMap.set(root.id, root);
      card.dataset.fnName = root.name;
      hideAllInlineHosts();
      strip.replaceChildren();
      appendStructuredInputStrip(strip, root.id);
      form.replaceChildren();
      const fields = descriptor.fields.find(field => field.name === 'profile')?.fields || [];
      for (const {name} of fields) {
        const label = document.createElement('label'); label.textContent = name + ' ';
        const input = document.createElement('input'); input.name = name; input.required = true;
        label.appendChild(input); form.appendChild(label);
      }
      const run = document.createElement('button');
      run.textContent = 'Run'; run.className = 'execute-run-btn'; form.appendChild(run);
    }
    card.querySelector('.fn-run-trigger').addEventListener('click', () => {
      pane.hidden = false;
      pane.classList.add('visible');
    });
    form.addEventListener('submit', async event => {
      event.preventDefault();
      resultHost.hidden = true;
      delete resultHost.gdExecutionFnId;
      const response = await fetch('/fixture/run', {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({'fn-id': window.lessonRootId, args: {profile: Object.fromEntries(new FormData(form))}})});
      const result = await response.json();
      if (response.ok && result.ok) resultHost.gdExecutionFnId = window.lessonRootId;
      output.textContent = response.ok && result.ok ? JSON.stringify(result.value) : 'Run refused';
      resultHost.hidden = false;
    });
  `});
  for (const file of [runtime + 'graphden-popover.js', runtime + 'graphden-forms.js', editor + 'editor-passive-canvas.js',
    editor + 'editor-literal-types.js', editor + 'editor-type-format.js', editor + 'editor-edit-modes.js',
    editor + 'editor-edit-structured-input.js',
    editor + 'editor-type-expand-render.js', editor + 'editor-overlay-type-expand.js', editor + 'editor-overlay-strips.js',
    editor + 'editor-tour-checks.js', editor + 'editor-tour-structured-input.js']) {
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
    const lesson = await page.evaluate(async () => {
      const response = await fetch('/fixture/lesson');
      if (!response.ok) throw new Error('Could not load the authored lesson');
      return response.json();
    });
    assert.equal(lesson.id, '43');
    await page.evaluate(data => window.installFixtureReceipt(data), lesson);
    const [, , findName, bindName, openRun, runResult] = lesson.steps;
    const checked = step => page.evaluate(check => _tourCheckPasses(check), step.check);
    assert.equal(await checked(bindName), false);
    await page.locator(findName.target).waitFor();
    const form = page.getByRole('form', {name: 'Run inputs'});
    assert.deepEqual(await page.locator('#exercise input').evaluateAll(items => items.map(item => item.name)),
      ['id', 'name', 'email']);
    await page.getByRole('button', {name: 'Remaining inputs', exact: true}).press('Enter');
    await page.getByRole('button', {name: /^profile — Type:/}).press('Space');
    await page.getByRole('button', {name: 'Bind profile.name'}).press('Enter');
    const dialog = page.getByRole('dialog', {name: 'Bind profile.name'});
    assert.equal(await checked(findName), true);
    await dialog.getByRole('textbox', {name: 'Value for profile.name'}).fill('Ada');
    await dialog.getByRole('button', {name: 'Bind', exact: true}).click();
    await page.getByRole('button', {name: 'Structured inputs', exact: true}).waitFor();
    assert.equal(await checked(bindName), true);
    assert.equal(await checked(openRun), false);
    await page.locator(openRun.target).press('Enter');
    assert.equal(await checked(openRun), true);
    assert.equal(await checked(runResult), false);
    const fields = await form.getByRole('textbox').evaluateAll(items => items.map(item => item.name));
    assert.deepEqual(fields, ['id', 'email']);
    await form.getByRole('textbox', {name: 'id'}).fill('42');
    await form.getByRole('textbox', {name: 'email'}).fill('ada@example.test');
    const [response] = await Promise.all([
      page.waitForResponse(result => new URL(result.url()).pathname === '/fixture/run'),
      form.getByRole('button', {name: 'Run'}).press('Enter'),
    ]);
    const expected = runResult.check.value;
    assert.equal(response.status(), 200);
    assert.deepEqual(await response.json(), {ok: true, value: expected});
    const output = await page.getByRole('status', {name: 'Run result'}).textContent();
    assert.deepEqual(JSON.parse(output), expected);
    assert.equal(await checked(runResult), true);
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
