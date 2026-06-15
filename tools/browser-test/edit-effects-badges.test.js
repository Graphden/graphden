// Effect-category badges on fn-overlays — DOM assertion that the
// editor renders one chip per :effects category, that the colour
// classes line up, and that pure fns DON'T get a strip at all.
//
// Run from this directory:  node edit-effects-badges.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, newContext} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium);
  console.log('effects-badges — chips render per :effects category');
  try {
    // web-server transitively depends on http-server (network, process),
    // env-required (env), read-resource (io), CRUD handlers (db),
    // current-time-ms (time). All six categories should land on the
    // web-server card. (:process was added to :http-server when the
    // Phase 1 service registry landed — it marks fns that spawn
    // supervised background work, the service-eligibility flag the
    // /api/entities/service create-guard reads.)
    await page.goto('http://localhost:9002/#web-server');
    await page.waitForTimeout(2500);

    const probe = await page.evaluate(() => {
      const overlay = Array.from(document.querySelectorAll('.node-overlay'))
        .find(el => (el.textContent || '').trim().startsWith('web-server'));
      if (!overlay) return {error: 'web-server overlay not found'};
      const strip = overlay.querySelector('.effects-strip');
      if (!strip) return {error: 'no effects-strip on web-server'};
      const chips = Array.from(strip.querySelectorAll('.effects-chip'))
        .map(c => ({
          text: c.textContent.trim(),
          // Each chip carries a category-specific class
          // `effects-chip-<tag>`.
          cls: Array.from(c.classList).filter(x => x.startsWith('effects-chip-')),
          drift: c.classList.contains('effects-chip-drift'),
          ghost: c.classList.contains('effects-chip-ghost')
        }));
      return {
        title: strip.title,
        chips
      };
    });
    assert(!probe.error, probe.error || 'probe ok');
    const tags = probe.chips.map(c => c.text).sort();
    assert(JSON.stringify(tags) === JSON.stringify(['db', 'env', 'io', 'network', 'process', 'time']),
           'web-server chips show all six categories: ' + JSON.stringify(tags));
    for (const c of probe.chips) {
      assert(c.cls.includes('effects-chip-' + c.text),
             'chip "' + c.text + '" has matching colour class');
      assert(!c.drift && !c.ghost,
             'web-server has no :expects-effects → no drift / ghost styling');
    }
    assert(/Effects:/.test(probe.title || ''),
           'strip title summarises effects: ' + JSON.stringify(probe.title));

    // ---------------------------------------------------------------
    // /health declares :expects-effects #{:time} AND computes #{:time}
    // — chip should be solid (no drift, no ghost).
    await page.goto('about:blank');
    await page.goto('http://localhost:9002/#health');
    await page.waitForTimeout(2500);
    const healthProbe = await page.evaluate(() => {
      const overlay = Array.from(document.querySelectorAll('.node-overlay'))
        .find(el => (el.textContent || '').trim().startsWith('health'));
      const strip = overlay && overlay.querySelector('.effects-strip');
      if (!strip) return {error: 'no effects-strip on health'};
      const chips = Array.from(strip.querySelectorAll('.effects-chip'))
        .map(c => ({
          text: c.textContent.trim(),
          drift: c.classList.contains('effects-chip-drift'),
          ghost: c.classList.contains('effects-chip-ghost')
        }));
      return {chips, title: strip.title};
    });
    assert(!healthProbe.error, healthProbe.error || 'health strip present');
    assert(healthProbe.chips.some(c => c.text === 'time' && !c.drift && !c.ghost),
           '/health time chip is solid (declared & computed agree)');
    assert(/Declared:/.test(healthProbe.title || ''),
           '/health strip title carries Declared: line');

    // ---------------------------------------------------------------
    // A pure fn — :add — has no :effects in the registry. For an
    // AUTHENTICATED viewer the strip still renders (it carries the
    // "declare effects…" affordance for adding an :expects-effects
    // contract on a previously-pure fn), but it has zero chips and
    // ONLY the edit-pencil. The "no strip on pure fns" behavior is
    // gated behind unauthenticated viewers — see the
    // `all.size > 0 || (effectsEditable && computed.length === 0)`
    // condition in `appendFnMetadataStrips`.
    await page.goto('about:blank');
    await page.goto('http://localhost:9002/#add');
    await page.waitForTimeout(2500);
    const pureProbe = await page.evaluate(() => {
      // Anchor on the root fn-card whose text starts with "add" and
      // contains the standard root affordances (→ return-type) so we
      // don't latch onto an arg-overlay that happens to share the
      // prefix (`:add-42` and friends leak in across smoke runs).
      const overlay = Array.from(document.querySelectorAll('.node-overlay'))
        .find(el => {
          const t = (el.textContent || '').trim();
          return t.startsWith('add') && t.includes('→')
                 && !el.classList.contains('placeholder-overlay');
        });
      if (!overlay) return {error: 'add overlay not found'};
      const strip = overlay.querySelector('.effects-strip');
      return {
        hasStrip: !!strip,
        chipCount: strip ? strip.querySelectorAll('.effects-chip').length : 0,
        hasEditPencil: !!strip?.querySelector('.effects-strip-edit'),
      };
    });
    assert(!pureProbe.error, pureProbe.error || 'add probe ok');
    assert(pureProbe.hasStrip,
           ':add (pure, authed) renders the strip as a "declare effects…" affordance');
    assert(pureProbe.chipCount === 0,
           ':add strip has zero chips (no effects to show, got '
           + pureProbe.chipCount + ')');
    assert(pureProbe.hasEditPencil,
           ':add strip carries the ✎ edit-pencil so admins can declare a contract');
  } finally {
    await browser.close();
  }
  console.log('effects-badges — PASS');
})().catch(e => {
  console.error('effects-badges — FAIL:', e.message);
  process.exit(1);
});
