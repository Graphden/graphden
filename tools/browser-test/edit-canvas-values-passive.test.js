// Standalone Chromium regression: real plans, renderer, styles and overlay
// factories; transport and mutation callbacks are controlled, without a server.
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require('playwright');
const source = process.env.GRAPHDEN_CANVAS_SOURCE_ROOT || path.join(__dirname, '../..');

(async () => {
  const browser = await chromium.launch({headless: true});
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  try {
    await page.setContent('<main id="canvas"></main>');
    for (const file of ['web/vendor/preact.min.js', 'app/ui-preview/browser-runtime.js',
      'app/ui-preview/graph-styles.js', 'app/ui-preview/graph-renderer.js', 'app/ui-preview/builtin-plans.js',
      'web/runtime/graphden-forms.js', 'app/editor/editor-literal-types.js', 'app/editor/editor-type-format.js']) {
      await page.addScriptTag({path: path.join(source, 'resources/packages', file)});
    }
    await page.evaluate(() => {
      window.fixture = {failures: [], edits: 0};
      fixture.check = (condition, message) => { if (!condition) throw new Error(message); };
      window.gdPrefOwner = 'values-owner';
      window.gdPrefsReady = true;
      window.graphdenCurrentOrg = 'values-org';
      window.getCurrentBranchName = () => 'main';
      window.isAuthenticated = () => true;
      window.gdUIComponentFailed = component => fixture.failures.push(component);
      window.gdLoadUIComponentRuntime = async () => null;
      window.lookups = {fnMap: new Map([['own', {id: 'own', name: 'fixture'}]]),
        bindingMap: new Map(), slotMap: new Map()};
      window.implementationFnIds = new Set(['own']);
      window.argRowFromNode = data => data.arg || null;
      window.truncateLabel = (text, limit) => text.length > limit ? text.slice(0, limit) + '…' : text;
      window.enterArgValueEditMode = (arg, content) => {
        fixture.check(arg === fixture.arg && content === fixture.content, 'native value action keeps exact arg/anchor');
        fixture.edits++;
      };
    });
    for (const file of ['editor-passive-canvas.js', 'editor-canvas-graph.js', 'editor-canvas-values-graph.js',
      'editor-overlay-manager.js', 'editor-drag.js', 'editor-overlay-arg.js', 'editor-overlay-edge-label.js']) {
      await page.addScriptTag({path: path.join(source, 'resources/packages/app/editor', file)});
    }

    await page.evaluate(() => {
      const {check} = fixture;
      const container = document.getElementById('canvas');
      fixture.arg = {'fn-id': 'own', type: 'text'};
      const literal = (id, secretRef) => {
        const data = {label: secretRef ? '/vault/native-path' : '" "', arg: fixture.arg, secretRef,
          literalRepr: ['svg', {width: '12', height: '12'}, ['rect', {width: '12', height: '12', fill: '#1234'}]]};
        createArgOverlay({id: () => id, data: key => key === undefined ? data : data[key]}, container);
        return getNodeOverlay(id);
      };
      fixture.node = literal('literal', false);
      fixture.secret = literal('secret', true);
      fixture.content = fixture.node.querySelector('.arg-value-text');
      fixture.preview = fixture.content.querySelector('.arg-literal-preview');
      fixture.valueHost = fixture.content.querySelector('.gd-canvas-body-host');
      fixture.chip = fixture.node.querySelector('.arg-type-chip');
      const edge = {argName: 'value', arg: fixture.arg, typeChain: [{type: 'text'},
        {type: 'any', fns: ['base'], source: 'slot'},
        {type: 'text', fns: ['child'], source: 'binding-override'}]};
      fixture.edge = buildEdgeLabelOverlay({id: () => 'edge', data: key => key === undefined ? edge : edge[key]},
        container, 'value', 'edge');
      fixture.historyHost = fixture.edge.querySelector('.edge-type-chain .gd-canvas-body-host');
      const hosts = [...container.querySelectorAll('.gd-canvas-body-host')];
      check(fixture.valueHost.textContent === '"␣"', 'real literal projection preserves whitespace glyphs');
      check(fixture.preview.querySelector('rect').getAttribute('fill') === '#1234', 'typed SVG repr is preserved');
      check(!GraphdenRenderer.ownsTarget(fixture.preview) && GraphdenRenderer.ownsTarget(fixture.valueHost),
        'repr and graph body have separate ownership');
      check(fixture.chip.textContent === 'text' && fixture.chip.getAttribute('aria-label').includes('Type: text'),
        'real native type chip wraps graph presentation');
      check(fixture.historyHost.querySelectorAll('.gd-canvas-values-history-row').length === 2
        && fixture.historyHost.textContent.includes('override'), 'real edge owner projects nested history records');
      check(!fixture.secret.querySelector('.arg-literal-preview, .gd-canvas-values-label')
        && fixture.secret.textContent.includes('🔒 /vault/native-path'), 'secret path stays entirely native without repr');
      check(new Set(hosts.map(host => host.dataset.gdUiStyle)).size === 1
        && hosts.every(host => host.dataset.gdUiStyle) && document.querySelectorAll('style[data-gd-ui-styles]').length === 1,
      'node and edge bodies share one stylesheet after real render');
      fixture.k = GraphdenBrowser.keyword;
      fixture.record = object => new Map(Object.entries(object).map(([key, value]) => [fixture.k(key), value]));
      // A real const graph evaluates adversarial personal presentation. Borrow
      // its primitive/function ABI from an existing generated shipped plan.
      fixture.personal = (styles = []) => {
        const plan = GraphdenBuiltinPlans.plans.accountMenu;
        const primitive = plan.primitives.find(item => item.op === 'const');
        const fn = structuredClone(plan.functions.find(item => item.primitive === primitive.id));
        const output = fixture.record({tree: [fixture.k('span'), fixture.record({class: 'gd-canvas-values-custom'}), 'Custom'],
          title: '', styles});
        fn.args = [{...fn.args[0], name: 'value', expr: {kind: 'literal', value: GraphdenBrowser.encode(output)}}];
        return GraphdenBrowser.createRuntime({format: 1, primitiveAbi: 1, entries: {view: fn.id},
          functions: [fn], primitives: [primitive],
          inputs: {[fn.id]: {accepted: ['inputs'], required: ['inputs'], destinations: []}}}, {operationLimit: 150000});
      };
      fixture.style = (property, value) => fixture.record({selector: '& .gd-canvas-values-custom',
        declarations: new Map([[property, value]])});
    });

    await page.evaluate(async () => {
      const {check} = fixture;
      const runtime = fixture.personal([fixture.style('font-style', 'italic')]);
      window.gdLoadUIComponentRuntime = async () => runtime;
      fixture.content.tabIndex = 0;
      fixture.content.focus();
      await gdCanvasValuesGraph.reload();
      check(fixture.valueHost.textContent === 'Custom'
        && getComputedStyle(fixture.valueHost.firstElementChild).fontStyle === 'italic', 'actual selected graph/style renders');
      check(fixture.content.querySelector('.arg-literal-preview') === fixture.preview
        && fixture.node.querySelector('.arg-type-chip') === fixture.chip
        && document.activeElement === fixture.content, 'selected refresh preserves exact native controls, repr and focus');
      check(!fixture.secret.querySelector('.arg-value-text .gd-canvas-values-custom'),
        'selected graph never receives secret label host');
      fixture.content.click();
      check(fixture.edits === 1, 'same native value action remains wired once');
      for (const [surface, facts] of [['other', {}], ['value-label', {label: null}],
        ['value-label', {value: 'forbidden'}], ['type-chip', {'secondary-kind': 'other'}],
        ['type-history', {history: [{label: 'x', 'source-label': ['not-text'], kind: 'slot'}]}],
        ['type-history', {history: [{label: 'x', 'source-label': 'base', kind: 'slot', id: 'forbidden'}]}]]) {
        const host = document.createElement('span');
        let rejected = false;
        try { gdCanvasValuesGraph.mount(fixture.node, host, surface, facts); } catch (_) { rejected = true; }
        check(rejected && !GraphdenRenderer.ownsTarget(host) && !host.childNodes.length, 'invalid facts fail before ownership');
      }
      // All cases pass the existing general style boundary; refusal belongs to
      // the new role's finite in-flow policy, not CSS parser rejection.
      for (const [property, value] of [['position', 'absolute'], ['height', '1px'],
        ['margin', '-1px'], ['margin', 'calc(-1px)'], ['display', 'grid']]) {
        const rules = [fixture.style(property, value)];
        GraphdenStyles.normalize(rules);
        window.gdLoadUIComponentRuntime = async () => fixture.personal(rules);
        const failures = fixture.failures.length;
        await gdCanvasValuesGraph.reload();
        await Promise.resolve();
        check(fixture.failures.length === failures + 1 && fixture.failures.at(-1) === 'canvas-values',
          'invalid flow plan is classified as component fallback: ' + property);
        check(fixture.valueHost.textContent === '"␣"' && fixture.chip.textContent === 'text'
          && !fixture.node.querySelector('.gd-canvas-values-custom'), 'fallback updates every body');
      }
    });

    await page.evaluate(async () => {
      const {check} = fixture;
      const nodeHosts = [...fixture.node.querySelectorAll('.gd-canvas-body-host')];
      unregisterNodeOverlay('literal');
      check(!fixture.node.isConnected && nodeHosts.every(host => !host.childNodes.length
        && !GraphdenRenderer.ownsTarget(host) && !host.dataset.gdUiStyle), 'actual node removal disposes all bodies');
      check(GraphdenRenderer.ownsTarget(fixture.historyHost) && document.querySelectorAll('style[data-gd-ui-styles]').length === 1,
        'edge retains shared stylesheet after node disposal');
      unregisterNodeOverlay('secret');
      _removeAllEdgeOverlays();
      check(!fixture.edge.isConnected && !GraphdenRenderer.ownsTarget(fixture.historyHost)
        && !fixture.historyHost.childNodes.length && !document.querySelector('style[data-gd-ui-styles]'),
      'actual edge removal releases last renderer and shared sheet');
      const owner = document.createElement('section');
      const host = document.createElement('span');
      owner.appendChild(host);
      document.getElementById('canvas').appendChild(owner);
      gdCanvasValuesGraph.mount(owner, host, 'value-label', {label: 'Authorized A'});
      let release;
      let signal;
      window.gdLoadUIComponentRuntime = (_name, _plan, _options, current) => {
        signal = current;
        return new Promise(resolve => { release = resolve; });
      };
      const pending = gdCanvasValuesGraph.reload();
      window.gdPrefOwner = 'values-owner-b';
      const fresh = document.createElement('span');
      owner.appendChild(fresh);
      gdCanvasValuesGraph.mount(owner, fresh, 'value-label', {label: 'Authorized B'});
      release(fixture.personal());
      await pending;
      check(signal.aborted && !host.childNodes.length && !GraphdenRenderer.ownsTarget(host)
        && fresh.textContent === 'Authorized B' && !fresh.querySelector('.gd-canvas-values-custom'),
      'mount-before-reload retires old facts and ignores late previous-owner plan');
      gdCanvasValuesGraph.dispose(owner);
      gdCanvasValuesGraph.dispose(owner);
      check(!fresh.childNodes.length && !document.querySelector('style[data-gd-ui-styles]'), 'explicit disposal is idempotent');
    });
    assert.deepEqual(errors, [], 'no browser errors');
    console.log('PASS canvas values: real overlay ownership, previews, selected plans, flow fallback and late scope replies');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
