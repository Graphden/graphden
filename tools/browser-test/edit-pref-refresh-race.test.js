// A preferences GET begun during a PUT may carry an older server snapshot.
const {chromium} = require('playwright');
const {assert, newContext, waitForServerHealthy, BASE} = require('./edit-test-helpers');

(async () => {
  await waitForServerHealthy();
  const {browser, page} = await newContext(chromium, {boot: false});
  try {
    await page.goto(BASE + '/');
    await page.waitForFunction(() => gdPrefsReady && gdPrefOwner !== null);
    const result = await page.evaluate(async () => {
      const original = window.authFetch;
      const value = {source: null, payload: {bindings: {'graph-fit': {keys: 'p q', leader: true}}}};
      let settlePut;
      let settleGet;
      const owner = window.gdPrefOwner;
      try {
        window.authFetch = (url, options) => {
          if (url === API.api_prefs_key('keymap') && options?.method === 'PUT')
            return new Promise(resolve => { settlePut = resolve; });
          if (url === API.api_prefs && !options)
            return new Promise(resolve => { settleGet = resolve; });
          return original(url, options);
        };
        const write = gdPrefWrite('keymap', value);
        const refresh = gdPrefsRefresh();
        settlePut({ok: true});
        await write;
        settleGet({ok: true, headers: {get: () => owner}, json: async () => ({keymap: null})});
        await refresh;
        const whilePending = gdPrefRead('keymap');
        window.authFetch = (url, options) => url === API.api_prefs && !options
          ? Promise.resolve({ok: true, headers: {get: () => owner}, json: async () => ({keymap: null})})
          : original(url, options);
        await gdPrefsRefresh();
        return {whilePending, afterSettled: gdPrefRead('keymap'), value};
      } finally { window.authFetch = original; }
    });
    assert(JSON.stringify(result.whilePending) === JSON.stringify(result.value),
      'stale GET does not erase a preference whose PUT was in flight when GET began');
    assert(result.afterSettled === null, 'a later GET can replace the local value with server truth');
    console.log('PASS preference refresh preserves in-flight theme/keymap writes');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
