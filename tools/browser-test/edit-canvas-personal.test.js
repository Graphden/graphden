// Persisted personal canvas: authorized plan, two passive bodies, native ownership.
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
const { observeRootServerCalls, withoutRecoveredServerErrors, withoutRecoveredRootErrors } = require('./root-server-call-test-helpers');
(async () => {
  await waitForServerHealthy();
  const { browser, page } = await newContext(chromium, { boot: false });
  const errors = [],
    plans = [],
    rootPlans = [],
    pending = [];
  const serverCalls = await observeRootServerCalls(page);
  const requestSequences = new WeakMap();
  let sequence = 0;
  let receipt, activation;
  page.on('pageerror', (e) => errors.push({ kind: 'pageerror', text: e.message }));
  page.on('console', (m) => {
    if (m.type() === 'error')
      errors.push({ kind: 'console', text: m.text(), url: m.location().url });
  });
  page.on('request', (request) => {
    if (['/api/ui/configuration/plan', '/api/ui/root/plan'].includes(new URL(request.url()).pathname))
      requestSequences.set(request, ++sequence);
  });
  page.on('response', (r) => {
    const path = new URL(r.url()).pathname;
    if (path === '/api/ui/configuration/plan' || path === '/api/ui/root/plan') {
      pending.push(
        (async () => {
          const b = await r.json().catch(() => null);
          if (path === '/api/ui/root/plan') {
            rootPlans.push({sequence: requestSequences.get(r.request()), status: r.status(),
              ok: b?.ok === true, code: b?.code, retryable: b?.retryable === true});
            return;
          }
          plans.push({
            kind: 'plan',
            url: r.url(),
            sequence: requestSequences.get(r.request()),
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
    // Complete the initial owned query before fixture writes change its source epoch.
    await serverCalls.settle();
    assert(
      (await api(page, 'POST', '/api/ui/configuration/prepare', { home: true })).ok,
      'explicit personal home ready',
    );
    const owner = await page.evaluate(() => gdPrefOwner);
    const p = await api(page, 'POST', '/api/ui/components/create/preview', {
      owner,
      'namespace-id': null,
      component: 'canvas',
    });
    assert(p.ok && p.manifest.component === 'canvas', 'canvas-only create preview');
    receipt = { request: p.request, manifest: p.manifest };
    const created = await api(page, 'POST', '/api/ui/components/create/apply', p.request);
    assert(created.ok && created.committed, 'exact canvas copy committed');
    const label = p.manifest.functions.find((fn) => fn.name === '_canvas-branch-label');
    assert(label, 'editable label belongs to the exact new canvas receipt');
    const labelGraph = await getEntities(page, label.id);
    for (const slot of ['then', 'else']) {
      const arg = synthArgs(labelGraph).find(
        (arg) => arg['fn-id'] === label.id && arg.name === slot,
      );
      assert(arg?.['binding-id'], 'copied label has its own literal branch');
      const edited = await api(
        page,
        'PUT',
        '/api/entities/binding/' + arg['binding-id'],
        'value=' + encodeURIComponent(JSON.stringify('personal branch label')),
      );
      assert(edited.status === 200 || edited.ok, 'personal graph label edit saved');
    }
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
    await page.reload();
    await page.waitForFunction(
      ({ id, canvas }) =>
        gdUIComponentRuntimeIdentity('canvas') === id &&
        gdUIComponentsRoots()['canvas-id'] === canvas &&
        graphReady() &&
        !graph.animating &&
        document.querySelector('.gd-canvas-name') &&
        document.querySelector('.gd-canvas-branch')?.textContent.includes('personal branch label'),
      { id: activation.receipt.id, canvas: p.manifest.roots['canvas-id'] },
      { timeout: 60000 },
    );
    assert(
      plans.some((r) => r.component === 'canvas' && r.status === 200 && r.ok && !r.fallback),
      'authorized personal canvas plan loaded after real reload',
    );
    const mounted = await page.evaluate(() => {
      const name = document.querySelector('.gd-canvas-name');
      const owner = name.closest('.node-overlay');
      const branch = document.querySelector('.gd-canvas-branch');
      const action = owner.querySelector('.more-actions-trigger');
      if (!action) return { ok: false };
      action.focus();
      const box = owner.getBoundingClientRect();
      window.canvasLiveProof = {
        owner,
        action,
        branch,
        box: { width: box.width, height: box.height },
        clicks: 0,
      };
      action.addEventListener('click', () => canvasLiveProof.clicks++, { once: true });
      return {
        ok: !!name.textContent && !!branch.textContent,
        scope: !!name.closest('[data-gd-ui-style]') && !!branch.closest('[data-gd-ui-style]'),
      };
    });
    assert(
      mounted.ok && mounted.scope,
      'personal name and branch bodies render with scoped styles',
    );
    await page.evaluate(async () => {
      await gdCanvasGraph.reload();
      await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    });
    const native = await page.evaluate(() => {
      const p = canvasLiveProof,
        b = p.owner.getBoundingClientRect();
      const good =
        p.owner.isConnected &&
        p.action.isConnected &&
        p.owner.contains(p.action) &&
        document.activeElement === p.action &&
        Math.abs(b.width - p.box.width) < 1 &&
        Math.abs(b.height - p.box.height) < 1;
      return { good };
    });
    const handle = await page.evaluateHandle(() => canvasLiveProof.action);
    await handle.asElement().click();
    const action = await page.evaluate(() => ({
      clicks: canvasLiveProof.clicks,
      opened: canvasLiveProof.action.getAttribute('aria-expanded') === 'true',
    }));
    assert(
      native.good && action.clicks === 1 && action.opened,
      'native action identity, focus, click and measured geometry survive plan refresh',
    );
    if (process.env.GRAPHDEN_CANVAS_SCREENSHOT)
      await page.screenshot({ path: process.env.GRAPHDEN_CANVAS_SCREENSHOT, fullPage: false });
    await serverCalls.settle();
    await Promise.all(pending);
    const browserErrors = withoutRecoveredRootErrors(withoutRecoveredServerErrors(errors, serverCalls.events), rootPlans);
    const unexpected = unexpectedSelectiveCopyErrors(browserErrors, plans, true);
    if (unexpected.length)
      console.log(
        'unexpected error counts',
        JSON.stringify({
          count: unexpected.length,
          errors: unexpected.map(({ kind, text, url }) => ({kind,
            route: url ? new URL(url).pathname : null,
            status: Number(text?.match(/status of (\d+)/)?.[1]) || null,
            pageError: kind === 'pageerror' ? String(text).slice(0, 120) : null})),
          plans: plans.map(({ component, status, code }) => ({ component, status, code })),
        }),
      );
    assert(unexpected.length === 0, 'no runtime errors or unclassified console errors');
    console.log('PASS persisted personal canvas two surfaces/native ownership');
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
          'only exact created canvas functions cleaned',
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
