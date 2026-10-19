// Actual Chromium renderer/CSS and native factories. Stored authorization and
// preview/apply/restore are exercised separately against the isolated server.
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require('playwright');
const source = process.env.GRAPHDEN_CANVAS_SOURCE_ROOT || path.join(__dirname, '../..');
(async () => {
  const browser = await chromium.launch({headless: true});
  const page = await browser.newPage({hasTouch: !!process.env.GRAPHDEN_VIEWPORT,
    viewport: process.env.GRAPHDEN_VIEWPORT ? {width: 390, height: 844} : {width: 1280, height: 720}});
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.setContent('<main id="canvas" style="width:300px;padding:20px"></main>');
    await page.addStyleTag({path: path.join(source, 'resources/packages/app/editor/editor-styles.css')});
    for (const file of ['web/vendor/preact.min.js', 'app/ui-preview/browser-runtime.js',
      'app/ui-preview/graph-styles.js', 'app/ui-preview/graph-renderer.js', 'app/ui-preview/builtin-plans.js']) {
      await page.addScriptTag({path: path.join(source, 'resources/packages', file)});
    }
    await page.evaluate(() => {
      window.fixture = {failures: [], selected: {}, saves: [], moves: 0};
      fixture.check = (value, message) => { if (!value) throw new Error(message); };
      fixture.control = element => {
        const r = element.getBoundingClientRect(), s = getComputedStyle(element);
        const mark = element.querySelector('[class$="control-mark"]');
        const m = mark.getBoundingClientRect(), ms = getComputedStyle(mark);
        const hit = document.elementFromPoint(m.x + m.width / 2, m.y + m.height / 2);
        return {minimum: r.width + .5 >= parseFloat(s.minWidth) && r.height + .5 >= parseFloat(s.minHeight),
          hit: hit === element || element.contains(hit), unscoped: !mark.closest('[data-gd-ui-style]'),
          visible: m.width > 0 && m.height > 0 && ms.display !== 'none' && Number(ms.opacity) > 0,
          bounds: [r.x,r.y,r.width,r.height], min: [s.minWidth,s.minHeight]};
      };
      window.gdPrefOwner = 'structure-owner'; window.gdPrefsReady = true;
      window.graphdenCurrentOrg = 'structure-org'; window.getCurrentBranchName = () => 'main';
      window.isAuthenticated = () => true; window.graphdenIsFnOwned = () => true;
      window.gdUIComponentFailed = identity => fixture.failures.push(identity);
      window.gdLoadUIComponentRuntime = async identity => fixture.selected[identity] || null;
      window.installTabTrap = () => {};
      window.returnFocusTo = element => element?.focus();
      window.implementationFnIds = new Set(['fn']);
      window.lookups = {fnMap: new Map([['fn', {id: 'fn', name: 'child'}]]), fnByName: new Map(),
        bindingByFnSlot: new Map([['fn|slot', {'list-append': true}]]),
        slotMap: new Map([['slot', {required: false}]]), slotOwnerById: new Map([['slot', 'other']])};
      window.richTypes = {tree: {return: ['list', 'tree']}};
      window.writeBindingFields = async (arg, fields) => { fixture.saves.push({arg, fields}); return {ok: true}; };
      fixture.tightens = [];
      window.API = {api_bindings_binding_id_tighten_fn_effects: id => '/native-tighten/' + id};
      window.authFetch = async (url, options) => {
        fixture.tightens.push({url, body: JSON.parse(options.body)});
        if (fixture.failTighten) {
          fixture.failTighten = false;
          return {ok: false, status: 422, text: async () => '<p class="error">Native test refusal</p>'};
        }
        return {ok: true};
      };
      window.initGraph = () => {};
      window.loadGraphData = () => {};
      window.slotRichType = () => ['list', 'text'];
      fixture.arg = {'fn-id': 'fn', 'slot-id': 'slot', name: 'items'};
      fixture.record = value => Array.isArray(value) ? value.map(fixture.record)
        : value && Object.getPrototypeOf(value) === Object.prototype
          ? new Map(Object.entries(value).map(([key, item]) => [GraphdenBrowser.keyword(key), fixture.record(item)])) : value;
      fixture.outputRuntime = output => ({run(entry, supplied, budget) {
        if (entry === 'initial') return fixture.builtin.run(entry, supplied, budget);
        return fixture.record(output);
      }});
    });
    for (const file of ['editor-passive-canvas.js', 'editor-canvas-values-graph.js',
      'editor-canvas-type-structure-graph.js', 'editor-canvas-bindings-graph.js',
      'editor-literal-types.js', 'editor-type-format.js', 'editor-type-expand-render.js',
      'editor-overlay-type-expand.js', 'editor-edit-modes.js', 'editor-overlay-seal.js']) {
      await page.addScriptTag({path: path.join(source, 'resources/packages/app/editor', file)});
    }
    await page.evaluate(() => {
      window.writeBindingFields = async (arg, fields) => { fixture.saves.push({arg, fields}); return {ok: true}; };
      fixture.builtin = GraphdenBrowser.createRuntime(GraphdenBuiltinPlans.plans.canvasTypeStructure, {operationLimit: 150000});
      const canvas = document.getElementById('canvas');
      const row = buildInlineTypeRow('record', {nested: ['list', 'text']}, 'root');
      const readonly = buildInlineTypeRow('leaf', 'text', 'readonly').querySelector('.type-inline-chip');
      fixture.check(!readonly.hasAttribute('role') && !readonly.hasAttribute('aria-label'),
        'readonly type text has no prohibited accessible-name override');
      canvas.appendChild(row); fixture.row = row; fixture.chip = row.querySelector('.type-inline-chip');
      fixture.badge = createSealBadge(fixture.arg, {isSequenceAnchor: true}); canvas.appendChild(fixture.badge);
      fixture.chip.click();
      fixture.check(fixture.row.querySelectorAll('.type-inline-child .gd-canvas-body-host').length > 0, 'actual nested expansion creates passive owners');
      fixture.chip.click();
      fixture.check(!fixture.row.querySelector('.type-inline-child .gd-canvas-body-host'), 'collapse removes nested bodies');
      fixture.check(fixture.chip.getAttribute('aria-expanded') === 'false', 'native expanded state preserved');
      fixture.canonical = compactTypeAsValue(['list', 'text']);
      fixture.chip.focus();
      fixture.identity = fixture.chip;
      fixture.chip.dataset.inlineAnchor = 'top';
      fixture.formats = 0; const format = gdCanvasTypeStructureGraph.format;
      gdCanvasTypeStructureGraph.format = (...args) => { fixture.formats++; return format(...args); };
      fixture.openTop = () => {
        fixture.top = ensureInlineHost('top'); expandedTypePaths.add('top');
        renderInlineExpansionInto(fixture.top, ['fn', {request: 'text'}, 'text', 'any'], 'top',
          {editable: true, bindingId: 'binding', onEdit: () => fixture.moves++});
        positionInlineHost(fixture.top, fixture.chip);
      };
      fixture.zoom = 1; window.gv = {ready: () => true, zoom: () => fixture.zoom, onViewportChange(callback) { fixture.viewportChanged = callback; }};
    });
    for (const [name, declarations] of [['empty', null], ['hidden', {display: 'none'}],
      ['transparent', {opacity: '0'}], ['zero', {display: 'block', width: '0', 'font-size': '0'}],
      ['large', {display: 'block', width: '4096px', padding: '16px', 'font-size': '48px'}]]) {
      await page.evaluate(({name, declarations}) => {
        closeInlineEdit(); fixture.chip.focus(); fixture.before = fixture.failures.length;
        for (const identity of ['canvas-type-structure', 'canvas-bindings']) {
          const className = 'gd-' + identity + '-optional';
          const output = {title: '', tree: name === 'empty' ? null
            : [GraphdenBrowser.keyword('span'), {class: className}, 'personal label'],
            styles: declarations ? [{selector: '& .' + className, declarations}] : []};
          fixture.selected[identity] = fixture.outputRuntime(output);
        }
      }, {name, declarations});
      await page.evaluate(() => Promise.all([gdCanvasTypeStructureGraph.reload(), gdCanvasBindingsGraph.reload()]));
      await page.evaluate(() => {
        fixture.check(fixture.chip === fixture.identity && document.activeElement === fixture.chip, 'runtime swap retains focus');
        fixture.failTighten = true;
        fixture.openTop();
      });
      await page.getByText('Additional constraints', {exact: true}).click();
      await page.evaluate(() => {
        const calls = fixture.formats;
        for (const zoom of [.75, 1.25, 1]) {
          fixture.zoom = zoom; repositionAllInlineHosts();
          const r = fixture.top.getBoundingClientRect();
          fixture.check(r.top >= 7 && r.left >= 7 && r.bottom <= innerHeight - 7 && r.right <= innerWidth - 7,
            'native type popover remains viewport bound at zoom ' + zoom + ': ' + JSON.stringify({top:r.top,left:r.left,bottom:r.bottom,right:r.right,width:r.width,height:r.height,viewport:[innerWidth,innerHeight]}));
        }
        fixture.check(fixture.formats === calls, 'pan/zoom reanchors without formatting');
        const effectInputs = [...fixture.top.querySelectorAll('input[type="checkbox"]')];
        const expectedEffects = ['cross-org', 'db', 'env', 'io', 'network', 'process', 'random', 'raw-sql', 'state', 'time'];
        fixture.check(JSON.stringify(effectInputs.map(input => input.value)) === JSON.stringify(expectedEffects)
          && effectInputs.every(input => input.getAttribute('aria-label') === 'Allow effect ' + input.value),
        'every canonical native effect category remains uniquely available with its accessible label');
        for (const input of effectInputs) {
          input.scrollIntoView({block: 'nearest'});
          const r = input.getBoundingClientRect();
          fixture.check(input.getAttribute('aria-label') && r.width > 0 && r.height > 0
            && document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2) === input,
          'every native effect checkbox retains its scrollable hit target');
        }
        fixture.top.querySelector('input[aria-label="Allow effect db"]').focus();
      });
      await page.locator('.type-inline-host input[aria-label="Allow effect db"]').check();
      await page.evaluate(() => {
        const cb = fixture.top.querySelector('input[aria-label="Allow effect db"]');
        const rect = cb.getBoundingClientRect();
        const target = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
        fixture.check(cb.checked && target === cb && !fixture.top.querySelector('.type-explainer-btn').disabled,
          'native type checkbox hit/state enables original Tighten control');
        const tighten = fixture.top.querySelector('.type-explainer-btn'), tr = tighten.getBoundingClientRect();
        const tightenHit = document.elementFromPoint(tr.x + tr.width / 2, tr.y + tr.height / 2);
        fixture.check(tighten === tightenHit || tighten.contains(tightenHit),
          'Tighten remains visible outside optional scrolling body');
        const button = fixture.top.querySelector('.type-inline-header-edit'), r = button.getBoundingClientRect();
        const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
        fixture.check(button === hit || button.contains(hit), 'Change type remains outside optional scrolling body');
      });
      await page.getByRole('button', {name: 'Tighten', exact: true}).click();
      await page.waitForFunction(() => fixture.top.querySelector('.type-explainer-tighten-err').style.display === 'block');
      await page.evaluate(() => {
        const error = fixture.top.querySelector('.type-explainer-tighten-err'), r = error.getBoundingClientRect();
        const button = fixture.top.querySelector('.type-explainer-btn'), b = button.getBoundingClientRect();
        const hit = document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2);
        fixture.check(error.textContent === 'Native test refusal' && r.top >= 0 && r.bottom <= innerHeight
          && !button.disabled && (hit === button || button.contains(hit)),
        'native refusal remains visible with its retry action outside the optional body');
      });
      await page.getByRole('button', {name: 'Tighten', exact: true}).click();
      await page.evaluate(() => {
        const call = fixture.tightens.at(-1);
        fixture.check(call?.url === '/native-tighten/binding'
          && JSON.stringify(call.body) === JSON.stringify({type: ['fn', {request: 'text'}, 'text', ['db'], 'unknown']})
          && fixture.top.style.display === 'none', 'visible Tighten retains the original native request handler');
        fixture.openTop();
      });
      await page.getByRole('button', {name: 'Change type', exact: true}).click();
      await page.evaluate(() => {
        fixture.check(fixture.moves > 0 && fixture.top.style.display === 'none'
          && !fixture.top.querySelector('[data-gd-ui-style]'), 'native Change type callback retires all type owners: ' + JSON.stringify({moves:fixture.moves,display:fixture.top.style.display,connected:fixture.top.isConnected,scopes:fixture.top.querySelectorAll('[data-gd-ui-style]').length}));
        fixture.chip.focus();
      });
      await page.evaluate(() => {
        fixture.check(fixture.chip === fixture.identity && document.activeElement === fixture.chip, 'runtime swap retains focused control');
        fixture.check(compactTypeAsValue(['list', 'text']) === fixture.canonical, 'display never changes canonical save grammar');
        const validation = validateLiteralAgainstType('0', ['refine', 'int', ['>', 0]]);
        fixture.check(validation.ok === false && validation.message.length > 0, 'empty presentation cannot erase native invalid state');
        fixture.outerControls = [fixture.control(fixture.chip), fixture.control(fixture.badge)];
        fixture.badge.click();
      });
      await page.waitForFunction(() => {
        const dialog = document.querySelector('.arg-value-edit-popover.gd-passive-popover');
        if (!dialog?.style.getPropertyValue('--gd-passive-viewport-height')) return false;
        const r = dialog.getBoundingClientRect();
        return r.top >= 7 && r.bottom <= innerHeight - 7;
      });
      const result = await page.evaluate(() => {
        const dialog = document.querySelector('.arg-value-edit-popover');
        const cb = dialog.querySelector('input[data-seal="terminal"]');
        const row = cb.closest('label');
        const hit = (element, owner = element) => {
          const r = element.getBoundingClientRect();
          const target = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
          return target === owner || owner.contains(target);
        };
        const checkbox = cb.getBoundingClientRect(), rowRect = row.getBoundingClientRect();
        const controls = fixture.outerControls;
        const native = controls.every(row => row.minimum && row.hit && row.unscoped && row.visible);
        const accessible = cb.getAttribute('aria-label') === 'Seal against descendants'
          && fixture.chip.getAttribute('aria-label').includes('Type:');
        const target = rowRect.height + .5 >= parseFloat(getComputedStyle(row).minHeight)
          && checkbox.width > 0 && checkbox.height > 0 && hit(cb);
        cb.focus(); const focused = document.activeElement === cb;
        cb.click(); fixture.check(cb.checked, 'native checkbox handler/state survives optional label');
        const blocked = gdSealBlocksBinder(fixture.arg, {sealedBy: 'ancestor', sealedByName: 'parent'});
        const permission = typeof blocked === 'string' && blocked.length > 0 && gdSealCanEdit('fn');
        const boxes = [...dialog.querySelectorAll('input[type="checkbox"]')].map(el => el.getBoundingClientRect());
        const overlap = boxes.some((a, i) => boxes.slice(i + 1).some(b =>
          Math.min(a.right, b.right) > Math.max(a.left, b.left) && Math.min(a.bottom, b.bottom) > Math.max(a.top, b.top)));
        return {native, controls, accessible, target, focused, permission, overlap,
          accepted: fixture.failures.length === fixture.before, coarse: matchMedia('(pointer: coarse)').matches};
      });
      assert(result.native && result.accessible && result.target && result.focused && result.permission
        && !result.overlap && result.accepted, name + ' native controls ' + JSON.stringify(result));
      await page.evaluate(() => {
        const dialog = document.querySelector('.arg-value-edit-popover');
        const sealInputs = [...dialog.querySelectorAll('input[type="checkbox"]')];
        fixture.check(sealInputs.length === 3, 'all applicable native seal controls remain available');
        for (const input of sealInputs) {
          input.scrollIntoView({block: 'nearest'});
          const r = input.getBoundingClientRect();
          const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
          fixture.check(input.getAttribute('aria-label') && r.width > 0 && r.height > 0
            && hit === input,
          'every seal checkbox retains its native accessible hit target: ' + JSON.stringify({key: input.dataset.seal,
            bounds: [r.x, r.y, r.width, r.height], viewport: [innerWidth, innerHeight], hit: hit?.className,
            scroll: dialog.querySelector('.gd-passive-popover-flow').scrollTop}));
        }
        const buttons = [...dialog.querySelectorAll('button')].filter(el => /^(Save|Cancel)$/.test(el.textContent));
        fixture.check(buttons.length === 2 && buttons.some(el => el.textContent === 'Cancel')
          && buttons.some(el => el.textContent === 'Save'), 'both native seal footer actions remain available');
        for (const button of buttons) {
          const r = button.getBoundingClientRect(), hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
          fixture.check(r.top >= 0 && r.bottom <= innerHeight && (hit === button || button.contains(hit)),
            'every native seal footer action stays visible');
        }
      });
      await page.getByRole('button', {name: 'Save', exact: true}).click();
      await page.waitForFunction(() => !document.querySelector('.arg-value-edit-popover'));
      assert.equal(await page.evaluate(() => fixture.saves.at(-1).fields.terminal), 'true');
      console.log('PASS optional ' + name + ' type/binding controls ' + JSON.stringify(result));
    }
    await page.evaluate(() => {
      const toggle = document.createElement('button'); toggle.textContent = 'Expand native type';
      document.getElementById('canvas').appendChild(toggle);
      attachInlineExpand(toggle, {field: ['list', 'text']}, 'escape-proof');
      fixture.toggle = toggle; toggle.click(); fixture.beforeEscape = inlineHostsByPath.get('escape-proof');
      fixture.check(fixture.beforeEscape?.isConnected && toggle.getAttribute('aria-expanded') === 'true', 'actual type trigger opens host');
    });
    await page.keyboard.press('Escape');
    await page.evaluate(() => {
      repositionAllInlineHosts(); window.dispatchEvent(new Event('resize'));
      fixture.check(!fixture.beforeEscape.isConnected && !expandedTypePaths.has('escape-proof')
        && fixture.toggle.getAttribute('aria-expanded') === 'false'
        && fixture.toggle.querySelector('.canvas-type-structure-control-mark').textContent === '▸',
      'Escape fully collapses native path, ARIA and mark before queued geometry');
    });
    await page.waitForFunction(() => !document.querySelector('.type-inline-host'));
    await page.getByRole('button', {name: 'Expand native type', exact: true}).click();
    await page.evaluate(() => {
      const reopened = inlineHostsByPath.get('escape-proof');
      fixture.check(reopened?.isConnected && reopened !== fixture.beforeEscape
        && reopened.querySelector('.gd-canvas-body-host') && fixture.toggle.getAttribute('aria-expanded') === 'true',
      'reopen creates new live bodies rather than reviving a disposed host');
      hideAllInlineHosts();
      fixture.pending = [];
      gdLoadUIComponentRuntime = (identity, _plan, _options, signal) => new Promise(resolve => fixture.pending.push({identity, signal, resolve}));
      fixture.loads = [gdCanvasTypeStructureGraph.reload(), gdCanvasBindingsGraph.reload()];
      fixture.scopes = [...document.querySelectorAll('[data-gd-ui-style]')].map(el => el.dataset.gdUiStyle);
      gdPrefOwner = 'different-owner'; window.dispatchEvent(new Event('gd-auth-changed'));
      fixture.check(fixture.pending.every(row => row.signal.aborted), 'auth aborts outstanding source requests');
      fixture.check(!fixture.row.querySelector('[data-gd-ui-style]'), 'auth clears old passive body scopes synchronously');
      fixture.check(fixture.scopes.every(id => !document.querySelector('style[data-gd-ui-styles="' + id + '"]')), 'auth releases old scoped styles');
      for (const row of fixture.pending) row.resolve(fixture.selected[row.identity]);
    });
    await page.evaluate(() => Promise.all(fixture.loads));
    await page.evaluate(() => fixture.check(!fixture.row.querySelector('[data-gd-ui-style]'), 'late source result cannot revive retired owners'));
    assert.deepEqual(errors, []);
    console.log('PASS type/binding native controls, nested collapse and auth lifetime');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
