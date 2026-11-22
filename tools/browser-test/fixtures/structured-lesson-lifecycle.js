'use strict';

const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const [origin, templateId, id = '43'] = process.argv.slice(2);
const token = process.env.GD_BROWSER_FIXTURE_TOKEN;
assert.equal(new URL(origin).hostname, '127.0.0.1');
assert.ok(templateId && token);

const lesson = {
  '43': {templateName: 'lesson-profile', childName: 'tutorial-structured-profile',
    select: 'Select the template', extend: 'Create your editable sample', first: 'Find the name field'},
  '44': {templateName: 'lesson-contact-route', childName: 'tutorial-contact-route',
    select: 'Select the contact route template', extend: 'Create your contact route', first: 'Find the sender city'},
  '45': {templateName: 'optional-label', childName: 'tutorial-optional-label',
    select: 'Select the optional label template', extend: 'Create your optional label', first: 'Open Run with label still optional'},
}[id];
assert.ok(lesson, 'known structured input lesson');
const {templateName, childName} = lesson;

async function openDisclosure(button, key = 'Space') {
  if (await button.getAttribute('aria-expanded') !== 'true') await button.press(key);
}

async function bindLeaf(page, reach, path, title, value) {
  await page.getByRole('button', {name: 'Bind ' + path, exact: true}).press('Enter');
  await reach(title);
  const dialog = page.getByRole('dialog', {name: 'Bind ' + path});
  await dialog.getByRole('textbox', {name: 'Value for ' + path}).fill(value);
  const [response] = await Promise.all([
    page.waitForResponse(res => new URL(res.url()).pathname === '/api/structured-input/apply' && res.request().method() === 'POST'),
    dialog.getByRole('button', {name: 'Bind', exact: true}).click(),
  ]);
  assert.deepEqual(response.request().postDataJSON().path, path.split('.'));
  if (value === 'null') assert.equal(response.request().postDataJSON().value, null);
  const result = await response.json();
  assert.equal(result.committed, true);
  assert.ok(result['binding-id']);
  return result['binding-id'];
}

async function runWithArgs(page, form, fnId, expectedArgs) {
  const [response] = await Promise.all([
    page.waitForResponse(res => new URL(res.url()).pathname === '/api/execute' && res.request().method() === 'POST'),
    form.getByRole('button', {name: 'Run', exact: true}).press('Enter'),
  ]);
  const body = response.request().postDataJSON();
  assert.equal(body['fn-id'], fnId);
  assert.deepEqual(body.args, expectedArgs);
  assert.equal(response.ok(), true);
  return (await response.json()).result;
}

async function exerciseOptional(page, reach, sampleCard, sample) {
  const trigger = sampleCard.locator('.ancestor-line[data-level="0"] .fn-run-trigger');
  const form = page.getByRole('form', {name: 'Run function'});
  const label = form.getByRole('textbox', {name: 'label (optional)', exact: true});
  await trigger.click();
  await reach('Run without a label');
  assert.equal(await label.inputValue(), '');
  assert.equal(await runWithArgs(page, form, sample.id, {}), 'text: automatic');
  await reach('Supply nil for one run');
  await label.fill('null');
  assert.equal(await runWithArgs(page, form, sample.id, {label: null}), 'explicit nil');

  await reach('Find the free label');
  await openDisclosure(sampleCard.getByRole('button', {name: 'Remaining inputs', exact: true}), 'Enter');
  const binding = await bindLeaf(page, reach, 'label', 'Bind nil to label', 'null');
  await reach('Run with the saved nil');
  const branch = new URL(page.url()).searchParams.get('branch');
  await page.reload({waitUntil: 'domcontentloaded'});
  await reach('Run with the saved nil');
  assert.equal(new URL(page.url()).searchParams.get('branch'), branch);
  await trigger.click();
  await form.getByText('No remaining inputs.', {exact: true}).waitFor();
  assert.equal(await label.count(), 0);
  assert.equal(await runWithArgs(page, form, sample.id, {}), 'explicit nil');

  await reach('Remove the saved nil');
  await openDisclosure(sampleCard.getByRole('button', {name: 'Structured inputs', exact: true}), 'Enter');
  await page.getByRole('button', {name: 'Remove label', exact: true}).press('Enter');
  const dialog = page.getByRole('dialog', {name: 'Remove label'});
  const [response] = await Promise.all([
    page.waitForResponse(res => new URL(res.url()).pathname === '/api/structured-input/apply' && res.request().method() === 'POST'),
    dialog.getByRole('button', {name: 'Remove', exact: true}).click(),
  ]);
  assert.equal(response.request().postDataJSON()['binding-id'], binding);
  assert.equal((await response.json()).committed, true);
  await reach('Run with the default again');
  await trigger.click();
  assert.equal(await label.inputValue(), '');
  assert.equal(await runWithArgs(page, form, sample.id, {}), 'text: automatic');
}

async function exerciseRecord(page, reach, sampleCard, sample, branch) {
  const contactLesson = id === '44';
  await openDisclosure(sampleCard.getByRole('button', {name: 'Remaining inputs', exact: true}), 'Enter');
  if (contactLesson) {
    await openDisclosure(page.getByRole('button', {name: /^sender — Type:/}));
    await openDisclosure(page.getByRole('button', {name: /^address — Type:/}));
    const sender = await bindLeaf(page, reach, 'sender.address.city', 'Bind the sender city', 'London');
    await reach('Find the recipient city');
    await openDisclosure(sampleCard.getByRole('button', {name: 'Structured inputs', exact: true}), 'Enter');
    await openDisclosure(page.getByRole('button', {name: /^recipient — Type:/}));
    await openDisclosure(page.getByRole('button', {name: /^address — Type:/}));
    const recipient = await bindLeaf(page, reach, 'recipient.address.city', 'Bind the recipient city', 'Paris');
    assert.notEqual(sender, recipient);
  } else {
    await openDisclosure(page.getByRole('button', {name: /^profile — Type:/}));
    await bindLeaf(page, reach, 'profile.name', 'Bind the name', 'Ada');
  }
  await reach('Open Run');

  await page.reload({waitUntil: 'domcontentloaded'});
  await reach('Open Run');
  assert.equal(new URL(page.url()).searchParams.get('branch'), branch);
  await sampleCard.locator('.ancestor-line[data-level="0"] .fn-run-trigger').click();
  await reach(contactLesson ? 'Run with two names' : 'Supply the rest and run');
  const form = page.getByRole('form', {name: 'Run function'});
  if (contactLesson) {
    assert.equal(await form.getByRole('textbox').count(), 2);
    for (const [side, name] of [['sender', 'Ada'], ['recipient', 'Bea']]) {
      await form.getByRole('group', {name: side}).getByRole('textbox', {name: 'name', exact: true}).fill(name);
    }
  } else {
    const fields = form.getByRole('group', {name: 'profile'});
    assert.equal(await fields.getByRole('textbox', {name: 'name', exact: true}).count(), 0);
    await fields.getByRole('textbox', {name: 'id', exact: true}).fill('42');
    await fields.getByRole('textbox', {name: 'email', exact: true}).fill('ada@example.test');
  }
  const args = contactLesson
    ? {sender: {name: 'Ada'}, recipient: {name: 'Bea'}}
    : {profile: {id: '42', email: 'ada@example.test'}};
  assert.deepEqual(await runWithArgs(page, form, sample.id, args), contactLesson
    ? 'Ada in London → Bea in Paris' : {id: '42', name: 'Ada', email: 'ada@example.test'});
}

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
    const reach = async title => {
      await step(title).waitFor();
      console.log('Lesson ' + id + ' reached', title);
    };
    const diagnostic = setTimeout(() => {
      console.error('Lesson ' + id + ' browser deadline', {pageErrors, failedResponses, pathname: new URL(page.url()).pathname});
    }, 52000).unref();
    browser.on('disconnected', () => clearTimeout(diagnostic));
    const doc = await page.goto(origin + '/?tutorial=' + id, {waitUntil: 'domcontentloaded'});
    assert.equal(doc.status(), 200, 'the fixture serves the normal editor document');
    try {
      await page.waitForFunction(() => window.API?.api_tour && window.API?.api_graph_entities);
      await page.waitForURL(url => url.searchParams.get('branch')?.startsWith('tutorial-' + id + '-'), {waitUntil: 'domcontentloaded'});
    } catch (error) {
      console.error('Editor startup', {pageErrors, failedResponses});
      throw error;
    }
    const branch = new URL(page.url()).searchParams.get('branch');
    await reach(lesson.select);
    await page.getByPlaceholder('Filter...', {exact: true}).fill(templateName);
    // Search and namespace rows can both display the exact same function.
    await page.locator(`#entity-list .entity-item[data-fn-id="${templateId}"]:visible`).first().click();
    await reach(lesson.extend);

    const template = page.locator(`.node-overlay[data-fn-name="${templateName}"] .ancestor-line[data-level="0"]`);
    await template.locator('button.more-actions-trigger').click();
    await page.locator('.row-actions-popover [data-action="extend-fn"]').click();
    const extend = page.getByRole('dialog', {name: 'Extend (create child fn)'});
    await extend.getByPlaceholder('New fn name').fill(childName);
    assert.equal(await extend.locator('.extend-ns-select').inputValue(), '');
    await extend.getByRole('button', {name: 'Save', exact: true}).click();
    await reach(lesson.first);

    const receipt = await page.evaluate(() => JSON.parse(localStorage.getItem('graphden.tour')));
    const sample = receipt.created.find(row => row.type === 'fn' && row.receipt === 'created');
    assert.ok(sample.id);
    assert.equal(sample['parent-id'], templateId);
    assert.equal(sample['branch-id'], receipt.sandboxBranchId);
    const sampleCard = page.locator(`.node-overlay[data-fn-name="${childName}"]`);
    if (id === '45') await exerciseOptional(page, reach, sampleCard, sample);
    else await exerciseRecord(page, reach, sampleCard, sample, branch);
    await reach('Finish the lesson');
    await page.getByRole('button', {name: 'Finish', exact: true}).click();
    const [deletion] = await Promise.all([
      page.waitForResponse(res => new URL(res.url()).pathname === '/api/branches/' + receipt.sandboxBranchId
        && res.request().method() === 'DELETE'),
      page.getByRole('button', {name: 'Delete branch & return', exact: true}).click(),
    ]);
    assert.equal(deletion.ok(), true);
    await page.waitForURL(url => !url.searchParams.has('branch'), {waitUntil: 'domcontentloaded'});
    const branches = await page.evaluate(async () => {
      const response = await window.authFetch('/api/branches');
      return response.json();
    });
    assert.ok(!(branches.branches ?? branches).some(row => row.id === receipt.sandboxBranchId));
    assert.deepEqual(pageErrors, []);
    console.log('Ordinary lesson ' + id + ' passed: Extend, Bind, reload, Run and exact branch cleanup');
  } finally {
    await browser.close();
  }
}

exercise().catch(error => { console.error(error); process.exitCode = 1; });
