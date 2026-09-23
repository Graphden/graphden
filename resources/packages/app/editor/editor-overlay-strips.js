// Editor Overlay (strips) - bottom-of-card metadata strips appended to
// the fn-overlay: return-type / effects / parents / namespace, the
// deep-free ⇣-strip, and the sign-in CTA. (The former optional-args /
// HOF-captured badge strips are gone — every unset arg renders as a
// uniform placeholder edge now; see layout's add-unset-arg-node.)
// Depends on: editor-state.js, editor-data.js, editor-icons.js.

// --- Strip helpers used by createFnOverlay ----------------------------------

// Per-fn metadata strips at the bottom of the overlay: return-type,
// effects (with drift visualisation), edit-parents, namespace. All four
// share `cardFnEntity` and the `rtEditable` predicate, so they live in
// one helper instead of four call-site copies of the same gate.
//
// `stripFacts` carries the server-computed facts the layout response
// attaches to each fn-node (`graphden.layout.strip-facts`):
// `returnTypeAlias` / `ruleOwner` / `branchLocal`. Don't add client-side
// inheritance / registry / branch-local walks here — they would
// re-derive server-owned reasoning.
function appendFnMetadataStrips(overlay, originalFnId, isNavRoot, stripFacts) {
  const cardFnEntity = lookups?.fnMap?.get(originalFnId);
  if (!cardFnEntity) return;
  stripFacts = stripFacts || {};
  const rt = cardFnEntity['return-type'];
  // TYPE-ROW (no parents, no base-fn impl): "→ (none)" and
  // "set parent…" are fn vocabulary — a type has no return and gets
  // its hierarchy through the type-create flows, so both strips are
  // noise on a type card (worst on stdlib primitives like `int`).
  //
  // EXCEPT the bare draft: a fresh sidebar "New graph…" fn has the exact
  // primitive shape (no parents, no impl, no structure) and used to be
  // swallowed by this guard, leaving NO affordance to assign its first
  // parent (the tutorial-tour finding). The discriminator is
  // the namespace: stdlib primitives are namespace-less, drafts are
  // created inside one — and real type-rows classify as refinement/
  // union/record/…, never `primitive`, so they keep hiding the strip.
  const isBareDraftFn = cardFnEntity.role === 'primitive'
                     && !!cardFnEntity['namespace-id'];
  const isTypeRow = !(Array.isArray(cardFnEntity['parent-ids'])
                      && cardFnEntity['parent-ids'].length)
                 && !cardFnEntity['return-type-fn-id']
                 && !rt
                 && !isBareDraftFn;
  // Ownership gate, not `isFnEditable`'s "no dependents": the server
  // takes a return-type or parent change on a referenced fn (see
  // `gdOwnEditable`), so the strips offer it too.
  const rtEditable = isNavRoot && !isTypeRow
                  && typeof gdOwnEditable === 'function' && gdOwnEditable(cardFnEntity);
  // The fn-row FLAGS (λ call-site params, 📍 branch-local) take the
  // effects pencil's looser gate, not `isFnEditable`'s "no dependents":
  // a fn is handed to a HOF or extended BECAUSE it is referenced, and
  // that is exactly when its calling convention or merge policy needs
  // saying. Ownership (tenancy) and package ownership still apply.
  const flagEditable = isNavRoot && !isTypeRow
                    && typeof gdFlagEditable === 'function' && gdFlagEditable(cardFnEntity);

  const c = { overlay, cardFnEntity, originalFnId, isNavRoot, stripFacts,
              rt, rtEditable, flagEditable };
  appendReturnTypeStrip(c);
  appendEffectsStrip(c);
  appendEditTypeRowStrip(c);
  appendSetParentStrip(c);
  // (Namespace surface lives as the `ns` badge in the row-actions
  // popover — served by `:partial-row-actions` and dispatched via
  // `editor-row-actions.js`. Removed the dedicated bottom strip:
  // same payload duplicated in two places turned the card into a
  // noisy stack of labels.)
  appendBranchLocalStrip(c);
}

// The strips below take the context `appendFnMetadataStrips` computed once:
// `{overlay, cardFnEntity, originalFnId, isNavRoot, stripFacts, rt,
//   rtEditable, flagEditable}`.

function appendReturnTypeStrip(c) {
  const { overlay, cardFnEntity, stripFacts, rtEditable, flagEditable, rt } = c;
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
  if (cardFnEntity.name && typeof richTypeEntryOf === 'function'
      && typeof formatTypeHint === 'function') {
    const re = richTypeEntryOf(cardFnEntity);
    if (re && re.return != null) {
      displayRich = formatTypeHint(re.return);
    }
  }
  // Prefer the original NAMED return-type over the unfolded
  // structural form. For `web-server :return-type :http-server-handle`
  // the structural unfold reads `→ () → null` — readable as either
  // "this fn takes no args, returns null" (wrong) or "returns a
  // 0-arg callable returning null" (right but takes thought). The
  // alias name "http-server-handle" carries the same information
  // more compactly. The parent-chain walk that resolves the
  // (possibly inherited) `return-type-fn-id` to a non-primitive
  // type-row name runs SERVER-side now (layout strip-facts).
  if (stripFacts.returnTypeAlias) {
    displayRich = ':' + stripFacts.returnTypeAlias;
  }

  if (rt || rtEditable || flagEditable || displayRich) {
    const strip = document.createElement('div');
    strip.className = 'return-type-strip';
    const displayText = displayRich || rt;
    // Pull the rich form (if any) to feed the refinement detector.
    // displayRich is a string alias like ':positive-int' (or null); the
    // rich-types lookup gives the structural ['refine', base, constraint]
    // form, which resolveRefinementAlias / refinementConstraintText
    // walks for the chip's stacked second line.
    const richReturn = (typeof richTypeEntryOf === 'function')
                       ? (richTypeEntryOf(cardFnEntity)?.return || null)
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
    // The flag gate, not `isFnEditable`: a fn that is extended or
    // referenced can still be given a return type — the checker re-flags
    // the callers that disagree, which is what the ⚠ lens is for. (The
    // Inspector's Returns row uses the same gate, editor-edit-modes-flags.js.)
    if (flagEditable) {
      strip.classList.add('return-type-strip-editable');
      strip.title = (displayRich && displayRich !== rt
                     ? 'Computed: ' + displayRich + ' — click to change return type'
                     : 'Click to change return type');
      strip.addEventListener('click', (e) => {
        e.stopPropagation();
        enterFnReturnTypeEditMode(cardFnEntity, strip);
      });
    }
    appendRuleProvenanceButton(strip, c);
    appendLambdaParamsChip(strip, c);
    overlay.appendChild(strip);
  }
}

// The return-type strip's `↳` button (see appendReturnTypeStrip).
function appendRuleProvenanceButton(strip, c) {
  const { cardFnEntity, stripFacts } = c;
  // Type-rule provenance — when this fn-def inherits (possibly through
  // a chain of intermediate fn-defs) from a base-fn whose
  // :return-type-rule computed the return type (assoc / get / dissoc /
  // conj / first / cons / …), the chip's value isn't from a declaration
  // or simple unification — it was COMPUTED. Surface a small `↳`
  // button so the user can answer "where did this return type come
  // from?" without reading the parent base-fn's source. The popover
  // (server-rendered, /partials/return-type-rule) names the rule's
  // source and lists the resolved bindings that fed into it. The
  // rule-owner walk itself runs SERVER-side (layout strip-facts →
  // `registry/rule-owner-of`).
  const ruleOwner = stripFacts.ruleOwner || null;
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
      // Server partial owns the rule-owner walk, narrative and
      // Inputs table; it only needs the fn's name.
      showReturnTypeRulePopover(cardFnEntity.name, provBtn);
    });
    strip.appendChild(provBtn);
  }
}

// The return-type strip's λ chip (see appendReturnTypeStrip).
function appendLambdaParamsChip(strip, c) {
  const { cardFnEntity, flagEditable } = c;
  // λ — the fn's CALL-SITE parameters when it is handed to a HOF or a
  // route as a callable (`:lambda-params` on the fn row). Only a
  // composed fn-def can carry one (a base-fn's arity is its impl's).
  // Shown on an editable root card in every state, elsewhere only
  // when declared: `λ derived` (the compile picks the one unambiguous
  // free arg, refusing when several qualify), `λ []` (everything
  // captured — a handler chain), `λ request` (named, in order).
  const isComposed = Array.isArray(cardFnEntity['parent-ids'])
                     && cardFnEntity['parent-ids'].length > 0;
  const declared = cardFnEntity['lambda-params'];
  if (isComposed && (flagEditable || declared != null)
      && typeof enterLambdaParamsEditMode === 'function') {
    const chip = document.createElement(flagEditable ? 'button' : 'span');
    chip.className = 'lambda-params-chip';
    if (flagEditable) chip.type = 'button';
    const shown = declared == null ? 'derived'
                : (declared.length === 0 ? '[]' : declared.join(', '));
    chip.textContent = 'λ ' + shown;
    chip.dataset.declared = declared == null ? 'derived' : JSON.stringify(declared);
    chip.title = (declared == null
      ? 'Call-site parameters: derived — when a HOF or a route calls this fn, the compile picks its one unambiguous free arg (and refuses when several qualify).'
      : declared.length === 0
        ? 'Call-site parameters: none — every input is captured from the graph when this fn is handed over as a callable.'
        : 'Call-site parameters, in order: ' + declared.join(', ')
          + ' — these are filled per call; the rest is captured.')
      + (flagEditable ? ' Click to change.' : '');
    chip.setAttribute('aria-label', chip.title);
    if (flagEditable) {
      chip.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        enterLambdaParamsEditMode(cardFnEntity, chip);
      });
    }
    strip.appendChild(chip);
  }
}

function appendEffectsStrip(c) {
  const { overlay, cardFnEntity, isNavRoot } = c;
  // --- effects strip ---
  // Small per-category badges (db / env / io / network / time /
  // effect). Reads richTypes[name].effects when available. Pure fns
  // get no row at all (no clutter for the 80% case). Each badge is
  // colour-coded and carries a hover-title with the full category name.
  //
  // graph-first-exception: the drift/over-declared set arithmetic
  // stays CLIENT-side deliberately — `:expects-effects` is editable
  // in-page (✎ below) without a layout refetch, so the client's live
  // DB value is fresher than anything the server could have baked
  // into the layout response; the arithmetic is presentation of
  // client-fresh state, not a re-derivation of server reasoning
  // (sync-time drift-checking REJECTS, it doesn't render).
  //
  // When the fn-def also declares `:expects-effects`, the strip
  // shows declared/computed drift visually:
  //   - computed AND declared    → solid chip (normal)
  //   - computed NOT declared    → solid chip with red outline
  //                                (DRIFT — author should declare it)
  //   - declared NOT computed    → outlined ghost chip
  //                                (over-declared, harmless)
  if (cardFnEntity.name && typeof richTypeEntryOf === 'function') {
    const re = richTypeEntryOf(cardFnEntity);
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
          enterExpectsEffectsEditMode(cardFnEntity, editBtn);
        });
        effRow.appendChild(editBtn);
      }
      overlay.appendChild(effRow);
    }
  }
}

function appendEditTypeRowStrip(c) {
  const { overlay, cardFnEntity, originalFnId, isNavRoot, rtEditable } = c;
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
}

function appendSetParentStrip(c) {
  const { overlay, cardFnEntity, rtEditable } = c;
  // --- set-parent strip (no-parents case only) ---
  // When the fn HAS a parent, the depth-1 ancestor row already shows
  // it AND carries an inline ✎ pencil — no separate strip needed.
  // When there's no parent, there's no row to attach the pencil to,
  // so we keep a minimal "set parent…" affordance here.
  if (rtEditable && typeof enterReparentEditMode === 'function') {
    const pids = cardFnEntity['parent-ids'] || [];
    if (pids.length === 0) {
      const strip = document.createElement('div');
      strip.className = 'reparent-strip';
      strip.textContent = 'set parent…';
      strip.title = 'Click to assign a parent (the rest of the chain follows)';
      strip.addEventListener('click', (e) => {
        e.stopPropagation();
        enterReparentEditMode(cardFnEntity, strip);
      });
      overlay.appendChild(strip);
    }
  }
}

function appendBranchLocalStrip(c) {
  const { overlay, cardFnEntity, stripFacts, flagEditable } = c;
  // --- branch-local strip ---
  // The transitive parent-ids walk runs SERVER-side (layout strip-facts
  // → `branch-local/branch-local-seed`, the module that owns merge-time
  // semantics); the strip reads the fact. Three states on an editable
  // root card, one on everything else:
  //   own       — this fn's row carries the flag: "branch-local", a
  //               click reopens the toggle;
  //   inherited — an ancestor seeded it: "branch-local", the popover
  //               says who and that descendants can only stay local
  //               (widening is refused by `crud.validation/branch-local-rej`);
  //   off       — editable root only, dimmed "merges across branches":
  //               the affordance to make a fn of one's own sticky-local
  //               (a per-environment port, path or schedule that must
  //               not ride a merge) without an fns.edn edit.
  // A read-only card shows the strip only when the flag is in effect.
  const branchLocal = stripFacts.branchLocal;
  if (branchLocal || flagEditable) {
    const state = !branchLocal ? 'off' : (branchLocal.own ? 'own' : 'inherited');
    const strip = document.createElement('div');
    strip.className = 'branch-local-strip branch-local-strip-' + state;
    strip.dataset.state = state;
    const glyph = document.createElement('span');
    glyph.className = 'branch-local-strip-glyph';
    glyph.textContent = '📍';
    glyph.setAttribute('aria-hidden', 'true');
    const label = document.createElement('span');
    label.className = 'branch-local-strip-label';
    label.textContent = state === 'off' ? 'merges across branches' : 'branch-local';
    strip.appendChild(glyph);
    strip.appendChild(label);
    // Tooltip explains the policy + names the ancestor that carries
    // the seed so the user can trace where it came from.
    strip.title = state === 'own'
      ? 'This fn is sticky-local: version rows do not propagate across branches on merge.'
      : state === 'inherited'
        ? ('This fn inherits branch-local from `:' + (branchLocal.seed || '<anon>')
           + '`. Version rows do not propagate across branches on merge.')
        : 'Version rows of this fn propagate on merge like any other.';
    if (flagEditable && typeof enterBranchLocalEditMode === 'function') {
      strip.classList.add('branch-local-strip-editable');
      strip.tabIndex = 0;
      strip.setAttribute('role', 'button');
      strip.title += ' Click to change.';
      const handler = (e) => {
        e.stopPropagation();
        enterBranchLocalEditMode(cardFnEntity, strip, branchLocal || null);
      };
      strip.addEventListener('click', handler);
      strip.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handler(e); }
      });
    }
    overlay.appendChild(strip);
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
  strip.className = 'hof-args-strip';   // same look as the λ strip; static looks in editor-styles.css
  strip.title = 'Free args this fn accepts from the caller and threads into its sub-tree: '
                + deepFreeArgs.join(', ');
  strip.textContent = deepFreeArgs.map(n => '⇣' + n).join(' ');
  overlay.appendChild(strip);
}
