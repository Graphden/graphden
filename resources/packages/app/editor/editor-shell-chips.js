// Editor Shell CHIPS — the Explorer's Packages popover.
//
// Of the three Explorer context-row chips, the branch chip is
// editor-branches.js and the VIEW chip (`#gd-ws-chip`) is
// editor-explorer-filters.js; this module owns the PACKAGES chip
// (`#gd-pkg-chip`, Build surface only): `gdRevealPkgChip` shows it only when
// the optional `registry` package is present (`window.API.api_packages_installed`
// probe, never a name), `gdOpenPkgPop` opens the `#gd-pkg-pop` panel that
// lazy-loads `GET /partials/packages-panel`. Install is a BUILD act, so it
// lives here and not on the Organization pane. A `.gd-pop` over a transparent
// scrim with the shared × (`ensurePopoverClose`). Wires the chip at load.

(() => {

  // ---- View chip --------------------------------------------------------
  // `#gd-ws-chip` is the VIEW chip — the active filter set's name (a saved
  // view, "N filters" or "All functions") and the popover of saved views.
  // Owned by editor-explorer-filters.js; nothing to wire here.


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
  // were never processed by htmx, so Install did nothing (lesson 30's
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
