// Editor Shell — redesign 2026-08. Owns the left-rail surface switching.
//
// Build is the live surface (the graph editor). The other surfaces (Run /
// Review / Operate / Workspaces / Settings) show a placeholder overlay until
// they are rebuilt, one at a time. The rail's inline onclick calls the single
// exported entry point `window.gdShellSurface(name, btn)`.
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

  // Copy is written from the user's side of the screen — each line says what
  // the surface will DO, not how it's wired.
  const SURFACES = {
    run: {
      label: 'Run',
      sub: 'Execute a function, fill its free arguments, acknowledge side effects, and trace the path. This surface is being rebuilt next.',
    },
    review: {
      label: 'Review',
      sub: 'Diff a branch and merge it — where a teammate, or an AI on its own branch, proposes changes as a graph diff. Being rebuilt next.',
    },
    operate: {
      label: 'Operate',
      sub: 'Services, monitoring, packages and apps — grouped by job instead of stacked as sidebar panels. Being rebuilt next.',
    },
    workspaces: {
      label: 'Workspaces',
      sub: 'Scope a project to a set of namespaces, and give each person a private branch overlay to tinker without touching the team. Being rebuilt next.',
    },
    settings: {
      label: 'Settings',
      sub: 'Account, API tokens, and the org access model — who may touch what. Being rebuilt next.',
    },
  };

  function gdShellSurface(name, btn) {
    const rail = document.getElementById('gd-rail');
    if (rail) {
      const buttons = rail.querySelectorAll('.gd-rail-btn[data-surface]');
      buttons.forEach((b) => {
        b.setAttribute('aria-pressed', b === btn ? 'true' : 'false');
      });
    }

    const overlay = document.getElementById('gd-surface-overlay');
    const operate = document.getElementById('gd-operate');

    // Build = the graph editor (explorer | canvas | inspector), no cover.
    if (name === 'build') {
      if (overlay) overlay.hidden = true;
      if (operate) operate.hidden = true;
      return;
    }

    // Operate has a real surface (the relocated ops/admin panels).
    if (name === 'operate') {
      if (overlay) overlay.hidden = true;
      if (operate) operate.hidden = false;
      return;
    }

    // The rest still show a placeholder until they're rebuilt.
    if (operate) operate.hidden = true;
    if (!overlay) return;
    const surface = SURFACES[name] || { label: name, sub: '' };
    const title = overlay.querySelector('.gd-surface-title');
    const sub = overlay.querySelector('.gd-surface-sub');
    if (title) title.textContent = surface.label;
    if (sub) sub.textContent = surface.sub;
    overlay.hidden = false;
  }

  window.gdShellSurface = gdShellSurface;

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

  function gdInspectorRender(fnId) {
    const el = document.getElementById('gd-inspector');
    if (!el) return;

    // `lookups` is a bundle-level `let` (not a window property), so read the
    // lexical global directly rather than `window.lookups` (which is undefined).
    const lk = (typeof lookups !== 'undefined') ? lookups : null;
    const fn = (fnId && lk?.fnMap) ? lk.fnMap.get(fnId) : null;
    if (!fn) { el.innerHTML = INSP_EMPTY; return; }

    // Namespace = the qualified name minus the fn's own last segment.
    let ns = '';
    if (typeof getQualifiedFnName === 'function') {
      const parts = getQualifiedFnName(fn).split('.');
      parts.pop();
      ns = parts.join('.');
    }

    const parentIds = Array.isArray(fn['parent-ids']) ? fn['parent-ids'] : [];
    const kind = parentIds.length ? 'fn-def'
      : (fn['return-type-fn-id'] ? 'base-fn' : 'type');

    const parentChips = parentIds.map((pid) => {
      const p = lk.fnMap.get(pid);
      return '<span class="gd-chip gd-chip-ref">→ ' + esc(fnLabel(p)) + '</span>';
    }).join(' ');

    let html = '<div class="gd-insp-head">'
      + '<div class="gd-insp-title"><span class="gd-insp-name">' + esc(fnLabel(fn))
      + '</span><span class="gd-insp-kind">' + esc(kind) + '</span></div>';
    if (ns) html += '<div class="gd-insp-ns">' + esc(ns) + '</div>';
    if (fn.description) html += '<p class="gd-insp-desc">' + esc(fn.description) + '</p>';
    html += '</div><div class="gd-insp-scroll">';
    if (parentChips) {
      html += '<div class="gd-insp-row"><span class="gd-insp-k">Parent</span>'
        + '<span class="gd-insp-v">' + parentChips + '</span></div>';
    }

    // Returns + Effects reuse the SAME server-computed `richTypes` the card
    // strips read (keyed by fn name) — not a client re-derivation.
    const rt = (typeof richTypes === 'object' && richTypes && fn.name)
      ? richTypes[fn.name] : null;
    let returnStr = (rt && typeof formatTypeHint === 'function')
      ? formatTypeHint(rt.return) : null;
    if (!returnStr && fn['return-type']) returnStr = fn['return-type'];
    if (returnStr) {
      html += '<div class="gd-insp-row"><span class="gd-insp-k">Returns</span>'
        + '<span class="gd-insp-v"><span class="gd-chip gd-chip-type">'
        + esc(returnStr) + '</span></span></div>';
    }
    const effects = (rt && Array.isArray(rt.effects)) ? rt.effects : [];
    if (effects.length) {
      const effChips = effects.map((e) => {
        const nm = String(e).replace(/^:/, '');
        return '<span class="effects-chip effects-chip-' + esc(nm) + '">'
          + esc(nm) + '</span>';
      }).join(' ');
      html += '<div class="gd-insp-row"><span class="gd-insp-k">Effects</span>'
        + '<span class="gd-insp-v gd-insp-effects">' + effChips + '</span></div>';
    } else if (rt) {
      html += '<div class="gd-insp-row"><span class="gd-insp-k">Effects</span>'
        + '<span class="gd-insp-v gd-insp-pure">pure</span></div>';
    }

    html += '<div class="gd-insp-runs-head">Runs &amp; stats</div>'
      + '<div id="gd-insp-runs" class="gd-insp-runs">'
      + '<div class="gd-insp-runs-loading">Loading runs…</div></div>'
      + '<p class="gd-insp-soon">Bindings and provenance are coming here next — '
      + 'rendered from the graph.</p>'
      + '</div>';
    el.innerHTML = html;
    gdLoadInspectorRuns(fnId);
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

    let html = '<h5>Workspace — scope the explorer</h5>'
      + '<button type="button" class="gd-pop-item' + (focused ? '' : ' sel') + '" data-ws="">'
      + '<span class="gd-pi">◍</span>All functions</button><div class="gd-pop-div"></div>';
    roots.forEach((nm) => {
      const sel = current.indexOf(nm) >= 0 ? ' sel' : '';
      html += '<button type="button" class="gd-pop-item' + sel + '" data-ws="' + esc(nm) + '">'
        + '<span class="gd-pi">&#955;</span>' + esc(nm) + '</button>';
    });

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
    document.body.appendChild(pop);
  }

  const wsChip = document.getElementById('gd-ws-chip');
  if (wsChip) wsChip.addEventListener('click', gdOpenWsPop);
  gdWsChipLabel();
})();
