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

    html += '<p class="gd-insp-soon">Bindings, provenance and this function’s '
      + 'own stats are coming here — rendered from the graph.</p>'
      + '</div>';
    el.innerHTML = html;
  }

  window.gdInspectorRender = gdInspectorRender;
})();
