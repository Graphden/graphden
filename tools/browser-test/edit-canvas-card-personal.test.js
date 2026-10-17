// Actual persisted personal plans for both fn-card concerns, exact receipt
// cleanup, retained native controls and measured desktop/coarse geometry.
const {chromium} = require('playwright');
const {assert, newContext, api, BASE, waitForServerHealthy, getEntities, synthArgs} = require('./edit-test-helpers');
const {undoConfiguration} = require('./configuration-undo-test-helpers');
const {unexpectedSelectiveCopyErrors} = require('./selective-copy-errors');
(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  const copies = [], activations = [], probes = [], errors = [], plans = [], pending = [], sourceRequests = [];
  const requestReceipts = new WeakMap();
  let phase = 'boot';
  await page.addInitScript(() => {
    window.canvasProofLifecycle = [];
    for (const type of ['gd-auth-changed', 'gd-graph-changed', 'focus', 'visibilitychange'])
      window.addEventListener(type, () => canvasProofLifecycle.push(type));
    document.addEventListener('DOMContentLoaded', () => {
      if (typeof gdPrefOnChange === 'function') gdPrefOnChange(key => {
        if (key === 'components') canvasProofLifecycle.push('components-preference');
      });
    }, {once: true});
  });
  page.on('pageerror', error => errors.push({kind: 'pageerror', text: error.message}));
  page.on('console', message => { if (message.type() === 'error') errors.push({kind: 'console', text: message.text(), url: message.location().url}); });
  page.on('request', request => {
    if (!request.url().endsWith('/ui/configuration/plan')) return;
    const receipt = {sequence: sourceRequests.length + 1, phase, component: request.postDataJSON()?.component, status: 'pending'};
    requestReceipts.set(request, receipt); sourceRequests.push(receipt);
  });
  page.on('requestfailed', request => {
    const receipt = requestReceipts.get(request);
    if (receipt) { receipt.status = 'failed'; receipt.failure = request.failure()?.errorText; }
  });
  page.on('response', response => {
    if (!response.url().endsWith('/ui/configuration/plan')) return;
    pending.push((async () => {
      const receipt = requestReceipts.get(response.request());
      if (receipt) { receipt.status = response.status(); receipt.type = response.headers()['content-type']; }
      const body = await response.json().catch(() => null);
      plans.push({kind: 'plan', url: response.url(), sequence: receipt.sequence,
        component: response.request().postDataJSON()?.component, status: response.status(),
        ok: body?.ok, fallback: body?.fallback, code: body?.code, selectionId: body?.['selection-id'], retryable: body?.retryable === true});
    })());
  });
  async function editLiteral(copy, name, slot, value, position) {
    const fn = copy.manifest.functions.find(row => row.name === name);
    assert(fn, 'exact copied helper ' + name);
    const entities = await getEntities(page, fn.id);
    const arg = synthArgs(entities).find(row => row['fn-id'] === fn.id && row.name === slot);
    assert(arg?.['binding-id'], 'own literal binding ' + slot);
    const item = position === undefined ? null : entities['list-items'].find(row => row['binding-id'] === arg['binding-id'] && row.position === position);
    assert(position === undefined || item, 'exact literal list item');
    const result = await api(page, 'PUT', '/api/entities/' + (item ? 'binding-list-item/' + item.id : 'binding/' + arg['binding-id']),
      'value=' + encodeURIComponent(JSON.stringify(value)));
    assert(result.ok || result.status === 200, 'ordinary source edit saved');
  }
  async function select(id) {
    await page.evaluate(async id => { await ensureSubtreeFor(id); await selectFn(id); }, id);
    await page.waitForFunction(id => selectedFnId === id && graphReady() && !graph.animating
      && document.querySelector('.node-overlay[data-original-fn-id="' + id + '"]'), id, {timeout: 60000});
    await page.mouse.move(5, 5);
  }
  async function activate(copy) {
    await page.evaluate(() => gdCaptureUIConfiguration());
    const state = await page.evaluate(() => gdUIConfigurationState());
    const preview = await api(page, 'POST', '/api/ui/configuration/preview', {operation: 'activate', command: {
      'selection-id': copy.manifest.roots['configuration-id'], 'expected-preference': state.preference,
      'expected-configuration-revision': state.revision,
    }});
    assert(preview.ok, 'persisted configuration preview');
    const applied = await api(page, 'POST', '/api/ui/configuration/apply', preview.request);
    assert(applied.ok && applied.committed, 'persisted configuration activation');
    activations.push(preview);
    phase = 'activate-' + copy.manifest.component;
    await page.evaluate(() => gdPrefsRefresh());
    await page.waitForFunction(({component, id}) => gdUIComponentRuntimeIdentity(component) === id,
      {component: copy.manifest.component, id: preview.receipt.id}, {timeout: 60000});
    return preview;
  }
  async function createProbe(copy, suffix, parents, extra = '') {
    const namespace = copy.manifest.namespaces.find(row => copy.manifest.functions.some(fn => fn['namespace-id'] === row.id))
      || copy.manifest.namespaces.at(-1);
    const name = 'canvas-card-' + suffix + '-' + process.pid + '-' + Date.now().toString(36);
    const created = await api(page, 'POST', '/api/entities/fn', 'name=' + name + '&namespace-id=' + namespace.id
      + '&parent-ids=' + parents.join(',') + extra);
    assert(created.ok || created.id || created.status === 200 || created.status === 201, 'owned presentation probe created');
    const fn = (await getEntities(page, name)).fns.find(row => row.name === name);
    assert(fn, 'exact created presentation probe'); probes.push(fn.id); return fn;
  }
  try {
    await page.goto(BASE + '/#web-server');
    await page.waitForFunction(() => gdPrefsReady && gdPrefOwner && gdUIConfigurationState?.()?.revision
      && graphReady() && !graph.animating, null, {timeout: 60000});
    assert((await api(page, 'POST', '/api/ui/configuration/prepare', {home: true})).ok, 'personal home prepared');
    const owner = await page.evaluate(() => gdPrefOwner);
    async function createCopy(component) {
      const preview = await api(page, 'POST', '/api/ui/components/create/preview', {owner, 'namespace-id': null, component});
      assert(preview.ok && preview.manifest.component === component, 'exact component preview ' + component);
      const copy = {request: preview.request, manifest: preview.manifest}; copies.push(copy);
      const applied = await api(page, 'POST', '/api/ui/components/create/apply', preview.request);
      assert(applied.ok && applied.committed, 'exact component copy ' + component);
      return copy;
    }
    const inheritance = await createCopy('canvas-inheritance');
    let metadata;
    await editLiteral(inheritance, '_body', 'attrs', {class: 'gd-canvas-inheritance-label gd-canvas-inheritance-personal'});
    await editLiteral(inheritance, '_starts-group-remaining-3', 'else', true);
    await editLiteral(inheritance, '_label-declarations', 'vals', '0 12px', 1);
    const base = await page.evaluate(async () => {
      const result = {};
      for (const name of ['const', 'add', 'env']) result[name] = (await resolveFnByName(name))?.id;
      return result;
    });
    assert(base.const && base.add && base.env, 'native primitive identities resolved');
    const mi = await createProbe(inheritance, 'mi', [base.const, base.add]);
    const groupWitness = inheritance.manifest.functions.find(row => row.name === '_starts-group-remaining-3');
    await select(groupWitness.id);
    await page.evaluate(id => {
      const card = document.querySelector('.node-overlay[data-original-fn-id="' + id + '"]');
      const run = card.querySelector('.fn-run-trigger');
      if (!run) throw new Error('native Run target missing');
      run.focus(); window.cardFocusProof = {card, run,
        group: card.querySelector('.ancestor-line[data-level="1"]').dataset.groupId};
    }, groupWitness.id);
    phase = 'personal-inheritance';
    const inheritedActivation = await activate(inheritance);
    assert(await page.evaluate(() => cardFocusProof.card.isConnected && document.activeElement === cardFocusProof.run
      && cardFocusProof.card.querySelector('.fn-run-trigger') === cardFocusProof.run
      && cardFocusProof.card.querySelector('.ancestor-line[data-level="1"]').dataset.groupId !== cardFocusProof.group),
      'actual personal grouping updates retained native row/control without losing focus');
    await select(mi.id);
    await page.waitForSelector('.node-overlay .gd-canvas-inheritance-personal', {timeout: 60000});
    assert(await page.evaluate(id => {
      const card = document.querySelector('.node-overlay[data-original-fn-id="' + id + '"]');
      const cells = [...card.querySelectorAll('.ancestor-line[data-level="1"] > span')];
      return cells.length === 2 && cells.every(cell => cell.querySelector('.gd-canvas-inheritance-personal')
        && cell.querySelector('button.more-actions-trigger'));
    }, mi.id), 'stored authorized personal plan presents both native MI cell owners');
    metadata = await createCopy('canvas-metadata');
    for (const [name, cls] of [['_return-normal', 'gd-canvas-metadata-return-text'],
      ['_return-refine', 'gd-canvas-metadata-refine'], ['_lambda-body', 'gd-canvas-metadata-lambda']]) {
      await editLiteral(metadata, name, 'attrs', {class: cls + ' gd-canvas-metadata-personal'});
    }
    await editLiteral(metadata, '_effect-class', 'parts', 'gd-canvas-metadata-personal gd-canvas-metadata-effect gd-canvas-metadata-effect-', 0);
    await editLiteral(metadata, '_style-effect', 'vals', '0 12px', 1);
    const effect = await createProbe(metadata, 'effect', [base.env], '&expects-effects=' + encodeURIComponent('["network"]'));
    phase = 'personal-metadata';
    // API fixture setup changes the registry outside the page's type shell.
    // Hydrate the real authorized shell before retaining its native controls.
    await page.reload();
    await page.waitForFunction(() => gdPrefsReady && graphReady() && !graph.animating, null, {timeout: 60000});
    await select(effect.id);
    await page.waitForSelector('.node-overlay[data-original-fn-id="' + effect.id + '"] .effects-chip-env', {timeout: 60000});
    await page.evaluate(id => {
      const card = document.querySelector('.node-overlay[data-original-fn-id="' + id + '"]');
      const lambda = card.querySelector('.lambda-params-chip'); lambda.focus();
      window.metadataFocusProof = {card, lambda, effect: card.querySelector('.effects-chip-env')};
    }, effect.id);
    const metadataActivation = await activate(metadata);
    assert(await page.evaluate(expected => gdUIComponentsRoots()['canvas-inheritance-id'] === expected,
      inheritance.manifest.roots['canvas-inheritance-id']), 'second component retains the first authored component identity');
    await page.waitForFunction(() => metadataFocusProof.card.querySelector('.gd-canvas-metadata-personal')
      && metadataFocusProof.effect.querySelector('.gd-canvas-metadata-drift'), null, {timeout: 60000});
    assert(await page.evaluate(() => metadataFocusProof.card.isConnected && document.activeElement === metadataFocusProof.lambda
      && metadataFocusProof.card.querySelector('.lambda-params-chip') === metadataFocusProof.lambda
      && metadataFocusProof.card.querySelector('.effects-chip-env') === metadataFocusProof.effect),
      'actual personal metadata retains exact native lambda/effect controls and focus');
    console.log('bounded activation receipts', JSON.stringify({
      requests: sourceRequests.filter(row => row.phase === 'activate-canvas-metadata'),
      identities: await page.evaluate(() => ({
        inheritance: gdUIComponentRuntimeIdentity('canvas-inheritance'),
        metadata: gdUIComponentRuntimeIdentity('canvas-metadata'),
        selectedInheritance: gdUIComponentsRoots()['canvas-inheritance-id'],
        selectedMetadata: gdUIComponentsRoots()['canvas-metadata-id'], lifecycle: canvasProofLifecycle,
      })),
    }));
    for (const dark of [false, true]) {
      await page.evaluate(dark => applyTheme(dark), dark);
      const geometry = await page.evaluate(async () => {
        for (let i = 0; i < 3; i++) await new Promise(requestAnimationFrame);
        const card = metadataFocusProof.card;
        const dims = () => JSON.stringify([...graph.nodes.values()].map(node => [node.id, node.x, node.y, nodeWidth(node), nodeHeight(node)]));
        const stable = dims(), zoom = viewport.zoom;
        let policyCalls = 0;
        const policyMethods = ['groups', 'paintBatch'];
        const originals = policyMethods.map(name => gdCanvasInheritanceGraph[name]);
        policyMethods.forEach((name, index) => { gdCanvasInheritanceGraph[name] = (...args) => {
          policyCalls++; return originals[index](...args);
        }; });
        const layout = () => new Map([...graph.nodes].map(([id, node]) => [id,
          {x: node.x, y: node.y, width: nodeWidth(node), height: nodeHeight(node)}]));
        const converged = !reflowFromMeasuredHeights(layout()) && stable === dims();
        viewport.zoom = zoom * 0.85; applyViewportTransform(); updateOverlayPositions();
        for (let i = 0; i < 2; i++) await new Promise(requestAnimationFrame);
        const zoomIndependent = stable === dims();
        viewport.zoom = zoom; applyViewportTransform(); updateOverlayPositions();
        policyMethods.forEach((name, index) => { gdCanvasInheritanceGraph[name] = originals[index]; });
        const controls = [...card.querySelectorAll('button')].filter(button => button.getBoundingClientRect().width > 0);
        const hit = controls.every(button => {
          const rect = button.getBoundingClientRect();
          const target = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
          return target === button || button.contains(target);
        });
        const passive = [...card.querySelectorAll('.gd-canvas-body-host')];
        const accessible = controls.every(button => !!(button.getAttribute('aria-label') || button.textContent || button.title).trim())
          && passive.every(host => !host.querySelector('button, input, a, [tabindex]'));
        return {converged, zoomIndependent, policyCalls, hit, accessible,
          fits: card.scrollWidth <= card.clientWidth + 2, coarse: matchMedia('(pointer: coarse)').matches,
          metadata: card.querySelectorAll('.gd-canvas-metadata-personal').length};
      });
      console.log('bounded card geometry', JSON.stringify({dark, ...geometry}));
      assert(geometry.converged && geometry.zoomIndependent && geometry.policyCalls === 0
        && geometry.hit && geometry.accessible && geometry.fits,
        'native geometry/focus/a11y remain valid in ' + (dark ? 'dark' : 'light') + ' theme');
      if (process.env.GRAPHDEN_CANVAS_SCREENSHOT)
        await page.screenshot({path: process.env.GRAPHDEN_CANVAS_SCREENSHOT + (dark ? '.dark.png' : '.light.png')});
    }
    // Exercise the actual retained effect anchor, then restore preferences.
    await page.locator('.node-overlay[data-original-fn-id="' + effect.id + '"] .effects-chip-env').click();
    await page.locator('.type-explainer.visible').waitFor({state: 'visible', timeout: 60000});
    await page.keyboard.press('Escape');
    phase = 'restore';
    for (const activation of [...activations].reverse()) {
      const undone = await undoConfiguration(page, activation); assert(undone.ok, 'exact configuration restored');
      activations.pop();
    }
    await page.evaluate(() => gdPrefsRefresh());
    await page.waitForFunction(() => !document.querySelector('.gd-canvas-inheritance-personal, .gd-canvas-metadata-personal'), null, {timeout: 60000});
    await Promise.all(pending);
    for (const [component, activation] of [['canvas-inheritance', inheritedActivation], ['canvas-metadata', metadataActivation]]) {
      assert(plans.some(row => row.component === component && row.status === 200 && row.ok
        && !row.fallback && row.selectionId === activation.receipt.id), 'actual authorized stored plan exported for ' + component);
    }
    assert(unexpectedSelectiveCopyErrors(errors, plans, true).length === 0, 'no unexpected browser/source errors');
    console.log('PASS persisted canvas inheritance and metadata personal plans, native controls, geometry and restore');
  } catch (error) {
    console.log('bounded card proof failure', JSON.stringify({phase, errors: errors.length,
      requests: sourceRequests,
      plans: plans.map(row => ({component: row.component, status: row.status, code: row.code}))}));
    console.log('bounded runtime diagnostics', JSON.stringify(await page.evaluate(() => ({
      ready: gdPrefsReady, status: gdUIComponentsStatus(),
      inheritance: gdUIComponentRuntimeIdentity('canvas-inheritance'),
      metadata: gdUIComponentRuntimeIdentity('canvas-metadata'),
      selectedInheritance: gdUIComponentsRoots()['canvas-inheritance-id'],
      selectedMetadata: gdUIComponentsRoots()['canvas-metadata-id'],
      lifecycle: canvasProofLifecycle,
      metadataBodies: document.querySelectorAll('.gd-canvas-metadata-personal').length,
    })).catch(() => null)));
    if (process.env.GRAPHDEN_CANVAS_SCREENSHOT)
      await page.screenshot({path: process.env.GRAPHDEN_CANVAS_SCREENSHOT + '.failure.png'});
    throw error;
  } finally {
    try {
      for (const activation of [...activations].reverse()) assert((await undoConfiguration(page, activation)).ok, 'cleanup restores exact prior configuration');
      await page.goto('about:blank');
      for (const id of [...probes].reverse()) {
        const result = await api(page, 'DELETE', '/api/entities/fn/' + id);
        assert(result.ok || result.status === 200 || result.status === 404, 'only exact presentation probe deleted');
      }
      for (const copy of [...copies].reverse()) {
        const headers = {'X-Graphden-Branch': copy.request['branch-id']};
        const result = await api(page, 'POST', '/api/functions/delete-batch', {functions: copy.manifest.functions}, headers);
        const expected = new Set(copy.manifest.functions.map(row => row.id));
        const actual = [...(result.deleted || []), ...(result['already-absent'] || [])];
        assert(actual.length === expected.size && new Set(actual).size === expected.size && actual.every(id => expected.has(id)), 'only exact created component cohort cleaned');
        for (const namespace of [...copy.manifest.namespaces].reverse()) {
          const result = await api(page, 'DELETE', '/api/entities/ns/' + namespace.id, undefined, headers);
          assert(result.ok || result.status === 200 || result.status === 404, 'only exact receipt namespace cleaned');
        }
      }
    } finally { await browser.close(); }
  }
})().catch(error => { console.error(error.name + ': ' + error.message); process.exitCode = 1; });
