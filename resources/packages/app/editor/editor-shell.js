// Editor Shell — redesign 2026-08. Owns the left-rail surface switching.
//
// Build is the live surface (the graph editor). The other surfaces (Run /
// Review / Operate / Workspaces / Settings) show a placeholder overlay until
// they are rebuilt, one at a time. The rail's inline onclick calls the single
// exported entry point `window.gdShellSurface(name, btn)`.
(function () {
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
    if (!overlay) return;

    if (name === 'build') {
      overlay.hidden = true;
      return;
    }

    const surface = SURFACES[name] || { label: name, sub: '' };
    const title = overlay.querySelector('.gd-surface-title');
    const sub = overlay.querySelector('.gd-surface-sub');
    if (title) title.textContent = surface.label;
    if (sub) sub.textContent = surface.sub;
    overlay.hidden = false;
  }

  window.gdShellSurface = gdShellSurface;
})();
