// Provenance popover e2e — click the `↳` badge next to a narrowed
// arg-type chip → 4-tier resolution chain renders.
//
// Coverage:
//   • Seed a fn parented to `:http-server` (its :port slot is
//     refined to `[:refine :int [:and [:>= 1] [:<= 65535]]]` →
//     guarantees a narrowing chain with at least one ancestor tier).
//   • Navigate, find the `↳` badge on the :port arg-overlay, click.
//   • Assert .provenance-popover.visible AND a "Resolved via" /
//     "Inheritance chain" section is rendered.
//   • Escape dismisses; aria-expanded on the badge flips to "false".
//
// Run from this directory:  node edit-provenance-popover.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, api, getEntities, newContext, deleteFnByName} =
  require('./edit-test-helpers');


const RUN_ID = '-' + process.pid + '-' + Date.now().toString(36);
const PROBE_FN = 'prov-popover-probe' + RUN_ID;


async function cleanup(page) {
  try { await deleteFnByName(page, PROBE_FN); } catch (_) {}
}


(async () => {
  const {browser, page} = await newContext(chromium);
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-provenance-popover — ↳ badge click → 4-tier resolution chain');

  try {
    await cleanup(page);

    // ===================================================================
    // Seed: probe parented to :http-server + an explicit type-override
    // on its :port slot. The override (port → user-port refinement)
    // is what triggers `getTypeNarrowingInfo` → `kind: 'override'`,
    // which is what renders the `↳` provenance badge.
    // ===================================================================
    const ents = await getEntities(page);
    const httpServer = ents.fns.find(
      (f) => f.name === 'http-server' && (f['parent-ids'] || []).length === 0);
    assert(httpServer, ':http-server baseline resolved');
    const userPort = ents.fns.find((f) => f.name === 'user-port');
    assert(userPort, ':user-port refinement type-row resolved');
    // :http-server has a :port slot; find its id via fn-slot junctions.
    const portSlotId = ents['fn-slots']
      .filter((fs) => fs['fn-id'] === httpServer.id)
      .map((fs) => fs['slot-id'])
      .find((sid) => {
        const slot = (ents.slots || []).find((s) => s.id === sid);
        return slot?.name === 'port';
      });
    assert(portSlotId, ':http-server.port slot resolved');

    await api(page, 'POST', '/api/entities/fn',
              'name=' + PROBE_FN + '&parent-ids=' + httpServer.id);
    const probe = (await getEntities(page)).fns.find(
      (f) => f.name === PROBE_FN);
    assert(probe, 'probe fn-def created');

    // Bind :port slot with a type-override → user-port. Need value=8080
    // for the type-check to accept the override (8080 is in
    // [1024..65535]).
    const bindResp = await api(page, 'POST', '/api/entities/binding',
                               'fn-id=' + probe.id
                               + '&slot-id=' + portSlotId
                               + '&type-override-fn-id=' + userPort.id
                               + '&value=8080');
    assert(JSON.stringify(bindResp).includes('created successfully'),
           'binding with type-override created: '
           + JSON.stringify(bindResp).slice(0, 200));

    // ===================================================================
    // Navigate; wait for the fn-card + at least one arg-overlay with
    // a provenance badge.
    // ===================================================================
    await page.goto('about:blank');
    await page.goto((process.env.GRAPHDEN_URL || 'http://localhost:9002')
                    + '/#' + PROBE_FN);
    await page.waitForFunction(
      () => graphReady()
            && !graph.animating
            && !!document.querySelector('.arg-type-provenance'),
      null,
      {timeout: 20000, polling: 100});

    // ===================================================================
    // Phase A: badge state pre-click.
    // ===================================================================
    const preClick = await page.evaluate(() => {
      const badges = Array.from(document.querySelectorAll('.arg-type-provenance'));
      const first = badges[0];
      return {
        badgeCount: badges.length,
        text: first?.textContent?.trim(),
        ariaExpanded: first?.getAttribute('aria-expanded'),
      };
    });
    assert(preClick.badgeCount >= 1,
           '≥ 1 ↳ provenance badge rendered: ' + preClick.badgeCount);
    assert(preClick.text === '↳',
           'badge glyph is ↳ (got ' + JSON.stringify(preClick.text) + ')');
    assert(preClick.ariaExpanded === 'false',
           'badge starts with aria-expanded="false"');

    // ===================================================================
    // Phase B: click the badge → provenance popover opens.
    // ===================================================================
    await page.evaluate(() => {
      const badge = document.querySelector('.arg-type-provenance');
      badge.click();
    });
    await page.waitForSelector('.provenance-popover.visible',
                               {timeout: 5000});

    const popoverState = await page.evaluate(() => {
      const pop = document.querySelector('.provenance-popover.visible');
      const badge = document.querySelector('.arg-type-provenance');
      return {
        visible: !!pop,
        ariaLabel: pop?.getAttribute('aria-label'),
        ariaExpanded: badge?.getAttribute('aria-expanded'),
        hasResolutionSection: !!pop?.querySelector('.resolution-section, .resolution-tiers')
                              || /resolved|inheritance|tier|chain/i
                                 .test(pop?.textContent || ''),
        bodyText: pop?.textContent?.slice(0, 300) || '',
      };
    });
    assert(popoverState.visible, '.provenance-popover.visible after click');
    assert(/provenance|narrowing/i.test(popoverState.ariaLabel || ''),
           'aria-label mentions provenance/narrowing: '
           + JSON.stringify(popoverState.ariaLabel));
    assert(popoverState.ariaExpanded === 'true',
           'badge aria-expanded flipped to "true" on open');
    assert(popoverState.hasResolutionSection,
           'popover renders a resolution section (heading or class): '
           + JSON.stringify(popoverState.bodyText));

    // ===================================================================
    // Phase C: Escape dismisses; aria-expanded flips back.
    // ===================================================================
    await page.keyboard.press('Escape');
    await page.waitForFunction(
      () => {
        const p = document.querySelector('.provenance-popover');
        return !p || !p.classList.contains('visible');
      },
      null,
      {timeout: 3000, polling: 50});
    const dismissed = await page.evaluate(() => {
      const pop = document.querySelector('.provenance-popover');
      const badge = document.querySelector('.arg-type-provenance');
      return {
        hidden: !pop || !pop.classList.contains('visible'),
        ariaExpanded: badge?.getAttribute('aria-expanded'),
      };
    });
    assert(dismissed.hidden, 'provenance popover dismissed by Escape');
    assert(dismissed.ariaExpanded === 'false',
           'badge aria-expanded back to "false" after dismiss');

    console.log('✓ provenance popover verified — click / 4-tier chain / dismiss');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await cleanup(page).catch(() => {});
    await browser.close();
  }
})();
