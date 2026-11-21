'use strict';

const assert = require('node:assert/strict');
const {chromium} = require('playwright');

const [origin, owner, ownerName, target] = process.argv.slice(2);
const token = process.env.GD_BROWSER_FIXTURE_TOKEN;
assert.equal(new URL(origin).hostname, '127.0.0.1');
assert.ok(owner && ownerName && target && token);

async function applyChange(page, dialog, label) {
  const applied = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/structured-input/apply');
  await dialog.getByRole('button', {name: label, exact: true}).click();
  const response = await applied;
  const result = await response.json();
  assert.equal(response.status(), 200, JSON.stringify(result));
  assert.equal(result.committed, true);
  await dialog.waitFor({state: 'hidden'});
  return {body: response.request().postDataJSON(), result};
}

async function exercise() {
  const browser = await chromium.launch({
    executablePath: process.env.GD_LESSON_CHROMIUM || undefined, args: ['--no-sandbox'],
  });
  const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
  const requests = [];
  const refreshErrors = [];
  try {
    const errors = [];
    page.on('response', response => {
      const url = new URL(response.url());
      if (url.pathname === '/api/graph/entities' || url.pathname === '/api/types') {
        requests.push([url.pathname, url.searchParams.get('scope'), response.status()]);
      }
    });
    page.on('console', event => {
      if (event.type() === 'error') refreshErrors.push(event.text().slice(0, 220));
    });
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', route => new URL(route.request().url()).origin === origin
      ? route.continue() : route.abort());
    await page.addInitScript(({origin, token}) => {
      if (location.origin === origin) localStorage.setItem('graphden.auth.password', token);
    }, {origin, token});
    page.setDefaultTimeout(12000);
    const documentResponse = await page.goto(origin, {waitUntil: 'domcontentloaded'});
    assert.equal(documentResponse.status(), 200);

    await page.getByPlaceholder('Filter...', {exact: true}).fill(ownerName);
    await page.locator(`#entity-list .entity-item[data-fn-id="${owner}"]:visible`).first().click();
    await page.getByRole('button', {name: 'Remaining inputs', exact: true}).press('Enter');
    await page.getByRole('button', {name: /^profile — Type:/}).press('Space');
    await page.getByRole('button', {name: 'Bind profile.name'}).press('Enter');

    const dialog = page.getByRole('dialog', {name: 'Bind profile.name'});
    const value = dialog.getByRole('textbox', {name: 'Value for profile.name'});
    await value.fill('Keep this draft');
    const choose = dialog.getByRole('button', {name: 'Choose function'});
    await choose.press('Enter');
    const filter = page.getByPlaceholder('Filter fns…');
    await filter.waitFor();
    await filter.press('Escape');
    assert.equal(await value.inputValue(), 'Keep this draft');

    await choose.press('Enter');
    await filter.fill('reference-uppercase');
    await page.locator('.fn-picker-row', {hasText: 'reference-uppercase'}).click();
    const picked = dialog.getByRole('textbox', {name: 'Function for profile.name'});
    assert.match(await picked.inputValue(), /reference-uppercase$/);
    assert.equal(await picked.getAttribute('readonly'), '');
    if (process.env.GD_STRUCTURED_REF_SCREENSHOT) {
      await page.screenshot({path: process.env.GD_STRUCTURED_REF_SCREENSHOT});
    }

    const bound = await applyChange(page, dialog, 'Bind');
    assert.deepEqual(bound.body.path, ['profile', 'name']);
    assert.equal(bound.body['fn-id'], owner);
    assert.equal(bound.body['ref-fn-id'], target);
    assert.equal(Object.hasOwn(bound.body, 'value'), false);
    const binding = bound.result['binding-id'];
    assert.ok(binding);

    const waitForVersion = previous => page.waitForFunction(({owner, binding, previous}) => {
      const descriptor = window.gdStructuredInputFor?.(owner);
      return descriptor?.occupied?.some(field => field['binding-id'] === binding
        && field['version-id'] !== previous);
    }, {owner, binding, previous});

    await waitForVersion(null);
    const edit = page.getByRole('button', {name: 'Edit profile.name'});
    const change = page.getByRole('dialog', {name: 'Edit profile.name'});
    await edit.press('Enter');
    await change.getByRole('textbox', {name: 'New value for profile.name'}).fill('Bea');
    const literal = await applyChange(page, change, 'Save');
    assert.equal(literal.body['binding-id'], binding);
    assert.equal(literal.body.value, 'Bea');
    assert.ok(literal.body['expected-version']);
    assert.equal(Object.hasOwn(literal.body, 'ref-fn-id'), false);

    await waitForVersion(literal.body['expected-version']);
    await edit.press('Enter');
    await change.getByRole('button', {name: 'Choose function'}).press('Enter');
    await filter.fill('reference-uppercase');
    await page.locator('.fn-picker-row', {hasText: 'reference-uppercase'}).click();
    const rewritten = await applyChange(page, change, 'Save');
    assert.equal(rewritten.body['binding-id'], binding);
    assert.equal(rewritten.body['ref-fn-id'], target);
    assert.notEqual(rewritten.body['expected-version'], literal.body['expected-version']);

    await waitForVersion(rewritten.body['expected-version']);
    await page.getByRole('button', {name: 'Remove profile.name'}).press('Enter');
    const remove = page.getByRole('dialog', {name: 'Remove profile.name'});
    const removed = await applyChange(page, remove, 'Remove');
    assert.equal(removed.body['binding-id'], binding);
    assert.equal(Object.hasOwn(removed.body, 'ref-fn-id'), false);
    assert.equal(Object.hasOwn(removed.body, 'value'), false);
    await page.getByRole('button', {name: 'Bind profile.name'}).waitFor();
    assert.deepEqual(errors, []);
  } catch (error) {
    console.error('Editor refresh evidence', {requests: requests.slice(-16), refreshErrors: refreshErrors.slice(-10)});
    throw error;
  } finally {
    await browser.close();
  }
}

exercise().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
