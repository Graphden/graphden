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


// Block until the executor's /health returns 200 OR `deadlineMs`
// elapses. Use this BEFORE any browser-side operation that loads
// the editor (which fires multiple in-browser fetches that have NO
// retry); if the JVM is mid-restart from an OOM, those fetches
// throw `TypeError: Failed to fetch` and the test dies.
//
// Pure node fetch + a short polling loop — no Playwright, no
// browser involvement, so this can stand between API setup
// (which uses retry-bearing nodeApi) and `await page.goto(...)`
// without contending with anything.
async function waitForServerHealthy(deadlineMs = 60000) {
  const start = Date.now();
  while (Date.now() - start < deadlineMs) {
    try {
      const r = await fetch(BASE + '/health', {signal: AbortSignal.timeout(2000)});
      if (r.ok) return;
    } catch (_) { /* swallow + retry */ }
    await new Promise((res) => setTimeout(res, 500));
  }
  throw new Error('server still unhealthy after ' + deadlineMs + 'ms');
}


// Standard browser+context setup with auth pre-seeded into localStorage.
//
// Renderer-heap hardening:
// - `--js-flags=--max-old-space-size=1024` caps V8's per-renderer
//   heap at 1 GB. The editor's `graphData` is ~4.5 MB JSON parsed
//   into ~30 MB of JS objects — 1 GB is plenty for the heaviest
//   test that mounts the editor multiple times. Previously this
//   was set to 4 GB thinking "more headroom = stability", but the
//   dev host runs e2e Chrome alongside the executor JVM (3 GB) +
//   the always-on :9002 demo (1.5 GB) + Postgres + dev tooling —
//   total demand exceeded host RAM and the kernel fired the
//   GLOBAL OOM-killer (dmesg: CONSTRAINT_NONE), killing chrome AND
//   java at random. Tighter Chrome cap keeps everyone alive.
// - `--disable-dev-shm-usage` falls back to /tmp instead of
//   /dev/shm for Chrome's shared memory. Default /dev/shm in
//   docker / minimal Linux is 64 MB; under heavy DOM mutation
//   that's exhausted in seconds and Chrome OOM-kills the tab.
async function newContext(chromium) {
  const browser = await chromium.launch({
    headless: true,
    args: [
      '--js-flags=--max-old-space-size=1024',
      '--disable-dev-shm-usage',
      // Chrome refuses to start as root without --no-sandbox
      // (crbug/638180). The e2e stack runs as root.
      '--no-sandbox',
      // The zygote pre-fork + GPU subprocess can't initialise in
      // some restrictive namespaces (CapRover hosts, certain CI
      // runners): every page.goto crashes with `[page crash]
      // renderer crashed` (Compositor int3 in dmesg). `--no-zygote`
      // disables the renderer pre-fork; `--in-process-gpu` collapses
      // the GPU sub-process into main. Renderer itself stays in its
      // own process — keeps Cytoscape responsive.
      '--no-zygote',
      '--in-process-gpu',
    ],
  });
  const ctx = await browser.newContext({ viewport: { width: 1400, height: 900 } });
  await ctx.addInitScript((auth) => {
    // about:blank has no origin → localStorage access throws. The
    // navigation to localhost runs the init script again on a real
    // origin, so just swallow the failure here.
    try { localStorage.setItem('graphden.auth.password', auth); } catch (_) {}
  }, AUTH);
  const page = await ctx.newPage();
  // Generous default timeouts so a transient GC pause under the
  // deliberately-tight e2e heap doesn't fail a test. The executor has
  // no leak (see the `project_e2e_memory_no_leak` note) — it just
  // occasionally stalls for a few seconds during a stop-the-world GC
  // in the 2.25 GB box, then recovers. Waiting longer rides out the
  // stall instead of aborting mid-test. Navigation gets the most
  // headroom: a `page.goto` landing inside a >30s GC / host-contention
  // window was the harshest flake. These are DEFAULTS — call-site
  // `{timeout: …}` overrides (incl. intentional short negative-assert
  // waits) are untouched.
  page.setDefaultTimeout(45000);
  page.setDefaultNavigationTimeout(90000);
  page.on('pageerror', e => console.log('  [pageerror]', e.message));
  page.on('crash', () => console.log('  [page crash] renderer crashed'));
  // A failed fetch reaches the page as a bare `TypeError: Failed to fetch` —
  // the same string for a reset connection, an abort, a DNS miss and a CORS
  // rejection. Chromium knows which it was; nobody was asking. Print the
  // net::ERR_* so a network failure is diagnosable from the log alone.
  page.on('requestfailed', (req) => {
    const failure = req.failure();
    console.log('  [requestfailed]', req.method(), req.url(),
                '—', (failure && failure.errorText) || 'unknown');
  });
  // Block until /health is 200 BEFORE the initial goto. Without
  // this, a test that starts during a JVM OOM-restart window has
  // its initial page.goto fire editor's initGraph against a dead
  // server; browser-side `branchAwareFetch` has no retry and the
  // test dies on `TypeError: Failed to fetch`. The cost is at
  // most ~30s during a cold-start window, ~500ms otherwise.
  await waitForServerHealthy();
  await page.goto(BASE + '/');
  await page.waitForTimeout(300);
  return { browser, page };
}

// Direct-API fetch helper that runs in-page so it inherits the
// Routed through Node-side fetch (NOT Playwright) so calls don't
// serialise behind the editor page's background loaders AND get
// the same transient-error retry as `deleteFnByName`. The `page`
// arg is kept for backward signature compat but unused. Returns
// the parsed JSON on success, or `{status, body}` on a 4xx/5xx so
// error-path tests can assert on the response shape.
async function api(_page, method, path, body) {
  const r = await nodeApi(method, path, body);
  const txt = await r.text();
  if (r.ok) {
    try { return JSON.parse(txt); } catch (_) { return { status: r.status, body: txt }; }
  }
  return { status: r.status, body: txt };
}


// Convenience: full graph dump. Same nodeApi routing as api() —
// bypasses the browser so it stays fast even while the editor is
// mid-render, AND retries on transient connection errors that
// happen during the OOM/restart window. Throws on HTTP error
// (the call site usually expects a populated map and shouldn't
// have to disambiguate `{status, body}` from a graph result).
async function getEntities(_page) {
  return nodeApiJson('GET', '/api/graph/entities');
}

// Test-side flattened view of `(fn × slot × binding × item)`. Mirrors
// the server-side `synth-args-from-bindings` derivation, but lives in
// the test harness so the API can stop emitting `:args` while tests
// keep their query DSL. Returns one row per (fn, slot) plus one row
// per binding-list-item, with closest-binding overlays applied:
//
//   {id, fn-id, slot-id, binding-id, item-id, name, value, ref-id,
//    source-id, type, prev-arg-id, next-arg-id}
//
// `id` is the synth id used by the editor's argMap key — for anchor
// rows it's a deterministic UUID xor of (fn-id, slot-id); for list-
// item rows it's the actual item-id.
function synthArgs(ents) {
  const fns = ents.fns || [];
  const slots = ents.slots || [];
  const fnSlots = ents['fn-slots'] || [];
  const bindings = ents.bindings || [];
  const items = ents['list-items'] || [];
  const fnById = new Map(fns.map(f => [f.id, f]));
  const slotById = new Map(slots.map(s => [s.id, s]));
  const ownFnSlots = new Map();
  fnSlots.forEach(fs => {
    if (!ownFnSlots.has(fs['fn-id'])) ownFnSlots.set(fs['fn-id'], []);
    ownFnSlots.get(fs['fn-id']).push(fs);
  });
  const bindingByFnSlot = new Map(
    bindings.map(b => [b['fn-id'] + '|' + b['slot-id'], b]));
  const itemsByBinding = new Map();
  items.slice().sort((a, b) => (a.position || 0) - (b.position || 0))
       .forEach(it => {
         const bid = it['binding-id'];
         if (!itemsByBinding.has(bid)) itemsByBinding.set(bid, []);
         itemsByBinding.get(bid).push(it);
       });

  // Deterministic UUID xor of two UUIDs (matches server `synth-arg-id`).
  function synthId(fnId, slotId) {
    const parse = s => s.replace(/-/g, '');
    const a = parse(fnId), b = parse(slotId);
    let out = '';
    for (let i = 0; i < 32; i++) {
      out += (parseInt(a[i], 16) ^ parseInt(b[i], 16)).toString(16);
    }
    return out.slice(0, 8) + '-' + out.slice(8, 12) + '-' + out.slice(12, 16)
         + '-' + out.slice(16, 20) + '-' + out.slice(20, 32);
  }

  function chain(fnId) {
    const seen = new Set();
    const out = [];
    const queue = [fnId];
    while (queue.length) {
      const fid = queue.shift();
      if (seen.has(fid)) continue;
      seen.add(fid);
      const f = fnById.get(fid);
      if (!f) continue;
      out.push(fid);
      (f['parent-ids'] || []).forEach(p => { if (!seen.has(p)) queue.push(p); });
    }
    return out;
  }

  const anchorRows = [];
  for (const fn of fns) {
    const fnId = fn.id;
    const seenSlots = new Set();
    for (const fid of chain(fnId)) {
      for (const fs of (ownFnSlots.get(fid) || [])) {
        const sid = fs['slot-id'];
        const slot = slotById.get(sid);
        if (!slot || seenSlots.has(sid)) continue;
        seenSlots.add(sid);
        const b = bindingByFnSlot.get(fnId + '|' + sid);
        const inheritsFrom = (fid !== fnId) ? synthId(fid, sid) : null;
        anchorRows.push({
          id: synthId(fnId, sid),
          'fn-id': fnId,
          'slot-id': sid,
          'binding-id': b ? b.id : null,
          name: (b && b['rename-to']) || slot.name,
          required: true,
          'source-id': inheritsFrom,
          value: b ? b.value : null,
          'ref-id': b ? b['ref-fn-id'] : null
        });
      }
    }
  }
  const itemRows = [];
  for (const b of bindings) {
    const its = itemsByBinding.get(b.id) || [];
    const anchorId = synthId(b['fn-id'], b['slot-id']);
    its.forEach((it, idx) => {
      itemRows.push({
        id: it.id,
        'fn-id': b['fn-id'],
        'slot-id': b['slot-id'],
        'binding-id': b.id,
        'item-id': it.id,
        name: null,
        required: false,
        'source-id': anchorId,
        value: it.value,
        'ref-id': it['ref-fn-id'],
        'prev-arg-id': idx === 0 ? anchorId : its[idx - 1].id,
        'next-arg-id': its[idx + 1] ? its[idx + 1].id : null
      });
    });
  }
  return anchorRows.concat(itemRows);
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
//
// New slot/binding model: a test fn carries `bindings` (and possibly
// `binding-list-items` under those bindings) plus rename-`slot`s
// owned by the fn itself. Order matters — list-items reference
// bindings, bindings reference fn + slot, fn-slot junctions
// reference fn + slot, slots are standalone. Delete from leaves to
// roots. Skip the legacy `args` field if the schema-snapshot doesn't
// include it.
//
// PERF: TWO compounding sources of contention removed.
//
// 1. Browser-routed `api()` helper used `page.evaluate(fetch(...))`
//    which serialises behind the editor's own loadGraphData round-
//    trips. Solved by using `directApi` — Playwright's
//    APIRequestContext, node-side, no browser hop.
//
// 2. Even with directApi, the SERVER side still contends: the
//    editor page polls /api/services + /api/types + /api/graph/
//    entities non-stop; each one takes the per-ctx invalidation-
//    lock to read while our DELETE wants it to write. Verified
//    empirically (debug-arg-value.js, 2026-06-20): cleanup BEFORE
//    editor navigation = 291ms; cleanup AFTER navigation = 27 s.
//    Going to about:blank halts the editor JS entirely, dropping
//    server load to zero so our DELETEs land at storage-only
//    speed (~10-20ms each).
//
// Across a 47-test suite, the two combined dropped per-test
// cleanup from ~30s to ~300ms — ~22 min saved.
// Pure Node HTTP — no Playwright involvement. The cleanup path
// must NOT contend with the editor page that's still running JS.
// Even Playwright's directApi via `page.context().request` ended up
// serialising behind the editor's in-flight /api/services + /api/
// types polls server-side (verified empirically 2026-06-20:
// directApi cleanup after editor navigation = 27s; node fetch
// cleanup = <500ms).
// Retry on transient network errors (ECONNRESET, socket hang up).
// The isolated e2e stack restarts the executor JVM on OOM via the
// Docker restart-policy + pinned host port — recovery window is
// ~10s. A request that hits that window throws a TypeError with
// `cause.code` of ECONNRESET / ECONNREFUSED. Retrying once with
// a generous backoff converts the OOM-recovery flake into a
// transparent self-heal: the test sees a brief pause, then a
// successful response from the freshly-restarted JVM.
//
// Only retry idempotent methods. POST/PUT might partially apply
// before the connection drops; replaying could double-insert or
// trip a 409. GET/DELETE are safe — DELETE is idempotent in our
// CRUD layer (delete-of-already-gone returns 404, not 500).
function isTransientNetworkError(err) {
  const msg = String(err && err.message || err);
  if (/socket hang up|ECONNRESET|ECONNREFUSED|fetch failed/i.test(msg)) return true;
  // `cause.code` is where undici stashes it; `code` is where node:http does.
  const code = (err && err.cause && err.cause.code) || (err && err.code);
  return code === 'ECONNRESET' || code === 'ECONNREFUSED' || code === 'UND_ERR_SOCKET';
}


// Minimal `fetch`-shaped request over node's CORE http client.
//
// We cannot use the global `fetch`: node's bundled undici crashes with an
// uncatchable `AssertionError: assert(!this.paused)` (Parser.finish) whenever
// a `Connection: close` response is big enough to trigger read backpressure.
// Reproduced on node 22.23.1 against `/api/graph/entities` (~6 MB) with a
// six-line script; `/health` (46 B) is fine, and keep-alive is fine. The
// assertion fires on the socket-end tick, outside any promise, so it takes
// the whole process down — which is why one bad GET killed entire test files
// before they reached their first browser assertion.
//
// Dropping `Connection: close` is not an option (see the http-kit AsyncChannel
// leak note on `nodeApi`). node's core client parses close-delimited bodies
// correctly, and `agent: false` gives every request its own socket and emits
// the close header for us.
//
// Returns only the surface `nodeApi`'s two callers use: ok / status / text /
// json. Bodies are buffered — the largest response in the suite is ~6 MB.
function coreHttpRequest(method, url, headers, body, signal) {
  const u = new URL(url);
  const mod = u.protocol === 'https:' ? require('node:https') : require('node:http');
  return new Promise((resolve, reject) => {
    const req = mod.request({
      protocol: u.protocol,
      hostname: u.hostname,
      port: u.port || (u.protocol === 'https:' ? 443 : 80),
      path: u.pathname + u.search,
      method,
      headers,
      agent: false,
      signal,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('error', reject);
      res.on('end', () => {
        const txt = Buffer.concat(chunks).toString('utf8');
        resolve({
          ok: res.statusCode >= 200 && res.statusCode < 300,
          status: res.statusCode,
          text: async () => txt,
          json: async () => JSON.parse(txt),
        });
      });
    });
    req.on('error', reject);
    if (body !== undefined) req.write(body);
    req.end();
  });
}


async function nodeApi(method, path, body) {
  // `Connection: close` is THE fix for the 1.8 GB http-kit AsyncChannel
  // retention leak (heap dump 2026-06-21). With the default HTTP/1.1
  // keep-alive, every fetch leaves the channel + the response's byte
  // buffers held in http-kit's wrap-ring-websocket tracking HashMap
  // until the TCP connection drops (idle timeout). Across 47 tests
  // doing hundreds of fetches each, that accumulated to 27,955 byte[]
  // (1.7 GB) — the dominant heap retainer, dwarfing every
  // application-level cache. Forcing connection-close releases the
  // channel immediately after the response is sent; no accumulation,
  // no OOM cascade.
  const headers = { 'Authorization': 'Bearer ' + AUTH, 'Connection': 'close' };
  let payload;
  if (body !== undefined) {
    if (typeof body === 'string') {
      headers['Content-Type'] = 'application/x-www-form-urlencoded';
      payload = body;
    } else {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    headers['Content-Length'] = Buffer.byteLength(payload);
  }
  const idempotent = method === 'GET' || method === 'DELETE';
  const maxAttempts = idempotent ? 4 : 1;
  let lastErr = null;
  for (let i = 0; i < maxAttempts; i++) {
    // Per-attempt 60s wall cap. Without this, a request against a
    // server that's queued up requests but not responding (e.g. mid-
    // GC pause or starved scheduler) waits indefinitely — neither
    // node's `fetch` nor its core client has a default timeout, and
    // both inherit the kernel socket wait. One stuck request can blow
    // past the per-test 5-min cap (verified empirically —
    // edit-inheritance-regression makes ~15 sequential layout calls;
    // under memory pressure a single stuck POST drowned out the whole
    // test). 60s lets a genuinely-slow JVM finish; anything past that
    // is a hang.
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 60000);
    try {
      return await coreHttpRequest(method, BASE + path, headers, payload, ctrl.signal);
    } catch (err) {
      lastErr = err;
      const aborted = err?.name === 'AbortError';
      if (!idempotent || (!isTransientNetworkError(err) && !aborted) || i === maxAttempts - 1) throw err;
      // Backoff: 5s + 15s + 30s = 50s total. Covers JVM cold-start
      // after OOM-restart (~30-40s — packages load + compile-eager
      // build the 3000-fn registry) plus a small safety margin.
      // Shorter backoff was retrying inside the restart window and
      // exhausting attempts before the JVM was ready (verified
      // empirically — edit-namespace-move 2026-06-21).
      await new Promise((r) => setTimeout(r, [5000, 15000, 30000][i]));
    } finally {
      clearTimeout(timer);
    }
  }
  throw lastErr;
}


async function nodeApiJson(method, path, body) {
  const r = await nodeApi(method, path, body);
  if (!r.ok) {
    throw new Error('nodeApi ' + method + ' ' + path + ': HTTP ' + r.status);
  }
  return r.json();
}


async function deleteFnByName(_page, name) {
  // Note: cleanup uses pure Node fetch (no Playwright), so it
  // never contends with the editor page's Playwright connection.
  // That removed the most catastrophic case (browser-side
  // serialisation that turned 11ms direct DELETEs into 14s).
  // The remaining post-test slowness (~13s per DELETE while editor
  // JS is still polling) is server-side scheduler pressure — to
  // shave that further, tests should `await page.close()` in
  // their finally BEFORE calling cleanup. Not enforced here
  // because the helper has no way to know if this is a pre- or
  // post-test call.
  const ents = await nodeApiJson('GET', '/api/graph/entities');
  const matches = ents.fns.filter(f => f.name === name);
  const secretLeaf = ents.fns.find(f => f.name === 'secret-leaf');
  for (const fn of matches) {
    // A secret fn-def can ONLY be removed through the secrets endpoint, which
    // also cleans up its OpenBao entry. The generic CRUD route answers 409 and
    // says so. This helper used to ignore the status, so cleanup "succeeded"
    // silently — but only AFTER it had already deleted the `:in` binding that
    // holds the vault path, leaving a half-deleted secret behind. That is how
    // the demo DB accumulated probe secrets with `path: null`.
    if (isSecretFnRow(fn, secretLeaf)) {
      await deleteOrThrow('/api/secrets/' + fn.id, name);
      continue;
    }

    const bindings = (ents.bindings || []).filter(b => b['fn-id'] === fn.id);
    const bindingIds = new Set(bindings.map(b => b.id));
    const items = (ents['list-items'] || []).filter(i => bindingIds.has(i['binding-id']));
    for (const it of items) {
      await deleteOrThrow('/api/entities/binding-list-item/' + it.id, name);
    }
    for (const b of bindings) {
      await deleteOrThrow('/api/entities/binding/' + b.id, name);
    }
    const ownFnSlots = (ents['fn-slots'] || []).filter(fs => fs['fn-id'] === fn.id);
    for (const fs of ownFnSlots) {
      // fn-slot rows have a composite PK; the API treats them as
      // entities keyed by `id` (set on creation); fall through if
      // the row predates id-bearing rows.
      if (fs.id) await deleteOrThrow('/api/entities/fn-slot/' + fs.id, name);
    }
    const ownSlotIds = ownFnSlots.map(fs => fs['slot-id']);
    for (const sid of ownSlotIds) {
      await deleteOrThrow('/api/entities/slot/' + sid, name);
    }
    await deleteOrThrow('/api/entities/fn/' + fn.id, name);
  }
}


// A secret is a fn-def whose parents are exactly `[secret-leaf]` — the same
// "pure secret" test the secrets panel uses (`isSecretFn` in editor-secrets.js).
function isSecretFnRow(fn, secretLeaf) {
  if (!secretLeaf) return false;
  const parents = fn['parent-ids'] || [];
  return parents.length === 1 && parents[0] === secretLeaf.id;
}


// DELETE that refuses to fail silently. 404 is fine — cleanup runs before the
// test too, when there is nothing to remove. Anything else means the entity
// survived, and a test that leaks entities poisons every later run against the
// same DB, so make it loud.
async function deleteOrThrow(path, name) {
  const r = await nodeApi('DELETE', path);
  if (r.ok || r.status === 404) return;
  const body = (await r.text()).slice(0, 200);
  const msg = 'cleanup of "' + name + '" failed: DELETE ' + path
              + ' → HTTP ' + r.status + ' ' + body;
  process.stderr.write('  ! ' + msg + '\n');
  throw new Error(msg);
}


module.exports = { assert, deepEqual, newContext, api, getEntities,
                   nodeApi, nodeApiJson,
                   synthArgs, waitFor, waitForServerHealthy,
                   deleteFnByName, AUTH, BASE };
