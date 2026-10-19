// Two ordinary wrapper descendants specialize shared E through map values.
// The real editor saves a local override and ranks callbacks from checker facts.
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require('playwright');
const {assert, newContext, api, BASE, AUTH, waitForServerHealthy} = require('./edit-test-helpers');
const {captureFixtureNamespaces, cleanupGraphFixture} = require('./ui-graph-fixture-cleanup');
const {renameArgViaEdgeLabel} = require('./tutorial-tour-helpers');
const run = process.pid + '-' + Date.now().toString(36);
const branch = 'execution-editor-' + run;
const namespace = 'user.execution_contract_' + run.replaceAll('-', '_');
const names = Object.fromEntries(['wrapper', 'browser-child', 'server-child', 'pure-callback', 'server-callback']
  .map(name => [name, name + '-' + run]));

async function goto(page, name) {
  await page.goto(BASE + '/?branch=' + branch + '#' + namespace + '.' + names[name]);
  await page.waitForFunction(expected => graphReady() && !graph.animating
    && lookups.fnMap.get(selectedFnId)?.name === expected, names[name], {timeout: 60000});
  await page.waitForSelector('.edge-label-overlay', {timeout: 30000});
}
async function expandMapValue(page, argName) {
  const chip = page.locator('.edge-label-overlay[data-arg-name="' + argName + '"] .arg-type-chip').first();
  await chip.waitFor({timeout: 30000});
  if (await chip.getAttribute('aria-expanded') !== 'true') await chip.click();
  const panel = page.locator('.type-inline-host:visible');
  await panel.waitFor();
  const values = panel.locator('.type-inline-row').filter({has: page.locator('.type-inline-label', {hasText: /^value$/})}).first();
  const valueChip = values.locator(':scope > .arg-type-chip');
  if (await valueChip.getAttribute('aria-expanded') !== 'true') await valueChip.click();
  return panel;
}

async function entry(page) {
  return page.evaluate(() => richTypeEntryOf(lookups.fnMap.get(selectedFnId)));
}
async function openPicker(page, owner) {
  await page.keyboard.press('Escape');
  await page.keyboard.press('Escape');
  const binder = page.locator('.placeholder-binder[data-fn-name="' + names[owner] + '"][data-arg-name="handler"]');
  await binder.waitFor({timeout: 30000});
  await binder.click();
  const choose = page.getByRole('button', {name: 'Bind fn-ref', exact: true});
  await choose.waitFor({timeout: 15000});
  await choose.click();
  await page.locator('.fn-picker-popover').waitFor();
}
async function candidate(page, name, compatible) {
  await page.locator('.fn-picker-popover input').fill(names[name]);
  const row = page.locator('.fn-picker-popover .fn-picker-row-' + (compatible ? 'compat' : 'incompat')
    + '[data-fn-name$="' + names[name] + '"]');
  await row.waitFor({timeout: 30000});
  return row;
}

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const errors = [];
  page.on('pageerror', error => { errors.push(error.message); console.log('execution pageerror', error.message.slice(0, 400)); });
  let consoleErrors = 0;
  page.on('console', message => { if (message.type() === 'error' && consoleErrors++ < 3) console.log('execution console.error', message.text().slice(0, 600)); });
  page.on('response', async response => {
    if (response.status() !== 422) return;
    const url = new URL(response.url());
    const body = await response.text().catch(() => 'unavailable');
    console.log('execution HTTP422', url.pathname + url.search, body.slice(0, 1200));
  });
  page.on('dialog', dialog => dialog.accept());
  let prepared, baseline, releaseTypeRefresh;
  try {
    baseline = await captureFixtureNamespaces(page);
    let source = fs.readFileSync(path.join(__dirname, '../../test/fixtures/platform/execution_editor.edn'), 'utf8')
      .replaceAll('user.execution-contract', namespace);
    for (const [name, unique] of Object.entries(names)) {
      source = source.replace(new RegExp(':' + name + '(?=[\\s}\\]])', 'g'), ':' + unique);
    }
    const imported = await page.request.post(BASE + '/api/import/graph?target=' + branch + '&create=true', {
      headers: {Authorization: 'Bearer ' + AUTH, 'Content-Type': 'application/edn'}, data: source, timeout: 120000,
    });
    assert(imported.ok(), 'ordinary graph source imported into isolated branch');
    const receipt = await imported.json();
    assert(receipt.ok !== false && receipt.status !== 'failed', 'fixture import accepted');
    prepared = {branch, namespace};
    await goto(page, 'browser-child');
    const inherited = await expandMapValue(page, 'handlers');
    assert(await inherited.getByRole('combobox', {name: 'Execution profile', exact: true}).count() === 0,
      'inherited constraint is read-only until a local override exists');
    assert((await inherited.innerText()).includes('Inherited constraint'), 'inherited control explains its read-only state');
    await page.keyboard.press('Escape');
    await renameArgViaEdgeLabel(page, 'handlers', 'events');
    const panel = await expandMapValue(page, 'events');
    const execution = panel.getByRole('combobox', {name: 'Execution profile', exact: true});
    await execution.waitFor();
    await execution.selectOption('browser');
    const typeRefresh = new Promise(resolve => { releaseTypeRefresh = resolve; });
    await page.route(BASE + '/api/types', async route => {
      await typeRefresh;
      await route.continue();
    }, {times: 1});
    const saved = page.waitForResponse(response => response.request().method() === 'POST'
      && response.url().includes('/tighten-fn-effects'));
    await panel.getByRole('button', {name: 'Tighten', exact: true}).click();
    const savedResponse = await saved;
    assert(savedResponse.ok(), 'map value execution requirement saved through production control');
    const proposal = savedResponse.request().postDataJSON();
    assert(JSON.stringify(proposal.type[2][4]) === JSON.stringify(['browser-main']), 'submitted map values require Browser');
    assert(Array.isArray(proposal.type[2][3]) && proposal.type[2][3].length === 0, 'pure effect ceiling survived execution edit');
    await page.waitForFunction(() => document.querySelector(
      '.edge-label-overlay[data-arg-name="events"] .arg-type-chip')?.getAttribute('aria-disabled') === 'true');
    const staleChip = page.locator('.edge-label-overlay[data-arg-name="events"] .arg-type-chip');
    assert(await staleChip.getAttribute('tabindex') === '-1', 'refresh removes the stale type action from keyboard focus');
    await staleChip.dispatchEvent('click');
    assert(await page.locator('.type-inline-host:visible').count() === 0,
      'stale E chip cannot reopen an editable panel during the held type refresh');
    releaseTypeRefresh(); releaseTypeRefresh = null;
    await page.waitForFunction(() => {
      const e = richTypeEntryOf(lookups.fnMap.get(selectedFnId));
      return e?.args?.handler?.[4]?.includes('browser-main') && e?.args?.events?.[0] === 'map';
    }, null, {timeout: 60000});
    const browserEntry = await entry(page);
    assert(browserEntry.args.handler[4].includes('browser-main'), 'map pin specializes sibling callback expectation');
    await page.waitForFunction(() => {
      const chip = document.querySelector('.edge-label-overlay[data-arg-name="events"] .arg-type-chip');
      return chip?.title.includes('Browser') && chip.getAttribute('aria-disabled') !== 'true';
    }, null, {timeout: 60000});
    const freshChip = page.locator('.edge-label-overlay[data-arg-name="events"] .arg-type-chip');
    assert(await freshChip.getAttribute('tabindex') === '0', 'coherent Browser action returns to keyboard focus');
    await freshChip.focus();
    if (await freshChip.getAttribute('aria-expanded') !== 'true') await freshChip.press('Enter');
    assert(await freshChip.getAttribute('aria-expanded') === 'true', 'Enter opens the fresh Browser contract');
    const reopened = await expandMapValue(page, 'events');
    assert(await reopened.getByRole('combobox', {name: 'Execution profile', exact: true}).inputValue() === 'browser',
      'saved anonymous override reopens with its exact Browser contract');
    await openPicker(page, 'browser-child');
    const rejected = await candidate(page, 'server-callback', false);
    await rejected.click();
    const mismatch = page.locator('.mismatch-explainer.visible');
    await mismatch.waitFor();
    const reason = await mismatch.innerText();
    assert(/Browser/.test(reason) && /JVM/.test(reason), 'server-only callback rejection explains the two execution profiles');
    await page.screenshot({path: '/tmp/graphden-a3-execution-refusal.png', fullPage: true});
    await openPicker(page, 'browser-child');
    await (await candidate(page, 'pure-callback', true)).click();
    await page.locator('.fn-picker-popover').waitFor({state: 'hidden', timeout: 30000});
    await goto(page, 'server-child');
    const serverEntry = await entry(page);
    assert(serverEntry.args.handler[4].includes('jvm-server') && !serverEntry.args.handler[4].includes('browser-main'),
      'neighboring descendant retains JVM requirement');
    await openPicker(page, 'server-child');
    await candidate(page, 'server-callback', true);
    await (await candidate(page, 'pure-callback', true)).click();
    await page.locator('.fn-picker-popover').waitFor({state: 'hidden', timeout: 30000});
    await goto(page, 'wrapper');
    const root = await entry(page);
    assert(root.args.handlers[2][4][0] === 'execution-var', 'shared root retains generic execution variable');
    assert(root.args.handler[4][0] === 'execution-var', 'shared callback slot remains generic');
    assert(errors.length === 0, 'execution editing produces no browser exceptions');
    console.log('✓ execution contracts: rename, local map pin, sibling isolation and callback filtering');
  } catch (error) {
    const snapshot = await page.evaluate(() => {
      const row = lookups.fnMap.get(selectedFnId);
      const args = [...document.querySelectorAll('.edge-label-overlay')].map(overlay => {
        const edge = graph.edges.get(overlay.dataset.edgeId);
        const data = typeof edge?.data === 'function' ? edge.data() : edge?.data;
        return data && argRowFromNode(data);
      }).filter(Boolean);
      return {url: location.href, selected: {id: row?.id, name: row?.name},
        rich: richTypeEntryOf(row),
        args: args.map(arg => ({name: arg.name, slot: lookups.slotMap.get(arg['slot-id']),
          binding: arg['binding-id'], expected: expectedSlotType(arg)})),
        chips: [...document.querySelectorAll('.edge-label-overlay .arg-type-chip')].map(chip => ({
          arg: chip.closest('.edge-label-overlay')?.dataset.argName, className: chip.className,
          title: chip.title, expanded: chip.getAttribute('aria-expanded'), text: chip.textContent})),
        panels: [...document.querySelectorAll('.type-inline-host')].map(panel => ({display: panel.style.display,
          text: panel.textContent.slice(0, 800)})),
        picker: [...document.querySelectorAll('.fn-picker-row')].map(row => ({className: row.className, name: row.dataset.fnName})),
        pickerExpected: document.querySelector('.fn-picker-expected')?.textContent,
        popovers: [...document.querySelectorAll('.arg-value-edit-popover')].map(panel => panel.getAttribute('aria-label'))};
    }).catch(() => ({snapshotUnavailable: true}));
    console.log('execution finite DOM/type snapshot', JSON.stringify(snapshot));
    await page.screenshot({path: '/tmp/graphden-a3-execution-panel-failure.png', fullPage: true}).catch(() => {});
    throw error;
  } finally {
    releaseTypeRefresh?.();
    try { if (prepared) await cleanupGraphFixture(page, prepared, baseline); }
    finally { await browser.close(); }
  }
})().catch(error => {console.error(error); process.exitCode = 1;});
