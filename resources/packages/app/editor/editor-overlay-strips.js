// Editor Overlay (strips) - bottom-of-card metadata strips appended to
// the fn-overlay: return-type / effects / parents / namespace,
// optional-args, HOF-captured-args, and the sign-in CTA.
// Depends on: editor-state.js, editor-data.js, editor-icons.js.

// --- Strip helpers used by createFnOverlay ----------------------------------

// Optional-but-unbound args (e.g. :get.default when no default was supplied)
// render as a thin, muted strip instead of their own placeholder nodes —
// they carry sane fallbacks so they're not part of the function's interface,
// just a nicety the caller may or may not care about.
function appendOptionalArgsStrip(overlay, optionalArgs) {
  if (!Array.isArray(optionalArgs) || !optionalArgs.length) return;
  const strip = document.createElement('div');
  Object.assign(strip.style, {
    padding: '2px 8px',
    color: 'var(--light-fg)',
    fontSize: '10px',
    fontStyle: 'italic',
    borderTop: '1px dashed var(--input-border)',
    background: 'var(--sidebar-bg)',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
    textOverflow: 'ellipsis'
  });
  strip.title = 'Optional args (unset, using defaults): ' + optionalArgs.join(', ');
  strip.textContent = optionalArgs.map(n => '?' + n).join(' ');
  overlay.appendChild(strip);
}

// Per-fn metadata strips at the bottom of the overlay: return-type,
// effects (with drift visualisation), edit-parents, namespace. All four
// share `cardFnEntity` and the `rtEditable` predicate, so they live in
// one helper instead of four call-site copies of the same gate.
function appendFnMetadataStrips(overlay, originalFnId, isNavRoot) {
  const cardFnEntity = lookups?.fnMap?.get(originalFnId);
  if (!cardFnEntity) return;
  const rt = cardFnEntity['return-type'];
  const rtEditable = isNavRoot
                  && (typeof isFnEditable === 'function' && isFnEditable(originalFnId))
                  && (typeof isAuthenticated === 'function' && isAuthenticated());

  // --- return-type strip ---
  // Two display modes:
  //   - Non-root cards (expanded ancestors): show only when a type is
  //     set, read-only — informational, doesn't add visual noise to
  //     fns the user can't edit from here anyway.
  //   - Root card: always show; clickable when fn is editable+authed
  //     so the user can SET a return-type even when the fn currently
  //     has none ("→ (none)" placeholder).
  // Pull the rich computed return-type from /api/types — for fn-defs
  // whose `:return-type` column is null, this is the only place the
  // computed shape lives client-side.
  let displayRich = null;
  let richReturn = null;
  if (cardFnEntity.name && typeof richTypes === 'object' && richTypes
      && typeof formatTypeHint === 'function') {
    const re = richTypes[cardFnEntity.name];
    if (re && re.return != null) {
      richReturn = re.return;
      displayRich = formatTypeHint(re.return);
    }
  }
  // Prefer the original NAMED return-type over the unfolded
  // structural form. For `web-server :return-type :http-server-handle`
  // the structural unfold reads `→ () → null` — readable as either
  // "this fn takes no args, returns null" (wrong) or "returns a
  // 0-arg callable returning null" (right but takes thought). The
  // alias name "http-server-handle" carries the same information
  // more compactly, so when the (possibly inherited)
  // `return-type-fn-id` resolves to a type-row with a real name we
  // use that instead. Falls back to the structural form when no
  // named alias exists (e.g. inline `[:fn …]` declarations).
  //
  // Walks the parent chain because composed fn-defs INHERIT
  // `:return-type` from their parent — `web-server`'s row carries
  // null, the value lives on `:http-server`'s row.
  if (lookups?.fnMap) {
    const PRIMITIVES = new Set(['null', 'uuid', 'text', 'int', 'bool',
                                 'numeric', 'timestamptz', 'jsonb',
                                 'bytes', 'any', 'fn', 'sequence',
                                 'keyword', 'float']);
    const visited = new Set();
    const queue = [cardFnEntity];
    let inheritedRtFnId = null;
    while (queue.length && !inheritedRtFnId) {
      const f = queue.shift();
      if (!f || visited.has(f.id)) continue;
      visited.add(f.id);
      if (f['return-type-fn-id']) {
        inheritedRtFnId = f['return-type-fn-id'];
        break;
      }
      for (const pid of (f['parent-ids'] || [])) {
        const pf = lookups.fnMap.get(pid);
        if (pf) queue.push(pf);
      }
    }
    if (inheritedRtFnId) {
      const rtFn = lookups.fnMap.get(inheritedRtFnId);
      if (rtFn?.name && typeof rtFn.name === 'string'
          && !PRIMITIVES.has(rtFn.name)) {
        displayRich = ':' + rtFn.name;
      }
    }
  }

  if (rt || rtEditable || displayRich) {
    const strip = document.createElement('div');
    strip.className = 'return-type-strip';
    const displayText = displayRich || rt;
    strip.textContent = displayText ? ('→ ' + displayText) : '→ (none)';
    strip.title = rt
      ? ('Return type: ' + rt + (displayRich && displayRich !== rt ? ' (computed: ' + displayRich + ')' : ''))
      : 'No return type set';
    if (rtEditable) {
      strip.classList.add('return-type-strip-editable');
      strip.title = (displayRich && displayRich !== rt
                     ? 'Computed: ' + displayRich + ' — click to change return type'
                     : 'Click to change return type');
      strip.addEventListener('click', (e) => {
        e.stopPropagation();
        enterFnReturnTypeEditMode(cardFnEntity, strip);
      });
    }
    overlay.appendChild(strip);
  }

  // --- effects strip ---
  // Small per-category badges (db / env / io / network / time /
  // effect). Reads richTypes[name].effects when available. Pure fns
  // get no row at all (no clutter for the 80% case). Each badge is
  // colour-coded and carries a hover-title with the full category name.
  //
  // When the fn-def also declares `:expects-effects`, the strip
  // shows declared/computed drift visually:
  //   - computed AND declared    → solid chip (normal)
  //   - computed NOT declared    → solid chip with red outline
  //                                (DRIFT — author should declare it)
  //   - declared NOT computed    → outlined ghost chip
  //                                (over-declared, harmless)
  if (cardFnEntity.name && typeof richTypes === 'object' && richTypes) {
    const re = richTypes[cardFnEntity.name];
    const computed = (re && Array.isArray(re.effects)) ? re.effects : [];
    // Prefer the live DB value (updated by UI edits) over the
    // richTypes snapshot which is rebuilt only at server start.
    const dbDeclared = Array.isArray(cardFnEntity['expects-effects'])
      ? cardFnEntity['expects-effects'] : null;
    const declared = dbDeclared
      || ((re && Array.isArray(re['expects-effects'])) ? re['expects-effects'] : null);
    const all = new Set([...computed, ...(declared || [])]);
    // The :expects-effects edit affordance is gated more loosely than
    // most footer-strip controls — the field is a documentation/drift
    // annotation, not a structural change, so we don't need to block
    // when the fn already has children (the `isFnEditable` gate is for
    // deletion). Auth + nav-root is enough.
    const effectsEditable = isNavRoot
                         && (typeof isAuthenticated === 'function' && isAuthenticated());
    if (all.size > 0 || (effectsEditable && computed.length === 0)) {
      const effRow = document.createElement('div');
      effRow.className = 'effects-strip';
      const titleParts = [];
      if (computed.length) titleParts.push('Effects: ' + computed.join(', '));
      if (declared)        titleParts.push('Declared: ' + declared.join(', '));
      const drift = computed.filter(e => declared && declared.indexOf(e) < 0);
      const overDeclared = (declared || []).filter(e => computed.indexOf(e) < 0);
      if (drift.length)         titleParts.push('Drift (undeclared): ' + drift.join(', '));
      if (overDeclared.length)  titleParts.push('Over-declared: '       + overDeclared.join(', '));
      effRow.title = titleParts.join('\n');
      Array.from(all).sort().forEach((eff) => {
        const isComputed = computed.indexOf(eff) >= 0;
        const isDeclared = declared && declared.indexOf(eff) >= 0;
        const chip = document.createElement('button');
        chip.type = 'button';
        let cls = 'effects-chip effects-chip-' + eff;
        if (!isComputed && isDeclared) cls += ' effects-chip-ghost';   // declared only
        if ( isComputed && declared && !isDeclared) cls += ' effects-chip-drift'; // unexpected
        chip.className = cls;
        chip.textContent = eff;
        chip.title = isComputed
          ? (isDeclared ? 'Effect: ' + eff + ' (declared & computed) — tap for details'
                        : 'Effect: ' + eff + ' (DRIFT — not in :expects-effects) — tap for details')
          : 'Effect: ' + eff + ' (declared but not computed) — tap for details';
        chip.setAttribute('aria-label', chip.title);
        chip.addEventListener('click', (e) => {
          e.stopPropagation();
          if (typeof showEffectExplainer === 'function') {
            showEffectExplainer({ effect: eff, anchorEl: chip });
          }
        });
        effRow.appendChild(chip);
      });
      // Inline "declare effects…" edit pencil — only for the
      // navigation-root card and only when the viewer can edit. On
      // pure fns with no contract yet, the strip otherwise wouldn't
      // exist; the all.size>0 gate above admits this case so the
      // pencil is always reachable.
      if (effectsEditable && typeof enterExpectsEffectsEditMode === 'function') {
        // Same `✎` glyph in both states (contract / no-contract) so
        // the affordance is one consistent thing the user can learn,
        // not two-text-vs-icon. The title carries the state-specific
        // hint for new users and screen readers.
        const editBtn = document.createElement('button');
        editBtn.type = 'button';
        editBtn.className = 'effects-strip-edit';
        editBtn.textContent = '✎';
        editBtn.title = declared
          ? 'Edit declared effect contract'
          : 'Declare an effect contract (drift checker compares declared vs computed)';
        editBtn.setAttribute('aria-label', editBtn.title);
        editBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          enterExpectsEffectsEditMode(cardFnEntity, editBtn, declared);
        });
        effRow.appendChild(editBtn);
      }
      overlay.appendChild(effRow);
    }
  }

  const appendClickStrip = (label, title, onClick) => {
    const strip = document.createElement('div');
    strip.className = 'reparent-strip';
    strip.textContent = label;
    strip.title = title;
    strip.addEventListener('click', (e) => {
      e.stopPropagation();
      onClick(strip);
    });
    overlay.appendChild(strip);
  };

  // --- edit-type-row strip ---
  // Type-rows have no parents and no callable signature — their value
  // lives in the structural fields (`base-fn-id`, `element-fn-id`,
  // `constraint`, or fn-slots for records). The fn-action toolbar at
  // the bottom of the card handles rename / delete / namespace, but
  // none of those touch the type's *definition*. Surface a single
  // strip whose click reopens the create-type form pre-populated with
  // the current values — submit goes through PUT.
  //
  // Only the kinds whose definition fits in one form (no compound
  // delta against existing slots) get an editable affordance: record
  // edit is read-only-prefilled and only rename works. fn-types are
  // anonymous structural fn-rows attached via `slot.type-fn-id`; the
  // arg-chip popover handles their rename instead.
  if (isNavRoot && rtEditable && typeof openTypeEditForm === 'function') {
    const editableRoles = new Set(['refinement', 'union', 'variant', 'list',
                                    'record',
                                    ':refinement', ':union', ':variant', ':list',
                                    ':record']);
    const role = cardFnEntity.role;
    if (editableRoles.has(role)) {
      const strip = document.createElement('div');
      strip.className = 'edit-type-strip';
      strip.tabIndex = 0;
      strip.setAttribute('role', 'button');
      strip.textContent = 'edit this type…';
      strip.title = 'Open the type-edit form to change this type-row\'s definition';
      const handler = (e) => {
        e.stopPropagation();
        openTypeEditForm(originalFnId, strip);
      };
      strip.addEventListener('click', handler);
      strip.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          handler(e);
        }
      });
      overlay.appendChild(strip);
    }
  }

  // --- set-parent strip (no-parents case only) ---
  // When the fn HAS a parent, the depth-1 ancestor row already shows
  // it AND carries an inline ✎ pencil — no separate strip needed.
  // When there's no parent, there's no row to attach the pencil to,
  // so we keep a minimal "set parent…" affordance here.
  if (rtEditable && typeof enterReparentEditMode === 'function') {
    const pids = cardFnEntity['parent-ids'] || [];
    if (pids.length === 0) {
      appendClickStrip(
        'set parent…',
        'Click to assign a parent (the rest of the chain follows)',
        (strip) => enterReparentEditMode(cardFnEntity, strip));
    }
  }

  // (Namespace surface lives as a left-pinned `ns` badge on the
  // fn-name row — see `createNamespaceBadge` in editor-icons.js.
  // Removed the dedicated bottom strip: same payload duplicated in
  // two places turned the card into a noisy stack of labels.)
}

// When the nav-root fn's viewer isn't signed in, surface a single
// "Sign in to edit" CTA at the bottom of the overlay. Once authed,
// every per-fn action lives next to its target (rename/extend/delete
// icons on the root row, re-parent pencil on the parent row, describe
// inside the description tooltip, ns badge on the root row) — so the
// bar has nothing to carry and is omitted entirely.
function appendFnActionToolbar(overlay, originalFnId, isNavRoot) {
  if (!isNavRoot) return;
  if (typeof isAuthenticated === 'function' && isAuthenticated()) return;
  if (!lookups || !lookups.fnMap || !lookups.fnMap.get(originalFnId)) return;

  const bar = document.createElement('div');
  bar.className = 'fn-action-toolbar';

  const hint = document.createElement('span');
  hint.className = 'fn-action-toolbar-hint';
  hint.textContent = 'Sign in to edit';
  bar.appendChild(hint);

  const signIn = document.createElement('button');
  signIn.type = 'button';
  signIn.className = 'fn-action-btn';
  signIn.setAttribute('aria-label', 'Sign in');
  signIn.title = 'Sign in';
  const glyph = document.createElement('span');
  glyph.className = 'fn-action-btn-glyph';
  glyph.setAttribute('aria-hidden', 'true');
  glyph.textContent = '🔒';
  const label = document.createElement('span');
  label.className = 'fn-action-btn-label';
  label.textContent = 'Sign in';
  signIn.appendChild(glyph);
  signIn.appendChild(label);
  signIn.addEventListener('click', (e) => {
    e.stopPropagation();
    const lock = document.getElementById('auth-lock-btn');
    if (lock) lock.click();
  });
  signIn.addEventListener('mousedown', (e) => e.stopPropagation());
  signIn.addEventListener('touchstart', (e) => e.stopPropagation(), { passive: true });
  bar.appendChild(signIn);

  overlay.appendChild(bar);
}

// HOF-captured args (e.g. `:request` on a Ring-handler subtree) are free
// slots that the enclosing higher-order call site will fill at runtime —
// not interface args for the graph-level caller. Render as a compact
// strip prefixed with `λ` so the user can see the slot exists without
// needing to plan for supplying it themselves.
function appendHofCapturedArgsStrip(overlay, hofCapturedArgs) {
  if (!Array.isArray(hofCapturedArgs) || !hofCapturedArgs.length) return;
  const strip = document.createElement('div');
  Object.assign(strip.style, {
    padding: '2px 8px',
    color: 'var(--hof-fg)',
    fontSize: '10px',
    fontStyle: 'italic',
    borderTop: '1px dashed var(--hof-border)',
    background: 'var(--hof-bg)',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
    textOverflow: 'ellipsis'
  });
  strip.title = 'Args supplied by the enclosing HOF invocation: ' + hofCapturedArgs.join(', ');
  strip.textContent = hofCapturedArgs.map(n => 'λ' + n).join(' ');
  overlay.appendChild(strip);
}
