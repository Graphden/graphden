// Real Chromium plans/renderer/stylesheet lifecycle, independent of server
// startup. Persisted source authorization is exercised by the personal test.
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require('playwright');
const source = process.env.GRAPHDEN_CANVAS_SOURCE_ROOT || path.join(__dirname, '../..');
(async () => {
  const browser = await chromium.launch({headless: true});
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.setContent('<main id="canvas"></main>');
    for (const file of ['web/vendor/preact.min.js', 'app/ui-preview/browser-runtime.js',
      'app/ui-preview/graph-styles.js', 'app/ui-preview/graph-renderer.js', 'app/ui-preview/builtin-plans.js']) {
      await page.addScriptTag({path: path.join(source, 'resources/packages', file)});
    }
    await page.addStyleTag({path: path.join(source, 'resources/packages/app/editor/editor-styles.css')});
    await page.evaluate(() => {
      window.fixture = {failures: [], clicks: 0, groups: null};
      fixture.check = (value, message) => { if (!value) throw new Error(message); };
      window.gdPrefOwner = 'card-owner-a'; window.gdPrefsReady = true;
      window.graphdenCurrentOrg = 'card-org'; window.getCurrentBranchName = () => 'main';
      window.isAuthenticated = () => true;
      window.gdUIComponentFailed = component => fixture.failures.push(component);
      window.gdLoadUIComponentRuntime = async identity => fixture.selected?.[identity] || null;
      fixture.record = value => Array.isArray(value) ? value.map(fixture.record)
        : value && Object.getPrototypeOf(value) === Object.prototype
          ? new Map(Object.entries(value).map(([key, item]) => [GraphdenBrowser.keyword(key), fixture.record(item)])) : value;
      fixture.k = GraphdenBrowser.keyword;
      fixture.native = {id: 'card', name: 'native-card', 'parent-ids': ['parent'], 'lambda-params': ['request', 'response'],
        'expects-effects': ['db', 'time'], 'return-type': ':any'};
      window.richTypeEntryOf = () => ({return: ['refine', 'int', ['>', 0]], effects: ['db', 'network']});
      window.formatTypeHint = () => ':positive-int';
      window.refinementConstraintText = () => '> 0';
      window.formatTypeHumanReadable = () => 'positive integer';
      window.enterLambdaParamsEditMode = (fn, anchor) => {
        fixture.check(fn === fixture.native && anchor === fixture.lambda, 'lambda keeps exact native owner/anchor'); fixture.clicks++;
      };
      window.enterFnReturnTypeEditMode = (fn, anchor) => {
        fixture.check(fn === fixture.native && anchor === fixture.returns, 'return control keeps exact owner'); fixture.clicks++;
      };
      window.showEffectExplainer = ({effect, anchorEl}) => {
        fixture.check(effect === 'network' && anchorEl === fixture.effect, 'effect keeps exact native anchor'); fixture.clicks++;
      };
      fixture.levels = Array.from({length: 4}, (_, position) => ({position, depth: position,
        mi: position === 1, clickable: position === 1, 'duplicate-next': false}));
    });
    for (const file of ['editor-passive-canvas.js', 'editor-canvas-values-graph.js',
      'editor-canvas-inheritance-graph.js', 'editor-canvas-metadata-graph.js', 'editor-overlay-strips.js']) {
      await page.addScriptTag({path: path.join(source, 'resources/packages/app/editor', file)});
    }
    await page.evaluate(() => {
      // Use the actual metadata factories, with a fixture-owned provenance
      // callback so its native sibling button can be checked independently.
      window.appendRuleProvenanceButton = strip => {
        const button = document.createElement('button'); button.type = 'button'; button.textContent = '↳';
        fixture.provenance = button; strip.appendChild(button);
      };
      const owner = document.createElement('section'); owner.id = 'native-owner';
      document.getElementById('canvas').appendChild(owner); fixture.owner = owner;
      const context = {overlay: owner, cardFnEntity: fixture.native, stripFacts: {}, rt: ':any',
        rtEditable: true, flagEditable: true, isNavRoot: true};
      const sample = fixture.record({surface: 'return', 'declared-return': ':any', 'computed-return': ':positive-int', constraint: '> 0',
        'constraint-title': 'positive integer', 'lambda-mode': 'derived', params: [], effect: '', computed: false, declared: false, 'has-contract': false});
      const output = GraphdenBrowser.createRuntime(GraphdenBuiltinPlans.plans.canvasMetadata, {operationLimit:150000}).run('view', {inputs:sample});
      GraphdenRenderer.vnode(output.get(fixture.k('tree')), 'gd-canvas-metadata', 1024);
      gdCheckCanvasFlowStyles(GraphdenStyles.normalize(output.get(fixture.k('styles'))));
      appendReturnTypeStrip(context); appendEffectsStrip(context);
      fixture.returns = owner.querySelector('.return-type-strip');
      fixture.lambda = owner.querySelector('.lambda-params-chip');
      fixture.effect = owner.querySelector('.effects-chip-network');
      fixture.check(fixture.returns.textContent.includes('→ :positive-int') && fixture.returns.textContent.includes('> 0'), 'real refinement return body ' + fixture.returns.outerHTML + JSON.stringify(fixture.failures));
      fixture.check(fixture.lambda.textContent === 'λ request, response', 'ordered lambda body');
      fixture.check(fixture.effect.querySelector('.gd-canvas-metadata-drift'), 'computed undeclared drift body');
      fixture.check(owner.querySelector('.effects-chip-time .gd-canvas-metadata-ghost'), 'overdeclared ghost body');
      fixture.labelHost = document.createElement('span'); owner.appendChild(fixture.labelHost);
      gdCanvasInheritanceGraph.mount(owner, fixture.labelHost, 'label', {label: 'parent'});
      fixture.applyPolicy = () => { fixture.groups = gdCanvasInheritanceGraph.groups(fixture.levels, false); };
      fixture.applyPolicy(); gdCanvasInheritanceGraph.bind(owner, fixture.applyPolicy);
      fixture.check(fixture.groups[2].group === fixture.groups[3].group, 'builtin grouped passive followers');
      fixture.lambda.focus();
      fixture.provenanceIdentity = fixture.provenance; fixture.effectIdentity = fixture.effect;
      // Customize actual compiled graph literals, keeping the same authorized
      // entries and their ordinary primitive/capture ABI.
      fixture.custom = name => structuredClone(GraphdenBuiltinPlans.plans[name]);
      fixture.replace = (plan, from, to) => {
        const walk = item => {
          if (!item || typeof item !== 'object') return;
          if (Array.isArray(item) && item[0] === 'string' && item[1] === from) item[1] = to;
          else for (const value of Object.values(item)) walk(value);
        }; walk(plan); return plan;
      };
      const inherit = fixture.replace(fixture.custom('canvasInheritance'), 'gd-canvas-inheritance-label',
        'gd-canvas-inheritance-label gd-canvas-inheritance-personal');
      const previous = inherit.functions.find(fn => fn.args.some(arg => arg.name === 'key'
        && arg.expr.kind === 'literal' && arg.expr.value[0] === 'keyword' && arg.expr.value[2] === 'previous-mi'));
      const branch = inherit.functions.find(fn => fn.args.some(arg => arg.name === 'test'
        && arg.expr.kind === 'call' && arg.expr.fn === previous.id));
      fixture.check(branch, 'exact compiled group tail found');
      branch.args.find(arg => arg.name === 'else').expr = {kind: 'literal', value: ['bool', true]};
      fixture.personalInheritance = GraphdenBrowser.createRuntime(inherit, {operationLimit: 2048});
      const meta = fixture.replace(fixture.custom('canvasMetadata'), 'gd-canvas-metadata-effect gd-canvas-metadata-effect-',
        'gd-canvas-metadata-personal gd-canvas-metadata-effect gd-canvas-metadata-effect-');
      fixture.personalMetadata = GraphdenBrowser.createRuntime(meta, {operationLimit: 150000});
      fixture.selected = {'canvas-inheritance': fixture.personalInheritance, 'canvas-metadata': fixture.personalMetadata};
    });
    await page.evaluate(async () => { await Promise.all([gdCanvasInheritanceGraph.reload(), gdCanvasMetadataGraph.reload()]); });
    await page.waitForFunction(() => fixture.labelHost.querySelector('.gd-canvas-inheritance-personal')
      && fixture.effect.querySelector('.gd-canvas-metadata-personal'));
    await page.evaluate(() => {
      fixture.check(fixture.groups[2].group !== fixture.groups[3].group, 'personal ordinary graph changes passive group membership');
      fixture.check(document.activeElement === fixture.lambda && fixture.effect === fixture.effectIdentity
        && fixture.provenance === fixture.provenanceIdentity, 'runtime swaps retain exact focused native controls');
      fixture.lambda.click(); fixture.effect.click(); fixture.returns.click();
      fixture.check(fixture.clicks === 3, 'all original control callbacks remain live');
      fixture.check(fixture.lambda.getAttribute('aria-label').includes('Call-site parameters'), 'native accessible name survives');
      const inherited = fixture.personalInheritance;
      // A bad LATER receipt must discard earlier personal split state and
      // restart from the builtin seed, rather than continue builtin mid-fold.
      fixture.selected['canvas-inheritance'] = {run(entry, supplied) {
        const value = inherited.run(entry, supplied);
        if (entry === 'initial' && supplied.inputs.get(fixture.k('surface')) === 'groups'
          && supplied.inputs.get(fixture.k('level')).get(fixture.k('position')) === 3) {
          value.get(fixture.k('row')).set(fixture.k('position'), 999);
        }
        return value;
      }};
    });
    await page.evaluate(() => gdCanvasInheritanceGraph.reload());
    await page.waitForFunction(() => !fixture.labelHost.querySelector('.gd-canvas-inheritance-personal')
      && fixture.groups[2].group === fixture.groups[3].group);
    await page.evaluate(() => {
      fixture.check(fixture.failures.includes('canvas-inheritance'), 'late policy failure diagnosed');
      fixture.check(fixture.groups.length === 4 && fixture.groups.every((row, i) => row.position === i), 'whole builtin retry retains every native ordinal');
      // Real compiled const output with an active element must be refused.
      const plan = GraphdenBuiltinPlans.plans.accountMenu;
      const primitive = plan.primitives.find(row => row.op === 'const');
      const fn = structuredClone(plan.functions.find(row => row.primitive === primitive.id));
      const output = fixture.record({tree: [fixture.k('button'), fixture.record({}), 'Forbidden'], title: '', styles: []});
      fn.args = [{name: 'value', slot: fn.args.find(arg => arg.name === 'value').slot,
        expr: {kind: 'literal', value: GraphdenBrowser.encode(output)}}]; fn.env = []; fn.aliases = [];
      fixture.selected['canvas-metadata'] = GraphdenBrowser.createRuntime({format: 1, primitiveAbi: 1,
        entries: {view: fn.id}, inputs: {[fn.id]: {accepted: ['inputs'], required: [], destinations: []}},
        functions: [fn], primitives: [primitive]});
    });
    await page.evaluate(() => gdCanvasMetadataGraph.reload());
    await page.waitForFunction(() => !fixture.owner.querySelector('.gd-canvas-metadata-personal'));
    await page.evaluate(() => {
      fixture.check(fixture.failures.includes('canvas-metadata') && !fixture.owner.textContent.includes('Forbidden'), 'active personal metadata refused across all bodies');
      fixture.check(fixture.effect.querySelector('.gd-canvas-metadata-drift'), 'whole metadata builtin fallback');
      fixture.pending = [];
      gdLoadUIComponentRuntime = (identity, _plan, _options, signal) => new Promise(resolve => fixture.pending.push({identity, signal, resolve}));
      fixture.loads = [gdCanvasInheritanceGraph.reload(), gdCanvasMetadataGraph.reload()];
      fixture.oldScopes = [...fixture.owner.querySelectorAll('[data-gd-ui-style]')].map(el => el.dataset.gdUiStyle);
      gdAccount = {id: 'other-account'}; gdPrefOwner = 'card-owner-b';
      window.dispatchEvent(new Event('gd-auth-changed'));
      fixture.check(fixture.labelHost.textContent === '' && !fixture.owner.querySelector('.gd-canvas-metadata-effect'), 'auth retires all passive old-account content immediately');
      fixture.check(fixture.pending.every(row => row.signal.aborted), 'auth aborts both old component requests');
      fixture.check(fixture.oldScopes.every(id => !document.querySelector('style[data-gd-ui-styles="' + id + '"]')), 'old scoped styles released');
      for (const row of fixture.pending) row.resolve(row.identity === 'canvas-inheritance' ? fixture.personalInheritance : fixture.personalMetadata);
    });
    await page.evaluate(() => Promise.all(fixture.loads));
    await page.evaluate(() => {
      fixture.check(!fixture.owner.querySelector('.gd-canvas-inheritance-personal, .gd-canvas-metadata-personal'), 'late old-account plans cannot restore retired bodies');
      fixture.check(fixture.lambda === document.activeElement && fixture.provenance.isConnected, 'native focus/controls retained through passive disposal');
    });
    assert.deepEqual(errors, []);
    console.log('PASS passive canvas card personal groups/bodies, retained controls, whole fallback, auth generation/styles');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
