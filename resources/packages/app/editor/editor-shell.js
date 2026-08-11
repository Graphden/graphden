// Editor Shell — redesign 2026-08. Owns the left-rail surface switching.
//
// Build is the graph editor. Review opens the branch-diff modal; Operate /
// Workspaces / Settings are real <section>s (see REAL_SURFACES). The rail's
// inline onclick calls the single exported entry point
// `window.gdShellSurface(name, btn)`. (There is no Run surface — running a fn
// is the ▶ action on its node/card; its history is the inspector Runs tab.)
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
  function gdShellSurface(name, btn) {
    gdSetRailPressed(btn ? btn.getAttribute('data-surface') : name);
    gdHideAllSurfaces();
    // Record the active surface so surface-scoped chrome can gate on it — the
    // Explorer's left-edge expand tab only makes sense on Build (the other
    // surfaces are full overlays with no Explorer).
    document.body.setAttribute('data-surface', name);

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

  // The rail surfaces that own a real <section> (vs the placeholder overlay).
  const REAL_SURFACES = {
    operate: 'gd-operate',
    platform: 'gd-platform',
    settings: 'gd-settings',
  };

  function gdSetRailPressed(activeSurface) {
    const rail = document.getElementById('gd-rail');
    if (!rail) return;
    rail.querySelectorAll('.gd-rail-btn[data-surface]').forEach((b) => {
      b.setAttribute('aria-pressed', b.getAttribute('data-surface') === activeSurface ? 'true' : 'false');
    });
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

    // (No Account card here — your account is the single hub behind the avatar
    // chip in the top bar: who you are, Account & security → /account, Sign
    // out / Sign out everywhere. Duplicating it in Settings only split the
    // identity actions across two places.)

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
    const verEl = document.getElementById('gd-set-version');
    if (verEl && !verEl.dataset.loaded) {
      verEl.dataset.loaded = '1';
      fetch('/version')
        .then((r) => (r.ok ? r.json() : null))
        .then((v) => {
          if (!v) return;
          verEl.innerHTML = ['backend', 'packages'].map((k) =>
            '<div class="gd-set-ver"><span>' + k + '</span><code>'
            + esc(String(v[k] || '—').slice(0, 12)) + '</code></div>').join('');
        })
        .catch(() => {});
    }

    const caps = document.getElementById('gd-set-caps');
    if (caps) {
      if (document.body.classList.contains('gd-tenancy')) {
        const canWrite = !document.body.classList.contains('gd-no-write');
        const canExec = !document.body.classList.contains('gd-no-execute');
        const chip = (label, on) => '<span class="gd-cap ' + (on ? 'gd-cap-on' : 'gd-cap-off')
          + '">' + (on ? '✓ ' : '✕ ') + label + '</span>';
        caps.innerHTML = chip('write', canWrite) + chip('execute', canExec);
      } else {
        caps.innerHTML = '<div class="gd-set-hint">Single-tenant — every action is available.</div>';
      }
    }
  }
  window.gdRenderSettings = gdRenderSettings;

  // ---- Shared helpers -------------------------------------------------------
  function gdFnKind(fn) {
    const parentIds = Array.isArray(fn['parent-ids']) ? fn['parent-ids'] : [];
    return parentIds.length ? 'fn-def' : (fn['return-type-fn-id'] ? 'base-fn' : 'type');
  }

  // ---- Workspaces surface ---------------------------------------------------
  // A full-page home for the two orthogonal scoping axes:
  //   • SPATIAL — focus a set of namespace roots (the explorer hides the rest)
  //     + pin shared libraries so they stay visible under any focus;
  //   • PERSONAL — a ~-prefixed branch is your private overlay.
  // Reuses the same bundle-lexical workspace API the ctxbar chip popover uses
  // (setGraphdenWorkspace / graphdenWorkspaceRoots / graphdenPins /
  // graphdenTogglePin / updateEntityList) — no new state, no backend.
  function gdWsTopLevelRoots() {
    const roots = [];
    try {
      const nss = (typeof graphData !== 'undefined' && graphData) ? (graphData.namespaces || []) : [];
      nss.forEach((n) => { if (!n['parent-id'] && n.name) roots.push(n.name); });
    } catch (_) { /* ignore */ }
    return roots.sort((a, b) => a.localeCompare(b));
  }

  function gdWsApplyFocus(roots) {
    if (typeof setGraphdenWorkspace === 'function') setGraphdenWorkspace(roots);
    gdWsChipLabel();
    if (typeof updateEntityList === 'function' && typeof graphData !== 'undefined') {
      updateEntityList(graphData);
    }
  }

  function gdRenderWorkspaces() {
    const host = document.getElementById('gd-workspaces-body');
    if (!host) return;
    const roots = gdWsTopLevelRoots();
    const current = (typeof graphdenWorkspaceRoots === 'function') ? graphdenWorkspaceRoots() : [];
    const pins = (typeof graphdenPins === 'function') ? graphdenPins() : [];
    const focused = current.length > 0;
    const branch = (typeof getCurrentBranchName === 'function') ? getCurrentBranchName() : 'main';
    const isPersonal = branch.charAt(0) === '~';

    let ns = '<div class="gd-ws-nslist">';
    if (!roots.length) {
      ns += '<div class="gd-set-hint">No namespaces yet.</div>';
    }
    roots.forEach((nm) => {
      const inFocus = current.indexOf(nm) >= 0;
      const pinned = pins.indexOf(nm) >= 0;
      ns += '<div class="gd-ws-nsrow">'
        + '<button type="button" class="gd-ws-nsbtn' + (inFocus ? ' active' : '')
        +   '" data-ws-toggle="' + esc(nm) + '" aria-pressed="' + inFocus + '">'
        +   '<span class="gd-pi">&#955;</span>' + esc(nm) + '</button>'
        + '<button type="button" class="gd-ws-pin' + (pinned ? ' pinned' : '')
        +   '" data-ws-pin="' + esc(nm) + '" aria-pressed="' + pinned + '" title="'
        +   (pinned ? 'Unpin' : 'Pin — keep visible under any focus') + '">&#128204;</button>'
        + '</div>';
    });
    ns += '</div>';

    host.innerHTML =
      '<div class="gd-ws-grid">'
      + '<div class="gd-set-card"><h2>Scope</h2>'
      +   '<p class="gd-set-hint">Focus the namespaces you work in — the explorer '
      +   'hides everything else. Pin shared libraries (&#128204;) to keep them '
      +   'in view under any focus.</p>'
      +   '<div class="gd-ws-summary">Focus: <b>' + esc(focused ? current.join(', ') : 'All functions') + '</b>'
      +     (focused ? ' <button type="button" class="gd-set-btn" id="gd-ws-clear">Show all</button>' : '')
      +   '</div>'
      +   ns
      + '</div>'
      + '<div class="gd-set-card"><h2>Personal overlay</h2>'
      +   '<p class="gd-set-hint">Scope is spatial; a personal <b>branch</b> is your '
      +   'private overlay — install a package or add scratch fns only you see, then '
      +   'drop it. Personal branches carry a <code>~</code> prefix.</p>'
      +   '<div class="gd-set-row"><div class="gd-set-copy">'
      +     '<div class="gd-set-label">Current branch</div>'
      +     '<div class="gd-set-hint">' + (isPersonal ? 'Personal — private to you.' : 'Shared with the team.') + '</div></div>'
      +     '<span class="gd-chip ' + (isPersonal ? 'gd-bind gd-bind-free' : 'gd-chip-ref') + '">' + esc(branch) + '</span></div>'
      +   '<button type="button" class="gd-set-btn" id="gd-ws-branches">Manage branches…</button>'
      + '</div>'
      + '</div>';

    const clear = document.getElementById('gd-ws-clear');
    if (clear) clear.onclick = () => { gdWsApplyFocus(null); gdRenderWorkspaces(); };
    host.querySelectorAll('[data-ws-toggle]').forEach((b) => {
      b.onclick = () => {
        const nm = b.getAttribute('data-ws-toggle');
        const cur = (typeof graphdenWorkspaceRoots === 'function') ? graphdenWorkspaceRoots() : [];
        const i = cur.indexOf(nm);
        if (i >= 0) cur.splice(i, 1); else cur.push(nm);
        gdWsApplyFocus(cur.length ? cur : null);
        gdRenderWorkspaces();
      };
    });
    host.querySelectorAll('[data-ws-pin]').forEach((b) => {
      b.onclick = () => {
        if (typeof graphdenTogglePin === 'function') graphdenTogglePin(b.getAttribute('data-ws-pin'));
        if (typeof updateEntityList === 'function' && typeof graphData !== 'undefined') {
          updateEntityList(graphData);
        }
        gdRenderWorkspaces();
      };
    });
    const br = document.getElementById('gd-ws-branches');
    if (br) {
      br.onclick = () => { const c = document.getElementById('branch-chip-btn'); if (c) c.click(); };
    }
  }
  window.gdRenderWorkspaces = gdRenderWorkspaces;

  // ---- Right inspector ------------------------------------------------------
  // First slice: identity Overview off DIRECT fn fields the client already has
  // (name, namespace, description, parents) — no re-derivation of server-known
  // state. The rich panels (bindings, resolved types, effects, provenance, and
  // this function's OWN stats/errors) arrive next as `GET /partials/inspector`.
  // graph-first-exception (temporary, tracked): richer content migrates to a
  // server-rendered partial; this keeps the selection→inspector loop legible.
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

    const parentIds = Array.isArray(fn['parent-ids']) ? fn['parent-ids'] : [];
    const kind = gdFnKind(fn);

    let head = '<div class="gd-insp-head">'
      + '<button type="button" class="gd-insp-close" aria-label="Close inspector">&times;</button>'
      + '<div class="gd-insp-title"><span class="gd-insp-name">' + esc(fnLabel(fn))
      + '</span><span class="gd-insp-kind">' + esc(kind) + '</span></div>';
    if (ns) head += '<div class="gd-insp-ns">' + esc(ns) + '</div>';
    if (fn.description) head += '<p class="gd-insp-desc">' + esc(fn.description) + '</p>';
    head += '</div>';

    const tabbar = '<div class="gd-insp-tabs" role="tablist">'
      + INSP_TABS.map((t) => '<button type="button" class="gd-insp-tab'
          + (t.id === inspTab ? ' active' : '') + '" role="tab" aria-selected="'
          + (t.id === inspTab) + '" data-insp-tab="' + t.id + '">'
          + t.label + '</button>').join('')
      + '</div>';

    el.innerHTML = head + tabbar + '<div id="gd-insp-tabbody" class="gd-insp-scroll"></div>';
    el.querySelectorAll('.gd-insp-tab').forEach((b) => {
      b.addEventListener('click', () => {
        if (inspTab === b.dataset.inspTab) return;
        inspTab = b.dataset.inspTab;
        el.querySelectorAll('.gd-insp-tab').forEach((x) => {
          const on = x.dataset.inspTab === inspTab;
          x.classList.toggle('active', on);
          x.setAttribute('aria-selected', String(on));
        });
        renderInspTab(fnId, fn, kind, parentIds, lk);
      });
    });
    renderInspTab(fnId, fn, kind, parentIds, lk);

    // On narrow viewports the inspector is a bottom sheet — reveal it on
    // selection; the × (shown only ≤1100 via CSS) dismisses it. On wide
    // viewports the class is inert (the inspector is a static column).
    document.body.classList.add('gd-insp-open');
    const closeBtn = el.querySelector('.gd-insp-close');
    if (closeBtn) closeBtn.onclick = () => document.body.classList.remove('gd-insp-open');
  }

  function inspRow(k, v) {
    return '<div class="gd-insp-row"><span class="gd-insp-k">' + k + '</span>' + v + '</div>';
  }

  // Overview = identity extras (parents / returns / effects) off DIRECT fn
  // fields + the SAME server-computed richTypes the card strips read.
  function gdInspOverviewHtml(fn, parentIds, lk) {
    let html = '';
    const parentChips = parentIds.map((pid) => {
      const p = lk?.fnMap?.get(pid) ?? null;
      return '<span class="gd-chip gd-chip-ref">→ ' + esc(fnLabel(p)) + '</span>';
    }).join(' ');
    if (parentChips) html += inspRow('Parent', '<span class="gd-insp-v">' + parentChips + '</span>');

    const rt = (typeof richTypes === 'object' && richTypes && fn.name) ? richTypes[fn.name] : null;
    let returnStr = (rt && typeof formatTypeHint === 'function') ? formatTypeHint(rt.return) : null;
    if (!returnStr && fn['return-type']) returnStr = fn['return-type'];
    if (returnStr) {
      html += inspRow('Returns', '<span class="gd-insp-v"><span class="gd-chip gd-chip-type">'
        + esc(returnStr) + '</span></span>');
    }
    const effects = (rt && Array.isArray(rt.effects)) ? rt.effects : [];
    if (effects.length) {
      const effChips = effects.map((e) => {
        const nm = String(e).replace(/^:/, '');
        return '<span class="effects-chip effects-chip-' + esc(nm) + '">' + esc(nm) + '</span>';
      }).join(' ');
      html += inspRow('Effects', '<span class="gd-insp-v gd-insp-effects">' + effChips + '</span>');
    } else if (rt) {
      html += inspRow('Effects', '<span class="gd-insp-v gd-insp-pure">pure</span>');
    }
    return html || '<div class="gd-insp-sec-empty">No further type info.</div>';
  }

  function renderInspTab(fnId, fn, kind, parentIds, lk) {
    const body = document.getElementById('gd-insp-tabbody');
    if (!body) return;
    if (inspTab === 'overview') {
      body.innerHTML = gdInspOverviewHtml(fn, parentIds, lk);
      return;
    }
    if (inspTab === 'bindings') {
      body.innerHTML = '<div id="gd-insp-detail" class="gd-insp-detail-host">'
        + '<div class="gd-insp-runs-loading">Loading bindings…</div></div>';
      gdLoadInspectorDetail(fnId);
      return;
    }
    if (inspTab === 'stats') {
      body.innerHTML = '<div id="gd-insp-runs" class="gd-insp-runs">'
        + '<div class="gd-insp-runs-loading">Loading runs…</div></div>';
      gdLoadInspectorRuns(fnId);
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
        if (host) host.innerHTML = txt;
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
  function gdOpenWsPop() {
    gdCloseWsPop();
    const chip = document.getElementById('gd-ws-chip');
    if (!chip) return;
    const scrim = document.createElement('div');
    scrim.id = 'gd-ws-scrim';
    scrim.className = 'gd-pop-scrim';
    scrim.addEventListener('click', gdCloseWsPop);
    document.body.appendChild(scrim);

    const roots = [];
    try {
      const nss = (typeof graphData !== 'undefined' && graphData) ? (graphData.namespaces || []) : [];
      nss.forEach((n) => { if (!n['parent-id'] && n.name) roots.push(n.name); });
    } catch (_) { /* ignore */ }
    roots.sort((a, b) => a.localeCompare(b));
    const current = (typeof graphdenWorkspaceRoots === 'function') ? graphdenWorkspaceRoots() : [];
    const focused = current.length > 0;

    const pins = (typeof graphdenPins === 'function') ? graphdenPins() : [];
    let html = '<h5>Workspace — scope the explorer</h5>'
      + '<button type="button" class="gd-pop-item' + (focused ? '' : ' sel') + '" data-ws="">'
      + '<span class="gd-pi">◍</span>All functions</button><div class="gd-pop-div"></div>';
    roots.forEach((nm) => {
      const sel = current.indexOf(nm) >= 0 ? ' sel' : '';
      const pinned = pins.indexOf(nm) >= 0;
      html += '<div class="gd-pop-row">'
        + '<button type="button" class="gd-pop-item' + sel + '" data-ws="' + esc(nm) + '">'
        +   '<span class="gd-pi">&#955;</span>' + esc(nm) + '</button>'
        + '<button type="button" class="gd-pop-pin' + (pinned ? ' pinned' : '') + '"'
        +   ' data-pin="' + esc(nm) + '" aria-pressed="' + (pinned ? 'true' : 'false') + '"'
        +   ' title="' + (pinned ? 'Unpin' : 'Pin — keep visible even when scoped') + '">&#128204;</button>'
        + '</div>';
    });
    html += '<div class="gd-pop-hint">Pin shared libraries (&#128204;) to keep them in view under any workspace.</div>';

    const pop = document.createElement('div');
    pop.id = 'gd-ws-pop';
    pop.className = 'gd-pop';
    pop.innerHTML = html;
    const r = chip.getBoundingClientRect();
    pop.style.left = r.left + 'px';
    pop.style.top = (r.bottom + 6) + 'px';
    pop.querySelectorAll('.gd-pop-item').forEach((it) => {
      it.addEventListener('click', () => {
        const ws = it.getAttribute('data-ws');
        if (typeof setGraphdenWorkspace === 'function') setGraphdenWorkspace(ws ? [ws] : null);
        gdWsChipLabel();
        if (typeof updateEntityList === 'function' && typeof graphData !== 'undefined') {
          updateEntityList(graphData);
        }
        gdCloseWsPop();
      });
    });
    pop.querySelectorAll('.gd-pop-pin').forEach((pb) => {
      pb.addEventListener('click', (e) => {
        e.stopPropagation(); // pin, don't scope
        const root = pb.getAttribute('data-pin');
        if (typeof graphdenTogglePin === 'function') graphdenTogglePin(root);
        const nowPinned = (typeof graphdenIsPinned === 'function') && graphdenIsPinned(root);
        pb.classList.toggle('pinned', nowPinned);
        pb.setAttribute('aria-pressed', nowPinned ? 'true' : 'false');
        pb.title = nowPinned ? 'Unpin' : 'Pin — keep visible even when scoped';
        if (typeof updateEntityList === 'function' && typeof graphData !== 'undefined') {
          updateEntityList(graphData);
        }
      });
    });
    document.body.appendChild(pop);
  }

  const wsChip = document.getElementById('gd-ws-chip');
  if (wsChip) wsChip.addEventListener('click', gdOpenWsPop);
  gdWsChipLabel();
})();
