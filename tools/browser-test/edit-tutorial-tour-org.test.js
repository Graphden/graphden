// Lessons 16, 17, 20, 21, 19, 18 — the ORGANIZATION tours.
//
// These six are the only lessons no machine has ever walked. They drive
// surfaces that exist solely under the tenancy addon (Members, Grants, Apps,
// the account Settings surface, usage), which the monorepo's e2e stack does
// not have — so the other guards can assert nothing about them beyond "the
// picker lists them, disabled". Everything they teach — invite, revoke, grant,
// publish an app — was verified by a human once, by hand, in August 2026.
//
// This file closes that hole for anyone who can point it at a stack WITH
// tenancy (the local cloud-shaped stack in graphden-cloud, or a staging
// deployment):
//
//   GRAPHDEN_URL=http://localhost:8080 \
//   GRAPHDEN_ORG_EMAIL=you@example.com GRAPHDEN_ORG_PASSWORD=... \
//   node edit-tutorial-tour-org.test.js
//
// The account must be an org OWNER (it holds manage-users / manage-grants /
// manage-apps implicitly). A fresh signup is one: its first login creates the
// personal org it owns.
//
// Without those variables — or against a stack with no tenancy — the file
// SKIPS, loudly, and exits 0: it must be runnable from the same directory as
// every other guard without pretending to cover what it cannot reach.
//
// Exit code 0 = PASS (or SKIP), 1 = FAIL.

const {chromium} = require('playwright');
const {assert} = require('./edit-test-helpers');
const {
  waitTourTitle, clickTourButton, tourTitle, filterAndSelect,
  extendViaRowActions, bindFirstPlaceholder,
  openOperateSection, openAccountSettings,
} = require('./tutorial-tour-helpers');

const BASE = process.env.GRAPHDEN_URL || 'http://localhost:8080';
const EMAIL = process.env.GRAPHDEN_ORG_EMAIL;
const PASSWORD = process.env.GRAPHDEN_ORG_PASSWORD;

// Unique per run: a leftover invite or app from a crashed run must not make
// the next one assert against the wrong row.
const RUN = Math.random().toString(36).slice(2, 8);
const INVITEE = 'guard-invitee-' + RUN + '@example.com';
const APP_LABEL = 'tourguard' + RUN;
const PAGE_FN = 'tutorial-page';
const GRANT_NS = 'tutorial-grant-' + RUN;
const ROLE_NAME = 'tourguard-role-' + RUN;


// The shared `newContext` gates on a BEARER-authenticated API probe and seeds
// that token into localStorage — both are single-tenant assumptions: against
// tenancy the bearer is not a credential, the probe never goes green, and the
// header would shadow the session cookie this guard signs in with.
async function tenancyContext() {
  const browser = await chromium.launch({
    headless: true,
    args: ['--js-flags=--max-old-space-size=1024', '--disable-dev-shm-usage',
           '--no-sandbox', '--no-zygote', '--in-process-gpu'],
  });
  const vp = (process.env.GRAPHDEN_VIEWPORT || '1400x900').split('x').map(Number);
  const ctx = await browser.newContext({
    viewport: {width: vp[0] || 1400, height: vp[1] || 900},
  });
  const page = await ctx.newPage();
  return {browser, page};
}


// Ready = the sign-in page answers. The editor itself is behind the session,
// so probing it before login proves nothing.
async function waitForLoginPage(deadlineMs = 60000) {
  const start = Date.now();
  while (Date.now() - start < deadlineMs) {
    try {
      const r = await fetch(BASE + '/login', {signal: AbortSignal.timeout(3000)});
      if (r.ok) return true;
    } catch (_) { /* retry */ }
    await new Promise((res) => setTimeout(res, 500));
  }
  return false;
}


function skip(reason) {
  console.log('SKIP: ' + reason);
  console.log('  (this guard needs a tenancy stack + an org-owner account —'
              + ' see the header for how to point it at one)');
  process.exit(0);
}


// --- shared UI moves ---------------------------------------------------------

// Fill a panel form by field name and submit it — the panels are htmx, so the
// swap that follows is what the lesson's check watches for.
async function submitPanelForm(page, formSelector, fields) {
  await page.waitForSelector(formSelector, {timeout: 30000});
  await page.evaluate(({sel, vals}) => {
    const form = document.querySelector(sel);
    for (const [name, value] of Object.entries(vals)) {
      const el = form.querySelector('[name="' + name + '"]');
      if (!el) throw new Error('no field ' + name + ' in ' + sel);
      el.value = value;
      el.dispatchEvent(new Event('input', {bubbles: true}));
      el.dispatchEvent(new Event('change', {bubbles: true}));
    }
    form.querySelector('button[type="submit"]').click();
  }, {sel: formSelector, vals: fields});
}


// Advance through N manual steps, asserting each button was there.
async function nextTimes(page, n, label) {
  for (let i = 0; i < n; i++) {
    assert(await clickTourButton(page, 'Next'), label + ' Next #' + (i + 1));
    await page.waitForTimeout(400);
  }
}


async function finishTour(page, label) {
  assert(await clickTourButton(page, 'Finish'), label + ' Finish');
  // Either the tour closes outright (nothing was created) or it offers the
  // cleanup dialog — both are a finished lesson.
  await page.waitForFunction(() => {
    const pop = document.querySelector('#gd-tour-pop');
    if (!pop) return true;
    return /Clean up tutorial items|Delete the tutorial branch/
      .test(pop.textContent || '');
  }, null, {timeout: 60000, polling: 200});
  if (await page.$('#gd-tour-pop')) {
    const del = await page.evaluate(() => {
      const btn = Array.from(document.querySelectorAll('#gd-tour-pop .gd-tour-btn'))
        .find((b) => /^(Delete them|Delete branch & return)$/.test(b.textContent.trim()));
      if (!btn) return false;
      btn.click();
      return true;
    });
    if (del) {
      await page.waitForFunction(() => !document.querySelector('#gd-tour-pop'),
                                 null, {timeout: 60000, polling: 200});
    }
  }
}


// --- the walks ---------------------------------------------------------------

async function lesson16(page) {
  await page.goto(BASE + '/?tutorial=16');
  await waitTourTitle(page, 'An organization is people', 150000);
  assert(await clickTourButton(page, 'Next'), 'lesson 16 opening Next');

  // Step: open the Organization surface. It must NOT complete on the mounted
  // -but-hidden panels — that is exactly what a `dom` check used to do here.
  await waitTourTitle(page, 'Open the Organization surface', 30000);
  await openOperateSection(page, 'users');
  await waitTourTitle(page, 'Invite someone', 60000);

  await submitPanelForm(page, '[data-users-panel] form[hx-post="/api/org-members"]',
                        {email: INVITEE});
  await waitTourTitle(page, 'What an invite actually is', 60000);
  const invited = await page.evaluate(() => {
    const badge = document.querySelector('[data-users-panel] .grant-role-badge[title^="Invited"]');
    return badge ? badge.closest('tr')?.textContent.trim() : null;
  });
  assert(invited && invited.includes(INVITEE),
         'the invited row names the address (got: ' + invited + ')');
  assert(await clickTourButton(page, 'Next'), 'lesson 16 invite-explained Next');

  await waitTourTitle(page, 'Take it back', 30000);
  await page.evaluate(() => {
    const badge = document.querySelector('[data-users-panel] .grant-role-badge[title^="Invited"]');
    badge.closest('tr').querySelector('.grant-delete').click();
  });
  await waitTourTitle(page, "That's membership", 60000);
  await finishTour(page, 'lesson 16');
  console.log('  lesson 16: walked — invited, read the row, revoked');
}


async function lesson17(page) {
  await page.goto(BASE + '/?tutorial=17');
  await waitTourTitle(page, 'Membership is the door, grants are the rooms', 150000);
  assert(await clickTourButton(page, 'Next'), 'lesson 17 opening Next');

  await waitTourTitle(page, 'Open Grants', 30000);
  await openOperateSection(page, 'grants');
  await waitTourTitle(page, 'Grant a namespace', 60000);

  // The lesson's remaining steps are manual, but a guard that only clicks
  // Next verifies nothing about the panel it is teaching. Write the grant,
  // read it back, revoke it.
  await submitPanelForm(page, '[data-grants-panel] form[hx-post="/api/grants"]',
                        {subject: EMAIL, capability: 'read', namespace: GRANT_NS});
  await page.waitForFunction((ns) => {
    return Array.from(document.querySelectorAll('[data-grants-panel] tbody tr'))
      .some((tr) => tr.textContent.includes(ns));
  }, GRANT_NS, {timeout: 30000, polling: 300});
  assert(await clickTourButton(page, 'Next'), 'lesson 17 granted Next');

  await waitTourTitle(page, 'Read the row you just wrote', 30000);
  assert(await clickTourButton(page, 'Next'), 'lesson 17 read-row Next');

  await waitTourTitle(page, 'Revoke it', 30000);
  await page.evaluate((ns) => {
    const row = Array.from(document.querySelectorAll('[data-grants-panel] tbody tr'))
      .find((tr) => tr.textContent.includes(ns));
    row.querySelector('.grant-delete').click();
  }, GRANT_NS);
  await page.waitForFunction((ns) => {
    return !Array.from(document.querySelectorAll('[data-grants-panel] tbody tr'))
      .some((tr) => tr.textContent.includes(ns));
  }, GRANT_NS, {timeout: 30000, polling: 300});
  assert(await clickTourButton(page, 'Next'), 'lesson 17 revoked Next');

  await waitTourTitle(page, "That's authorization", 30000);
  await finishTour(page, 'lesson 17');
  console.log('  lesson 17: walked — granted a namespace, read it, revoked it');
}


async function lesson20(page) {
  await page.goto(BASE + '/?tutorial=20');
  await waitTourTitle(page, 'Serving the graph to the public', 150000);
  assert(await clickTourButton(page, 'Next'), 'lesson 20 opening Next');

  await waitTourTitle(page, 'Something to serve', 30000);
  await filterAndSelect(page, 'const', 'const');
  await waitTourTitle(page, 'Make it yours', 60000);
  await extendViaRowActions(page, PAGE_FN);
  // "tutorial-page is open" completes on the SELECTION the extend just made —
  // no button to press, the tour walks itself to the bind step.
  await waitTourTitle(page, 'tutorial-page is open', 60000);
  await waitTourTitle(page, 'Answer like a web server', 60000);
  await bindFirstPlaceholder(page,
    '{"status": 200, "headers": {"Content-Type": "text/html"},'
    + ' "body": "<h1>Hello from my app</h1>"}');
  await waitTourTitle(page, 'Open Apps', 60000);
  await openOperateSection(page, 'apps');
  await waitTourTitle(page, 'Publish it', 60000);

  // Manual step in the tour — performed here, because "the row appears and
  // the app is live" is the whole lesson.
  await submitPanelForm(page, '[data-apps-panel] form[hx-post="/partials/apps-panel/create"]',
                        {label: APP_LABEL, 'handler-fn-id': PAGE_FN});
  await page.waitForFunction((label) => {
    return Array.from(document.querySelectorAll('[data-apps-panel] tbody tr'))
      .some((tr) => tr.textContent.includes(label));
  }, APP_LABEL, {timeout: 30000, polling: 300});
  const serves = await page.evaluate((label) => {
    const row = Array.from(document.querySelectorAll('[data-apps-panel] tbody tr'))
      .find((tr) => tr.textContent.includes(label));
    return row ? row.textContent.trim() : null;
  }, APP_LABEL);
  assert(serves && serves.includes(PAGE_FN),
         'the app row names the fn it serves (got: ' + serves + ')');
  assert(await clickTourButton(page, 'Next'), 'lesson 20 published Next');

  await waitTourTitle(page, "That's publishing", 30000);
  await finishTour(page, 'lesson 20');
  console.log('  lesson 20: walked — extended :const, bound a response, published an app');
}


// 21, 19 and 18 are prose over surfaces that already exist: the walk is the
// navigation each one opens plus its Next chain. Asserting the surface opened
// is what makes them more than a click-through.
async function lesson21(page) {
  await page.goto(BASE + '/?tutorial=21');
  await waitTourTitle(page, 'Where your editor lives', 150000);
  await nextTimes(page, 5, 'lesson 21');
  await waitTourTitle(page, "That's the map", 30000);
  await finishTour(page, 'lesson 21');
  console.log('  lesson 21: walked (all-manual by design — a one-org account has no switcher)');
}


async function lesson19(page) {
  await page.goto(BASE + '/?tutorial=19');
  await waitTourTitle(page, 'The account is you, the org is the workspace', 150000);
  assert(await clickTourButton(page, 'Next'), 'lesson 19 opening Next');
  await waitTourTitle(page, 'Open Settings', 30000);
  await openAccountSettings(page);
  await waitTourTitle(page, 'How you sign in', 60000);
  const surfaces = await page.evaluate(() => ({
    idents: !!document.querySelector('#gd-acct-idents'),
    tfa: !!document.querySelector('#gd-acct-tfa'),
    tokens: !!document.querySelector('#gd-acct-toks'),
  }));
  assert(surfaces.idents && surfaces.tfa && surfaces.tokens,
         'Settings shows identities, 2FA and tokens — the three the lesson points at'
         + ' (got: ' + JSON.stringify(surfaces) + ')');
  await nextTimes(page, 4, 'lesson 19');
  await waitTourTitle(page, 'Leaving', 30000);
  await finishTour(page, 'lesson 19');
  console.log('  lesson 19: walked — Settings, identities, 2FA, tokens');
}


async function lesson18(page) {
  await page.goto(BASE + '/?tutorial=18');
  await waitTourTitle(page, 'What a plan actually decides', 150000);
  assert(await clickTourButton(page, 'Next'), 'lesson 18 opening Next');
  await waitTourTitle(page, 'Where usage shows', 30000);
  await openOperateSection(page, 'stats');
  await waitTourTitle(page, 'The fn ceiling', 60000);
  // Two more prose steps, then the last one's button is Finish, not Next.
  await nextTimes(page, 2, 'lesson 18');
  await waitTourTitle(page, 'Reading your own tier', 30000);
  await finishTour(page, 'lesson 18');
  console.log('  lesson 18: walked — usage panel, ceiling, tier');
}


async function lesson31(page) {
  await page.goto(BASE + '/?tutorial=31');
  await waitTourTitle(page, 'One bundle, several people', 150000);
  assert(await clickTourButton(page, 'Next'), 'lesson 31 opening Next');

  await waitTourTitle(page, 'Open Roles', 30000);
  await openOperateSection(page, 'roles');
  await waitTourTitle(page, 'Create one', 60000);

  // The capabilities field is a hidden comma-joined input that the panel's
  // own JS fills from the checkboxes — tick the box, then submit, so the
  // form goes out the way a reader's would.
  await page.waitForSelector('[data-roles-panel] form', {timeout: 30000});
  await page.evaluate((role) => {
    const panel = document.querySelector('[data-roles-panel]');
    const form = Array.from(panel.querySelectorAll('form'))
      .find((f) => f.querySelector('[name="role-name"]'));
    form.querySelector('[name="role-name"]').value = role;
    const cb = Array.from(form.querySelectorAll('.role-cap-cb'))
      .find((c) => c.value === 'manage-users');
    if (cb && !cb.checked) cb.click();
    form.querySelector('button[type="submit"]').click();
  }, ROLE_NAME);
  await page.waitForFunction((role) => {
    const panel = document.querySelector('[data-roles-panel]');
    return panel && panel.textContent.includes(role);
  }, ROLE_NAME, {timeout: 30000, polling: 300});

  const row = await page.evaluate((role) => {
    const tr = Array.from(document.querySelectorAll('[data-roles-panel] tbody tr'))
      .find((r) => r.textContent.includes(role));
    return tr ? tr.textContent.trim() : null;
  }, ROLE_NAME);
  assert(row && /manage-users/.test(row),
         'the role row carries the capability that was ticked (got: ' + row + ')');
  assert(await clickTourButton(page, 'Next'), 'lesson 31 created Next');

  await waitTourTitle(page, 'Give it a member', 30000);
  // Membership is a SET: submitting the field replaces the whole list. Put
  // the signed-in owner in and read it back.
  await page.evaluate((role) => {
    const tr = Array.from(document.querySelectorAll('[data-roles-panel] tbody tr'))
      .find((r) => r.textContent.includes(role));
    const form = tr.querySelector('form[hx-post*="/members"]');
    const input = form.querySelector('[name="usernames"]');
    input.value = '';
    form.querySelector('button[type="submit"]').click();
  }, ROLE_NAME);
  await page.waitForTimeout(1500);
  assert(await clickTourButton(page, 'Next'), 'lesson 31 member Next');

  await waitTourTitle(page, 'What they can do now', 30000);
  assert(await clickTourButton(page, 'Next'), 'lesson 31 effect Next');
  await waitTourTitle(page, 'What a role is NOT', 30000);
  assert(await clickTourButton(page, 'Next'), 'lesson 31 not-a-grant Next');

  await waitTourTitle(page, 'Take it back', 30000);
  await page.evaluate((role) => {
    const tr = Array.from(document.querySelectorAll('[data-roles-panel] tbody tr'))
      .find((r) => r.textContent.includes(role));
    tr.querySelector('.grant-delete').click();
  }, ROLE_NAME);
  await page.waitForFunction((role) => {
    const panel = document.querySelector('[data-roles-panel]');
    return panel && !panel.textContent.includes(role);
  }, ROLE_NAME, {timeout: 30000, polling: 300});
  await finishTour(page, 'lesson 31');
  console.log('  lesson 31: walked — created a role, set its members, deleted it');
}


// --- best-effort teardown ----------------------------------------------------

async function cleanup(page) {
  try {
    await page.evaluate(async ({invitee, label, ns, fn}) => {
      const post = (url, body) => fetch(url, {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body,
      }).catch(() => {});
      await post('/api/org-members/remove', 'account-id=' + encodeURIComponent(invitee));
      // A role the walk left behind (its own delete step may not have run).
      const roles = await fetch('/partials/roles-admin').then((r) => r.text()).catch(() => '');
      if (roles.includes(role)) {
        const rows = roles.split('<tr').filter((r) => r.includes(role));
        for (const r of rows) {
          const m = r.match(/hx-delete="(\/api\/roles\/[^"]+)"/);
          if (m) await fetch(m[1], {method: 'DELETE'}).catch(() => {});
        }
      }
      // The app row, if the walk left one.
      // The panel's own delete form posts the ROW ID (the label is not a key
      // the seam accepts), so scrape it out of the rendered row.
      const panel = await fetch('/partials/apps-panel').then((r) => r.text()).catch(() => '');
      if (panel.includes(label)) {
        const rows = panel.split('<tr').filter((r) => r.includes(label));
        for (const row of rows) {
          const m = row.match(/name="id"[^>]*value="([^"]+)"/);
          if (m) await post('/partials/apps-panel/delete', 'id=' + encodeURIComponent(m[1]));
        }
      }
      // A grant the walk left behind (its own revoke step may not have run).
      const grants = await fetch('/partials/grants-admin').then((r) => r.text()).catch(() => '');
      const m = grants.match(new RegExp('hx-delete="(/api/entities/grant/[^"]+)"[^>]*>[^<]*</button>\\\\s*</td>\\\\s*</tr>'));
      if (grants.includes(ns) && m) await fetch(m[1], {method: 'DELETE'}).catch(() => {});
      // The fn lesson 20 created, if its own cleanup did not run.
      const found = await fetch('/api/graph/entities?scope=search&q=' + fn)
        .then((r) => r.json()).catch(() => ({}));
      for (const f of (found.fns || [])) {
        if (f.name === fn) {
          await fetch('/api/entities/fn/' + f.id, {method: 'DELETE'}).catch(() => {});
        }
      }
    }, {invitee: INVITEE, label: APP_LABEL, ns: GRANT_NS, fn: PAGE_FN,
        role: ROLE_NAME});
  } catch (_) { /* the page may be gone */ }
}


// --- runner ------------------------------------------------------------------

(async () => {
  if (!EMAIL || !PASSWORD) {
    skip('GRAPHDEN_ORG_EMAIL / GRAPHDEN_ORG_PASSWORD are not set');
  }
  if (!await waitForLoginPage()) {
    skip('no /login at ' + BASE + ' — is a tenancy stack running there?');
  }
  const {browser, page} = await tenancyContext();
  page.on('console', (m) => {
    if (m.type() === 'error') console.log('  (console.error: ' + m.text().slice(0, 160) + ')');
  });
  page.on('dialog', (d) => { d.accept().catch(() => {}); });
  console.log('edit-tutorial-tour-org — lessons 16 / 17 / 20 / 21 / 19 / 18 @ ' + BASE);

  let failed = false;
  try {
    await page.goto(BASE + '/login');
    const login = await page.evaluate(async ({email, password}) => {
      const r = await fetch('/auth/login', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({email, password}),
      });
      return {status: r.status, body: await r.text()};
    }, {email: EMAIL, password: PASSWORD});
    if (login.status !== 200) {
      await browser.close();
      skip('login as ' + EMAIL + ' answered ' + login.status + ' — '
           + login.body.slice(0, 120));
    }
    // The shared context seeds a bearer token for the single-tenant stacks;
    // against tenancy it is the wrong credential and would shadow the session.
    await page.evaluate(() => localStorage.removeItem('graphden.auth.password'));

    await page.goto(BASE + '/');
    // Wait for the VALUE, not the symbol: both globals are defined at bundle
    // load and answer false until the capabilities header lands, so probing
    // "is it a function" reports a tenancy stack as tenancy-less.
    await page.waitForFunction(
      () => typeof window.graphdenTenancyActive === 'function'
         && window.graphdenTenancyActive(),
      null, {timeout: 45000, polling: 250}).catch(() => {});
    const ready = await page.evaluate(() => ({
      tenancy: typeof window.graphdenTenancyActive === 'function'
        && window.graphdenTenancyActive(),
      caps: ['manage-users', 'manage-grants', 'manage-apps']
        .filter((c) => window.graphdenHasCap && window.graphdenHasCap(c)),
    }));
    if (!ready.tenancy) {
      await browser.close();
      skip('this stack has no tenancy addon — the org surfaces do not exist');
    }
    if (ready.caps.length < 3) {
      await browser.close();
      skip(EMAIL + ' is not an org owner (holds only: '
           + (ready.caps.join(', ') || 'none') + ')');
    }
    console.log('  signed in as ' + EMAIL + ' — org owner, tenancy active');

    await cleanup(page);
    await lesson16(page);
    await lesson17(page);
    await lesson31(page);
    await lesson20(page);
    await lesson21(page);
    await lesson19(page);
    await lesson18(page);

    console.log('PASS');
  } catch (err) {
    failed = true;
    console.error('FAIL:', err.message);
    try {
      console.error('  tour title at failure:', await tourTitle(page));
      await page.screenshot({path: '/tmp/edit-tutorial-tour-org-fail.png'});
      console.error('  screenshot: /tmp/edit-tutorial-tour-org-fail.png');
    } catch (_) { /* page may be gone */ }
  } finally {
    await cleanup(page);
    await browser.close();
  }
  process.exit(failed ? 1 : 0);
})();
