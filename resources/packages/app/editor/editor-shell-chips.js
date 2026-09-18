// Editor Shell CHIPS — the Explorer's Workspace and Packages popovers.
//
// Two of the three Explorer
// context-row chips (the branch chip is editor-branches.js): the WORKSPACE chip
// (`#gd-ws-chip` → `gdOpenWsPop`, a multi-select checklist of root-namespace
// "projects" plus restore of ⊘-hidden namespaces; the store itself is
// editor-branch-context.js) and the PACKAGES chip (`#gd-pkg-chip`, Build
// surface only): `gdRevealPkgChip` shows it only when the optional `registry`
// package is present (`window.API.api_packages_installed` probe, never a name),
// `gdOpenPkgPop` opens the `#gd-pkg-pop` panel that lazy-loads
// `GET /partials/packages-panel`. Install is a BUILD act, so it lives here and
// not on the Organization pane. Both are `.gd-pop`s over a transparent scrim
// and carry the shared × (`ensurePopoverClose`). Wires the chips at load.

(() => {

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
          + ' role="checkbox" aria-checked="' + (on ? 'true' : 'false') + '" data-ws="' + gdEscHtml(n.name) + '"'
          + (n.desc ? ' title="' + gdEscHtml(n.desc) + '"' : '') + '>'
          + '<span class="gd-pi">' + (on ? '☑' : '☐') + '</span>'
          + '<span class="gd-ws-nm">' + gdEscHtml(n.name) + '</span>'
          + (n.desc ? '<span class="gd-ws-desc">' + gdEscHtml(n.desc) + '</span>' : '')
          + '</button>';
      });
      if (hidden.length) {
        html += '<div class="gd-pop-div"></div>'
          + '<div class="gd-pop-cap">Hidden by you — restore to your view</div>';
        hidden.slice().sort((a, b) => a.localeCompare(b)).forEach((h) => {
          html += '<div class="gd-pop-row">'
            + '<span class="gd-pop-item gd-ws-hidden" title="Hidden from your explorer">'
            +   '<span class="gd-pi">⦸</span>' + gdEscHtml(h) + '</span>'
            + '<button type="button" class="gd-pop-pin" data-restore="' + gdEscHtml(h) + '"'
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
      // Inside render(), after the innerHTML: ticking a namespace re-renders
      // in place, which would otherwise drop the button. The scrim behind
      // every .gd-pop is a transparent click-catcher, not a dimmed backdrop,
      // so "click outside" is a move the reader has to guess — the titled
      // PANELS in this family carry a visible × too. (The plain menus —
      // branch policy, protection, the diff chip — stay bare.)
      if (typeof ensurePopoverClose === 'function') {
        ensurePopoverClose(pop, gdCloseWsPop, 'Close workspace picker', {prepend: true});
      }
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
  window.gdClosePkgPop = () => gdClosePkgPop();
  function gdClosePkgPop() {
    const p = document.getElementById('gd-pkg-pop'); if (p) p.remove();
    const s = document.getElementById('gd-pkg-scrim'); if (s) s.remove();
  }
  // The panel is fetched EXPLICITLY and processed synchronously after the
  // swap — not via `hx-trigger="load"`. htmx fires a load trigger on a
  // timer, and a second open (the tutorial re-targets the chip, a user
  // double-clicks) in that window re-created the popover while the first
  // load was still landing: the panel showed up, but the buttons inside it
  // were never processed by htmx, so Install did nothing (lesson 29's
  // "server idle" flake — 2/5 runs, htmx-internal-data absent on the
  // button). One sequence counter: only the newest open's response lands.
  let _pkgPopSeq = 0;
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
      + '<div class="ns-children"><div class="loading">Loading…</div></div>';
    if (typeof ensurePopoverClose === 'function') {
      ensurePopoverClose(pop, gdClosePkgPop, 'Close packages panel', {prepend: true});
    }
    const r = chip.getBoundingClientRect();
    pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - 468)) + 'px';
    pop.style.top = (r.bottom + 6) + 'px';
    document.body.appendChild(pop);
    const mount = pop.querySelector('.ns-children');
    const seq = ++_pkgPopSeq;
    const fetcher = typeof window.authFetch === 'function' ? window.authFetch : fetch;
    fetcher('/partials/packages-panel')
      .then((resp) => resp.text())
      .then((html) => {
        // A newer open replaced this popover, or it was closed meanwhile.
        if (seq !== _pkgPopSeq || !mount.isConnected) return;
        mount.innerHTML = html;
        if (window.htmx && typeof window.htmx.process === 'function') window.htmx.process(mount);
      })
      .catch(() => {
        if (seq === _pkgPopSeq && mount.isConnected) {
          mount.innerHTML = '<div class="loading">Failed to load the packages panel</div>';
        }
      });
  }
  const pkgChip = document.getElementById('gd-pkg-chip');
  if (pkgChip) pkgChip.addEventListener('click', gdOpenPkgPop);
  gdRevealPkgChip();
})();
