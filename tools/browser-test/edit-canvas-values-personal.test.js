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
  waitFor,
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
  let receipt, activation, witnessId;
  let phase = "boot";
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
            selectionId: b?.['selection-id'],
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
    assert(await page.evaluate(expected => matchMedia('(pointer: coarse)').matches === expected,
      !!process.env.GRAPHDEN_VIEWPORT), 'browser input mode matches the requested desktop/coarse pass');
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
      if (edited['type-warnings']?.length) console.log('customization warning count', edited['type-warnings'].length);
      return arg;
    }
    await editLiteral('_canvas-values-label-tree', 'attrs', {class: 'gd-canvas-values-label gd-canvas-values-personal'});
    await editLiteral('_canvas-values-type-primary', 'attrs', {class: 'gd-canvas-values-primary gd-canvas-values-label gd-canvas-values-personal'});
    await editLiteral('_canvas-values-style-label', 'keys', 'padding', 0);
    await editLiteral('_canvas-values-style-label', 'vals', '36px', 0);
    // This copied accessor's default is not used by valid surface records.
    // Give its literal a real native color type, so the backend graph repr and
    // inherited ANY→color chain can be measured without injecting either.
    const colorId = await page.evaluate(async () => (await resolveFnByName('core.refinements/color'))?.id);
    assert(colorId, 'canonical installed color type resolved by qualified name');
    const defaultArg = await editLiteral('_canvas-values-surface', 'default', '#1234');
    const narrowed = await api(page, 'PUT', '/api/entities/binding/' + defaultArg['binding-id'],
      'type-override-fn-id=' + encodeURIComponent(colorId));
    assert(narrowed.ok || narrowed.status === 200, 'copied default has an ordinary color binding override');
    await editLiteral('_canvas-values-style-history-row', 'keys', 'padding', 2);
    await editLiteral('_canvas-values-style-history-row', 'vals', '160px', 2);
    const probe = p.manifest.functions.find(row => row.name === '_canvas-values-surface');
    assert(probe, 'native history and repr probe belongs to the same exact receipt');
    const witness = p.manifest.functions.find(row => row.name === '_canvas-values-rule-label');
    assert(witness, 'two-item sequence witness belongs to the exact receipt');
    witnessId = witness.id;
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
    // API setup deliberately happened outside the page's preference/index
    // state. A hash-only navigation does not reload either of those stores.
    phase = 'persisted-runtime';
    await page.reload();
    await page.waitForFunction(id => gdUIComponentRuntimeIdentity('canvas-values') === id
      && graphReady() && !graph.animating, activation.receipt.id, {timeout: 60000});
    phase = 'native-repr-history';
    await page.evaluate(async id => { await ensureSubtreeFor(id); await selectFn(id); }, probe.id);
    await page.waitForFunction(id => selectedFnId === id && !graph.animating
      && document.querySelector('.arg-literal-preview svg rect[fill="#1234"]'), probe.id);
    await page.locator('.node-overlay[data-fn-name="_canvas-values-surface"] .ancestor-line[data-level="1"]').click();
    await page.mouse.move(5, 5);
    await page.waitForFunction(() => {
      const card = document.querySelector('.node-overlay[data-fn-name="_canvas-values-surface"]');
      return card && !graph.animating && getSpec(card.dataset.nodeId).fullDepth === 1
        && !previewState.has(card.dataset.nodeId)
        && document.querySelector('.edge-label-overlay[data-arg-name="default"] .edge-type-chain');
    });
    const nativePresentation = await page.evaluate(async ({color, bindingId, owner}) => {
      for (let i = 0; i < 3; i++) await new Promise(requestAnimationFrame);
      const label = document.querySelector('.edge-label-overlay[data-arg-name="default"] .edge-type-chain').closest('.edge-label-overlay');
      const edge = gv.edge(label.dataset.edgeId);
      const target = edge.target();
      const chain = edge.data('typeChain');
      const arg = argRowFromNode(target.data());
      const binding = lookups.bindingMap.get(arg?.['binding-id']);
      const presentation = typeChipPresentation(arg);
      const card = getNodeOverlay(target.id());
      const preview = card.querySelector('.arg-literal-preview');
      const body = card.querySelector('.arg-value-text .gd-canvas-body-host');
      const rect = preview?.getBoundingClientRect();
      const historyRect = label.getBoundingClientRect();
      const grid = lastGridPos(), row = grid[target.id()].row;
      const neighbors = [..._overlaysByNodeId].filter(([id, overlay]) =>
        overlay.isConnected && grid[id] && grid[id].row !== row);
      const clearance = neighbors.length > 0 && neighbors.every(([id, overlay]) => {
        const box = overlay.getBoundingClientRect();
        return grid[id].row < row ? box.bottom + GRID_GAP_Y * viewport.zoom <= historyRect.top + 2
          : box.top >= historyRect.bottom + GRID_GAP_Y * viewport.zoom - 2;
      });
      return {
        committed: getSpec(document.querySelector('.node-overlay[data-fn-name="_canvas-values-surface"]').dataset.nodeId).fullDepth === 1
          && !previewState.size,
        // Preserve the server's existing flat chain separately from its rich
        // nominal refinement presentation; this test does not redefine types.
        identity: arg?.['binding-id'] === bindingId && arg?.['fn-id'] === owner
          && binding?.['type-override-fn-id'] === color,
        chain: Array.isArray(chain) && chain.length >= 2
          && chain[0].source === 'binding-override' && chain[0].type === resolveArgType(arg)
          && chain.some(entry => entry.type === 'any' && entry.source === 'slot-declared'),
        nominal: presentation.alias === 'color' && presentation.display === 'color'
          && card.querySelector('.arg-type-chip .gd-canvas-values-primary')?.textContent === presentation.display,
        repr: !!target.data('literalRepr') && !!preview?.querySelector('rect[fill="#1234"]')
          && preview.parentNode === body.parentNode && !GraphdenRenderer.ownsTarget(preview)
          && GraphdenRenderer.ownsTarget(body) && getComputedStyle(preview).overflow === 'hidden'
          && Math.abs(rect.width - 12 * viewport.zoom) < 1 && Math.abs(rect.height - 12 * viewport.zoom) < 1,
        fit: card.scrollWidth <= card.clientWidth + 2,
        tall: label.offsetHeight > Math.max(...Object.keys(grid).filter(id => grid[id].row === row).map(id => gv.node(id)?.height() || 0)),
        clearance, historyHeight: label.offsetHeight, previewWidth: preview?.offsetWidth,
      };
    }, {color: colorId, bindingId: defaultArg['binding-id'], owner: probe.id});
    console.log('bounded native presentation', JSON.stringify(nativePresentation));
    assert(nativePresentation.committed && nativePresentation.identity && nativePresentation.chain
      && nativePresentation.nominal && nativePresentation.repr
      && nativePresentation.fit && nativePresentation.tall && nativePresentation.clearance,
      'real backend history and sanitized repr fit measured rows and retain native sibling ownership');
    phase = 'selected-plan';
    await page.evaluate(async id => { await ensureSubtreeFor(id); await selectFn(id); }, witness.id);
    await page.waitForFunction(
      ({id, component, witness}) => selectedFnId === witness && gdUIComponentRuntimeIdentity('canvas-values') === id
        && gdUIComponentsRoots()['canvas-values-id'] === component && graphReady() && !graph.animating
        && document.querySelector('.arg-value-text .gd-canvas-values-personal')
        && document.querySelector('.arg-type-chip .gd-canvas-values-personal')
        && document.querySelectorAll('.arg-seq-btn-remove').length >= 2,
      {id: activation.receipt.id, component: p.manifest.roots['canvas-values-id'], witness: witness.id}, {timeout: 60000});
    await Promise.all(pending);
    assert(plans.some(row => row.component === 'canvas-values' && row.status === 200 && row.ok && !row.fallback),
      'authorized persisted plan customizes actual value and type surfaces');
    phase = 'geometry';
    const geometry = await page.evaluate(async () => {
      const frames = async () => { for (let i = 0; i < 3; i++) await new Promise(requestAnimationFrame); };
      const card = document.querySelector('.arg-value-text .gd-canvas-values-personal').closest('.node-overlay');
      const native = card.querySelector('.arg-value-text');
      const control = document.querySelector('.arg-seq-btn-up');
      control.focus();
      const id = card.dataset.nodeId;
      const dimensions = () => JSON.stringify([...graph.nodes.values()].map(node =>
        [node.id, node.x, node.y, nodeWidth(node), nodeHeight(node)]));
      const layout = () => new Map([...graph.nodes].map(([key, node]) => [key,
        {x: node.x, y: node.y, width: nodeWidth(node), height: nodeHeight(node)}]));
      await frames();
      const wide = gv.node(id).width();
      const stable = dimensions();
      const converged = !reflowFromMeasuredHeights(layout()) && stable === dimensions();
      if (!converged) return {converged, wide, fit: null, seq: null, zoomIndependent: null, retained: null};
      const cards = [...document.querySelectorAll('.node-overlay')];
      // Native placeholders intentionally keep a 20px footprint and let the
      // larger coarse-input binder paint outside it. Measure actual passive
      // card owners, then check the entire native button separately.
      const fit = cards.filter(overlay => !gv.node(overlay.dataset.nodeId).data('isPlaceholder'))
        .every(overlay => overlay.scrollWidth <= overlay.clientWidth + 2);
      const binders = [...document.querySelectorAll('.placeholder-binder')];
      const surface = document.getElementById('graph-surface').getBoundingClientRect();
      const neighbors = [...cards.filter(overlay => !gv.node(overlay.dataset.nodeId).data('isPlaceholder')),
        ...document.querySelectorAll('.edge-label-overlay'), ...binders];
      const overlaps = (a, b) => Math.min(a.right, b.right) > Math.max(a.left, b.left)
        && Math.min(a.bottom, b.bottom) > Math.max(a.top, b.top);
      const binderBounds = binders.length === 2 && binders.every(button => {
        const bounds = button.getBoundingClientRect();
        const owner = button.closest('.node-overlay');
        const inside = bounds.left >= surface.left && bounds.right <= surface.right
          && bounds.top >= surface.top && bounds.bottom <= surface.bottom;
        // Proportional inset samples stay inside the rounded native hit area.
        const reachable = [[0.5, 0.5], [0.25, 0.5], [0.75, 0.5], [0.5, 0.25], [0.5, 0.75]]
          .every(([x, y]) => {
            const hit = document.elementFromPoint(bounds.left + bounds.width * x, bounds.top + bounds.height * y);
            return hit === button || button.contains(hit);
          });
        return inside && reachable && getComputedStyle(owner).overflow === 'visible'
          && neighbors.every(other => other === button || !overlaps(bounds, other.getBoundingClientRect()));
      });
      const trunks = [...document.querySelectorAll('.edge-label-seq')];
      const seq = trunks.length === 2 && trunks.every(label => {
        const trunk = label.getBoundingClientRect();
        const members = [...graph.edges.values()].filter(edge => edge.data.seqGroup === label.dataset.seqGroup);
        const controlled = members.filter(edge => !edge.data.seqTail);
        return members.length === 3 && controlled.length === 2
          && members.every(edge => !!getNodeOverlay(edge.targetId)) && controlled.every(edge => {
          const target = getNodeOverlay(edge.targetId);
          const item = _edgeOverlaysByEdgeId.get(edge.id);
          if (!target || !item || !item.classList.contains('edge-seq-item') || item.querySelectorAll('button').length !== 4) return false;
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
        && control.isConnected && document.activeElement === control && !!control.getAttribute('aria-label')
        && !!native.getAttribute('aria-label');
      window.valuesLiveProof = {card, native, control, id, wide};
      return {converged, fit, binderBounds, seq, zoomIndependent, retained, wide,
        controlWidths: [...document.querySelectorAll('.edge-seq-item')].map(item => item.offsetWidth)};
    });
    console.log('bounded measured geometry', JSON.stringify(geometry));
    assert(geometry.converged && geometry.fit && geometry.binderBounds && geometry.seq && geometry.zoomIndependent && geometry.retained,
      'actual intrinsic cards and sequence controls fit, converge and retain native focus across reload/zoom');
    phase = 'native-action';
    const anchor = await page.evaluateHandle(() => valuesLiveProof.native);
    await anchor.asElement().click();
    const popover = page.locator('.arg-value-edit-popover');
    await popover.waitFor({state: 'visible', timeout: 60000});
    // Like editOwnValue, observe the asynchronous native typed form rather
    // than treating the initial loading skeleton as an editable control.
    const input = popover.locator('[data-form-field], .arg-value-edit-input').first();
    await input.waitFor({state: 'visible'});
    await input.focus();
    await page.evaluate(() => {
      window.valuesEscapeProof = {focused: !!document.activeElement.closest('.arg-value-edit-popover'),
        picker: !!document.querySelector('.fn-picker-popover')};
      document.addEventListener('keydown', event => {
        if (event.key !== 'Escape') return;
        valuesEscapeProof.observed = true;
        setTimeout(() => { valuesEscapeProof.prevented = event.defaultPrevented; }, 0);
      }, {once: true, capture: true});
    });
    await page.keyboard.press('Escape');
    await page.locator('.arg-value-edit-popover').waitFor({state: 'detached'});
    assert(await page.evaluate(() => valuesLiveProof.native.isConnected), 'native value action retains its original anchor');
    const handle = await page.evaluateHandle(() => valuesLiveProof.card.querySelector('.drag-handle'));
    const dragBox = await handle.asElement().boundingBox();
    assert(dragBox, 'native drag handle is measured');
    await page.evaluate(() => {
      const node = graph.nodes.get(valuesLiveProof.id);
      valuesLiveProof.beforeDrag = {x: node.x, y: node.y, zoom: viewport.zoom};
    });
    await page.mouse.move(dragBox.x + dragBox.width / 2, dragBox.y + dragBox.height / 2);
    await page.mouse.down();
    await page.mouse.move(dragBox.x + dragBox.width / 2 + 24, dragBox.y + dragBox.height / 2 + 18, {steps: 5});
    await page.mouse.up();
    const dragged = await page.evaluate(() => {
      const node = graph.nodes.get(valuesLiveProof.id);
      valuesLiveProof.dragged = {x: node.x, y: node.y};
      const before = valuesLiveProof.beforeDrag;
      return userMovedNodes.has(valuesLiveProof.id)
        && Math.abs(node.x - before.x - 24 / before.zoom) <= 1
        && Math.abs(node.y - before.y - 18 / before.zoom) <= 1;
    });
    assert(dragged, 'real pointer drag marks the native node as user positioned');
    if (process.env.GRAPHDEN_CANVAS_SCREENSHOT)
      await page.screenshot({path: process.env.GRAPHDEN_CANVAS_SCREENSHOT, fullPage: false});
    phase = 'builtin-shrink';
    const restoreSequence = sequence;
    const undone = await undoConfiguration(page, activation);
    assert(undone.ok, 'original preference restored before measuring builtin shrink');
    activation = null;
    await page.evaluate(() => gdPrefsRefresh());
    assert(await waitFor(async () => {
      const terminal = plans.find(row => row.sequence > restoreSequence && row.component === 'canvas-values'
        && ((row.status === 200 && row.ok && row.selectionId === state.selection?.['fn-id'])
          || (row.status === 422 && row.code === 'component-missing' && row.fallback === 'builtin')));
      return terminal && await page.evaluate(({selection, missing}) => {
        const current = gdUIConfigurationState();
        return current?.selection?.['fn-id'] === selection
          && (missing ? gdUIComponentRuntimeIdentity('canvas-values') === null
            : gdUIComponentRuntimeIdentity('canvas-values') === selection);
      }, {selection: state.selection?.['fn-id'], missing: terminal.code === 'component-missing'});
    }, 60000), 'undo reaches the terminal restored selection and authorized plan');
    await page.waitForFunction(() => !document.querySelector('.gd-canvas-values-personal'));
    await page.waitForFunction(() => gv.node(valuesLiveProof.id)?.width() < valuesLiveProof.wide);
    assert(await page.evaluate(() => valuesLiveProof.card.isConnected
      && valuesLiveProof.card.querySelector('.arg-value-text') === valuesLiveProof.native
      && graph.nodes.get(valuesLiveProof.id).x === valuesLiveProof.dragged.x
      && graph.nodes.get(valuesLiveProof.id).y === valuesLiveProof.dragged.y),
      'custom to builtin shrinks the same measured native card');
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
  } catch (error) {
    await Promise.all(pending);
    const facts = await page.evaluate(({selection, witness}) => ({
      selectedRoot: selectedFnId === witness,
      selectedRuntime: gdUIComponentRuntimeIdentity('canvas-values') === selection,
      values: document.querySelectorAll('.arg-value-text .gd-canvas-values-personal').length,
      types: document.querySelectorAll('.arg-type-chip .gd-canvas-values-personal').length,
      sequences: document.querySelectorAll('.edge-label-seq').length,
      controls: document.querySelectorAll('.arg-seq-btn-remove').length,
      fallback: document.querySelectorAll('.gd-canvas-values-label').length,
      escape: window.valuesEscapeProof || null,
    }), {selection: activation?.receipt?.id, witness: witnessId}).catch(() => null);
    console.log('bounded canvas proof failure', JSON.stringify({phase, facts,
      errors: errors.length, plans: plans.map(({component, status, code}) => ({component, status,
        code: typeof code === 'string' && /^[a-z0-9:/-]{1,80}$/.test(code) ? code : null}))}));
    if (process.env.GRAPHDEN_CANVAS_SCREENSHOT)
      await page.screenshot({path: process.env.GRAPHDEN_CANVAS_SCREENSHOT + '.failure.png'}).catch(() => {});
    throw error;
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
