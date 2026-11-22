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
  '46': {templateName: 'lesson-profile', childName: 'tutorial-whole-profile',
    select: 'Select the profile template', extend: 'Create your whole-profile sample', first: 'Find the whole record'},
  '47': {templateName: 'lesson-computed-greeting', childName: 'tutorial-computed-greeting',
    select: 'Select the greeting template', extend: 'Create your greeting sample', first: 'Find the callback field'},
}[id];
assert.ok(lesson, 'known structured input lesson');
const {templateName, childName} = lesson;

async function openDisclosure(button, key = 'Space') {
  if (await button.getAttribute('aria-expanded') !== 'true') await button.press(key);
}

async function bindInput(page, reach, path, title, value) {
  await page.getByRole('button', {name: 'Bind ' + path, exact: true}).press('Enter');
  await reach(title);
  const dialog = page.getByRole('dialog', {name: 'Bind ' + path});
  await dialog.getByRole('textbox', {name: 'Value for ' + path}).fill(value);
  const [response] = await Promise.all([
    page.waitForResponse(res => new URL(res.url()).pathname === '/api/structured-input/apply' && res.request().method() === 'POST'),
    dialog.getByRole('button', {name: 'Bind', exact: true}).click(),
  ]);
  assert.deepEqual(response.request().postDataJSON().path, path.split('.'));
  if (path === 'profile' || value === 'null') assert.deepEqual(response.request().postDataJSON().value, JSON.parse(value));
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

async function removeInput(page, path, binding) {
  await page.getByRole('button', {name: 'Remove ' + path, exact: true}).press('Enter');
  const dialog = page.getByRole('dialog', {name: 'Remove ' + path});
  const [response] = await Promise.all([
    page.waitForResponse(res => new URL(res.url()).pathname === '/api/structured-input/apply' && res.request().method() === 'POST'),
    dialog.getByRole('button', {name: 'Remove', exact: true}).click(),
  ]);
  assert.equal(response.request().postDataJSON()['binding-id'], binding);
  assert.equal((await response.json()).committed, true);
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
  const binding = await bindInput(page, reach, 'label', 'Bind nil to label', 'null');
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
  await removeInput(page, 'label', binding);
  await reach('Run with the default again');
  await trigger.click();
  assert.equal(await label.inputValue(), '');
  assert.equal(await runWithArgs(page, form, sample.id, {}), 'text: automatic');
}

async function inputCommand(page, phase, command) {
  return page.evaluate(async ({phase, command}) => {
    const response = await window.authFetch('/api/structured-input/' + phase, {
      method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(command),
    });
    return {status: response.status, body: await response.json()};
  }, {phase, command});
}

async function readInput(page, owner) {
  return page.evaluate(async id => {
    const response = await window.authFetch('/api/graph/entities?scope=subtree&root-id=' + encodeURIComponent(id));
    if (!response.ok) throw new Error('Could not read the lesson sample');
    const {fns} = await response.json();
    return fns.find(row => row.id === id)?.['structured-input'];
  }, owner);
}

async function exerciseWhole(page, reach, sampleCard, sample, branch) {
  const trigger = sampleCard.locator('.ancestor-line[data-level="0"] .fn-run-trigger');
  const form = page.getByRole('form', {name: 'Run function'});
  const profile = {id: '42', name: 'Ada', email: 'ada@example.test'};
  const leaf = {'fn-id': sample.id, action: 'bind', path: ['profile', 'name'], value: 'Blocked'};
  const before = await inputCommand(page, 'preview', leaf);
  assert.equal(before.body.ok, true);
  assert.ok(before.body['expected-state']);

  await openDisclosure(sampleCard.getByRole('button', {name: 'Remaining inputs', exact: true}), 'Enter');
  const binding = await bindInput(page, reach, 'profile', 'Bind all three profile fields', JSON.stringify(profile));
  await openDisclosure(sampleCard.getByRole('button', {name: 'Structured inputs', exact: true}), 'Enter');
  await reach('Run the whole profile');
  const occupied = await readInput(page, sample.id);
  assert.deepEqual(occupied.fields, []);
  assert.deepEqual(occupied.occupied.map(row => row.path), [['profile']]);
  assert.equal(occupied.occupied[0]['binding-id'], binding);
  assert.ok(occupied.occupied[0]['version-id']);
  assert.equal(await sampleCard.getByRole('button', {name: /^Bind profile(?:\.|$)/}).count(), 0);
  const duplicate = {...leaf, path: ['profile'], value: {...profile, name: 'Blocked'}};
  for (const command of [duplicate, leaf]) {
    assert.equal((await inputCommand(page, 'preview', command)).status, 409);
  }
  assert.equal((await inputCommand(page, 'apply', {...leaf, 'expected-state': before.body['expected-state']})).status, 409);
  assert.deepEqual(await readInput(page, sample.id), occupied);

  await page.reload({waitUntil: 'domcontentloaded'});
  await reach('Run the whole profile');
  assert.equal(new URL(page.url()).searchParams.get('branch'), branch);
  await trigger.click();
  await form.getByText('No remaining inputs.', {exact: true}).waitFor();
  assert.deepEqual(await runWithArgs(page, form, sample.id, {}), profile);

  await reach('Remove the whole profile binding');
  await openDisclosure(sampleCard.getByRole('button', {name: 'Structured inputs', exact: true}), 'Enter');
  await removeInput(page, 'profile', binding);
  await reach('Find the name inside profile');
  await openDisclosure(sampleCard.getByRole('button', {name: 'Remaining inputs', exact: true}), 'Enter');
  await openDisclosure(page.getByRole('button', {name: /^profile — Type:/}));
  await bindInput(page, reach, 'profile.name', 'Bind only the name', 'Bea');
  await reach('Run with the remaining fields');
  const partial = await readInput(page, sample.id);
  assert.deepEqual(partial.occupied.map(row => row.path), [['profile', 'name']]);
  assert.equal(partial.fields[0]['bind-path'], undefined);
  assert.deepEqual(partial.fields[0].fields.map(field => field.name).sort(), ['email', 'id']);
  await trigger.click();
  const fields = form.getByRole('group', {name: 'profile'});
  assert.equal(await fields.getByRole('textbox', {name: 'name', exact: true}).count(), 0);
  await fields.getByRole('textbox', {name: 'id', exact: true}).fill('77');
  await fields.getByRole('textbox', {name: 'email', exact: true}).fill('bea@example.test');
  assert.deepEqual(await runWithArgs(page, form, sample.id, {profile: {id: '77', email: 'bea@example.test'}}),
    {id: '77', name: 'Bea', email: 'bea@example.test'});
}

async function bindFunction(page, reach, path, title, name, expectedId) {
  await page.getByRole('button', {name: 'Bind ' + path, exact: true}).press('Enter');
  await reach(title);
  const dialog = page.getByRole('dialog', {name: 'Bind ' + path, exact: true});
  await dialog.getByRole('button', {name: 'Choose function'}).press('Enter');
  await page.getByPlaceholder('Filter fns…').fill(name);
  await page.locator('.fn-picker-row', {hasText: name}).click();
  const value = await dialog.getByRole('textbox', {name: 'Function for ' + path}).inputValue();
  assert.ok(value.endsWith(name));
  const preview = page.waitForResponse(res => new URL(res.url()).pathname === '/api/structured-input/preview'
    && res.request().method() === 'POST');
  const apply = page.waitForResponse(res => new URL(res.url()).pathname === '/api/structured-input/apply'
    && res.request().method() === 'POST');
  // Preserve both waits before clicking, but print the checked preview when it stops the commit.
  apply.catch(() => {});
  await dialog.getByRole('button', {name: 'Bind', exact: true}).click();
  const checked = await preview;
  const proposal = await checked.json();
  assert.equal(checked.status(), 200, JSON.stringify(proposal));
  assert.equal(proposal.ok, true, JSON.stringify(proposal));
  const response = await apply;
  const request = response.request().postDataJSON();
  assert.deepEqual(request.path, path.split('.'));
  assert.equal(request['ref-fn-id'], expectedId);
  assert.equal(Object.hasOwn(request, 'value'), false);
  const result = await response.json();
  assert.equal(result.committed, true, JSON.stringify(result));
  assert.ok(result['binding-id']);
  return result['binding-id'];
}

async function exerciseComputed(page, reach, sampleCard, sample, branch) {
  const refs = await page.evaluate(async () => {
    const response = await window.authFetch('/api/tour', {cache: 'no-store'});
    if (!response.ok) throw new Error('Could not read the lesson references');
    return (await response.json())['structured-template-refs']?.['47'];
  });
  assert.ok(refs.builder && refs.callback && refs.builder !== refs.callback);

  const initial = await readInput(page, sample.id);
  assert.notEqual(initial['run-available'], true);
  const unchecked = await inputCommand(page, 'preview', {
    'fn-id': sample.id, action: 'bind', path: ['profile'], 'ref-fn-id': refs.builder,
  });
  assert.equal(unchecked.status, 422, JSON.stringify(unchecked.body));
  assert.deepEqual(await readInput(page, sample.id), initial);
  await openDisclosure(sampleCard.getByRole('button', {name: 'Remaining inputs', exact: true}), 'Enter');
  await openDisclosure(page.getByRole('button', {name: /^style — Type:/}));
  const callback = await bindFunction(page, reach, 'style.callback', 'Bind the callback', 'mark-greeting', refs.callback);
  await reach('Find the builder destination');
  await openDisclosure(sampleCard.getByRole('button', {name: 'Structured inputs', exact: true}), 'Enter');
  const builder = await bindFunction(page, reach, 'profile', 'Bind the profile builder', 'build-greeting-profile', refs.builder);
  await reach('Run from two named inputs');
  assert.notEqual(builder, callback);

  await page.reload({waitUntil: 'domcontentloaded'});
  await reach('Run from two named inputs');
  assert.equal(new URL(page.url()).searchParams.get('branch'), branch);
  const projection = await readInput(page, sample.id);
  assert.equal(projection['run-available'], true);
  assert.deepEqual(Object.fromEntries(projection.occupied.map(row => [row.path.join('.'), row['binding-id']])),
    {profile: builder, 'style.callback': callback});
  const input = projection.fields.find(row => row.name === 'profile');
  assert.equal(input.kind, 'reference-inputs');
  assert.deepEqual(input.fields[0].fields.map(field => field.name), ['name']);
  assert.deepEqual(projection.fields.find(row => row.name === 'style').fields.map(field => field.name), ['suffix']);

  await sampleCard.locator('.ancestor-line[data-level="0"] .fn-run-trigger').click();
  const form = page.getByRole('form', {name: 'Run function'});
  await form.getByRole('textbox', {name: 'name', exact: true}).fill('Ada');
  await form.getByRole('textbox', {name: 'suffix', exact: true}).fill('team');
  assert.equal(await form.getByRole('textbox').count(), 2);
  const args = {profile: {profile: {name: 'Ada'}}, style: {suffix: 'team'}};
  assert.equal(await runWithArgs(page, form, sample.id, args), 'Hello Ada! · team');
}

async function exerciseRecord(page, reach, sampleCard, sample, branch) {
  const contactLesson = id === '44';
  await openDisclosure(sampleCard.getByRole('button', {name: 'Remaining inputs', exact: true}), 'Enter');
  if (contactLesson) {
    await openDisclosure(page.getByRole('button', {name: /^sender — Type:/}));
    await openDisclosure(page.getByRole('button', {name: /^address — Type:/}));
    const sender = await bindInput(page, reach, 'sender.address.city', 'Bind the sender city', 'London');
    await reach('Find the recipient city');
    await openDisclosure(sampleCard.getByRole('button', {name: 'Structured inputs', exact: true}), 'Enter');
    await openDisclosure(page.getByRole('button', {name: /^recipient — Type:/}));
    await openDisclosure(page.getByRole('button', {name: /^address — Type:/}));
    const recipient = await bindInput(page, reach, 'recipient.address.city', 'Bind the recipient city', 'Paris');
    assert.notEqual(sender, recipient);
  } else {
    await openDisclosure(page.getByRole('button', {name: /^profile — Type:/}));
    await bindInput(page, reach, 'profile.name', 'Bind the name', 'Ada');
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
    }, ['46', '47'].includes(id) ? 82000 : 52000).unref();
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
    else if (id === '46') await exerciseWhole(page, reach, sampleCard, sample, branch);
    else if (id === '47') await exerciseComputed(page, reach, sampleCard, sample, branch);
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
