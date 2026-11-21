'use strict';

const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const [origin, templateId] = process.argv.slice(2);
const token = process.env.GD_BROWSER_FIXTURE_TOKEN;
assert.equal(new URL(origin).hostname, '127.0.0.1');
assert.ok(templateId && token);

async function exercise() {
  const browser = await chromium.launch({
    executablePath: process.env.GD_LESSON_CHROMIUM || undefined, args: ['--no-sandbox'],
  });
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
    await page.route('**/*', route => new URL(route.request().url()).origin === origin
      ? route.continue() : route.abort());
    await page.addInitScript(({origin, token}) => {
      if (location.origin === origin) localStorage.setItem('graphden.auth.password', token);
    }, {origin, token});
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    const failedResponses = [];
    page.on('response', response => {
      if (response.status() >= 400) failedResponses.push([new URL(response.url()).pathname, response.status()]);
    });
    page.setDefaultTimeout(15000);
    const step = title => page.locator('#gd-tour-pop .gd-tour-title').filter({hasText: new RegExp('^' + title + '$')});
    const doc = await page.goto(origin + '/?tutorial=43', {waitUntil: 'domcontentloaded'});
    assert.equal(doc.status(), 200, 'the fixture serves the normal editor document');
    try {
      await page.waitForFunction(() => window.API?.api_tour && window.API?.api_graph_entities);
      await page.waitForURL(url => url.searchParams.get('branch')?.startsWith('tutorial-43-'), {waitUntil: 'domcontentloaded'});
    } catch (error) {
      console.error('Editor startup', {pageErrors, failedResponses});
      throw error;
    }
    const branch = new URL(page.url()).searchParams.get('branch');
    await step('Select the template').waitFor();
    await page.getByPlaceholder('Filter...', {exact: true}).fill('lesson-profile');
    // Search and namespace rows can both display the exact same function.
    await page.locator(`#entity-list .entity-item[data-fn-id="${templateId}"]:visible`).first().click();
    await step('Create your editable sample').waitFor();

    const template = page.locator('.node-overlay[data-fn-name="lesson-profile"] .ancestor-line[data-level="0"]');
    await template.locator('button.more-actions-trigger').click();
    await page.locator('.row-actions-popover [data-action="extend-fn"]').click();
    const extend = page.getByRole('dialog', {name: 'Extend (create child fn)'});
    await extend.getByPlaceholder('New fn name').fill('tutorial-structured-profile');
    assert.equal(await extend.locator('.extend-ns-select').inputValue(), '');
    await extend.getByRole('button', {name: 'Save', exact: true}).click();
    await step('Find the name field').waitFor();

    const receipt = await page.evaluate(() => JSON.parse(localStorage.getItem('graphden.tour')));
    const sample = receipt.created.find(row => row.type === 'fn' && row.receipt === 'created');
    assert.ok(sample.id);
    assert.equal(sample['parent-id'], templateId);
    assert.equal(sample['branch-id'], receipt.sandboxBranchId);
    await page.getByRole('button', {name: 'Remaining inputs', exact: true}).click();
    await page.getByRole('button', {name: /^profile — Type:/}).press('Space');
    await page.getByRole('button', {name: 'Bind profile.name'}).press('Enter');
    await step('Bind the name').waitFor();
    const bind = page.getByRole('dialog', {name: 'Bind profile.name'});
    await bind.getByRole('textbox', {name: 'Value for profile.name'}).fill('Ada');
    await bind.getByRole('button', {name: 'Bind', exact: true}).click();
    await step('Open Run').waitFor();

    await page.reload({waitUntil: 'domcontentloaded'});
    await step('Open Run').waitFor();
    assert.equal(new URL(page.url()).searchParams.get('branch'), branch);
    await page.locator('.node-overlay[data-fn-name="tutorial-structured-profile"] .ancestor-line[data-level="0"] .fn-run-trigger').click();
    await step('Supply the rest and run').waitFor();
    const form = page.getByRole('form', {name: 'Run function'});
    const fields = form.getByRole('group', {name: 'profile'});
    assert.equal(await fields.getByRole('textbox', {name: 'name', exact: true}).count(), 0);
    await fields.getByRole('textbox', {name: 'id', exact: true}).fill('42');
    await fields.getByRole('textbox', {name: 'email', exact: true}).fill('ada@example.test');
    const [response] = await Promise.all([
      page.waitForResponse(res => new URL(res.url()).pathname === '/api/execute' && res.request().method() === 'POST'),
      form.getByRole('button', {name: 'Run', exact: true}).press('Enter'),
    ]);
    assert.equal(response.request().postDataJSON()['fn-id'], sample.id);
    assert.deepEqual((await response.json()).result, {id: '42', name: 'Ada', email: 'ada@example.test'});
    await step('Finish the lesson').waitFor();
    await page.getByRole('button', {name: 'Finish', exact: true}).click();
    await page.getByRole('button', {name: 'Delete branch & return', exact: true}).click();
    await page.waitForURL(url => !url.searchParams.has('branch'));
    const branches = await page.evaluate(async () => {
      const response = await window.authFetch('/api/branches');
      return response.json();
    });
    assert.ok(!(branches.branches ?? branches).some(row => row.id === receipt.sandboxBranchId));
    assert.deepEqual(pageErrors, []);
    console.log('Ordinary lesson 43 passed: Extend, Bind, reload, Run and exact branch cleanup');
  } finally {
    await browser.close();
  }
}

exercise().catch(error => { console.error(error); process.exitCode = 1; });
