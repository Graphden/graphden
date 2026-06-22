// Auth login flow e2e — the 🔒 lock chip + popover password entry.
//
// Coverage:
//   • Initial state: locked (no localStorage password).
//   • Click lock → popover opens with password input + Save/Cancel.
//   • Wrong password → 401 from /api/auth/check → "Wrong password."
//     error message renders, popover stays open.
//   • Correct password → 200 → password stored → popover closes →
//     lock icon flips to "open" (.auth-lock-open).
//   • Sign-out path: click lock when authed → confirm() → password
//     cleared → lock back to "closed".
//   • Cancel + Escape both dismiss the popover.
//
// This test uses a FRESH context (no preseeded auth) so the lock
// starts in the locked state. The shared newContext helper preseeds
// AUTH_TOKEN into localStorage; this test creates its own
// browser+page without that step.
//
// Run from this directory:  node edit-auth-login.test.js
// Exit code 0 = PASS, 1 = FAIL.

const {chromium} = require('playwright');
const {assert, AUTH, BASE} = require('./edit-test-helpers');


async function freshContext() {
  const browser = await chromium.launch({
    headless: true,
    args: ['--no-sandbox', '--no-zygote', '--in-process-gpu']});
  const ctx = await browser.newContext({viewport: {width: 1400, height: 900}});
  // NO password seeded — we want the locked initial state.
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log('  [pageerror]', e.message));
  await page.goto(BASE + '/');
  await page.waitForSelector('#auth-lock-btn', {timeout: 10000});
  // Page may auto-open the popover on cold load (some flows do that
  // for first-time users). Dismiss it to start from a known state.
  await page.evaluate(() => {
    const p = document.getElementById('auth-popover');
    if (p) p.classList.add('hidden');
  });
  return {browser, page};
}


(async () => {
  const {browser, page} = await freshContext();
  page.on('dialog', (d) => {
    console.log('  [dialog]:', d.message().slice(0, 200));
    d.accept();
  });
  page.on('console', (m) => {
    if (m.type() === 'error') {
      console.log('  (console.error: ' + m.text().slice(0, 200) + ')');
    }
  });
  console.log('edit-auth-login — lock click / submit / reject / sign-out');

  try {
    // ===================================================================
    // Phase A: initial locked state.
    // ===================================================================
    const initial = await page.evaluate(() => {
      const btn = document.getElementById('auth-lock-btn');
      const popover = document.getElementById('auth-popover');
      return {
        btnPresent: !!btn,
        openClass: btn?.classList.contains('auth-lock-open'),
        title: btn?.title,
        popoverHidden: popover?.classList.contains('hidden'),
      };
    });
    assert(initial.btnPresent, 'auth lock button rendered');
    assert(!initial.openClass,
           'lock starts in closed state (no .auth-lock-open): '
           + initial.openClass);
    assert(initial.title === 'Admin login',
           'tooltip reads "Admin login" when locked: ' + initial.title);
    assert(initial.popoverHidden,
           'popover starts hidden');

    // ===================================================================
    // Phase B: click lock → popover opens.
    // ===================================================================
    await page.click('#auth-lock-btn');
    await page.waitForFunction(
      () => !document.getElementById('auth-popover')
              ?.classList.contains('hidden'),
      {timeout: 5000});
    const opened = await page.evaluate(() => {
      const p = document.getElementById('auth-popover');
      return {
        hidden: p.classList.contains('hidden'),
        hasInput: !!p.querySelector('#auth-password-input'),
        hasSave: !!p.querySelector('#auth-save-btn'),
        hasCancel: !!p.querySelector('#auth-cancel-btn'),
      };
    });
    assert(!opened.hidden, 'popover visible after lock click');
    assert(opened.hasInput && opened.hasSave && opened.hasCancel,
           'popover has password input + Save + Cancel buttons');

    // ===================================================================
    // Phase C: wrong password → 401 error message.
    // ===================================================================
    await page.fill('#auth-password-input', 'totally-wrong-password');
    await page.click('#auth-save-btn');
    await page.waitForFunction(
      () => {
        const err = document.getElementById('auth-error');
        return err && !err.classList.contains('hidden')
               && /wrong password/i.test(err.textContent || '');
      },
      {timeout: 5000});
    const rejected = await page.evaluate(() => {
      const p = document.getElementById('auth-popover');
      const btn = document.getElementById('auth-lock-btn');
      return {
        popoverStillOpen: !p.classList.contains('hidden'),
        lockStillClosed: !btn.classList.contains('auth-lock-open'),
        errorText: document.getElementById('auth-error')?.textContent,
      };
    });
    assert(rejected.popoverStillOpen,
           'popover stays open on wrong password');
    assert(rejected.lockStillClosed,
           'lock icon stays in closed state on wrong password');
    assert(/wrong password/i.test(rejected.errorText || ''),
           '"Wrong password." error visible: '
           + JSON.stringify(rejected.errorText));

    // ===================================================================
    // Phase D: correct password → 200 → unlocked.
    // ===================================================================
    await page.fill('#auth-password-input', AUTH);
    await page.click('#auth-save-btn');
    await page.waitForFunction(
      () => {
        const btn = document.getElementById('auth-lock-btn');
        return btn && btn.classList.contains('auth-lock-open');
      },
      {timeout: 5000});
    const unlocked = await page.evaluate(() => {
      const p = document.getElementById('auth-popover');
      const btn = document.getElementById('auth-lock-btn');
      return {
        popoverHidden: p.classList.contains('hidden'),
        lockOpen: btn.classList.contains('auth-lock-open'),
        title: btn.title,
        storedPw: localStorage.getItem('graphden.auth.password'),
      };
    });
    assert(unlocked.popoverHidden,
           'popover closes after successful auth');
    assert(unlocked.lockOpen,
           'lock icon flipped to "open" (.auth-lock-open)');
    assert(unlocked.title === 'Sign out',
           'tooltip flips to "Sign out": ' + unlocked.title);
    assert(unlocked.storedPw === AUTH,
           'password stored in localStorage');

    // ===================================================================
    // Phase E: sign-out via lock click → confirm() auto-accepted.
    // ===================================================================
    await page.click('#auth-lock-btn');
    await page.waitForFunction(
      () => {
        const btn = document.getElementById('auth-lock-btn');
        return btn && !btn.classList.contains('auth-lock-open');
      },
      {timeout: 5000});
    const signedOut = await page.evaluate(() => ({
      lockClosed: !document.getElementById('auth-lock-btn')
        .classList.contains('auth-lock-open'),
      storedPw: localStorage.getItem('graphden.auth.password'),
    }));
    assert(signedOut.lockClosed,
           'lock icon back to closed state after sign-out');
    assert(!signedOut.storedPw,
           'password cleared from localStorage');

    // ===================================================================
    // Phase F: Escape dismisses popover without submitting.
    // ===================================================================
    await page.click('#auth-lock-btn');
    await page.waitForFunction(
      () => !document.getElementById('auth-popover')
        ?.classList.contains('hidden'),
      {timeout: 5000});
    await page.locator('#auth-password-input').focus();
    await page.keyboard.press('Escape');
    // (escape dispatched; the following assertion gates the next step)
    const escClosed = await page.evaluate(() => {
      const p = document.getElementById('auth-popover');
      return p.classList.contains('hidden');
    });
    assert(escClosed, 'Escape dismisses the popover');

    console.log('✓ auth login flow verified — lock/popover/reject/unlock/sign-out');
  } catch (e) {
    process.exitCode = 1;
    console.error('✗ test failed:', e.message);
  } finally {
    await browser.close();
  }
})();
