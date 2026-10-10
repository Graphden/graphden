// Managed graph DOM must never enter the ambient legacy action dispatcher.
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require('playwright');
(async () => {
  const browser = await chromium.launch({headless: true});
  try {
    const page = await browser.newPage();
    await page.setContent('<div id="legacy"><div id="graph"></div><button id="native" data-action="privileged" data-gd-ui-style="forged">native</button></div><div id="outer"><div id="inner"></div></div>');
    for (const file of ['web/vendor/preact.min.js', 'app/ui-preview/browser-runtime.js', 'app/ui-preview/graph-styles.js', 'app/ui-preview/graph-renderer.js', 'web/runtime/graphden-runtime.js']) {
      await page.addScriptTag({path: path.resolve('resources/packages', file)});
    }
    const result = await page.evaluate(() => {
      const api = GraphdenBrowser;
      const attrs = values => new Map(Object.entries(values).map(([key, value]) => [api.keyword(key), value]));
      const host = document.getElementById('graph');
      const component = GraphdenRenderer.mount(host);
      const calls = [];
      registerActionHandler('custom', () => calls.push('custom'));
      registerActionHandler('privileged', () => calls.push('privileged'));
      bindActionDispatch(document.getElementById('legacy'));
      host.addEventListener('click', () => calls.push('component'));
      component.render(['div', attrs({}), ['button', attrs({'data-action': 'custom'}), ['span', attrs({}), 'custom']], ['button', attrs({'data-action': 'privileged'}), 'privileged']]);
      host.querySelector('span').click();
      host.querySelectorAll('button')[1].click();
      document.getElementById('native').click();
      const before = [...calls];
      const textOwned = GraphdenRenderer.ownsTarget(host.querySelector('span').firstChild);
      const inner = document.getElementById('inner');
      const innerOwner = GraphdenRenderer.mount(inner);
      let ancestorRejected = false;
      let descendantRejected = false;
      try { GraphdenRenderer.mount(document.getElementById('outer')); } catch (_) { ancestorRejected = true; }
      const empty = document.createElement('div');
      inner.appendChild(empty);
      try { GraphdenRenderer.mount(empty); } catch (_) { descendantRejected = true; }
      innerOwner.dispose();
      component.dispose();
      host.innerHTML = '<button data-action="privileged">released</button>';
      host.firstChild.click();
      return {before, after: calls, textOwned, ancestorRejected, descendantRejected,
        released: !GraphdenRenderer.ownsTarget(host.firstChild), forged: GraphdenRenderer.ownsTarget(document.getElementById('native'))};
    });
    assert.deepEqual(result.before, ['component', 'component', 'privileged']);
    assert.deepEqual(result.after, ['component', 'component', 'privileged', 'component', 'privileged']);
    assert.equal(result.textOwned, true);
    assert.equal(result.ancestorRejected, true);
    assert.equal(result.descendantRejected, true);
    assert.equal(result.released, true);
    assert.equal(result.forged, false);
    console.log('PASS real DOM graph event isolation and ownership lifecycle');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
