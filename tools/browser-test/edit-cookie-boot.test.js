// Cookie identity can arrive after DOMContentLoaded. Initial navigation and
// reload must each load tree/types once after that identity is known.
// Only the account probe is intercepted; graph and type responses are real.
// Run: GRAPHDEN_URL=http://localhost:<port> node edit-cookie-boot.test.js
const {chromium} = require('playwright');
const {assert, newContext, BASE} = require('./edit-test-helpers');

(async () => {
  const {browser, page} = await newContext(chromium, {boot: false});
  const counts = {tree: 0, types: 0};
  let releaseAuth;
  let authGate;
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname === '/api/graph/entities' && url.searchParams.get('scope') === 'tree') counts.tree++;
    if (url.pathname === '/api/types') counts.types++;
  });
  await page.route('**/auth/me', async route => {
    await authGate;
    await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({
      account: {id: 'test-cookie-boot', 'display-name': 'Cookie boot test'}, 'orgs?': false,
    })});
  });
  try {
    for (const reload of [false, true]) {
      counts.tree = 0;
      counts.types = 0;
      const arrived = page.waitForRequest(request => new URL(request.url()).pathname === '/auth/me', {timeout: 30000});
      authGate = new Promise(resolve => { releaseAuth = resolve; });
      if (reload) await page.reload({waitUntil: 'domcontentloaded'});
      else await page.goto(BASE + '/', {waitUntil: 'domcontentloaded'});
      await arrived;
      assert(counts.tree === 0 && counts.types === 0, 'graph boot waits for the cookie identity');
      releaseAuth();
      await page.waitForSelector('#entity-list .ns-header', {timeout: 60000});
      await page.evaluate(() => window.gdWaitForGraphReady());
      assert(await page.evaluate(() => isAuthenticated()), 'cookie identity enables authenticated editor affordances');
      assert(counts.tree === 1, 'one tree request per navigation, got ' + counts.tree);
      assert(counts.types === 1, 'one types request per navigation, got ' + counts.types);
    }
    console.log('PASS cookie boot and reload each fetch tree/types once');
  } finally {
    releaseAuth?.();
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
