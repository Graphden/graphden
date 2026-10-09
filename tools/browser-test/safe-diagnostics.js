// Diagnostic output is a projection onto trusted metadata, not string masking.
// Never retain the original error/cause, URL parameters, body or console text.
const METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']);
const ERRORS = new Set(['Error', 'TypeError', 'SyntaxError', 'ReferenceError', 'RangeError', 'TimeoutError', 'AbortError']);
const NETWORK = new Set(['net::ERR_ABORTED', 'net::ERR_CONNECTION_REFUSED', 'net::ERR_CONNECTION_RESET',
  'net::ERR_NAME_NOT_RESOLVED', 'net::ERR_TIMED_OUT', 'ECONNRESET', 'ECONNREFUSED', 'UND_ERR_SOCKET']);
const ROOTS = new Set(['entities', 'branches', 'packages', 'services', 'types', 'lint', 'secrets',
  'execute', 'executions', 'views', 'queues', 'accounts', 'sessions', 'publications']);
function method(value) { return METHODS.has(value) ? value : 'UNKNOWN'; }
function status(value) { return Number.isInteger(value) && value >= 100 && value <= 599 ? value : 'unknown'; }
function errorKind(error) { return ERRORS.has(error?.name) ? error.name : 'Error'; }
function networkKind(value) { return NETWORK.has(value) ? value : 'network-error'; }
function route(value) {
  try {
    const path = new URL(value, 'http://diagnostic.invalid').pathname;
    if (['/', '/health', '/version', '/api/graph/layout', '/api/graph/entities'].includes(path)) return path;
    const parts = path.split('/');
    if (parts[1] === 'api' && ROOTS.has(parts[2])) return '/api/' + parts[2] + (parts.length > 3 ? '/:resource' : '');
    if (parts[1] === 'assets') return '/assets/:resource';
    if (parts[1] === 'partials') return '/partials/:resource';
  } catch (_) { /* malformed URLs carry no safe metadata */ }
  return 'unknown-route';
}
function httpFailure(verb, url, code, kind = 'Error') {
  // A NEW error, without cause: callers can safely print its stack.
  return new Error(method(verb) + ' ' + route(url) + ': HTTP ' + status(code)
    + ' (' + errorKind({name: kind}) + ')');
}
function installDiagnostics(page) {
  const counts = {pageErrors: 0, crashes: 0, consoleErrors: 0, consoleWarnings: 0, requestFailures: 0, httpFailures: 0};
  page.on('pageerror', e => { counts.pageErrors++; console.log('  [pageerror]', errorKind(e)); });
  page.on('crash', () => { counts.crashes++; console.log('  [page crash] renderer crashed'); });
  // A failed fetch reaches the page as a bare `TypeError: Failed to fetch` —
  // the same string for a reset connection, an abort, a DNS miss and a CORS
  // rejection. Chromium knows which it was; nobody was asking. Print the
  // net::ERR_* so a network failure is diagnosable from the log alone.
  // Lines that are ONLY noise when a navigation (or the page closing)
  // accounts for them. 54 of the 90 files boot a page here, seed data over
  // the API, then `reload()` / goto `#fn` so the editor boots on the probe —
  // which aborts the first boot's still-in-flight fetches (initGraph,
  // /api/services, /api/types, /api/lint, secrets, the Operate partials).
  // Each abort used to print an editor `console.error … Failed to fetch` AND
  // a `[requestfailed] … ERR_ABORTED`: ~280 lines per suite run that buried
  // the real failures. Hold such a line for up to a second; a main-frame
  // navigation (or page close) inside that window folds everything held —
  // plus the aborts Chromium reports in the 1.5 s AFTER the navigation event
  // (loadingFailed is delivered after frameNavigated) — into one `[nav]`
  // line. Otherwise the line prints as before: a genuine "Failed to fetch"
  // (server down) is never lost, just a second late.
  const heldAborts = [];
  let fold = null; // {label, count, timer, at} — the summary being assembled
  const printFold = () => {
    if (!fold) return;
    // A fold opened speculatively on a navigation that aborted nothing (a
    // same-document `#hash` change, a clean boot) prints nothing.
    if (fold.count > 0) {
      console.log('  [nav] ' + fold.count + ' in-flight fetch(es) of the previous document aborted by '
                  + fold.label);
    }
    fold = null;
  };
  const foldInto = (label) => {
    if (fold && fold.label !== label) printFold();
    if (!fold) fold = {label, count: 0, at: Date.now(), timer: null};
    for (const it of heldAborts) clearTimeout(it.timer);
    fold.count += heldAborts.length;
    heldAborts.length = 0;
    clearTimeout(fold.timer);
    fold.timer = setTimeout(printFold, 500);
    if (typeof fold.timer.unref === 'function') fold.timer.unref();
  };
  const holdAbortLine = (line) => {
    if (fold && Date.now() - fold.at < 1500) {
      fold.count += 1;
      clearTimeout(fold.timer);
      fold.timer = setTimeout(printFold, 500);
      if (typeof fold.timer.unref === 'function') fold.timer.unref();
      return;
    }
    const item = {line};
    item.timer = setTimeout(() => {
      const i = heldAborts.indexOf(item);
      if (i >= 0) { heldAborts.splice(i, 1); console.log(line); }
    }, 1000);
    if (typeof item.timer.unref === 'function') item.timer.unref();
    heldAborts.push(item);
  };
  page.on('framenavigated', (frame) => {
    if (frame !== page.mainFrame()) return;
    if (heldAborts.length === 0 && !(fold && Date.now() - fold.at < 1500)) {
      // Nothing held: still open a fold so late-reported aborts of the
      // document just left are counted, but only print if any arrive.
      fold = {label: 'navigation to ' + route(frame.url()),
              count: 0, at: Date.now(), timer: null};
      return;
    }
    foldInto('navigation to ' + route(frame.url()));
  });
  page.on('close', () => {
    if (heldAborts.length === 0) return;
    foldInto('page close');
    printFold();
  });
  page.on('requestfailed', (req) => {
    const failure = req.failure();
    const errorText = networkKind(failure && failure.errorText);
    counts.requestFailures++;
    const line = '  [requestfailed] ' + method(req.method()) + ' ' + route(req.url()) + ' — ' + errorText;
    if (errorText !== 'net::ERR_ABORTED') { console.log(line); return; }
    // ERR_ABORTED *after* a response arrived is the page navigating or
    // closing before the body was read — the server already answered
    // (the `[op]` line above it shows the status). Skip those outright;
    // hold the rest for the navigation check above.
    req.response().then((resp) => {
      if (!resp) holdAbortLine(line);
    }).catch(() => holdAbortLine(line));
  });
  // Two blind spots kept this suite's flake undiagnosed for weeks. Both are
  // filled below; together they turned "a wait timed out" into "the package
  // update took 21 s under a full heap", the observation that led to the G1
  // Full-GC root cause.
  //
  // 1. The page's console.error was invisible. Editor code reports every failed
  //    fetch there ("… fetch threw TypeError: Failed to fetch"), so the one line
  //    naming the failing REQUEST went unprinted, and the test surfaced only the
  //    downstream symptom — a wait that never completed.
  page.on('console', (msg) => {
    const t = msg.type();
    if (t !== 'error' && t !== 'warning') return;
    counts[t === 'error' ? 'consoleErrors' : 'consoleWarnings']++;
    const line = '  [console.' + t + '] ' + (msg.text() === 'Failed to fetch' ? 'fetch-failed' : 'message-hidden');
    // An editor fetch rejected with the bare "Failed to fetch": held for the
    // navigation check above (`holdAbortLine`).
    if (t === 'error' && msg.text() === 'Failed to fetch') {
      holdAbortLine(line);
      return;
    }
    console.log(line);
  });
  // 2. Mutating ops were untimed. HTMX drives them as XHR (not fetch), so a
  //    fetch-wrap would miss them; page.on('response') sees both. These are the
  //    ops whose long waits flake, so print how long each actually took and what
  //    it returned — the difference between "the server took 60 s" (it did, a GC
  //    stall) and "it answered in 2 s and the DOM never updated" (it didn't).
  const started = new Map();
  page.on('request', (req) => {
    // The layout POST rides along: its failure used to reach the log only
    // as the editor's "Failed to fetch layout from backend", status unknown.
    if (/\/api\/(packages|branches|entities|graph\/layout)/.test(req.url())
        && req.method() !== 'GET') started.set(req, Date.now());
  });
  page.on('response', async (res) => {
    const req = res.request();
    const t0 = started.get(req);
    if (t0 === undefined) return;
    started.delete(req);
    const line = '  [op] ' + method(req.method()) + ' ' + route(res.url())
                 + ' → ' + status(res.status()) + ' in ' + Math.max(0, Date.now() - t0) + 'ms';
    if (res.ok()) { console.log(line); return; }
    counts.httpFailures++;
    console.log(line + ' — response-body-hidden');
  });
  return counts;
}
module.exports = {installDiagnostics, httpFailure, errorKind, networkKind, route, method, status};
