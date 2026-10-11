// Inspector native adapter: chrome is an ordinary browser graph. HTMX panels,
// Run forms and comparison conversations own independent sibling hosts.
(() => {
  let currentFn = null;
  let requestGeneration = 0;
  let pending = null;
  function fnLabel(fn) { return fn?.name || '(anonymous)'; }
  function invalidate() {
    requestGeneration++;
    pending?.abort();
    pending = null;
  }
  function clearBody(body) {
    invalidate();
    window.gdCode?.destroyWithin?.(body);
    body.replaceChildren();
  }
  function requestHost(id) {
    const host = document.getElementById(id);
    const generation = requestGeneration;
    const signal = pending?.signal;
    return {host, signal, current: () => generation === requestGeneration && host?.isConnected
      && document.getElementById(id) === host && !signal?.aborted};
  }
  function gdInspectorRender(fnId) {
    const el = document.getElementById('gd-inspector');
    if (!el) return;
    const lk = typeof lookups !== 'undefined' ? lookups : null;
    const fn = fnId ? lk?.fnMap?.get(fnId) : null;
    if (typeof gdDiffCaptureInspectorFocus === 'function') gdDiffCaptureInspectorFocus(el, fn?.id || null);
    currentFn = fn || null;
    el.dataset.fnId = fn?.id || '';
    let chrome = el.querySelector('#gd-insp-chrome-host');
    let body = el.querySelector('#gd-insp-tabbody');
    if (!chrome) {
      chrome = document.createElement('div');
      chrome.id = 'gd-insp-chrome-host';
      const diff = document.createElement('div');
      diff.dataset.gdInspectorDiffHost = '1';
      body = document.createElement('div');
      body.id = 'gd-insp-tabbody';
      body.className = 'gd-insp-scroll';
      body.setAttribute('role', 'tabpanel');
      body.setAttribute('tabindex', '0');
      el.appendChild(chrome);
      el.appendChild(diff);
      el.appendChild(body);
    }
    let namespace = '';
    if (fn && typeof getQualifiedFnName === 'function') {
      const parts = getQualifiedFnName(fn).split('.'); parts.pop(); namespace = parts.join('.');
    }
    if (!fn) clearBody(body);
    body.hidden = !fn;
    window.gdInspectorGraph.render(chrome, {
      selected: !!fn, name: fn?.name || '', namespace, description: fn?.description || '',
      parents: Array.isArray(fn?.['parent-ids']) ? fn['parent-ids'] : [], 'has-return': !!fn?.['return-type-fn-id'],
    }, {panel: body,
      load(tab, intent) { if (currentFn) renderInspTab(currentFn.id, currentFn, tab, intent); },
      close() { invalidate(); },
    });
    if (typeof gdDiffRenderInspectorSection === 'function') gdDiffRenderInspectorSection(el, fn?.id || null);
  }
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape' || event.defaultPrevented || !window.gdInspectorGraph.isOpen()) return;
    const el = document.getElementById('gd-inspector');
    const close = el?.querySelector('.gd-insp-close');
    if (!close?.getClientRects().length || !el.contains(document.activeElement)) return;
    event.preventDefault(); window.gdInspectorGraph.close();
  });
  // Overview content is a server partial (`GET /partials/inspector-overview`)
  // — the parent-chip ancestor walk, type formatting and effects chips render
  // where the reasoning lives; the client only mounts and post-formats.
  function gdLoadInspectorOverview(fnId) {
    const request = requestHost('gd-insp-overview');
    const url = '/partials/inspector-overview?fn-id=' + encodeURIComponent(fnId);
    fetch(url, {signal: request.signal})
      .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
      .then((txt) => {
        if (!request.current()) return;
        const host = request.host;
        if (host) {
          host.innerHTML = txt;
          // One type notation everywhere — same post-pass as the
          // Bindings tab (raw kept in title=).
          if (typeof formatServerTypeTexts === 'function') {
            formatServerTypeTexts(host);
          }
          // "Used by" — who extends / references this fn, appended
          // below the partial. A late response appends into a host a
          // newer selection already disconnected — a no-op.
          if (typeof gdAppendFnUsages === 'function') {
            gdAppendFnUsages(host, fnId);
          }
          // The Overview's λ / 📍 rows open the flag popovers on a fn the
          // reader may edit (editor-edit-modes-flags.js).
          if (typeof gdBindInspectorFlagRows === 'function') {
            gdBindInspectorFlagRows(host);
          }
        }
      })
      .catch(() => {
        if (!request.current()) return;
        const host = request.host;
        if (host) host.innerHTML = '<div class="gd-insp-sec-empty">Could not load overview.</div>';
      });
  }

  function renderInspTab(fnId, fn, tab, intent = {}) {
    const body = document.getElementById('gd-insp-tabbody');
    if (!body) return;
    // Each branch below replaces the body through innerHTML: the outgoing
    // Run pane's CodeMirror views hold document observers until destroyed,
    // and once the body is swapped nothing can reach them any more.
    clearBody(body);
    pending = new AbortController();
    if (tab === 'overview') {
      body.innerHTML = '<div id="gd-insp-overview" class="gd-insp-overview-host">'
        + '<div class="gd-insp-runs-loading">Loading overview…</div></div>';
      gdLoadInspectorOverview(fnId);
      return;
    }
    if (tab === 'bindings') {
      body.innerHTML = '<div id="gd-insp-detail" class="gd-insp-detail-host">'
        + '<div class="gd-insp-runs-loading">Loading bindings…</div></div>';
      gdLoadInspectorDetail(fnId);
      return;
    }
    if (tab === 'stats') {
      // Runs tab = the RUN PANE (form + result, mounted by
      // editor-execute.js gdMountRunPane) + this fn's run history
      // below it. The pane mount owns the history too (it binds
      // Repeat / path / expand against its own result host); the
      // plain-partial load is the signed-out / module-missing
      // fallback — gdMountRunPane returns without touching the
      // hosts in those cases.
      body.innerHTML = '<div id="gd-insp-run-host"></div>'
        + '<div id="gd-insp-runs" class="gd-insp-runs">'
        + '<div class="gd-insp-runs-loading">Loading runs…</div></div>';
      const authed = (typeof isAuthenticated === 'function') && isAuthenticated();
      if (authed && typeof window.gdMountRunPane === 'function') {
        const generation = requestGeneration;
        window.gdMountRunPane(fnId, {autofocus: !intent.keyboard, canFocus: () => !body.closest('[inert]'),
          isCurrent: () => generation === requestGeneration && body.isConnected && window.gdInspectorGraph.isOpen()});
      } else {
        gdLoadInspectorRuns(fnId);
      }
      return;
    }
    if (tab === 'history') {
      body.innerHTML = '<div id="gd-insp-history" class="gd-insp-history">'
        + '<div class="gd-insp-runs-loading">Loading versions…</div></div>';
      gdLoadInspectorHistory(fnId, fn);
    }
  }

  // Per-fn bindings + provenance: server partial GET /partials/inspector-detail.
  // PUBLIC route (projects graph structure already public via /api/graph/entities),
  // so it renders signed-out too. Token-guarded against a stale response landing
  // after the selection moved on.
  function gdLoadInspectorDetail(fnId) {
    const request = requestHost('gd-insp-detail');
    const url = '/partials/inspector-detail?fn-id=' + encodeURIComponent(fnId);
    fetch(url, {signal: request.signal})
      .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
      .then((txt) => {
        if (!request.current()) return;
        const host = request.host;
        if (host) {
          host.innerHTML = txt;
          // One type notation everywhere: re-render the partial's
          // raw-EDN type strings through formatTypeHint (raw kept
          // in title=). Also folds generated `a-NNNN` aliases into
          // their definitions instead of leaking internal ids.
          if (typeof formatServerTypeTexts === 'function') {
            formatServerTypeTexts(host);
          }
          // The Lint section's Not-an-issue / Restore are hx-post
          // buttons — process the swapped-in fragment so they fire.
          if (typeof htmx !== 'undefined' && htmx.process) htmx.process(host);
        }
      })
      .catch(() => {
        if (!request.current()) return;
        const host = request.host;
        if (host) {
          host.innerHTML = '<div class="gd-insp-sec-empty">Bindings unavailable.</div>';
        }
      });
  }

  // Per-fn runs + 7-day stats: reuse the existing server partial
  // `GET /partials/execute-history?fn-id=` (which already renders the
  // "N runs · N failed · avg N ms" strip + this fn's run rows). fetch() is
  // branch/auth-wrapped (editor-branches.js). A token guards against a stale
  // response landing after the user has already selected another fn.
  function gdLoadInspectorRuns(fnId) {
    const request = requestHost('gd-insp-runs');
    const url = '/partials/execute-history?fn-id=' + encodeURIComponent(fnId);
    fetch(url, {signal: request.signal})
      .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
      .then((txt) => {
        if (!request.current()) return;
        const host = request.host;
        if (!host) return;
        host.innerHTML = txt;
        if (typeof htmx !== 'undefined' && htmx.process) htmx.process(host);
      })
      .catch(() => {
        if (!request.current()) return;
        const host = request.host;
        if (host) {
          host.innerHTML = '<div class="gd-insp-runs-loading">'
            + 'Sign in to see this function’s runs.</div>';
        }
      });
  }

  // History tab: this fn's version timeline via the existing
  // `GET /partials/fn-versions?fn-id=&current-branch=&title=` partial (the
  // same data as the ⌛ popover). Rows use HTMX to lazy-load per-version
  // executions, so htmx.process the swapped-in fragment.
  function gdLoadInspectorHistory(fnId, fn) {
    const request = requestHost('gd-insp-history');
    const branch = (typeof getCurrentBranchName === 'function') ? getCurrentBranchName() : 'main';
    const url = '/partials/fn-versions?fn-id=' + encodeURIComponent(fnId)
      + '&current-branch=' + encodeURIComponent(branch)
      + '&title=' + encodeURIComponent(fnLabel(fn));
    fetch(url, {signal: request.signal})
      .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
      .then((txt) => {
        if (!request.current()) return;
        const host = request.host;
        if (!host) return;
        mountFnVersionsContent(host, txt, fn);
      })
      .catch(() => {
        if (!request.current()) return;
        const host = request.host;
        if (host) {
          host.innerHTML = '<div class="gd-insp-runs-loading">'
            + 'Sign in to see version history.</div>';
        }
      });
  }

  window.gdInspectorRender = gdInspectorRender;
  window.gdInspectorShowRuns = function gdInspectorShowRuns(fnId, opts) {
    window.gdInspectorGraph.preset('stats');
    if (!opts?.preselectOnly) gdInspectorRender(fnId);
  };
  window.gdInspectorContextChanged = () => {
    invalidate();
    gdInspectorRender(null);
  };
  window.addEventListener('pagehide', invalidate);
  // Empty presentation is graph-owned too; mount before any selection so a
  // personal preference can load this component while Settings covers Build.
  gdInspectorRender(null);
})();
