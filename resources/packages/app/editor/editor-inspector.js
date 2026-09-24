// Editor INSPECTOR — the right column: head + Overview / Bindings / Runs /
// Versions.
//
// `window.gdInspectorRender(fnId)`
// paints the persistent HEAD (name / namespace / description + the 2-field
// kind classifier `gdFnKind`) client-side from the lookups cache — the
// sub-100ms selection path — and every tab body is a server partial: Overview
// `GET /partials/inspector-overview` (`gdLoadInspectorOverview`, token-guarded,
// `formatServerTypeTexts` post-pass), Bindings `/partials/inspector-detail`,
// Runs `/partials/execute-history` (the Run pane mounts into it —
// `gdMountRunPane`, editor-execute.js), Versions `/partials/fn-versions`.
// `window.gdInspectorShowRuns` is the ▶ entry that lands on Runs. Bottom-sheet
// on narrow viewports (`gd-insp-open`). Compare mode appends its diff panel
// after every render (`gdDiffRenderInspectorSection`, editor-diff-inspector.js).
//
// HTML escaping goes through `gdEscapeHtml` (web/runtime/graphden-popover.js).

(() => {

  // ---- Shared helpers -------------------------------------------------------
  // graph-first-exception: the 2-field kind classifier stays client — it feeds
  // the persistent inspector HEAD, which renders synchronously from the lookups
  // cache on every selection (sub-100ms path); the server-owned kind reasoning
  // ships in /partials/inspector-overview alongside it.
  function gdFnKind(fn) {
    const parentIds = Array.isArray(fn['parent-ids']) ? fn['parent-ids'] : [];
    return parentIds.length ? 'fn-def' : (fn['return-type-fn-id'] ? 'base-fn' : 'type');
  }

  // (No Workspaces SURFACE: retired — workspace scoping is the ctxbar
  // chip's popover (gdOpenWsPop). The full-page surface + its render fn
  // were removed with the rail button.)

  // ---- Right inspector ------------------------------------------------------
  // The persistent HEAD renders client-side off DIRECT fn fields the lookups
  // cache already holds (name, namespace, description, the 2-line kind
  // classifier) — deliberate: the selection→head loop stays local and
  // re-derives no server reasoning. ALL tab content is server partials now:
  // Overview (/partials/inspector-overview), Bindings (/inspector-detail),
  // Runs (/execute-history), Versions (/fn-versions).
  const INSP_EMPTY =
    '<div class="gd-insp-empty">Select a node to inspect its bindings, types, '
    + 'effects, and this function’s own run history.</div>';

  function fnLabel(fn) {
    return fn?.name ? fn.name : '(anonymous)';
  }

  // The inspector is TABBED: a persistent head (identity) + a tab bar whose
  // body swaps between Overview / Bindings / Stats / History. `inspTab`
  // persists across selections so clicking a second node keeps you on the
  // same lens. Bindings/Stats/History are lazy server partials, fetched into
  // #gd-insp-tabbody only when their tab is shown.
  const INSP_TABS = [
    { id: 'overview', label: 'Overview' },
    { id: 'bindings', label: 'Bindings' },
    { id: 'stats', label: 'Runs' },
    { id: 'history', label: 'Versions' },
  ];
  let inspTab = 'overview';

  function gdInspectorRender(fnId) {
    const el = document.getElementById('gd-inspector');
    if (!el) return;

    // `lookups` is a bundle-level `let` (not a window property), so read the
    // lexical global directly rather than `window.lookups` (which is undefined).
    const lk = (typeof lookups !== 'undefined') ? lookups : null;
    const fn = (fnId && lk?.fnMap) ? lk.fnMap.get(fnId) : null;
    // Every path below replaces the whole column — tear down the outgoing
    // tab body's CodeMirror views (the Run pane's code-typed args) first.
    window.gdCode?.destroyWithin?.(el);
    if (!fn) { el.innerHTML = INSP_EMPTY; document.body.classList.remove('gd-insp-open'); return; }

    // Namespace = the qualified name minus the fn's own last segment.
    let ns = '';
    if (typeof getQualifiedFnName === 'function') {
      const parts = getQualifiedFnName(fn).split('.');
      parts.pop();
      ns = parts.join('.');
    }

    const kind = gdFnKind(fn);

    let head = '<div class="gd-insp-head">'
      + '<button type="button" class="gd-insp-close" aria-label="Close inspector">&times;</button>'
      + '<div class="gd-insp-title"><span class="gd-insp-name">' + gdEscapeHtml(fnLabel(fn))
      + '</span><span class="gd-insp-kind">' + gdEscapeHtml(kind) + '</span></div>';
    if (ns) head += '<div class="gd-insp-ns">' + gdEscapeHtml(ns) + '</div>';
    if (fn.description) head += '<p class="gd-insp-desc">' + gdEscapeHtml(fn.description) + '</p>';
    head += '</div>';

    // Full ARIA tab pattern: `id` + `aria-controls` tie each tab to the ONE
    // panel below (all four tabs render into the same `#gd-insp-tabbody`),
    // and roving tabindex means Tab enters the strip once while ← → move
    // between tabs — pressing Tab four times to reach the last tab is the
    // thing the pattern exists to avoid.
    const tabbar = '<div class="gd-insp-tabs" role="tablist" aria-label="Inspector sections">'
      + INSP_TABS.map((t) => '<button type="button" class="gd-insp-tab'
          + (t.id === inspTab ? ' active' : '') + '" role="tab" id="gd-insp-tab-' + t.id
          + '" aria-controls="gd-insp-tabbody" aria-selected="'
          + (t.id === inspTab) + '" tabindex="' + (t.id === inspTab ? '0' : '-1')
          + '" data-insp-tab="' + t.id + '">'
          + t.label + '</button>').join('')
      + '</div>';

    el.innerHTML = head + tabbar
      + '<div id="gd-insp-tabbody" class="gd-insp-scroll" role="tabpanel"'
      + ' aria-labelledby="gd-insp-tab-' + inspTab + '" tabindex="0"></div>';

    // Compare mode's per-fn diff panel (entries old→new + anchored
    // threads) slots in right under the head — the inspector IS where
    // the selected node's change details live now (UX-v3).
    if (typeof gdDiffRenderInspectorSection === 'function') {
      gdDiffRenderInspectorSection(el, fnId);
    }

    const selectTab = (id, moveFocus) => {
      if (inspTab !== id) {
        inspTab = id;
        renderInspTab(fnId, fn);
      }
      let focusTarget = null;
      el.querySelectorAll('.gd-insp-tab').forEach((x) => {
        const on = x.dataset.inspTab === inspTab;
        x.classList.toggle('active', on);
        x.setAttribute('aria-selected', String(on));
        x.setAttribute('tabindex', on ? '0' : '-1');
        if (on) focusTarget = x;
      });
      const body = el.querySelector('#gd-insp-tabbody');
      if (body) body.setAttribute('aria-labelledby', 'gd-insp-tab-' + inspTab);
      if (moveFocus && focusTarget) focusSafely(focusTarget);
    };

    el.querySelectorAll('.gd-insp-tab').forEach((b) => {
      b.addEventListener('click', () => selectTab(b.dataset.inspTab, false));
    });

    const tablist = el.querySelector('.gd-insp-tabs');
    if (tablist) {
      tablist.addEventListener('keydown', (e) => {
        const ids = INSP_TABS.map((t) => t.id);
        const at = ids.indexOf(inspTab);
        if (e.key === 'ArrowRight') {
          e.preventDefault();
          selectTab(ids[(at + 1) % ids.length], true);
        } else if (e.key === 'ArrowLeft') {
          e.preventDefault();
          selectTab(ids[(at - 1 + ids.length) % ids.length], true);
        } else if (e.key === 'Home') {
          e.preventDefault();
          selectTab(ids[0], true);
        } else if (e.key === 'End') {
          e.preventDefault();
          selectTab(ids[ids.length - 1], true);
        }
      });
    }
    renderInspTab(fnId, fn);

    // On narrow viewports the inspector is a bottom sheet — reveal it on
    // selection; the × (shown only ≤1100 via CSS) dismisses it. On wide
    // viewports the class is inert (the inspector is a static column).
    document.body.classList.add('gd-insp-open');
    const closeBtn = el.querySelector('.gd-insp-close');
    if (closeBtn) closeBtn.onclick = () => document.body.classList.remove('gd-insp-open');
  }

  // Escape closes the sheet when it IS a sheet (the × is on screen only
  // then) — consumed, so a running tour is not ended by it. Registered
  // once, at load.
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || e.defaultPrevented) return;
    if (!document.body.classList.contains('gd-insp-open')) return;
    const x = document.querySelector('#gd-inspector .gd-insp-close');
    if (!x || x.getClientRects().length === 0) return;   // wide viewport: a static column
    if (!document.getElementById('gd-inspector')?.contains(document.activeElement)) return;
    e.preventDefault();
    document.body.classList.remove('gd-insp-open');
  });

  // Overview content is a server partial (`GET /partials/inspector-overview`)
  // — the parent-chip ancestor walk, type formatting and effects chips render
  // where the reasoning lives; the client only mounts and post-formats.
  let inspOverviewToken = null;
  function gdLoadInspectorOverview(fnId) {
    inspOverviewToken = fnId;
    const url = '/partials/inspector-overview?fn-id=' + encodeURIComponent(fnId);
    fetch(url)
      .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
      .then((txt) => {
        if (inspOverviewToken !== fnId) return; // selection moved on
        const host = document.getElementById('gd-insp-overview');
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
        if (inspOverviewToken !== fnId) return;
        const host = document.getElementById('gd-insp-overview');
        if (host) host.innerHTML = '<div class="gd-insp-sec-empty">Could not load overview.</div>';
      });
  }

  function renderInspTab(fnId, fn) {
    const body = document.getElementById('gd-insp-tabbody');
    if (!body) return;
    // Each branch below replaces the body through innerHTML: the outgoing
    // Run pane's CodeMirror views hold document observers until destroyed,
    // and once the body is swapped nothing can reach them any more.
    window.gdCode?.destroyWithin?.(body);
    if (inspTab === 'overview') {
      body.innerHTML = '<div id="gd-insp-overview" class="gd-insp-overview-host">'
        + '<div class="gd-insp-runs-loading">Loading overview…</div></div>';
      gdLoadInspectorOverview(fnId);
      return;
    }
    if (inspTab === 'bindings') {
      body.innerHTML = '<div id="gd-insp-detail" class="gd-insp-detail-host">'
        + '<div class="gd-insp-runs-loading">Loading bindings…</div></div>';
      gdLoadInspectorDetail(fnId);
      return;
    }
    if (inspTab === 'stats') {
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
        window.gdMountRunPane(fnId);
      } else {
        gdLoadInspectorRuns(fnId);
      }
      return;
    }
    if (inspTab === 'history') {
      body.innerHTML = '<div id="gd-insp-history" class="gd-insp-history">'
        + '<div class="gd-insp-runs-loading">Loading versions…</div></div>';
      gdLoadInspectorHistory(fnId, fn);
    }
  }

  // Per-fn bindings + provenance: server partial GET /partials/inspector-detail.
  // PUBLIC route (projects graph structure already public via /api/graph/entities),
  // so it renders signed-out too. Token-guarded against a stale response landing
  // after the selection moved on.
  let inspDetailToken = null;
  function gdLoadInspectorDetail(fnId) {
    inspDetailToken = fnId;
    const url = '/partials/inspector-detail?fn-id=' + encodeURIComponent(fnId);
    fetch(url)
      .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
      .then((txt) => {
        if (inspDetailToken !== fnId) return; // selection moved on
        const host = document.getElementById('gd-insp-detail');
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
        if (inspDetailToken !== fnId) return;
        const host = document.getElementById('gd-insp-detail');
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
  let inspRunsToken = null;
  function gdLoadInspectorRuns(fnId) {
    inspRunsToken = fnId;
    const url = '/partials/execute-history?fn-id=' + encodeURIComponent(fnId);
    fetch(url)
      .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
      .then((txt) => {
        if (inspRunsToken !== fnId) return; // selection moved on
        const host = document.getElementById('gd-insp-runs');
        if (!host) return;
        host.innerHTML = txt;
        if (typeof htmx !== 'undefined' && htmx.process) htmx.process(host);
      })
      .catch(() => {
        if (inspRunsToken !== fnId) return;
        const host = document.getElementById('gd-insp-runs');
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
  let inspHistoryToken = null;
  function gdLoadInspectorHistory(fnId, fn) {
    inspHistoryToken = fnId;
    const branch = (typeof getCurrentBranchName === 'function') ? getCurrentBranchName() : 'main';
    const url = '/partials/fn-versions?fn-id=' + encodeURIComponent(fnId)
      + '&current-branch=' + encodeURIComponent(branch)
      + '&title=' + encodeURIComponent(fnLabel(fn));
    fetch(url)
      .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
      .then((txt) => {
        if (inspHistoryToken !== fnId) return;
        const host = document.getElementById('gd-insp-history');
        if (!host) return;
        host.innerHTML = txt;
        if (typeof htmx !== 'undefined' && htmx.process) htmx.process(host);
      })
      .catch(() => {
        if (inspHistoryToken !== fnId) return;
        const host = document.getElementById('gd-insp-history');
        if (host) {
          host.innerHTML = '<div class="gd-insp-runs-loading">'
            + 'Sign in to see version history.</div>';
        }
      });
  }

  window.gdInspectorRender = gdInspectorRender;

  // ▶ Run entry (editor-execute.js): land the inspector on the Runs
  // tab. `preselectOnly` presets the tab without rendering — for the
  // caller about to selectFn(), whose own inspector render then lands
  // on Runs directly (avoids a double render + a flash of Overview).
  window.gdInspectorShowRuns = function gdInspectorShowRuns(fnId, opts) {
    inspTab = 'stats';
    if (opts?.preselectOnly) return;
    gdInspectorRender(fnId);
  };
})();
