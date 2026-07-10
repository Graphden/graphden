// Visual-regression tests for the Graphden editor.
//
// Captures screenshots at key UI states and diffs against committed
// baselines under tests/editor.spec.js-snapshots/. Run via
// `bb visual` (assert) or `bb visual-update` (refresh baselines).
//
// Cytoscape only mounts its canvas once a fn is selected (no
// canvas → no graph), so every test navigates to /#<fn-name> to
// pre-select a function via the URL hash mechanism.

const { test, expect } = require('@playwright/test');

const AUTH_TOKEN = process.env.AUTH_TOKEN || '';

test.beforeEach(async ({ page }) => {
  if (AUTH_TOKEN) {
    await page.addInitScript((token) => {
      try { localStorage.setItem('graphden.auth.password', token); } catch (_) {}
    }, AUTH_TOKEN);
  }
});

// Wait for the editor to finish first paint:
//  - Cytoscape canvas exists (means /api/graph/layout returned)
//  - One additional rAF so its first frame has been committed
async function waitForGraphRendered(page) {
  // Edges are SVG now; a rendered path means /api/graph/layout returned and drew.
  await page.waitForSelector('#edge-lines path', { state: 'attached', timeout: 20_000 });
  await page.evaluate(() => new Promise(requestAnimationFrame));
  await page.evaluate(() => new Promise(requestAnimationFrame));
}

async function setTheme(page, theme) {
  await page.evaluate((t) => {
    if (t === 'dark') document.body.classList.add('theme-dark');
    else document.body.classList.remove('theme-dark');
    try { localStorage.setItem('graphden.theme', t); } catch (_) {}
  }, theme);
  await page.evaluate(() => new Promise(requestAnimationFrame));
}

test.describe('Editor — visual baselines', () => {
  test('web-server loaded, light theme', async ({ page }) => {
    await page.goto('/#web-server');
    await waitForGraphRendered(page);
    await setTheme(page, 'light');
    await expect(page).toHaveScreenshot('01-web-server-light.png');
  });

  test('web-server loaded, dark theme', async ({ page }) => {
    await page.goto('/#web-server');
    await waitForGraphRendered(page);
    await setTheme(page, 'dark');
    await expect(page).toHaveScreenshot('02-web-server-dark.png');
  });

  // -- Provenance popovers -------------------------------------------------
  //
  // Two click-driven popovers anchored to ↳ glyphs:
  //   (a) `.arg-type-provenance` next to a type-chip → "Type narrowing"
  //       popover with 4-tier resolution + inheritance chain.
  //   (b) `.return-type-strip-provenance` on a fn-card's return-type strip
  //       → "Type rule" popover naming the base-fn whose :return-type-rule
  //       computed the return type, with the resolved input bindings.
  //
  // Functional (not visual) — assert DOM shape so future CSS tweaks
  // don't churn the baseline.

  test('type narrowing popover — opens and renders the resolution chain', async ({ page }) => {
    await page.goto('/#core.collections.assoc-fn');
    await waitForGraphRendered(page);

    // `:assoc-fn` narrows `:value` from `:any` to `[:fn …]` via a binding
    // type-override — one ↳ badge appears on the value's edge-label.
    const badge = page.locator('.arg-type-provenance').first();
    await expect(badge).toBeVisible();
    await expect(badge).toHaveAttribute(
      'title', /Narrowed at :assoc-fn .* click for full chain/);

    await badge.click();
    const popover = page.locator('.provenance-popover.visible');
    await expect(popover).toBeVisible();
    await expect(popover.locator('.provenance-popover-title'))
      .toHaveText('Type narrowing');

    // "Resolved via" surfaces the 4 tiers; the winning one is ✓-marked
    // and the slot tier names the originating base-fn (`:assoc`).
    const winningRow = popover.locator('.type-inline-resolution-active');
    await expect(winningRow).toContainText('Binding type-override');
    await expect(winningRow).toContainText('assoc-fn');
    await expect(popover).toContainText('Slot declaration');

    // Source-fn names render as clickable links — click `:assoc` and
    // verify the editor navigates to it and the popover dismisses.
    await popover.locator('.type-inline-resolution-link', { hasText: 'assoc' })
                 .last().click();
    await expect(page).toHaveURL(/#core\.collections\.assoc$/);
    await expect(page.locator('.provenance-popover.visible')).toHaveCount(0);
  });

  test('type-rule popover — return-type computed by base-fn rule', async ({ page }) => {
    // `:health-status` has primary-parent `:assoc-timestamp` which itself
    // descends from `:assoc` — the rule-owner walk should surface the
    // ↳ trigger pointing at `:assoc`.
    await page.goto('/#app.common.health-status');
    await waitForGraphRendered(page);

    const trigger = page.locator('.return-type-strip-provenance').first();
    await expect(trigger).toBeVisible();
    await expect(trigger).toHaveAttribute(
      'title', /Computed by :assoc's :return-type-rule/);

    await trigger.click();
    const popover = page.locator('.provenance-popover.visible');
    await expect(popover).toBeVisible();
    await expect(popover.locator('.provenance-popover-title'))
      .toHaveText('Type rule');
    await expect(popover.locator('.provenance-popover-intro'))
      .toContainText("Return type computed by");
    await expect(popover.locator('.provenance-popover-intro'))
      .toContainText("assoc");

    // The inputs section lists the bindings the rule consumed. Exact
    // count depends on the intermediate fn-def's slot set (:health-status
    // → :assoc-timestamp adds a :timestamp slot on top of :assoc's
    // :map / :key / :value), so don't pin the number — assert the
    // shape: header present and at least the :map / :key / :value
    // rows that every assoc-descended chain has.
    await expect(popover).toContainText('Inputs');
    const rowTexts = await popover.locator('.type-inline-resolution-row')
                                  .allTextContents();
    expect(rowTexts.some(t => t.includes('map'))).toBe(true);
    expect(rowTexts.some(t => t.includes('key'))).toBe(true);
    expect(rowTexts.some(t => t.includes('value'))).toBe(true);

    // Per-rule narrative line — Wave 2 added a one-sentence
    // interpretation of what the rule did, sandwiched between the
    // intro and the Inputs table. For an :assoc-descendant the
    // sentence names the literal key.
    const narrative = popover.locator('.provenance-popover-narrative');
    await expect(narrative).toBeVisible();
    await expect(narrative).toContainText('field');
  });


  test('inline ▸/▾ on a refinement chip — chain breadcrumb + kind tag', async ({ page }) => {
    // `:user-port` is a refinement: `[:refine :int [:and [:>= 1024] [:<= 65535]]]`.
    // Wave 2 added two surfaces here:
    //   - "Subtype chain" breadcrumb: `:user-port ⊂ :int ⊂ :numeric`
    //   - Kind tag in the header (REFINEMENT)
    // Drive the expansion programmatically via the helper — no need to
    // click through arg overlays to hit a refinement-typed chip.
    await page.goto('/#core.refinements.user-port');
    await waitForGraphRendered(page);

    const result = await page.evaluate(() => {
      const host = document.createElement('div');
      host.style.cssText = 'position:fixed;top:9999px;left:-9999px;';
      document.body.appendChild(host);
      renderInlineExpansionInto(host, 'user-port', '/visual-test',
                                { typeName: 'user-port' });
      const chainSteps = Array.from(
        host.querySelectorAll('.type-inline-refinement-chain-row > *'))
        .map(el => el.textContent);
      const kindTag = host.querySelector('.type-inline-header-kind');
      const html = host.outerHTML;
      host.remove();
      return {
        chainSteps,
        kindTagText: kindTag ? kindTag.textContent : null,
        hasChainSection: !!host.querySelector
          || html.includes('type-inline-refinement-chain'),
      };
    });

    // Chain breadcrumb must include the alias, the base int, and the
    // primitive super (:numeric) — three named steps joined by ⊂.
    expect(result.chainSteps.some(s => s.includes('user-port'))).toBe(true);
    expect(result.chainSteps.some(s => s.includes('int'))).toBe(true);
    expect(result.chainSteps.some(s => s.includes('numeric'))).toBe(true);
    expect(result.chainSteps.some(s => s.includes('⊂'))).toBe(true);
    // Kind tag must read "Refinement".
    expect(result.kindTagText).toBe('Refinement');
  });


  test('cycle indicator — recursive alias renders ↻ instead of looping', async ({ page }) => {
    // The cycle indicator fires when an inline expansion would
    // re-encounter an ancestor type. We don't have a self-recursive
    // alias in the standard registry, so trigger it directly by
    // seeding ancestorTypes with a name the panel would otherwise
    // expand.
    await page.goto('/#web-server');
    await waitForGraphRendered(page);

    const result = await page.evaluate(() => {
      const host = document.createElement('div');
      host.style.cssText = 'position:fixed;top:9999px;left:-9999px;';
      document.body.appendChild(host);
      // Render `[:list :tree]` where :tree is already on the ancestor
      // chain — the element row should show the cycle chip.
      renderInlineExpansionInto(host, ['list', 'tree'], '/cycle-test',
                                { ancestorTypes: new Set(['tree']) });
      const cycle = host.querySelector('.type-inline-chip-cycle');
      const result = {
        hasCycle: !!cycle,
        cycleText: cycle ? cycle.textContent : null,
        cycleTitle: cycle ? cycle.getAttribute('title') : null,
      };
      host.remove();
      return result;
    });
    expect(result.hasCycle).toBe(true);
    expect(result.cycleText).toContain('↻');
    expect(result.cycleText).toContain('tree');
    expect(result.cycleTitle).toContain('Recursive');
  });

  test('sidebar with namespaces expanded — verifies entity list rendering', async ({ page }) => {
    await page.goto('/#web-server');
    await waitForGraphRendered(page);
    await setTheme(page, 'light');
    // On narrow viewports the sidebar auto-collapses on first
    // visit. For the snapshot we always want it OPEN — that's the
    // surface we're testing. The floating expand button has
    // `opacity:0; pointer-events:none` while the sidebar is open
    // (so isVisible() can mis-read it); the authoritative source
    // is `body.sidebar-collapsed`.
    const isCollapsed = await page.evaluate(() =>
      document.body.classList.contains('sidebar-collapsed'));
    if (isCollapsed) {
      await page.locator('#sidebar-expand-floating').click();
      await page.waitForTimeout(250);  // slide-in animation
    }
    // Click any collapsed namespace arrow to expand it. We don't
    // care WHICH namespace — the snapshot tests rendering of
    // expanded children styling, not the specific contents.
    const arrow = page.locator('.ns-arrow.collapsed').first();
    if (await arrow.count()) {
      await arrow.click();
      await page.waitForTimeout(150);
    }
    // Crop to the sidebar so this snapshot is small + stable. The
    // graph canvas can shift by a pixel between runs and would
    // otherwise dominate the diff.
    const sidebar = page.locator('#side-menu');
    await expect(sidebar).toHaveScreenshot('03-sidebar-expanded.png');
  });
});
