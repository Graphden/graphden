// Editor Marketplace — the MARKET surface + the share/publish dialog.
// (docs/MARKETPLACE.md)
//
// The surface is server-rendered: `/partials/marketplace` (the listing —
// tabs, search, category, sort, cards / the Executor roster) and
// `/partials/marketplace/item` (one package: versions with install / fork /
// apply, reviews). Every partial root is `[data-marketplace]` and carries
// its own htmx target, so this module only MOUNTS the first partial into
// `#gd-market-root` and reacts to swaps: after an action that changes the
// user's active theme / keymap, re-pull the preferences so the editor
// reflects it at once.
//
// Shared with Settings → Appearance / Keyboard:
//   gdMarketOwnCards(kind)        — the caller's saved versions of a kind
//   gdMarketFetchPayload(n, v)    — a published theme / keymap payload
//   gdMarketOpenPublishDialog(o)  — "Save / share…" for a theme or keymap
//   gdMarketCategoriesInto(sel, kind) — fill a <select> from the vocabulary
//   gdMarketPresent()             — is the optional registry package loaded?

(() => {
  

  function api() { return (typeof window.API === 'object' && window.API) ? window.API : null; }
  function gdMarketPresent() { return !!api() && typeof api().partials_marketplace !== 'undefined'; }
  function fetcher() { return window.authFetch || fetch; }

  // ---- category vocabulary (the graph's :listing-categories, fetched once) ----
  let _categories = null;
  let _categoriesPromise = null;
  function gdMarketCategories() {
    if (_categories) return Promise.resolve(_categories);
    if (!gdMarketPresent() || typeof api().api_marketplace_categories !== 'string') return Promise.resolve({});
    if (!_categoriesPromise) {
      _categoriesPromise = fetcher()(api().api_marketplace_categories)
        .then((r) => (r.ok ? r.json() : {}))
        .then((j) => { _categories = (j && typeof j === 'object') ? j : {}; return _categories; })
        .catch(() => ({}));
    }
    return _categoriesPromise;
  }
  // Fill `select` with the kind's categories (keeps its first "none" option).
  function gdMarketCategoriesInto(select, kind, selected) {
    if (!select) return;
    gdMarketCategories().then((cats) => {
      const list = Array.isArray(cats?.[kind]) ? cats[kind] : [];
      for (const c of list) {
        const o = document.createElement('option');
        o.value = c; o.textContent = c;
        if (selected && selected === c) o.selected = true;
        select.appendChild(o);
      }
    });
  }

  // ---- data helpers for Settings ----
  async function gdMarketOwnCards(kind) {
    if (!gdMarketPresent()) return [];
    try {
      const r = await fetcher()(api().api_marketplace + '?kind=' + encodeURIComponent(kind) + '&mine=1&sort=name');
      return r.ok ? (await r.json()) : [];
    } catch (_) { return []; }
  }
  async function gdMarketFetchPayload(name, version) {
    if (!gdMarketPresent()) return null;
    try {
      const r = await fetcher()(api().api_packages_name_version(name, version));
      if (!r.ok) return null;
      const row = await r.json();
      return row?.payload || null;
    } catch (_) { return null; }
  }
  function bumpPatch(v) {
    const m = /^(\d+)\.(\d+)\.(\d+)/.exec(String(v || ''));
    if (!m) return '1.0.0';
    return m[1] + '.' + m[2] + '.' + (Number(m[3]) + 1);
  }

  // ---- the surface ----
  let _mounted = false;
  function gdRenderMarket() {
    const root = document.getElementById('gd-market-root');
    if (!root || !gdMarketPresent()) return;
    if (_mounted && root.querySelector('[data-marketplace]')) return;
    _mounted = true;
    root.innerHTML = '<div data-marketplace="1" class="mk-root mk-loading">Loading the marketplace…</div>';
    fetcher()(api().partials_marketplace)
      .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
      .then((html) => {
        root.innerHTML = html;
        if (window.htmx) window.htmx.process(root);
      })
      .catch(() => {
        _mounted = false;
        root.innerHTML = '<div data-marketplace="1" class="mk-root mk-empty">The marketplace could not be loaded — are you signed in?</div>';
      });
  }
  // Open the surface on a package (from the packages chip's link, Settings).
  function gdMarketOpen(query) {
    if (typeof window.gdShellSurface === 'function') window.gdShellSurface('market');
    const root = document.getElementById('gd-market-root');
    if (!root || !gdMarketPresent() || !query) return;
    const url = query.name
      ? api().partials_marketplace_item + '?name=' + encodeURIComponent(query.name)
      : api().partials_marketplace + '?kind=' + encodeURIComponent(query.kind || 'fns');
    _mounted = true;
    fetcher()(url).then((r) => (r.ok ? r.text() : Promise.reject(r.status))).then((html) => {
      root.innerHTML = html;
      if (window.htmx) window.htmx.process(root);
    }).catch(() => {});
  }

  // After ANY swap inside the surface: an apply / install may have changed
  // the user's preferences or the installed pins.
  document.addEventListener('htmx:afterSwap', (e) => {
    const t = e.target;
    if (!(t instanceof Element) || !t.closest('#gd-market-root')) return;
    if (typeof window.gdPrefsRefresh === 'function') window.gdPrefsRefresh();
    // keep the keyboard inside the surface (the swapped root has no focus)
    const first = t.querySelector('button, input, select, a[href]');
    if (first && typeof focusSafely === 'function' && document.activeElement === document.body) focusSafely(first);
  });

  // Delegated surface switch for server-rendered buttons (`data-gd-surface`):
  // the packages chip's "Browse marketplace →" needs no inline script.
  document.addEventListener('click', (e) => {
    const b = e.target instanceof Element ? e.target.closest('[data-gd-surface]') : null;
    if (!b) return;
    e.preventDefault();
    if (typeof dismissAllPopovers === 'function') dismissAllPopovers();
    if (typeof window.gdClosePkgPop === 'function') window.gdClosePkgPop();
    if (b.dataset.gdSurface === 'market') gdMarketOpen(b.dataset.gdKind ? { kind: b.dataset.gdKind } : null);
    else if (typeof window.gdShellSurface === 'function') window.gdShellSurface(b.dataset.gdSurface);
  });

  // ---- the publish dialog (theme / keymap "Save / share…") ----
  // opts: {kind, payload, name?, description?, category?, tags?, onDone(result)}
  let _dlg = null;
  function closeDialog() {
    if (!_dlg) return;
    const d = _dlg; _dlg = null;
    d.el.remove();
    d.scrim.remove();
    if (typeof returnFocusTo === 'function') returnFocusTo(d.returnTo);
  }
  // One registration for the dialog KIND (the shared dismiss/Escape/tab-trap
  // contract, docs/ACCESSIBILITY.md) — the closures read whichever instance
  // is open.
  if (typeof installPopoverDismiss === 'function') {
    installPopoverDismiss({
      getEl: () => _dlg?.el || null,
      isVisible: () => !!_dlg,
      onDismiss: closeDialog,
      trapFocus: true,
      getReturnFocus: () => _dlg?.returnTo || null,
    });
  }
  async function gdMarketOpenPublishDialog(opts) {
    closeDialog();
    const kind = opts.kind === 'keymap' ? 'keymap' : 'theme';
    const label = kind === 'keymap' ? 'keyboard layout' : 'theme';
    const scrim = document.createElement('div');
    scrim.className = 'gd-pop-scrim';
    scrim.id = 'gd-mkpub-scrim';
    document.body.appendChild(scrim);
    const el = document.createElement('div');
    el.className = 'gd-pop gd-mkpub';
    el.id = 'gd-mkpub-pop';
    el.setAttribute('role', 'dialog');
    el.setAttribute('aria-modal', 'true');
    el.setAttribute('aria-labelledby', 'gd-mkpub-title');
    const tenancy = typeof window.graphdenTenancyActive === 'function' && window.graphdenTenancyActive();
    el.innerHTML = ''
      + '<h5 id="gd-mkpub-title">Save ' + label + '</h5>'
      + '<p class="gd-mkpub-sub">A saved ' + label + ' is a versioned package in the registry. Keep it private to yourself'
      + (tenancy ? ' / your organization' : '') + ', or tick <b>Public</b> to list it in the marketplace for everyone.</p>'
      + '<div class="gd-nspub-field"><label for="gd-mkpub-name">Name</label><input type="text" class="packages-publish-input" id="gd-mkpub-name" placeholder="my-' + label.replace(' ', '-') + '"></div>'
      + '<div class="gd-nspub-field"><label for="gd-mkpub-version">Version</label><input type="text" class="packages-publish-input" id="gd-mkpub-version" placeholder="1.0.0"></div>'
      + '<div class="gd-nspub-field"><label for="gd-mkpub-desc">Description</label><textarea class="packages-publish-input" id="gd-mkpub-desc" rows="2" maxlength="2000"></textarea></div>'
      + '<div class="gd-nspub-field"><label for="gd-mkpub-category">Category</label><select class="packages-publish-input" id="gd-mkpub-category"><option value="">— none —</option></select></div>'
      + '<div class="gd-nspub-field"><label for="gd-mkpub-tags">Tags</label><input type="text" class="packages-publish-input" id="gd-mkpub-tags" placeholder="comma, separated"></div>'
      + '<label class="gd-nspub-public"><input type="checkbox" id="gd-mkpub-public"> Public — list it in the marketplace</label>'
      + '<div class="gd-nspub-actions"><button type="button" class="gd-set-btn" id="gd-mkpub-cancel">Cancel</button>'
      + '<button type="button" class="packages-install-btn gd-mkpub-go" id="gd-mkpub-go">Save</button></div>'
      + '<div id="gd-mkpub-result" class="gd-nspub-result" aria-live="polite"></div>';
    document.body.appendChild(el);
    const nameIn = el.querySelector('#gd-mkpub-name');
    const verIn = el.querySelector('#gd-mkpub-version');
    nameIn.value = opts.name || '';
    el.querySelector('#gd-mkpub-desc').value = opts.description || '';
    el.querySelector('#gd-mkpub-tags').value = Array.isArray(opts.tags) ? opts.tags.join(', ') : (opts.tags || '');
    gdMarketCategoriesInto(el.querySelector('#gd-mkpub-category'), kind, opts.category || '');
    // next patch of the caller's own versions of that name, else 1.0.0
    const own = await gdMarketOwnCards(kind);
    const suggest = () => {
      const card = own.find((c) => c.name === nameIn.value.trim());
      verIn.value = card ? bumpPatch(card.latest) : '1.0.0';
    };
    suggest();
    nameIn.addEventListener('input', suggest);
    const returnTo = document.activeElement;
    _dlg = { el, scrim, returnTo };
    scrim.addEventListener('click', closeDialog);
    el.querySelector('#gd-mkpub-cancel').addEventListener('click', closeDialog);
    const result = el.querySelector('#gd-mkpub-result');
    const go = el.querySelector('#gd-mkpub-go');
    const setResult = (msg, ok) => {
      result.textContent = msg;
      result.className = 'gd-nspub-result ' + (ok ? 'packages-fork-ok' : 'packages-fork-err');
    };
    go.addEventListener('click', async () => {
      const name = nameIn.value.trim();
      const version = verIn.value.trim();
      if (!name || !version) { setResult('Name and version are required.', false); return; }
      go.disabled = true;
      setResult('Saving…', true);
      try {
        const r = await fetcher()(api().api_marketplace_publish, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            kind, name, version,
            description: el.querySelector('#gd-mkpub-desc').value,
            category: el.querySelector('#gd-mkpub-category').value,
            tags: el.querySelector('#gd-mkpub-tags').value,
            public: el.querySelector('#gd-mkpub-public').checked,
            payload: opts.payload,
          }),
        });
        const j = r.ok ? await r.json() : null;
        if (j?.ok) {
          setResult('Saved ' + name + '@' + version + (j.public ? ' — public in the marketplace.' : '.'), true);
          if (typeof opts.onDone === 'function') opts.onDone(j);
          setTimeout(closeDialog, 900);
        } else {
          setResult('Refused: ' + (j?.reason || (typeof authFetchErrorMessage === 'function'
            ? authFetchErrorMessage(r, { fallback: 'HTTP ' + r.status }) : 'HTTP ' + r.status)), false);
          go.disabled = false;
        }
      } catch (e) {
        setResult(e?.message || 'Save failed.', false);
        go.disabled = false;
      }
    });
    if (typeof focusIntoDialog === 'function') focusIntoDialog(el); else nameIn.focus();
  }

  window.gdMarketPresent = gdMarketPresent;
  window.gdMarketCategories = gdMarketCategories;
  window.gdMarketCategoriesInto = gdMarketCategoriesInto;
  window.gdMarketOwnCards = gdMarketOwnCards;
  window.gdMarketFetchPayload = gdMarketFetchPayload;
  window.gdMarketOpenPublishDialog = gdMarketOpenPublishDialog;
  window.gdRenderMarket = gdRenderMarket;
  window.gdMarketOpen = gdMarketOpen;
  window.gdMarketBumpPatch = bumpPatch;

  // the category vocabulary is wanted by the ns-publish popover too — warm it
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => gdMarketCategories());
  else gdMarketCategories();
})();
