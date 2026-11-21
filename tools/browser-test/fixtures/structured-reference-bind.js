'use strict';

const assert = require('node:assert/strict');
const {chromium} = require('playwright');

const [origin, owner, ownerName, target] = process.argv.slice(2);
const token = process.env.GD_BROWSER_FIXTURE_TOKEN;
assert.equal(new URL(origin).hostname, '127.0.0.1');
assert.ok(owner && ownerName && target && token);

async function exercise() {
  const browser = await chromium.launch({
    executablePath: process.env.GD_LESSON_CHROMIUM || undefined, args: ['--no-sandbox'],
  });
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
    const errors = [];
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
    await page.locator(`#entity-list .entity-item[data-fn-id="${owner}"]`).click();
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

    const applied = page.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/structured-input/apply');
    await dialog.getByRole('button', {name: 'Bind', exact: true}).click();
    const response = await applied;
    const body = response.request().postDataJSON();
    assert.deepEqual(body.path, ['profile', 'name']);
    assert.equal(body['fn-id'], owner);
    assert.equal(body['ref-fn-id'], target);
    assert.equal(Object.hasOwn(body, 'value'), false);
    assert.equal((await response.json()).committed, true);
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
}

exercise().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
