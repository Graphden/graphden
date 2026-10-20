// Stored ordinary graphs drive both new concerns. Keep exact configuration
// receipts, original native owners, and bounded source-generation evidence.
const {chromium} = require('playwright');
const {assert, newContext, api, nodeApi, BASE, waitForServerHealthy, getEntities, synthArgs} = require('./edit-test-helpers');
const {undoConfiguration} = require('./configuration-undo-test-helpers');
const {savePresentationAudit} = require('./canvas-presentation-audit');
const {unexpectedSelectiveCopyErrors} = require('./selective-copy-errors');
const {withoutRecoveredRootErrors} = require('./root-server-call-test-helpers');
const {captureLoadedUIComponents, waitForLoadedUIComponents, drainUIPlanReplies} = require('./ui-component-runtime-test-helpers');
(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const copies = [], activations = [], errors = [], plans = [], pending = [], requests = [], rootPlans = [], rootPending = [];
  const receipts = new WeakMap(), rootRequests = new WeakMap();
  let rootSequence = 0, forcedRootPolicy = false;
  let phase = 'boot';
  let completed = false;
  let original;
  page.on('pageerror', error => errors.push({kind: 'pageerror', text: error.message}));
  page.on('console', message => { if (message.type() === 'error') errors.push({kind: 'console', text: message.text(), url: message.location().url}); });
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/api/ui/root/plan') rootRequests.set(request, ++rootSequence);
    if (!request.url().endsWith('/ui/configuration/plan')) return;
    const row = {sequence: requests.length + 1, phase, component: request.postDataJSON()?.component, status: 'pending'};
    requests.push(row); receipts.set(request, row);
  });
  page.on('requestfailed', request => {
    const row = receipts.get(request);
    if (row) { row.status = 'failed'; row.failure = request.failure()?.errorText; }
  });
  page.on('response', response => {
    if (new URL(response.url()).pathname === '/api/ui/root/plan') {
      rootPending.push((async () => {
        const body = await response.json().catch(() => null);
        rootPlans.push({sequence: rootRequests.get(response.request()), status: response.status(),
          ok: body?.ok === true, code: body?.code, retryable: body?.retryable === true});
      })());
    }
    if (!response.url().endsWith('/ui/configuration/plan')) return;
    pending.push((async () => {
      const row = receipts.get(response.request());
      row.status = response.status(); row.type = response.headers()['content-type'];
      const body = await response.json().catch(() => null);
      plans.push({kind: 'plan', url: response.url(), sequence: row.sequence, component: row.component,
        status: row.status, ok: body?.ok, fallback: body?.fallback, code: body?.code,
        selectionId: body?.['selection-id'], retryable: body?.retryable === true});
    })());
  });
  async function settle(snapshot) {
    await waitForLoadedUIComponents(page, snapshot);
    await drainUIPlanReplies(requests, plans, pending);
  }
  async function editLiteral(copy, name, slot, value, position) {
    const fn = copy.manifest.functions.find(row => row.name === name);
    assert(fn, 'exact owned source helper ' + name);
    const entities = await getEntities(page, fn.id);
    const arg = synthArgs(entities).find(row => row['fn-id'] === fn.id && row.name === slot);
    assert(arg?.['binding-id'], 'exact owned source binding ' + slot);
    const item = position === undefined ? null : entities['list-items'].find(row => row['binding-id'] === arg['binding-id'] && row.position === position);
    assert(position === undefined || item, 'exact literal sequence owner');
    const result = await api(page, 'PUT', '/api/entities/' + (item ? 'binding-list-item/' + item.id : 'binding/' + arg['binding-id']),
      'value=' + encodeURIComponent(JSON.stringify(value)));
    assert(result.ok || result.status === 200, 'ordinary source edit saved');
  }
  async function createCopy(component, owner) {
    const preview = await api(page, 'POST', '/api/ui/components/create/preview', {owner, 'namespace-id': null, component});
    assert(preview.ok && preview.manifest.component === component, 'exact component preview ' + component);
    const copy = {request: preview.request, manifest: preview.manifest}; copies.push(copy);
    const applied = await api(page, 'POST', '/api/ui/components/create/apply', copy.request);
    assert(applied.ok && applied.committed, 'exact component source copied ' + component);
    return copy;
  }
  async function activate(copy) {
    await page.evaluate(() => gdCaptureUIConfiguration());
    const state = await page.evaluate(() => gdUIConfigurationState());
    const preview = await api(page, 'POST', '/api/ui/configuration/preview', {operation: 'activate', command: {
      'selection-id': copy.manifest.roots['configuration-id'], 'expected-preference': state.preference,
      'expected-configuration-revision': state.revision,
    }});
    assert(preview.ok, 'configuration preview is authorized');
    const applied = await api(page, 'POST', '/api/ui/configuration/apply', preview.request);
    assert(applied.ok && applied.committed, 'configuration applied'); activations.push(preview);
    phase = 'activate-' + copy.manifest.component;
    await page.evaluate(() => gdPrefsRefresh());
    await page.waitForFunction(({component, id}) => gdUIComponentRuntimeIdentity(component) === id,
      {component: copy.manifest.component, id: preview.receipt.id}, {timeout: 60000});
    return preview;
  }
  try {
    await page.route(url => url.pathname === '/api/ui/root/plan', async route => {
      if (phase === 'hidden-personal-bindings' && !forcedRootPolicy) {
        forcedRootPolicy = true;
        await route.fulfill({status: 422, contentType: 'application/json',
          body: JSON.stringify({ok: false, code: 'policy-refresh-required', retryable: true})});
      } else await route.continue();
    });
    await page.goto(BASE + '/#web-server');
    await page.waitForFunction(() => gdPrefsReady && gdPrefOwner && gdUIConfigurationState?.()?.revision
      && graphReady() && !graph.animating, null, {timeout: 60000});
    original = await captureLoadedUIComponents(page);
    await settle(original);
    assert((await api(page, 'POST', '/api/ui/configuration/prepare', {home: true})).ok, 'personal home prepared');
    const owner = await page.evaluate(() => gdPrefOwner);
    const types = await createCopy('canvas-type-structure', owner);
    await editLiteral(types, '_view-class', 'parts', 'gd-canvas-type-structure-personal gd-canvas-type-structure-', 0);
    await editLiteral(types, '_primitive-human-table', 'vals', 'personal text', 2);
    const witness = types.manifest.functions.find(row => row.name === '_kind');
    assert(witness, 'typed source witness retained');
    await page.evaluate(async id => { await ensureSubtreeFor(id); await selectFn(id); }, witness.id);
    await page.waitForFunction(id => selectedFnId === id && graphReady() && !graph.animating
      && document.querySelector('.node-overlay[data-original-fn-id="' + id + '"]'), witness.id, {timeout: 60000});
    await page.waitForSelector('.arg-type-chip[data-inline-anchor]', {timeout: 60000});
    await page.locator('.arg-type-chip[data-inline-anchor]').first().click();
    await page.waitForSelector('.type-inline-host .type-inline-chip');
    await page.evaluate(() => {
      const host = [...document.querySelectorAll('.type-inline-host')].find(el => el.style.display !== 'none');
      const chip = host.querySelector('.type-inline-chip[role="button"]');
      if (!chip) throw Error('Focusable native type expansion missing');
      chip.focus();
      if (document.activeElement !== chip) throw Error('Native type focus was not established before activation');
      window.structureProof = {host, chip, canonical: compactTypeAsValue(['list', 'text'])};
    });
    const typeActivation = await activate(types);
    await page.waitForFunction(() => structureProof.host.querySelector('.gd-canvas-type-structure-personal')
      && formatTypeHumanReadable('text') === 'personal text', null, {timeout: 60000});
    assert(await page.evaluate(() => structureProof.host.isConnected && structureProof.chip.isConnected
      && document.activeElement === structureProof.chip
      && compactTypeAsValue(['list', 'text']) === structureProof.canonical),
    'persisted initial/view update retained type owners and canonical grammar');
    await settle({...original, configuration: typeActivation.receipt.id});
    if (process.env.GRAPHDEN_CANVAS_SCREENSHOT)
      await page.screenshot({path: process.env.GRAPHDEN_CANVAS_SCREENSHOT + '.types.png'});
    if (process.env.GRAPHDEN_CANVAS_A11Y_SNAPSHOT)
      await savePresentationAudit(page, '.type-inline-host', process.env.GRAPHDEN_CANVAS_A11Y_SNAPSHOT + '.types.html', 'Expanded type presentation');
    const bindings = await createCopy('canvas-bindings', owner);
    await editLiteral(bindings, '_class', 'parts', 'gd-canvas-bindings-personal gd-canvas-bindings-', 0);
    await page.evaluate(() => hideAllInlineHosts());
    await page.waitForSelector('.seal-badge', {timeout: 60000});
    await page.locator('.seal-badge').first().click();
    await page.waitForSelector('.seal-popover input[type="checkbox"]');
    await page.evaluate(() => {
      const box = document.querySelector('.seal-popover input[type="checkbox"]'); box.focus();
      window.bindingProof = {box, checked: box.checked, disabled: box.disabled,
        label: box.getAttribute('aria-label'), dialog: box.closest('[role="dialog"]')};
    });
    const selected = await captureLoadedUIComponents(page);
    await settle(selected);
    const bindingActivation = await activate(bindings);
    await page.waitForFunction(() => bindingProof.dialog.querySelector('.gd-canvas-bindings-personal'), null, {timeout: 60000});
    assert(await page.evaluate(expected => gdUIComponentsRoots()['canvas-type-structure-id'] === expected,
      types.manifest.roots['canvas-type-structure-id']), 'second selection retains the first ordinary component');
    assert(await page.evaluate(() => bindingProof.box.isConnected && document.activeElement === bindingProof.box
      && bindingProof.box.checked === bindingProof.checked && bindingProof.box.disabled === bindingProof.disabled
      && bindingProof.box.getAttribute('aria-label') === bindingProof.label),
    'stored binding view retains exact native checkbox, state and accessible name');
    await settle({...selected, configuration: bindingActivation.receipt.id});
    for (const dark of [false, true]) {
      await page.evaluate(dark => applyTheme(dark), dark);
      const geometry = await page.evaluate(async () => {
        await new Promise(requestAnimationFrame); await new Promise(requestAnimationFrame);
        const dimensions = () => JSON.stringify([...graph.nodes.values()].map(node => [node.id, node.x, node.y, nodeWidth(node), nodeHeight(node)]));
        const before = dimensions(), zoom = viewport.zoom;
        const originalFormat = gdCanvasTypeStructureGraph.format;
        let formatCalls = 0;
        gdCanvasTypeStructureGraph.format = (...args) => { formatCalls++; return originalFormat(...args); };
        viewport.zoom = zoom * .85; applyViewportTransform(); updateOverlayPositions();
        await new Promise(requestAnimationFrame); await new Promise(requestAnimationFrame);
        viewport.zoom = zoom; applyViewportTransform(); updateOverlayPositions();
        await new Promise(requestAnimationFrame);
        gdCanvasTypeStructureGraph.format = originalFormat;
        const box = bindingProof.box, rect = box.getBoundingClientRect();
        const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
        const dialog = bindingProof.dialog.getBoundingClientRect();
        const save = [...bindingProof.dialog.querySelectorAll('button')].find(el => el.textContent === 'Save');
        const button = save.getBoundingClientRect(), target = document.elementFromPoint(button.x + button.width / 2, button.y + button.height / 2);
        return {formatCalls, stable: before === dimensions(), hit: hit === box, save: target === save || save.contains(target),
          bounded: dialog.top >= 7 && dialog.bottom <= innerHeight - 7 && dialog.right <= innerWidth - 7,
          accessible: !!box.getAttribute('aria-label'), coarse: matchMedia('(pointer: coarse)').matches};
      });
      console.log('bounded type/binding geometry', JSON.stringify({dark, ...geometry}));
      if (process.env.GRAPHDEN_CANVAS_A11Y_SNAPSHOT)
        await savePresentationAudit(page, '.arg-value-edit-popover:has(.seal-popover)', process.env.GRAPHDEN_CANVAS_A11Y_SNAPSHOT
          + (dark ? '.bindings-dark.html' : '.bindings-light.html'), 'Seal presentation');
      assert(geometry.formatCalls === 0 && geometry.stable && geometry.hit && geometry.save && geometry.bounded && geometry.accessible, 'native controls work in both themes');
      if (process.env.GRAPHDEN_CANVAS_SCREENSHOT)
        await page.screenshot({path: process.env.GRAPHDEN_CANVAS_SCREENSHOT + (dark ? '.dark.png' : '.light.png')});
    }
    phase = 'hidden-personal-bindings';
    const loaded = await captureLoadedUIComponents(page);
    await settle(loaded);
    await editLiteral(bindings, '_style-seal-checkbox', 'keys', 'display', 0);
    await editLiteral(bindings, '_style-seal-checkbox', 'vals', 'none', 0);
    await page.evaluate(() => gdRefreshUIConfiguration());
    await page.waitForFunction(() => getComputedStyle(bindingProof.dialog.querySelector('.gd-canvas-bindings-seal-checkbox')).display === 'none',
      null, {timeout: 60000});
    await settle(loaded);
    assert(await page.evaluate(() => bindingProof.box.isConnected && document.activeElement === bindingProof.box
      && bindingProof.box.getBoundingClientRect().width > 0 && bindingProof.box.getAttribute('aria-label') === bindingProof.label),
    'actual stored hidden explanation leaves the checkbox intact');
    await page.keyboard.press('Escape');
    phase = 'restore';
    for (const activation of [...activations].reverse()) {
      assert((await undoConfiguration(page, activation)).ok, 'exact prior configuration restored'); activations.pop();
    }
    await page.evaluate(() => gdPrefsRefresh());
    await page.waitForFunction(() => formatTypeHumanReadable('text') === 'text'
      && !document.querySelector('.gd-canvas-type-structure-personal, .gd-canvas-bindings-personal'), null, {timeout: 60000});
    await settle(original);
    for (const [component, activation] of [['canvas-type-structure', typeActivation], ['canvas-bindings', bindingActivation]])
      assert(plans.some(row => row.component === component && row.status === 200 && row.ok && !row.fallback
        && row.selectionId === activation.receipt.id), 'actual authorized stored source exported for ' + component);
    await Promise.all(rootPending);
    assert(forcedRootPolicy, 'root plan retry exercised after the stored source edit');
    assert(rootPlans.some(row => row.status === 422 && row.code === 'policy-refresh-required'),
      'the forced root refusal was observed');
    assert(unexpectedSelectiveCopyErrors(withoutRecoveredRootErrors(errors, rootPlans), plans, true).length === 0,
      'no unexpected source/browser errors');
    completed = true;
    console.log('PASS persisted expanded types/bindings, preview/apply/restore and retained native controls');
  } catch (error) {
    console.log('bounded type/binding failure', JSON.stringify({phase, requests,
      plans: plans.map(row => ({component: row.component, status: row.status, code: row.code})),
      rootPlans: rootPlans.map(row => ({sequence: row.sequence, status: row.status, code: row.code, ok: row.ok})),
      errors: unexpectedSelectiveCopyErrors(errors, plans, true).map(row => ({
        kind: row.kind,
        route: row.url ? new URL(row.url).pathname.replace(/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/gi, ':id') : null,
        status: Number(row.text?.match(/^Failed to load resource: the server responded with a status of (\d+)/)?.[1]) || null,
        message: row.kind === 'pageerror' ? row.text.split('\n')[0].slice(0, 160) : null,
      }))}));
    if (process.env.GRAPHDEN_CANVAS_SCREENSHOT) await page.screenshot({path: process.env.GRAPHDEN_CANVAS_SCREENSHOT + '.failure.png'});
    throw error;
  } finally {
    try {
      for (const activation of [...activations].reverse()) assert((await undoConfiguration(page, activation)).ok, 'cleanup restores configuration');
      await page.goto('about:blank');
      for (const copy of [...copies].reverse()) {
        const headers = {'X-Graphden-Branch': copy.request['branch-id']};
        const result = await api(page, 'POST', '/api/functions/delete-batch', {functions: copy.manifest.functions}, headers);
        const expected = new Set(copy.manifest.functions.map(row => row.id));
        const actual = [...(result.deleted || []), ...(result['already-absent'] || [])];
        assert(actual.length === expected.size && new Set(actual).size === expected.size && actual.every(id => expected.has(id)), 'only receipt-owned component cohort cleaned');
        for (const namespace of [...copy.manifest.namespaces].reverse()) {
          const result = await api(page, 'DELETE', '/api/entities/ns/' + namespace.id, undefined, headers);
          assert(result.ok || result.status === 200 || result.status === 404, 'only receipt namespace cleaned');
        }
      }
      if (completed) {
        async function metadataReceipt(stage) {
          const response = await nodeApi('POST', '/api/ui/configuration/plan', {component: 'canvas-metadata'});
          const metadata = await response.json();
          const targetId = 'a393663f-fdd1-5fa6-b490-b0d9414ffc94';
          const target = await getEntities(page, targetId);
          console.log('post-delete metadata receipt', JSON.stringify({stage, status: response.status,
            ok: metadata.ok === true, code: metadata.code == null ? null : metadata.code === 'policy-refresh-required' ? metadata.code : 'other',
            retryable: metadata.retryable === true,
            reason: metadata.reason == null ? 'absent'
              : metadata.reason === 'Personal UI graph is unavailable. Using built-in components.' ? 'source-unavailable' : 'other',
            targetpresent: target.fns.some(row => row.id === targetId)}));
          return {response, metadata};
        }
        const first = await metadataReceipt('before-policy-settle');
        assert(first.response.status === 200 && first.metadata.ok
          || first.response.status === 422 && first.metadata.code === 'policy-refresh-required' && first.metadata.retryable === true,
        'post-delete source has a successful or known retryable policy receipt');
        phase = 'post-delete-policy-settle';
        await page.goto(BASE + '/#web-server');
        await page.waitForFunction(() => gdPrefsReady && gdPrefOwner && gdUIConfigurationState?.()?.revision
          && graphReady() && !graph.animating, null, {timeout: 60000});
        await page.evaluate(() => gdWaitForGraphPolicy());
        await settle(original);
        const {response, metadata} = await metadataReceipt('after-policy-settle');
        assert(response.status === 200 && metadata.ok && metadata.plan && !metadata.fallback,
          'deleting copied type-only twins preserves the existing metadata source certificate');
      }
    } finally { await browser.close(); }
  }
})().catch(error => { console.error(error.name + ': ' + error.message); process.exitCode = 1; });
