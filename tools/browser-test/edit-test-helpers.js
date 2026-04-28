// Shared helpers for the editor-edit e2e suite. Each promoted test
// file requires this to get a small assertion surface without
// pulling in a full JS test framework — the suite stays runnable
// with plain `node`.

function assert(cond, msg) {
  if (!cond) {
    process.stderr.write('  ✗ ' + msg + '\n');
    process.exitCode = 1;
    throw new Error('assertion failed: ' + msg);
  }
  process.stdout.write('  ✓ ' + msg + '\n');
}

function deepEqual(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

const AUTH = process.env.AUTH_TOKEN || 'test123';
const BASE = process.env.GRAPHDEN_URL || 'http://localhost:9002';

// Standard browser+context setup with auth pre-seeded into localStorage.
async function newContext(chromium) {
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } });
  await ctx.addInitScript((auth) => {
    // about:blank has no origin → localStorage access throws. The
    // navigation to localhost runs the init script again on a real
    // origin, so just swallow the failure here.
    try { localStorage.setItem('graphden.auth.password', auth); } catch (_) {}
  }, AUTH);
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('  [pageerror]', e.message));
  await page.goto(BASE + '/');
  await page.waitForTimeout(300);
  return { browser, page };
}

// Direct-API fetch helper that runs in-page so it inherits the
// editor's auth token from localStorage. Returns the parsed JSON
// (or a {status, body} object on error).
async function api(page, method, path, body) {
  return page.evaluate(async ({ method, path, body, base, auth }) => {
    const opts = {
      method,
      headers: { 'Authorization': 'Bearer ' + auth }
    };
    if (body !== undefined) {
      if (typeof body === 'string') {
        opts.headers['Content-Type'] = 'application/x-www-form-urlencoded';
        opts.body = body;
      } else {
        opts.headers['Content-Type'] = 'application/json';
        opts.body = JSON.stringify(body);
      }
    }
    const r = await fetch(base + path, opts);
    const txt = await r.text();
    if (r.ok) {
      try { return JSON.parse(txt); } catch (_) { return { status: r.status, body: txt }; }
    }
    return { status: r.status, body: txt };
  }, { method, path, body, base: BASE, auth: AUTH });
}

// Convenience: full graph dump + helpers.
async function getEntities(page) {
  return page.evaluate(async (base) => {
    const r = await fetch(base + '/api/graph/entities');
    return r.json();
  }, BASE);
}

// Wait until `predicate` returns truthy, polling up to `timeoutMs`.
async function waitFor(predicate, timeoutMs) {
  const deadline = Date.now() + (timeoutMs || 5000);
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await new Promise(r => setTimeout(r, 100));
  }
  return false;
}

// Cleanup any leftover entities created by a test (idempotent).
async function deleteFnByName(page, name) {
  const ents = await getEntities(page);
  const matches = ents.fns.filter(f => f.name === name);
  for (const fn of matches) {
    for (const a of ents.args.filter(x => x['fn-id'] === fn.id)) {
      await api(page, 'DELETE', '/api/entities/arg/' + a.id);
    }
    await api(page, 'DELETE', '/api/entities/fn/' + fn.id);
  }
}

module.exports = { assert, deepEqual, newContext, api, getEntities, waitFor,
                   deleteFnByName, AUTH, BASE };
