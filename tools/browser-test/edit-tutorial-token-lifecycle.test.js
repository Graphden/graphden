// Real Account token loop, twice with the same label. Supply an existing
// browser session via GRAPHDEN_SESSION_COOKIE on a tenancy-enabled instance.
// No trace, HAR, video, raw response dumps or secret-bearing screenshots.
const {chromium} = require('playwright');
const {assert, newContext, BASE} = require('./edit-test-helpers');
const REQUIRED = process.env.GRAPHDEN_REQUIRE_TOKENS === '1';
const {waitTourTitle, clickTourButton, filterAndSelect, openAccountSettings,
  waitTourClosed} = require('./tutorial-tour-helpers');

async function tokens(page) {
  return page.evaluate(async () => {
    const response = await fetch('/api/my-tokens/list');
    if (!response.ok) return null;
    const rows = await response.json();
    return Array.isArray(rows) ? rows : null;
  });
}

(async () => {
  if (REQUIRED && !process.env.GRAPHDEN_SESSION_COOKIE) {
    throw new Error('Required token gate needs a browser account session');
  }
  const {browser, page} = await newContext(chromium);
  // Discard any inherited tracing before the one-time response exists.
  // newContext creates no HAR/video recording; coverage records source only.
  await page.context().tracing.stop();
  page.removeAllListeners('pageerror');
  page.removeAllListeners('console');
  let errors = 0;
  page.on('pageerror', () => { errors++; });
  page.on('dialog', dialog => { void dialog.accept(); });
  let finished = false;
  try {
    if (REQUIRED) {
      await page.waitForFunction(() => !!window.gdAccount?.id
        && typeof window.graphdenTenancyActive === 'function'
        && window.graphdenTenancyActive(), null, {timeout: 45000});
    }
    const before = await tokens(page);
    if (!before) {
      assert(!REQUIRED, 'required token listing is available to this account');
      finished = true;
      console.log('SKIP token lesson: authenticated account/token addon unavailable');
      return;
    }
    const baselineIds = before.map(row => row.id).sort();
    for (let cycle = 0; cycle < 2; cycle++) {
      await page.goto(BASE + '/?tutorial=42');
      await waitTourTitle(page, 'Select a readable function', 120000);
      await filterAndSelect(page, 'core.const', 'const');
      await waitTourTitle(page, 'Open Account');
      await openAccountSettings(page);
      await waitTourTitle(page, 'Open the creation form');
      await page.locator('#gd-acct-tok-new').click();
      await waitTourTitle(page, 'Name this token');
      await page.fill('#gd-acct-tok-label', 'tutorial-token');
      await waitTourTitle(page, 'Limit its scopes');
      await page.locator('#gd-acct-tok-scopes input[value="write"]').check();
      for (const scope of ['execute', 'merge', 'services', 'secrets', 'packages']) {
        await page.locator('#gd-acct-tok-scopes input[value="' + scope + '"]').uncheck();
      }
      await waitTourTitle(page, 'Set a short lifetime');
      await page.selectOption('#gd-acct-tok-ttl', '7');
      await waitTourTitle(page, 'Create this token');
      await page.locator('#gd-acct-mint-form button[onclick="gdAcctMintToken()"]').click();
      await waitTourTitle(page, 'Verify restricted access');
      const entry = await page.evaluate(() => {
        const row = _tourState.created.find(item => item.type === 'api-token');
        return row && {id: row.id, name: row.name, scopes: row.scopes,
          expires: row['expires-at'], hasSecret: Object.hasOwn(row, 'token') || Object.hasOwn(row, 'token-hash')};
      });
      assert(entry?.id && !baselineIds.includes(entry.id), 'exact newly created token UUID');
      assert(!entry.hasSecret && entry.scopes === 'write' && entry.expires, 'masked bounded receipt');
      assert(await page.locator('#gd-acct-toks [data-token-id="' + entry.id + '"]').isVisible(),
        'exact token is visible in the masked Account list');
      await page.locator('#gd-acct-tok-reveal [data-token-check]').click();
      await waitTourTitle(page, 'Revoke and verify');
      await page.locator('#gd-acct-toks [data-revoke-token="' + entry.id + '"]').click();
      await waitTourTitle(page, 'Continue with your client');
      const verdict = await page.evaluate(() => {
        const row = _tourState.created.find(item => item.type === 'api-token');
        return row && row.receipt === 'removed' && row['execute-denied'] && row['revoked-auth'];
      });
      assert(verdict, 'actual scope refusal and post-revoke authentication refusal');
      assert(await clickTourButton(page, 'Finish'), 'finish token loop');
      await waitTourClosed(page, 30000);
      assert(JSON.stringify((await tokens(page)).map(row => row.id).sort()) === JSON.stringify(baselineIds),
        'same-label cycle leaves every pre-existing token untouched and no new token');
    }
    assert(errors === 0, 'no browser exceptions');
    finished = true;
    console.log('PASS token lesson: two restricted/revoked same-label cycles; exact cleanup');
  } catch (_) {
    // Deliberately omit exception text: automation errors can quote DOM.
    process.exitCode = 1;
    console.error('FAIL token lesson walkthrough; no secret-bearing artifacts recorded');
  } finally {
    if (!finished) {
      await page.evaluate(async () => { if (_tourState) await _tourEnd(); }).catch(() => {});
      const remove = page.getByRole('button', {name: 'Delete them', exact: true});
      if (await remove.isVisible().catch(() => false)) await remove.click().catch(() => {});
    }
    await browser.close();
  }
})().catch(() => { console.error('Token lesson runner failed; details withheld'); process.exitCode = 1; });
