// Editor Sidebar — the Organization / Platform surface SECTIONS.
//
// Nothing here is the Explorer
// tree. `mountAdminSection` / `activateOpSection` wrap each admin builder
// (grants / users / roles / packages / stats / assets → #gd-operate-panels;
// orgs / platform-access → #gd-platform-panels) as a selectable `gd-op-card`
// pane with a nav button, `htmx.process`-ed after append so `hx-trigger="load"`
// fires; `mountOpsSections` is the one entry the shell's `gdRenderOperate` /
// `gdRenderPlatform` call. `buildPackagesGovernanceSection` is the
// Organization surface's packages-governance card.
//
// Shares the bundle's script scope with editor-sidebar.js — the `_admin*`
// registries below are read by nothing else.

// Append a lazy-loading admin section (Grants / Users / Packages) and process
// it with HTMX. The section's `.ns-children` carries hx-get + hx-trigger="load";
// that trigger ONLY fires when htmx.process runs on a node already CONNECTED to
// the document — processing a detached node marks it processed but never fires
// load. So the section builders return an unprocessed node and we process here,
// after appendChild. `section` may be null (builder gated it out) → no-op.
// Built once, re-attached thereafter.
//
// renderSidebar wipes the list (`list.innerHTML = ''`) and rebuilt these
// sections from scratch on EVERY render. Each rebuild threw away a panel that
// had already loaded, put "Loading…" back, and made htmx re-fetch the partial —
// three times on a cold page load, measured. Besides the wasted round-trips it
// left a window where the panel showed "Loading…" although it had loaded
// moments earlier, which is exactly the window edit-packages-panel kept landing
// in. The test was right and the sidebar was wrong.
//
// So build each section once and re-attach the SAME node afterwards. htmx's
// `hx-trigger="load"` fires from `process()`, which we then call only on the
// first mount — a re-attached node keeps its loaded content and issues no
// request. The cache lives as long as the page: login, logout and branch
// switches all reload (editor-auth / switchToBranch), which is what clears it.
const _adminSections = new Map();
const _adminNavBtns = new Map();

// Human labels for the ops sections. Each mounts as ONE titled pane selected
// from a section list on the left — a clean settings layout, not a grid of
// look-alike tiles.
// graph-first-exception: pure nav chrome for the CLIENT-side section list —
// the pane bodies themselves are lazy hx-get server partials; only their menu
// entries live here, next to the mount wiring that owns which sections exist.
const OP_SECTION_LABELS = {
  grants: 'Grants', users: 'Members', roles: 'Roles', orgs: 'Organizations',
  packages: 'Packages', stats: 'Monitoring',
  'platform-access': 'Platform access', moderation: 'Moderation',
  assets: 'Assets', queues: 'Queues', tests: 'Tests', debug: 'Debug', executors: 'Executor',
};

// Show one section's pane on a surface and mark its nav item; hide the rest.
function activateOpSection(nav, pane, key) {
  [...pane.children].forEach((s) => { s.hidden = s.dataset.section !== key; });
  [...nav.children].forEach((b) => {
    b.setAttribute('aria-current', b.dataset.section === key ? 'page' : 'false');
  });
}

// Mount an ops/admin section as a selectable pane. `nav` is the surface's
// section-list container (the page shell always carries it — see
// `#gd-operate-nav` / `#gd-platform-nav` in app/editor/fns.edn). The pane carries exactly ONE heading (the card title); the build's own
// header label is dropped since the nav already names the section.
function mountAdminSection(pane, nav, key, build) {
  let section = _adminSections.get(key);
  let navBtn = _adminNavBtns.get(key);
  if (!section) {
    const built = build();
    if (!built) return;            // not applicable (not an admin, etc.)
    const ownHdr = built.querySelector(':scope > .ns-header');
    if (ownHdr) ownHdr.remove();
    section = document.createElement('section');
    section.className = 'gd-op-card';
    section.dataset.section = key;
    const h = document.createElement('h2');
    h.className = 'gd-op-card-title';
    h.textContent = OP_SECTION_LABELS[key] || key;
    section.appendChild(h);
    section.appendChild(built);
    _adminSections.set(key, section);
    navBtn = document.createElement('button');
    navBtn.type = 'button';
    navBtn.className = 'gd-op-nav-btn';
    navBtn.dataset.section = key;
    navBtn.textContent = OP_SECTION_LABELS[key] || key;
    navBtn.addEventListener('click', () => activateOpSection(nav, pane, key));
    _adminNavBtns.set(key, navBtn);
  }
  nav.appendChild(navBtn);
  pane.appendChild(section);
  // process() fires hx-trigger="load" and must run on a CONNECTED node.
  if (window.htmx && typeof window.htmx.process === 'function') window.htmx.process(section);
}

// LIVE panels (today: Operate's Assets; the code diagnostics used to be)
// show state that changes as the user edits, but their mounted node is cached
// (built once, re-attached; htmx does NOT re-fire `hx-trigger="load"` on an
// already-processed node). So a diagnostic recorded AFTER the panel's first
// load never appeared: the type-errors panel sat empty while the fn card
// carried the ⚠ badge (the badge is re-fetched per navigation, the cached
// panel was not). Re-fetch a live panel each time its surface is SHOWN — that
// is exactly when the user is looking at it and wants current data. Rebuild
// the lazy-load child from the section's builder (fresh, UNPROCESSED) and
// htmx.process it so the hx-get re-fires. The static admin panels
// (grants / users / …) are untouched — their data doesn't drift within a
// session.
function reloadLiveSections(hostId, builders) {
  const host = document.getElementById(hostId);
  if (!host || !window.htmx || typeof window.htmx.process !== 'function') return;
  Object.keys(builders).forEach((key) => {
    const build = builders[key];
    if (!build) return;
    const section = host.querySelector(':scope > section[data-section="' + key + '"]');
    if (!section) return;
    const built = build();          // fresh shell carrying an unprocessed hx-get child
    if (!built) return;
    const fresh = built.querySelector('.ns-children') || built;
    const old = section.querySelector('.ns-children');
    if (old) old.replaceWith(fresh);
    else section.appendChild(fresh);
    window.htmx.process(fresh);      // fires hx-trigger="load" → current diagnostics
  });
}

// Operate's only live panel left is Assets (override rows change as the user
// saves) — the code diagnostics are Explorer lenses + Inspector sections.
// Exposed for editor-shell.js's gdRenderOperate.
function reloadDynamicOpsSections() {
  reloadLiveSections('gd-operate-panels', {
    assets: typeof buildAssetsSection === 'function' ? buildAssetsSection : null,
  });
}
window.reloadDynamicOpsSections = reloadDynamicOpsSections;

// Packages GOVERNANCE (packages spec §4) — the Organization surface's
// read-mostly view: who may publish (a static capability note; the holders
// are managed in Roles/Grants), the org's published catalog and an install
// audit, both server-rendered by /partials/packages-governance. NOT an
// install surface — install lives on the Build packages chip.
function buildPackagesGovernanceSection() {
  if (!isAuthenticated()) return null;
  // Optional registry package absent → no /api/packages/* in window.API →
  // no governance section (probe, never a name).
  if (!window.API?.api_packages_installed) return null;
  // Pure mount shell — the who-may-publish note ships INSIDE the partial
  // now (server branches on the same tenancy fact via :tenancy-active?).
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-packages-governance';
  wrap.innerHTML = ''
    + '<div class="ns-children" hx-get="/partials/packages-governance" hx-trigger="load" hx-swap="innerHTML">' // api-url-drift-optional: registry-router
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  return wrap;
}

// Mount the Operate / Platform panes: Grants, Members, Roles, Organizations,
// Platform access, Packages, Monitoring, Assets. Each builder is
// a global from its own module and each returns null when it doesn't apply,
// so a section opts out by being absent rather than by being listed
// somewhere.
//
// Redesign 2026-08: these mount into surfaces, not the explorer, so the
// sidebar stays a clean namespace browser. Cross-org / platform panels go to the
// PLATFORM surface; everything else (org RBAC + the org's operational
// panels) to Organization. (Code diagnostics are Explorer lenses + Inspector
// sections; the diagnostics drawer under the canvas was retired.)
//
// Lifted out of `updateEntityList`, which had ninety lines of this in the
// middle of building the namespace tree — two surfaces, one function.
// The four hosts are static page-shell nodes (app/editor/fns.edn), so they
// are always present once the editor has booted.
function mountOpsSections(searchMode) {
  if (searchMode) return;
  const opsHost = document.getElementById('gd-operate-panels');
  const opsNavHost = document.getElementById('gd-operate-nav');
  const platHost = document.getElementById('gd-platform-panels');
  const platNavHost = document.getElementById('gd-platform-nav');
  if (!opsHost || !opsNavHost || !platHost || !platNavHost) return;
  for (const el of [opsHost, opsNavHost, platHost, platNavHost]) el.innerHTML = '';
  if (typeof buildGrantsAdminSection === 'function') {
    mountAdminSection(opsHost, opsNavHost, 'grants', buildGrantsAdminSection);
  }
  if (typeof buildUsersAdminSection === 'function') {
    mountAdminSection(opsHost, opsNavHost, 'users', buildUsersAdminSection);
  }
  if (typeof buildRolesAdminSection === 'function') {
    mountAdminSection(opsHost, opsNavHost, 'roles', buildRolesAdminSection);
  }
  if (typeof buildExecutorsAdminSection === 'function') {
    // Org's executor status (hosted/byo, BYO liveness, run snippet).
    mountAdminSection(opsHost, opsNavHost, 'executors', buildExecutorsAdminSection);
  }
  if (typeof buildOrgsAdminSection === 'function') {
    // Cross-org registry → Platform surface.
    mountAdminSection(platHost, platNavHost, 'orgs', buildOrgsAdminSection);
  }
  if (typeof buildPlatformAccessSection === 'function') {
    // Platform-access delegation → Platform surface (manage-platform-access).
    mountAdminSection(platHost, platNavHost, 'platform-access', buildPlatformAccessSection);
  }
  if (typeof buildModerationSection === 'function') {
    // Marketplace moderation queue → Platform surface (platform-admin).
    mountAdminSection(platHost, platNavHost, 'moderation', buildModerationSection);
  }
  // Packages (install/browse) live on the BUILD surface via the #gd-pkg-chip
  // context-bar chip → popover (editor-shell.js) — install is a build act.
  // What DOES belong here is the read-mostly GOVERNANCE view (packages spec
  // §4): catalog of what the org published, who may publish, install audit.
  mountAdminSection(opsHost, opsNavHost, 'packages', buildPackagesGovernanceSection);
  if (typeof buildStatsSection === 'function') {
    mountAdminSection(opsHost, opsNavHost, 'stats', buildStatsSection);
  }
  // (No Apps section: publishing a fn as an app is the ▣ row action on the
  // fn itself — editor-apps.js showFnAppsPopover; the apps LENS is the
  // org-wide overview. The Organization panel was retired.)
  if (typeof buildAssetsSection === 'function') {
    // Frontend-asset overrides — self-host only (the builder returns null
    // under an active tenancy addon; writes there are system-only).
    mountAdminSection(opsHost, opsNavHost, 'assets', buildAssetsSection);
  }
  if (typeof buildQueuesSection === 'function') {
    // The message queue: per-queue counts + dead letters (requeue / delete).
    mountAdminSection(opsHost, opsNavHost, 'queues', buildQueuesSection);
  }
  // Select a section on each surface so a pane is always showing — the one
  // the user is ALREADY on when there is one, the first otherwise. This
  // mount re-runs on every graph refresh (updateEntityList), and defaulting
  // unconditionally to the first section flipped an open panel back to
  // Packages under the reader whenever background state landed — a test
  // auto-run finishing was enough (caught by a tour whose green-dot check
  // never saw the then-drawer Tests panel it had just opened).
  const activeOrFirst = (nav, pane) => {
    const cur = [...nav.children]
      .find((b) => b.getAttribute('aria-current') === 'page')?.dataset.section;
    return (cur && pane.querySelector(':scope > section[data-section="' + cur + '"]'))
      ? cur : nav.firstElementChild.dataset.section;
  };
  if (opsNavHost.firstElementChild) {
    activateOpSection(opsNavHost, opsHost, activeOrFirst(opsNavHost, opsHost));
  }
  if (platNavHost.firstElementChild) {
    activateOpSection(platNavHost, platHost, activeOrFirst(platNavHost, platHost));
  }
}
