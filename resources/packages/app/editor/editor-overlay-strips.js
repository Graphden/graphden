// Editor Overlay (strips) - bottom-of-card metadata strips appended to
// the fn-overlay: return-type / effects / parents / namespace,
// optional-args, HOF-captured-args, and the sign-in CTA.
// Depends on: editor-state.js, editor-data.js, editor-icons.js.

// --- Strip helpers used by createFnOverlay ----------------------------------

// Optional-but-unbound args (e.g. :get.default when no default was supplied)
// render as a thin, muted strip instead of their own placeholder nodes —
// they carry sane fallbacks so they're not part of the function's interface,
// just a nicety the caller may or may not care about.
//
// Each `?name` is its own span so a hover shows the arg's declared type
// AND the ancestor that originally declared the slot (resolved via
// `findSlotDeclaringFn` from the optional entry's `slotId`). A user
// reading the strip learns the name, the type-shape, AND which fn in
// the inheritance chain introduced the optional arg — without opening
// any popover.
//
// Wire format: each entry is `{:name "n" :slot-id "uuid"}` (or a plain
// string in older payloads — kept for backward compatibility).
function appendOptionalArgsStrip(overlay, optionalArgs, originalFnId) {
  if (!Array.isArray(optionalArgs) || !optionalArgs.length) return;
  // Normalise the wire shape so the rendering loop stays uniform.
  const entries = optionalArgs.map((e) => {
    if (typeof e === 'string') return { name: e, slotId: null };
    return { name: e.name, slotId: e['slot-id'] || e.slotId };
  });
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
  strip.title = 'Optional args (unset, using defaults): '
              + entries.map((e) => e.name).join(', ');
  const fnName = originalFnId && lookups?.fnMap?.get(originalFnId)?.name;
  const richArgs = (fnName && typeof richTypes === 'object' && richTypes)
                   ? (richTypes[fnName]?.args || null) : null;
  entries.forEach((entry, i) => {
    if (i > 0) strip.appendChild(document.createTextNode(' '));
    const span = document.createElement('span');
    span.textContent = '?' + entry.name;
    const argType = richArgs ? richArgs[entry.name] : null;
    const typePart = (argType != null && typeof formatTypeHint === 'function')
                     ? ' : ' + formatTypeHint(argType) : '';
    let originPart = '';
    if (entry.slotId && originalFnId && typeof findSlotDeclaringFn === 'function') {
      const decl = findSlotDeclaringFn(originalFnId, entry.slotId);
      if (decl?.fnName) originPart = ' (from :' + decl.fnName + ')';
    }
    span.title = '?' + entry.name + typePart + originPart;
    strip.appendChild(span);
  });
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
    // Pull the rich form (if any) to feed the refinement detector.
    // displayRich is a string alias like ':positive-int' (or null); the
    // rich-types lookup gives the structural ['refine', base, constraint]
    // form, which resolveRefinementAlias / refinementConstraintText
    // walks for the chip's stacked second line.
    const richReturn = (cardFnEntity.name && typeof richTypes === 'object' && richTypes)
                       ? (richTypes[cardFnEntity.name]?.return || null)
                       : null;
    const refineStruct = (Array.isArray(richReturn) && richReturn[0] === 'refine')
      ? richReturn
      : (typeof resolveRefinementAlias === 'function'
          ? resolveRefinementAlias(displayRich ? displayRich.replace(/^:/, '') : null)
          : null);
    const constraintText = (typeof refinementConstraintText === 'function')
      ? refinementConstraintText(refineStruct) : null;
    if (constraintText) {
      // Stacked refinement on the return-type strip — mirrors the
      // arg-overlay chip's two-line layout (base / constraint).
      strip.classList.add('return-type-strip-refine');
      const arrow = document.createElement('span');
      arrow.className = 'return-type-strip-arrow';
      arrow.textContent = '→ ';
      strip.appendChild(arrow);
      const base = document.createElement('span');
      base.className = 'return-type-strip-base';
      base.textContent = displayText;
      strip.appendChild(base);
      const constraint = document.createElement('span');
      constraint.className = 'return-type-strip-constraint';
      constraint.textContent = constraintText;
      // Hover-title — natural-language form so the reader can translate
      // a terse `(>= 1024) (<= 65535)` constraint into "integer where
      // >= 1024 and <= 65535" without opening the inline panel.
      if (refineStruct && typeof formatTypeHumanReadable === 'function') {
        constraint.title = formatTypeHumanReadable(refineStruct);
      }
      strip.appendChild(constraint);
    } else {
      // Wrap the display text in a `flex: 1; overflow: hidden; ellipsis`
      // span instead of setting textContent on the strip directly — that
      // way a trailing provenance button (added below) is laid out as a
      // sibling flex item that never gets clipped by the strip's own
      // overflow:hidden + text-overflow:ellipsis. Without the wrapper,
      // a long type expression pushes the button past the strip's
      // visible edge and the OS hit-tester (Playwright + real mouse)
      // reports the STRIP as the click target, not the button.
      strip.classList.add('return-type-strip-flex');
      const textSpan = document.createElement('span');
      textSpan.className = 'return-type-strip-text';
      textSpan.textContent = displayText ? ('→ ' + displayText) : '→ (none)';
      strip.appendChild(textSpan);
    }
    // Strip title — three cases:
    //   - declared (`rt` is set): "Return type: <rt>" plus an "(computed: …)"
    //     suffix when the rich form is more specific.
    //   - no declared return-type but a computed one (`displayRich` only):
    //     "Computed return type: <displayRich>" — the strip's visible text
    //     is `→ <displayRich>`, so saying "No return type set" would
    //     mislead the user about what the strip is showing.
    //   - neither: "No return type set" (the `→ (none)` placeholder case).
    strip.title = rt
      ? ('Return type: ' + rt
         + (displayRich && displayRich !== rt
            ? ' (computed: ' + displayRich + ')' : ''))
      : (displayRich
          ? 'Computed return type: ' + displayRich
          : 'No return type set');
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
    // Type-rule provenance — when this fn-def inherits (possibly through
    // a chain of intermediate fn-defs) from a base-fn whose
    // :return-type-rule computed the return type (assoc / get / dissoc /
    // conj / first / cons / …), the chip's value isn't from a declaration
    // or simple unification — it was COMPUTED. Surface a small `↳`
    // button so the user can answer "where did this return type come
    // from?" without reading the parent base-fn's source. The popover
    // names the rule's source and lists the resolved bindings that fed
    // into it.
    //
    // The rule lives on a BASE-FN (the leaf of the primary-parent
    // chain), but the immediate primary-parent is often itself a fn-def
    // (`:assoc-timestamp → :assoc`, `:json-ok-response → … → :assoc`).
    // Walk the chain until we either hit a base-fn carrying the flag or
    // exhaust it. Cycle-guard via a Set + a hop cap so a misshapen
    // registry can't lock the renderer.
    const entry = (cardFnEntity.name && typeof richTypes === 'object' && richTypes)
                  ? richTypes[cardFnEntity.name] : null;
    let ruleOwner = null;
    if (entry && typeof richTypes === 'object' && richTypes) {
      const seen = new Set();
      let cur = entry['primary-parent'];
      let hops = 0;
      while (cur && !seen.has(cur) && hops < 32) {
        seen.add(cur);
        const cand = richTypes[cur];
        if (!cand) break;
        if (cand['has-return-type-rule?']) { ruleOwner = cur; break; }
        cur = cand['primary-parent'];
        hops += 1;
      }
    }
    if (ruleOwner && typeof showReturnTypeRulePopover === 'function') {
      const provBtn = document.createElement('button');
      provBtn.type = 'button';
      provBtn.className = 'return-type-strip-provenance';
      provBtn.textContent = '↳';
      provBtn.title = "Computed by :" + ruleOwner
                    + "'s :return-type-rule — click for inputs";
      provBtn.setAttribute('aria-label', provBtn.title);
      // Disclosure button — opens the type-rule popover.
      // `attachAndShow` (editor-provenance-popover.js) flips this to
      // "true" on open, hideProvenancePopover back to "false".
      provBtn.setAttribute('aria-expanded', 'false');
      provBtn.setAttribute('aria-haspopup', 'dialog');
      provBtn.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        // Look up the rule-owner base-fn's id so the popover's "open
        // base-fn" link can navigate. lookups.fnMap is indexed by id;
        // scan once.
        let ruleOwnerFnId = null;
        if (typeof lookups !== 'undefined' && lookups?.fnMap) {
          for (const f of lookups.fnMap.values()) {
            if (f.name === ruleOwner) { ruleOwnerFnId = f.id; break; }
          }
        }
        showReturnTypeRulePopover(entry, ruleOwner, ruleOwnerFnId, provBtn);
      });
      strip.appendChild(provBtn);
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

  // (Namespace surface lives as the `ns` badge in the row-actions
  // popover — served by `:partial-row-actions` and dispatched via
  // `editor-row-actions.js`. Removed the dedicated bottom strip:
  // same payload duplicated in two places turned the card into a
  // noisy stack of labels.)

  // --- branch-local strip ---
  // Walk parent-ids transitively (mirror of
  // `graphden.versioning.branch-local/effective-branch-local?`); any
  // ancestor with the flag makes this fn sticky-local. The strip is a
  // visual cue + an explainer tooltip — "this fn does not propagate
  // across branches on merge". No edit affordance: descendants
  // CAN'T widen back to non-local (sync-time guard rejects the write),
  // so showing a toggle here would be a footgun. Admin opt-in lives
  // in the fns.edn declaration of the root local ancestor.
  if (lookups?.fnMap) {
    const visited = new Set();
    const queue = [cardFnEntity];
    let local = false;
    let localAncestor = null;
    while (queue.length && !local) {
      const f = queue.shift();
      if (!f || visited.has(f.id)) continue;
      visited.add(f.id);
      if (f['branch-local?'] === true) {
        local = true;
        localAncestor = f;
        break;
      }
      for (const pid of (f['parent-ids'] || [])) {
        const pf = lookups.fnMap.get(pid);
        if (pf) queue.push(pf);
      }
    }
    if (local) {
      const strip = document.createElement('div');
      strip.className = 'branch-local-strip';
      const glyph = document.createElement('span');
      glyph.className = 'branch-local-strip-glyph';
      glyph.textContent = '📍';
      glyph.setAttribute('aria-hidden', 'true');
      const label = document.createElement('span');
      label.className = 'branch-local-strip-label';
      label.textContent = 'branch-local';
      strip.appendChild(glyph);
      strip.appendChild(label);
      // Tooltip explains the policy + names the ancestor that carries
      // the seed so the user can trace where it came from. When
      // self-marked, ancestor === cardFnEntity.
      const ownTrue = cardFnEntity['branch-local?'] === true;
      strip.title = ownTrue
        ? 'This fn is sticky-local: version rows do not propagate across branches on merge.'
        : ('This fn inherits branch-local from `:' + (localAncestor.name || '<anon>')
           + '`. Version rows do not propagate across branches on merge.');
      overlay.appendChild(strip);
    }
  }
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
  if (!lookups?.fnMap?.get(originalFnId)) return;

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


/**
 * Deep-free-args strip — names this fn accepts as free args from the
 * caller's expanded context whose actual use-sites live deeper than
 * the visible slot surface. Populated when the layout pipeline's
 * β-inline pass migrates a free-arg binding (e.g. `:base-handler` on
 * `_app-cached`) to its consumer's node (`_fresh-with-maybe-store`)
 * without that consumer declaring the arg as its own slot — the
 * binding flows down into the sub-tree (here `_fresh-response`).
 * Without this strip, the card shows the outgoing edge but nothing
 * on the card itself indicates "I take this name", which misleads
 * readers into hunting for the slot on one of the visible ancestor
 * rows. The `⇣` glyph reads as "propagates downward".
 */
function appendDeepFreeArgsStrip(overlay, deepFreeArgs) {
  if (!Array.isArray(deepFreeArgs) || !deepFreeArgs.length) return;
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
  strip.title = 'Free args this fn accepts from the caller and threads into its sub-tree: '
                + deepFreeArgs.join(', ');
  strip.textContent = deepFreeArgs.map(n => '⇣' + n).join(' ');
  overlay.appendChild(strip);
}
