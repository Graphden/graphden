// Persisted canvas-values: real authorization, scoped customization and measured geometry.
// Exact receipt cleanup leaves existing personal configuration and graphs intact.
const { chromium } = require('playwright');
const {
  assert,
  newContext,
  api,
  BASE,
  waitForServerHealthy,
  getEntities,
  synthArgs,
} = require('./edit-test-helpers');
const { unexpectedSelectiveCopyErrors } = require('./selective-copy-errors');
const { undoConfiguration } = require('./configuration-undo-test-helpers');
(async () => {
  await waitForServerHealthy();
  const { browser, page } = await newContext(chromium, { boot: false });
  const errors = [],
    plans = [],
    pending = [];
  let sequence = 0;
  let receipt, activation;
  page.on('pageerror', (e) => errors.push({ kind: 'pageerror', text: e.message }));
  page.on('console', (m) => {
    if (m.type() === 'error')
      errors.push({ kind: 'console', text: m.text(), url: m.location().url });
  });
  page.on('response', (r) => {
    if (r.url().endsWith('/ui/configuration/plan')) {
      const own = ++sequence;
      pending.push(
        (async () => {
          const b = await r.json().catch(() => null);
          plans.push({
            kind: 'plan',
            url: r.url(),
            sequence: own,
            component: r.request().postDataJSON()?.component,
            status: r.status(),
            ok: b?.ok,
            fallback: b?.fallback,
            code: b?.code,
            retryable: b?.retryable === true,
          });
        })(),
      );
    }
  });
  try {
    await page.goto(BASE + '/#web-server');
    await page.waitForFunction(
      () =>
        window.gdPrefsReady &&
        window.gdPrefOwner &&
        window.gdUIConfigurationState?.()?.revision &&
        graphReady() &&
        !graph.animating,
    );
    assert(
      (await api(page, 'POST', '/api/ui/configuration/prepare', { home: true })).ok,
      'explicit personal home ready',
    );
    const owner = await page.evaluate(() => gdPrefOwner);
    const p = await api(page, 'POST', '/api/ui/components/create/preview', {
      owner,
      'namespace-id': null,
      component: 'canvas-values',
    });
    assert(p.ok && p.manifest.component === 'canvas-values', 'canvas-only create preview');
    receipt = { request: p.request, manifest: p.manifest };
    const created = await api(page, 'POST', '/api/ui/components/create/apply', p.request);
    assert(created.ok && created.committed, 'exact canvas copy committed');
    async function editLiteral(name, slot, value, position) {
      const fn = p.manifest.functions.find(row => row.name === name);
      assert(fn, 'customization belongs to the exact created component');
      const entities = await getEntities(page, fn.id);
      const arg = synthArgs(entities).find(row => row['fn-id'] === fn.id && row.name === slot);
      assert(arg?.['binding-id'], 'customization has its own binding');
      const item = position === undefined ? null : entities['list-items'].find(row =>
        row['binding-id'] === arg['binding-id'] && row.position === position);
      assert(position === undefined || item, 'customization has its exact literal list item');
      const edited = await api(page, 'PUT', '/api/entities/' + (item ? 'binding-list-item/' + item.id : 'binding/' + arg['binding-id']),
        'value=' + encodeURIComponent(JSON.stringify(value)));
      assert(edited.status === 200 || edited.ok, 'ordinary graph customization saved');
    }
    await editLiteral('_canvas-values-label-tree', 'attrs', {class: 'gd-canvas-values-label gd-canvas-values-personal'});
    await editLiteral('_canvas-values-type-primary', 'attrs', {class: 'gd-canvas-values-primary gd-canvas-values-label gd-canvas-values-personal'});
    await editLiteral('_canvas-values-style-label', 'keys', 'padding', 0);
    await editLiteral('_canvas-values-style-label', 'vals', '36px', 0);
    const witness = p.manifest.functions.find(row => row.name === '_canvas-values-rule-label');
    assert(witness, 'two-item sequence witness belongs to the exact receipt');
    await page.evaluate(() => gdCaptureUIConfiguration());
    const state = await page.evaluate(() => gdUIConfigurationState());
    activation = await api(page, 'POST', '/api/ui/configuration/preview', {
      operation: 'activate',
      command: {
        'selection-id': p.manifest.roots['configuration-id'],
        'expected-preference': state.preference,
        'expected-configuration-revision': state.revision,
      },
    });
    assert(activation.ok, 'ordinary activation preview');
    const applied = await api(page, 'POST', '/api/ui/configuration/apply', activation.request);
    assert(applied.ok && applied.committed, 'ordinary selection persisted');
    await page.goto(BASE + '/#' + witness.id);
    await page.waitForFunction(
      ({id, component}) => gdUIComponentRuntimeIdentity('canvas-values') === id
        && gdUIComponentsRoots()['canvas-values-id'] === component && graphReady() && !graph.animating
        && document.querySelector('.arg-value-text .gd-canvas-values-personal')
        && document.querySelector('.arg-type-chip .gd-canvas-values-personal')
        && document.querySelectorAll('.arg-seq-btn-remove').length >= 2,
      {id: activation.receipt.id, component: p.manifest.roots['canvas-values-id']}, {timeout: 60000});
    await Promise.all(pending);
    assert(plans.some(row => row.component === 'canvas-values' && row.status === 200 && row.ok && !row.fallback),
      'authorized persisted plan customizes actual value and type surfaces');
    const geometry = await page.evaluate(async () => {
      const frames = async () => { for (let i = 0; i < 3; i++) await new Promise(requestAnimationFrame); };
      const card = document.querySelector('.arg-value-text .gd-canvas-values-personal').closest('.node-overlay');
      const native = card.querySelector('.arg-value-text');
      native.focus();
      const id = card.dataset.nodeId;
      const wide = gv.node(id).width();
      const dimensions = () => JSON.stringify([...graph.nodes.values()].map(node =>
        [node.id, node.x, node.y, nodeWidth(node), nodeHeight(node)]));
      const layout = () => new Map([...graph.nodes].map(([key, node]) => [key,
        {x: node.x, y: node.y, width: nodeWidth(node), height: nodeHeight(node)}]));
      await frames();
      const stable = dimensions();
      const converged = !reflowFromMeasuredHeights(layout()) && stable === dimensions();
      const fit = [...document.querySelectorAll('.node-overlay')].every(overlay =>
        overlay.scrollWidth <= overlay.clientWidth + 2);
      const seq = [...document.querySelectorAll('.edge-label-seq')].every(label => {
        const trunk = label.getBoundingClientRect();
        const members = [...graph.edges.values()].filter(edge => edge.data.seqGroup === label.dataset.seqGroup);
        return members.length >= 3 && members.every(edge => {
          const target = getNodeOverlay(edge.targetId);
          const item = _edgeOverlaysByEdgeId.get(edge.id);
          if (!target || !item) return true;
          const controls = item.getBoundingClientRect();
          return controls.left >= trunk.right + SEQ_FAN_GAP * viewport.zoom - 1
            && controls.right <= target.getBoundingClientRect().left - 5 * viewport.zoom;
        });
      });
      const beforeZoom = dimensions();
      setViewportZoom(viewport.zoom * 0.8);
      await frames();
      const zoomIndependent = beforeZoom === dimensions();
      // Refresh selects the real persisted runtime and retains native identity.
      await gdCanvasValuesGraph.reload();
      await frames();
      const retained = getNodeOverlay(id) === card && card.querySelector('.arg-value-text') === native
        && document.activeElement === native && !!native.getAttribute('aria-label');
      window.valuesLiveProof = {card, native, id, wide};
      return {converged, fit, seq, zoomIndependent, retained, wide};
    });
    assert(geometry.converged && geometry.fit && geometry.seq && geometry.zoomIndependent && geometry.retained,
      'actual intrinsic cards and sequence controls fit, converge and retain native focus across reload/zoom');
    const anchor = await page.evaluateHandle(() => valuesLiveProof.native);
    await anchor.asElement().click();
    await page.locator('.arg-value-edit-popover').waitFor({state: 'visible', timeout: 60000});
    await page.keyboard.press('Escape');
    assert(await page.evaluate(() => valuesLiveProof.native.isConnected), 'native value action retains its original anchor');
    const handle = await page.evaluateHandle(() => valuesLiveProof.card.querySelector('.drag-handle'));
    const dragBox = await handle.asElement().boundingBox();
    assert(dragBox, 'native drag handle is measured');
    await page.mouse.move(dragBox.x + dragBox.width / 2, dragBox.y + dragBox.height / 2);
    await page.mouse.down();
    await page.mouse.move(dragBox.x + dragBox.width / 2 + 24, dragBox.y + dragBox.height / 2 + 18, {steps: 5});
    await page.mouse.up();
    const dragged = await page.evaluate(() => {
      const node = graph.nodes.get(valuesLiveProof.id);
      valuesLiveProof.dragged = {x: node.x, y: node.y};
      return userMovedNodes.has(valuesLiveProof.id);
    });
    assert(dragged, 'real pointer drag marks the native node as user positioned');
    if (process.env.GRAPHDEN_CANVAS_SCREENSHOT)
      await page.screenshot({path: process.env.GRAPHDEN_CANVAS_SCREENSHOT, fullPage: false});
    const undone = await undoConfiguration(page, activation);
    assert(undone.ok, 'original preference restored before measuring builtin shrink');
    activation = null;
    await page.evaluate(() => gdPrefsRefresh());
    await page.waitForFunction(() => !document.querySelector('.gd-canvas-values-personal'));
    await page.waitForFunction(() => gv.node(valuesLiveProof.id)?.width() < valuesLiveProof.wide);
    assert(await page.evaluate(() => valuesLiveProof.card.isConnected
      && valuesLiveProof.card.querySelector('.arg-value-text') === valuesLiveProof.native
      && graph.nodes.get(valuesLiveProof.id).x === valuesLiveProof.dragged.x
      && graph.nodes.get(valuesLiveProof.id).y === valuesLiveProof.dragged.y),
      'custom to builtin shrinks the same measured native card');
    if (process.env.GRAPHDEN_CANVAS_SCREENSHOT)
      await page.screenshot({ path: process.env.GRAPHDEN_CANVAS_SCREENSHOT, fullPage: false });
    await Promise.all(pending);
    const unexpected = unexpectedSelectiveCopyErrors(errors, plans, true);
    if (unexpected.length)
      console.log(
        'unexpected error counts',
        JSON.stringify({
          count: unexpected.length,
          plans: plans.map(({ component, status, code }) => ({ component, status, code })),
        }),
      );
    assert(unexpected.length === 0, 'no runtime errors or unclassified console errors');
    console.log('PASS persisted canvas-values two surfaces/native ownership/measured geometry');
  } finally {
    try {
      if (activation?.ok) {
        const undone = await undoConfiguration(page, activation);
        assert(undone.ok, 'original configuration restored from exact receipt');
      }
      if (receipt) {
        const headers = { 'X-Graphden-Branch': receipt.request['branch-id'] };
        const r = await api(
          page,
          'POST',
          '/api/functions/delete-batch',
          { functions: receipt.manifest.functions },
          headers,
        );
        const ack = [...(r.deleted || []), ...(r['already-absent'] || [])],
          expected = new Set(receipt.manifest.functions.map((x) => x.id));
        assert(
          ack.length === expected.size &&
            new Set(ack).size === expected.size &&
            ack.every((id) => expected.has(id)),
          'only exact created canvas-values functions cleaned',
        );
        for (const ns of [...receipt.manifest.namespaces].reverse()) {
          const r = await api(page, 'DELETE', '/api/entities/ns/' + ns.id, undefined, headers);
          assert(r.ok || r.status === 200 || r.status === 404, 'only receipt namespace cleaned');
        }
      }
    } finally {
      await browser.close();
    }
  }
})().catch((e) => {
  console.error(e.name + ': ' + e.message);
  process.exitCode = 1;
});
