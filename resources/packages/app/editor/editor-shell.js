// Editor Shell — redesign 2026-08. Owns the surface switching.
//
// Build is the graph editor; Operate (labeled "Organization") / Platform /
// Settings are real <section>s (see REAL_SURFACES). The RAIL is retired
// (2026-08-15): surface ENTRY is the account chip's menu (editor-auth.js) +
// the deep-link hashes below; the brand button in the top bar is the way
// back to Build. Review and Workspaces were retired as surfaces earlier: a
// branch diff is the Δ button in the branch switcher, a workspace is the
// Explorer chip's popover. (There is no Run surface — running a fn is the ▶
// action on its node/card; its history is the inspector Runs tab.)
//
// Deep links: surfaces are hash-addressable with an `@` prefix — `#@settings`,
// `#@settings/account`, `#@organization`, `#@platform`. `@` can't start a fn
// name (valid-identifier?), so the namespace never collides with the
// `#<fn-name>` selection hashes; editor-main.js routes `@` hashes here.
(function () {
  // Progressive disclosure (redesign 2026-08): cards start COMPACT — the dense
  // return-type + effects metadata strips are hidden by default (that data is
  // in the inspector for the selected fn). The nav-controls "Details" toggle
  // reveals them across the canvas. Set before first render so the layout
  // engine measures the compact card heights.
  try {
    // Default compact; the choice persists (a user who reveals details keeps
    // them). '0' = user opted into full cards.
    if (localStorage.getItem('graphden.cards.compact') !== '0') {
      document.body.classList.add('gd-cards-compact');
    }
  } catch (_) { document.body.classList.add('gd-cards-compact'); }

  // The editor opens on Build; record it so surface-scoped chrome (the
  // Explorer expand tab) gates correctly before the first rail click.
  document.body.setAttribute('data-surface', 'build');

  // Build / Operate / Platform / Settings are the live surfaces (Review +
  // Workspaces were retired — see the rail comments in fns.edn). Build is the
  // graph editor; the rest are real <section>s (see REAL_SURFACES). The
  // placeholder overlay is only a defensive fallback for an unknown name.
  // opts.pushHash=false suppresses the history write (used when the CALL
  // originates from hash routing — pushing again would double the entry).
  function gdShellSurface(name, btn, opts) {
    gdHideAllSurfaces();
    // A surface switch is a context switch: floating popovers from the
    // previous surface (Run form, pickers, viewers) must not survive on
    // top of the new one. Outside-pointerdown alone misses hash-routed
    // and programmatic switches.
    if (typeof dismissAllPopovers === 'function') dismissAllPopovers();
    // Record the active surface so surface-scoped chrome can gate on it — the
    // Explorer's left-edge expand tab only makes sense on Build (the other
    // surfaces are full overlays with no Explorer).
    document.body.setAttribute('data-surface', name);
    if (opts?.pushHash !== false) gdPushSurfaceHash(name);

    // Build = the graph editor (explorer | canvas | inspector), no cover.
    if (name === 'build') return;

    // Real surfaces reveal their own section (+ populate dynamic content);
    // anything else falls back to the "being rebuilt" placeholder overlay.
    const realId = REAL_SURFACES[name];
    if (realId) {
      const el = document.getElementById(realId);
      if (el) el.hidden = false;
      const render = { operate: gdRenderOperate, platform: gdRenderPlatform, settings: gdRenderSettings }[name];
      if (typeof render === 'function') render();
      return;
    }

    const overlay = document.getElementById('gd-surface-overlay');
    if (!overlay) return;
    const title = overlay.querySelector('.gd-surface-title');
    const sub = overlay.querySelector('.gd-surface-sub');
    if (title) title.textContent = name;
    if (sub) sub.textContent = 'This surface is being rebuilt.';
    overlay.hidden = false;
  }

  // Operate panels are normally mounted as a side effect of the sidebar render
  // at boot. If they're missing — e.g. the sidebar last rendered in search mode,
  // which skips the ops sections — repopulate so Operate never opens empty.
  function gdRenderOperate() {
    const host = document.getElementById('gd-operate-panels');
    if (host && host.children.length === 0
        && typeof updateEntityList === 'function' && typeof graphData !== 'undefined' && graphData) {
      updateEntityList(graphData);
    }
    // Live panels (Assets today — the code diagnostics live in the Build
    // drawer now) are cached once mounted, so re-fetch them each time
    // Operate opens — that is when the user is viewing them and they must
    // reflect edits made since the last open.
    if (typeof window.reloadDynamicOpsSections === 'function') window.reloadDynamicOpsSections();
  }

  // Platform panels mount the same way (sidebar render → #gd-platform-panels);
  // repopulate if the surface opens empty. Same guard as Operate.
  function gdRenderPlatform() {
    const host = document.getElementById('gd-platform-panels');
    if (host && host.children.length === 0
        && typeof updateEntityList === 'function' && typeof graphData !== 'undefined' && graphData) {
      updateEntityList(graphData);
    }
  }

  // The surfaces that own a real <section> (vs the placeholder overlay).
  const REAL_SURFACES = {
    operate: 'gd-operate',
    platform: 'gd-platform',
    settings: 'gd-settings',
  };

  // ---- Surface deep links ---------------------------------------------------
  // surface name <-> `@` hash token. `organization` is the user-facing token
  // for the internal `operate` id (mechanics + e2e stay on `operate`).
  const SURFACE_TO_HASH = { operate: '@organization', platform: '@platform', settings: '@settings' };
  const HASH_TO_SURFACE = { organization: 'operate', platform: 'platform', settings: 'settings' };

  // Mirror the active surface into the URL the same way fn selection does
  // (pushState — no hashchange feedback loop, and browser Back walks the
  // trail). Build restores the selected fn's hash, or clears it.
  function gdPushSurfaceHash(name) {
    try {
      if (name === 'build') {
        let fnHash = '';
        const lk = (typeof lookups !== 'undefined') ? lookups : null;
        const sel = (typeof selectedFnId !== 'undefined') ? selectedFnId : null;
        const fn = (sel && lk?.fnMap) ? lk.fnMap.get(sel) : null;
        if (fn && typeof getQualifiedFnName === 'function') fnHash = '#' + getQualifiedFnName(fn);
        if ((window.location.hash || '') === fnHash) return;
        window.history.pushState(null, '', window.location.pathname + window.location.search + fnHash);
      } else {
        const want = '#' + SURFACE_TO_HASH[name];
        if (window.location.hash === want) return;
        window.history.pushState(null, '', want);
      }
    } catch (_) { /* ignore — hash is a mirror, not state */ }
  }

  // Route an `@` surface hash (already stripped of '#'). Returns true when
  // the hash addressed a surface (handled here), false for fn-name hashes.
  // `@settings/account` opens Settings scrolled to the Account card.
  function gdRouteSurfaceHash(hash) {
    if (hash?.charAt(0) !== '@') {
      // A fn-name (or empty) hash while a management surface is up means the
      // user navigated BACK to the editor — return to Build without pushing.
      if (document.body.getAttribute('data-surface') !== 'build') {
        gdShellSurface('build', null, { pushHash: false });
      }
      return false;
    }
    const parts = hash.slice(1).split('/');
    const name = HASH_TO_SURFACE[parts[0]];
    if (!name) return true; // unknown @token: swallow, never a fn name
    gdShellSurface(name, null, { pushHash: false });
    if (name === 'settings' && parts[1] === 'account') {
      const card = document.getElementById('gd-set-account');
      if (card) card.scrollIntoView({ block: 'start' });
    }
    return true;
  }

  // Brand button — from any surface, back to the editor.
  function gdShellGoHome() {
    gdShellSurface('build');
  }

  function gdHideAllSurfaces() {
    const overlay = document.getElementById('gd-surface-overlay');
    if (overlay) overlay.hidden = true;
    Object.values(REAL_SURFACES).forEach((id) => {
      const el = document.getElementById(id);
      if (el) el.hidden = true;
    });
  }

  window.gdShellSurface = gdShellSurface;
  window.gdRouteSurfaceHash = gdRouteSurfaceHash;
  window.gdShellGoHome = gdShellGoHome;

  // (Review surface retired: comparing a branch against main is the per-branch
  // diff button in the branch switcher — `.branch-row-diff` → showBranchDiff in
  // editor-branches.js — not a separate rail surface.)

  // Toggle the compact-cards mode, then re-lay-out so the graph reflows to the
  // new card heights (renderGraph re-measures the overlays).
  function gdToggleCardDetails(btn) {
    const compact = document.body.classList.toggle('gd-cards-compact');
    try { localStorage.setItem('graphden.cards.compact', compact ? '1' : '0'); } catch (_) {}
    if (btn) {
      btn.setAttribute('aria-pressed', String(!compact));
      btn.title = compact ? 'Show card details' : 'Hide card details';
    }
    if (typeof renderGraph === 'function') renderGraph(true);
  }
  window.gdToggleCardDetails = gdToggleCardDetails;

  // ---- Settings surface -----------------------------------------------------
  // Personal editor settings. Delegates to the ctxbar controls that already
  // own their state (theme toggle, hard-reload) so there's a single source of
  // truth; reads sign-in via window.isAuthenticated and capabilities off the
  // body classes the fetch layer stamps (gd-tenancy / gd-no-write / gd-no-execute).
  function gdRenderSettings() {
    const dark = document.body.classList.contains('theme-dark');
    const themeBtn = document.getElementById('gd-set-theme');
    if (themeBtn) {
      themeBtn.textContent = dark ? 'Dark' : 'Light';
      // Theme lives HERE now (the top-bar quick toggle was removed as a
      // duplicate). Toggle directly — applyTheme/setDarkStored are the prefs
      // module's own state owners.
      themeBtn.onclick = () => {
        const d = !document.body.classList.contains('theme-dark');
        applyTheme(d); setDarkStored(d);
        gdRenderSettings();
      };
    }
    const compact = document.body.classList.contains('gd-cards-compact');
    const compactBtn = document.getElementById('gd-set-compact');
    if (compactBtn) {
      compactBtn.textContent = compact ? 'Compact' : 'Detailed';
      compactBtn.onclick = () => { gdToggleCardDetails(null); gdRenderSettings(); };
    }

    // Account card — the merged /account page (identity, sign-in methods,
    // 2FA, API tokens). editor-account.js owns it; hidden outside the
    // accounts addon.
    if (typeof gdRenderAccountCard === 'function') gdRenderAccountCard();

    const hashEl = document.getElementById('gd-set-hash');
    if (hashEl) {
      hashEl.textContent = (typeof window.BUILD_HASH === 'string') ? window.BUILD_HASH : '—';
    }
    const reloadBtn = document.getElementById('gd-set-reload');
    if (reloadBtn) {
      // Reload (drop cache) lives HERE now — the top-bar quick-action was a
      // duplicate. hardReload is the prefs module's own implementation.
      reloadBtn.onclick = () => { hardReload(); };
    }
    // Build hashes + capability chips are server partials — the hash rows
    // come off the same baked resource /version serves, and the ✓/✕ chips
    // render from the request-scope seam the capability HEADER is stamped
    // from, so the copy and the on/off logic live with the fact. The
    // frontend hash above deliberately stays client (window.BUILD_HASH
    // witnesses what the BROWSER runs). This module only mounts.
    const mountPartial = (id, url, once) => {
      const el = document.getElementById(id);
      if (!el || (once && el.dataset.loaded)) return;
      el.dataset.loaded = '1';
      fetch(url)
        .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
        .then((txt) => { el.innerHTML = txt; })
        .catch(() => { delete el.dataset.loaded; });
    };
    // Hashes are boot-constant → once; capabilities re-fetch per open
    // (a grant change shows on the next open, as the header-derived
    // render did before).
    mountPartial('gd-set-version', '/partials/settings-build', true);
    mountPartial('gd-set-caps', '/partials/settings-access', false);
  }
  window.gdRenderSettings = gdRenderSettings;

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

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

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
      + '<div class="gd-insp-title"><span class="gd-insp-name">' + esc(fnLabel(fn))
      + '</span><span class="gd-insp-kind">' + esc(kind) + '</span></div>';
    if (ns) head += '<div class="gd-insp-ns">' + esc(ns) + '</div>';
    if (fn.description) head += '<p class="gd-insp-desc">' + esc(fn.description) + '</p>';
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

  // ---- Workspace switcher --------------------------------------------------
  // The context-bar chip scopes the explorer to a namespace root (a "workspace"
  // is just a set of namespace roots — see editor-branches.js). "All functions"
  // clears the focus. Reuses the existing lazy tree; no new entity, no backend.
  function gdWsChipLabel() {
    const b = document.querySelector('#gd-ws-chip b');
    if (b && typeof graphdenWorkspaceLabel === 'function') b.textContent = graphdenWorkspaceLabel();
  }
  function gdCloseWsPop() {
    const p = document.getElementById('gd-ws-pop');
    if (p) p.remove();
    const s = document.getElementById('gd-ws-scrim');
    if (s) s.remove();
  }
  // Root namespaces = the "ready-made projects" you pick from (name → description).
  function gdWsRoots() {
    const out = [];
    try {
      const nss = (typeof graphData !== 'undefined' && graphData) ? (graphData.namespaces || []) : [];
      nss.forEach((n) => { if (!n['parent-id'] && n.name) out.push({ name: n.name, desc: n.description || '' }); });
    } catch (_) { /* ignore */ }
    out.sort((a, b) => a.name.localeCompare(b.name));
    return out;
  }
  function gdWsRepaint() {
    gdWsChipLabel();
    if (typeof updateEntityList === 'function' && typeof graphData !== 'undefined') updateEntityList(graphData);
  }
  function gdOpenWsPop() {
    gdCloseWsPop();
    const chip = document.getElementById('gd-ws-chip');
    if (!chip) return;
    const scrim = document.createElement('div');
    scrim.id = 'gd-ws-scrim';
    scrim.className = 'gd-pop-scrim';
    scrim.addEventListener('click', gdCloseWsPop);
    document.body.appendChild(scrim);

    const pop = document.createElement('div');
    pop.id = 'gd-ws-pop';
    pop.className = 'gd-pop';
    const r = chip.getBoundingClientRect();
    pop.style.left = r.left + 'px';
    pop.style.top = (r.bottom + 6) + 'px';

    // Re-rendered in place on every toggle so you can compose a workspace
    // without the popover closing (multi-select checklist).
    const render = () => {
      const roots = gdWsRoots();
      // window-qualified: the bare identifier is shadowed by editor-branches.js's
      // top-level `let graphdenWorkspaceRoots` (the backing ARRAY), so only the
      // window property reaches the () accessor that returns a copy.
      const current = (typeof window.graphdenWorkspaceRoots === 'function') ? window.graphdenWorkspaceRoots() : [];
      const hidden = (typeof graphdenHiddenList === 'function') ? graphdenHiddenList() : [];
      const active = current.length > 0;
      let html = '<h5>Workspace — choose what you see</h5>'
        + '<button type="button" class="gd-pop-item' + (active ? '' : ' sel') + '" data-ws-all="1">'
        + '<span class="gd-pi">◍</span>All functions</button>'
        + '<div class="gd-pop-div"></div>'
        + '<div class="gd-pop-cap">Projects — tick the namespaces you work in</div>';
      roots.forEach((n) => {
        const on = current.indexOf(n.name) >= 0;
        html += '<button type="button" class="gd-pop-item gd-ws-opt' + (on ? ' sel' : '') + '"'
          + ' role="checkbox" aria-checked="' + (on ? 'true' : 'false') + '" data-ws="' + esc(n.name) + '"'
          + (n.desc ? ' title="' + esc(n.desc) + '"' : '') + '>'
          + '<span class="gd-pi">' + (on ? '☑' : '☐') + '</span>'
          + '<span class="gd-ws-nm">' + esc(n.name) + '</span>'
          + (n.desc ? '<span class="gd-ws-desc">' + esc(n.desc) + '</span>' : '')
          + '</button>';
      });
      if (hidden.length) {
        html += '<div class="gd-pop-div"></div>'
          + '<div class="gd-pop-cap">Hidden by you — restore to your view</div>';
        hidden.slice().sort((a, b) => a.localeCompare(b)).forEach((h) => {
          html += '<div class="gd-pop-row">'
            + '<span class="gd-pop-item gd-ws-hidden" title="Hidden from your explorer">'
            +   '<span class="gd-pi">⦸</span>' + esc(h) + '</span>'
            + '<button type="button" class="gd-pop-pin" data-restore="' + esc(h) + '"'
            +   ' title="Restore to view">↺</button></div>';
        });
      }
      html += '<div class="gd-pop-hint">Personal + per-browser (like your branch choice). '
        + 'Hide a namespace from its ⊘ in the tree. Nothing here changes the shared graph.</div>';
      pop.innerHTML = html;

      pop.querySelector('[data-ws-all]').addEventListener('click', () => {
        if (typeof setGraphdenWorkspace === 'function') setGraphdenWorkspace(null);
        gdWsRepaint(); gdCloseWsPop();
      });
      pop.querySelectorAll('.gd-ws-opt').forEach((it) => {
        it.addEventListener('click', () => {
          if (typeof graphdenToggleWorkspaceRoot === 'function') graphdenToggleWorkspaceRoot(it.getAttribute('data-ws'));
          gdWsRepaint(); render();   // keep open, reflect the tick
        });
      });
      pop.querySelectorAll('[data-restore]').forEach((rb) => {
        rb.addEventListener('click', (e) => {
          e.stopPropagation();
          if (typeof graphdenToggleHidden === 'function') graphdenToggleHidden(rb.getAttribute('data-restore'));
          gdWsRepaint(); render();
        });
      });
    };
    render();
    document.body.appendChild(pop);
  }

  const wsChip = document.getElementById('gd-ws-chip');
  if (wsChip) wsChip.addEventListener('click', gdOpenWsPop);
  gdWsChipLabel();

  // ---- Packages (build-surface: browse + install) --------------------------
  // Install is a BUILD act (add a dependency to your project), so it lives with
  // the workspace/branch context — not on the Organization admin page. The chip
  // shows only when the OPTIONAL registry package is present (its /api/packages/*
  // routes appear in window.API only when its router was installed at boot);
  // its popover lazy-loads the same server-rendered panel.
  function gdRevealPkgChip() {
    const chip = document.getElementById('gd-pkg-chip');
    if (!chip) return;
    chip.hidden = !(typeof window.API === 'object' && window.API
      && typeof window.API.api_packages_installed !== 'undefined');
  }
  function gdClosePkgPop() {
    const p = document.getElementById('gd-pkg-pop'); if (p) p.remove();
    const s = document.getElementById('gd-pkg-scrim'); if (s) s.remove();
  }
  function gdOpenPkgPop() {
    gdClosePkgPop();
    const chip = document.getElementById('gd-pkg-chip');
    if (!chip) return;
    const scrim = document.createElement('div');
    scrim.id = 'gd-pkg-scrim';
    scrim.className = 'gd-pop-scrim';
    scrim.addEventListener('click', gdClosePkgPop);
    document.body.appendChild(scrim);
    const pop = document.createElement('div');
    pop.id = 'gd-pkg-pop';
    pop.className = 'gd-pop';
    pop.innerHTML = '<h5>Packages</h5>'
      + '<div class="ns-children" hx-get="/partials/packages-panel" hx-trigger="load" hx-swap="innerHTML">'
      +   '<div class="loading">Loading…</div></div>';
    const r = chip.getBoundingClientRect();
    pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - 468)) + 'px';
    pop.style.top = (r.bottom + 6) + 'px';
    document.body.appendChild(pop);
    if (window.htmx && typeof window.htmx.process === 'function') window.htmx.process(pop);
  }
  const pkgChip = document.getElementById('gd-pkg-chip');
  if (pkgChip) pkgChip.addEventListener('click', gdOpenPkgPop);
  gdRevealPkgChip();
})();

// Escape closes the topmost context-bar popover (`.gd-pop`: workspace,
// packages, publish, branch-policy). Each of them already closes on its scrim
// click, and each owns a different close function — so route the key through
// the scrim rather than teaching this handler four APIs. Without it Escape
// did nothing there while every other popover in the editor answered it, and
// during the interactive tutorial it reached the tour instead and ended the
// lesson. `preventDefault` marks the key consumed (see graphden-popover.js).
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  const scrims = document.querySelectorAll('.gd-pop-scrim');
  if (!scrims.length) return;
  e.preventDefault();
  scrims[scrims.length - 1].click();
});
