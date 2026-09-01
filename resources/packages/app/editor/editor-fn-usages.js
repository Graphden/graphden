// Editor Fn Usages — the inspector's "Used by" section: every fn that
// USES the selected one, grouped by HOW (extended / referenced /
// resolver / the type-plane kinds), each row a navigation.
//
// The data always existed — `used-as-parent-count` / `used-as-ref-count`
// gate edits with "In use — detach those first" — but the editor never
// showed WHICH fns those were, so the reader was told to detach callers
// it could not find. `POST /api/fns/usages` (same walk as
// /api/types/usages) returns the list; this module renders it.
//
// `buildFnUsagesSection` is pure DOM construction from the payload —
// covered by tools/runtime-test/fn-usages-section.test.js under
// mini-dom; keep it free of editor globals.

// Group order + labels. Composition plane first (the inspector's main
// question), type plane after (shown when the selected row doubles as
// a type).
const FN_USAGE_GROUPS = [
  ['parent-of', 'Extended by'],
  ['ref-of', 'Referenced by'],
  ['resolver-of', 'Resolver in'],
  ['slot-of', 'Slot type in'],
  ['binding-of', 'Type override in'],
  ['base-of', 'Narrowed by'],
  ['element-of', 'List element of'],
  ['return-of', 'Return type of'],
  ['union-branch', 'Union branch of'],
  ['variant-branch', 'Variant branch of'],
  ['fn-type-arg-or-return', 'Fn-type mention in'],
  ['other', 'Mentioned in'],
];

// Named rows shown per group before folding into "… and N more".
// `:const` alone has hundreds of children — an uncapped list would
// bury the rest of the Overview tab.
const FN_USAGE_GROUP_CAP = 20;

function buildFnUsagesSection(payload, opts) {
  const usages = payload?.usages || [];
  const onNavigate = opts?.onNavigate;
  // One row per (kind, fn, slot): a binding ref AND a list-item ref on
  // the same slot answer the same "where is it used" question.
  const seen = new Set();
  const byKind = new Map();
  for (const u of usages) {
    const key = u.kind + '|' + u['fn-id'] + '|' + (u['slot-name'] || '');
    if (seen.has(key)) continue;
    seen.add(key);
    if (!byKind.has(u.kind)) byKind.set(u.kind, []);
    byKind.get(u.kind).push(u);
  }
  if (!seen.size) return null;

  const section = document.createElement('div');
  section.className = 'gd-insp-usages';
  const head = document.createElement('div');
  head.className = 'gd-insp-usages-head';
  head.textContent = 'Used by';
  const count = document.createElement('span');
  count.className = 'gd-insp-usages-count';
  count.textContent = String(payload.count || seen.size);
  head.appendChild(count);
  section.appendChild(head);
  // A :const-scale list is unreadable without a filter — offer one as
  // soon as the list outgrows a glance. Filtering hides rows in place
  // (name OR namespace substring); group labels keep the full counts.
  if (seen.size > 30) {
    const filter = document.createElement('input');
    filter.type = 'text';
    filter.className = 'gd-insp-usage-filter';
    filter.placeholder = 'Filter usages…';
    filter.setAttribute('aria-label', 'Filter the Used-by list');
    filter.addEventListener('input', () => {
      const needle = filter.value.trim().toLowerCase();
      for (const row of section.querySelectorAll('.gd-insp-usage-row')) {
        row.hidden = !!needle && !(row.textContent || '')
          .toLowerCase().includes(needle);
      }
    });
    section.appendChild(filter);
  }
  if (payload['truncated?']) {
    const note = document.createElement('div');
    note.className = 'gd-insp-usage-more';
    note.textContent = 'Showing the first '
      + usages.length + ' of ' + (payload.count || '?') + ' usages.';
    section.appendChild(note);
  }

  for (const [kind, label] of FN_USAGE_GROUPS) {
    const group = byKind.get(kind);
    if (!group?.length) continue;
    // Anonymous users (inline `{:parent :X …}` forms) fold into a
    // count — they have no navigable identity a reader would
    // recognise; the NAMED callers are the answer to "who uses this".
    const named = group.filter((u) => !u.anonymous);
    const anonCount = group.length - named.length;
    // Public names first, `_`-privates after — same order the sidebar
    // teaches; alphabetical within each half.
    const privRank = (u) => (String(u['fn-name']).startsWith('_') ? 1 : 0);
    named.sort((a, b) => (privRank(a) - privRank(b))
      || String(a['fn-name']).localeCompare(String(b['fn-name'])));
    const glabel = document.createElement('div');
    glabel.className = 'gd-insp-usage-glabel';
    glabel.textContent = label + ' ' + group.length;
    section.appendChild(glabel);
    const list = document.createElement('div');
    list.className = 'gd-insp-usage-list';
    for (const u of named.slice(0, FN_USAGE_GROUP_CAP)) {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'gd-insp-usage-row';
      row.setAttribute('aria-label',
        label + ': ' + u['fn-name']
        + (u['fn-namespace'] ? ' in ' + u['fn-namespace'] : '')
        + (u['slot-name'] ? ' (slot ' + u['slot-name'] + ')' : ''));
      const nameEl = document.createElement('span');
      nameEl.className = 'gd-insp-usage-name';
      nameEl.textContent = u['fn-name'];
      row.appendChild(nameEl);
      if (u['slot-name']) {
        const slotEl = document.createElement('span');
        slotEl.className = 'gd-insp-usage-slot';
        slotEl.textContent = ':' + u['slot-name'];
        row.appendChild(slotEl);
      }
      if (u['fn-namespace']) {
        const nsEl = document.createElement('span');
        nsEl.className = 'gd-insp-usage-ns';
        nsEl.textContent = u['fn-namespace'];
        row.appendChild(nsEl);
      }
      row.addEventListener('click', (e) => {
        e.preventDefault();
        if (typeof onNavigate === 'function' && u['fn-id']) onNavigate(u);
      });
      list.appendChild(row);
    }
    const dropped = Math.max(0, named.length - FN_USAGE_GROUP_CAP);
    if (dropped || anonCount) {
      const more = document.createElement('div');
      more.className = 'gd-insp-usage-more';
      const parts = [];
      if (dropped) parts.push('and ' + dropped + ' more');
      if (anonCount) parts.push(anonCount + ' anonymous');
      more.textContent = '… ' + parts.join(' + ');
      list.appendChild(more);
    }
    section.appendChild(list);
  }
  return section;
}

// Navigate to a usage row's fn — the shared by-id-else-by-qualified-name
// jump (gdNavigateToFn, editor-ui.js).
function gdNavigateToUsage(u) {
  const qname = u['fn-namespace']
    ? u['fn-namespace'] + '.' + u['fn-name']
    : u['fn-name'];
  if (typeof gdNavigateToFn === 'function') gdNavigateToFn(u['fn-id'], qname);
}

// Fetch + append into the Overview host. The host node is the staleness
// token: a newer selection re-renders the tab body, disconnecting this
// host, so a late response appends nowhere visible.
function gdAppendFnUsages(host, fnId) {
  if (!host || !fnId || !(window.API && API.api_fns_usages)) return;
  const doFetch = (typeof authFetch === 'function') ? authFetch : fetch;
  doFetch(API.api_fns_usages, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ 'fn-id': fnId }),
  })
    .then((r) => (r.ok ? r.json() : null))
    .then((payload) => {
      if (!payload || payload.ok === false || !host.isConnected) return;
      const section = buildFnUsagesSection(payload, { onNavigate: gdNavigateToUsage });
      if (section) host.appendChild(section);
    })
    .catch(() => { /* usages are supplementary — the overview stands without them */ });
}
