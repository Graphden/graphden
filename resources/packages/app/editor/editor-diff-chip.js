// Editor COMPARE MODE — the `Δ vs <branch> · N` CHIP and its review cockpit.
//
// `gdDiffModeRenderChip` puts
// the chip beside the branch chip (count = changed fns, `visible/total` while a
// lens filter is on); `gdOpenDiffChipMenu` is the menu behind it — 💬 Review &
// comments, 📤 propose the current branch, ⇢ merge the compared one in, × exit.
// A `.gd-pop` with a transparent scrim like the other context-bar popovers;
// `gdDiffChipInstallDismiss` wires its dismissal once.

// --- the "Δ vs <branch>" chip ----------------------------------------------

function gdDiffModeRenderChip() {
  const mount = document.getElementById('branch-mount');
  let chip = document.getElementById('gd-diff-chip');
  if (!_gdDiffMode) { if (chip) chip.remove(); return; }
  if (!mount) return;
  if (!chip) {
    chip = document.createElement('span');
    chip.id = 'gd-diff-chip';
    chip.className = 'gd-diff-chip';
    const label = document.createElement('button');
    label.className = 'gd-diff-chip-label';
    label.setAttribute('aria-haspopup', 'menu');
    label.setAttribute('aria-expanded', 'false');
    label.addEventListener('click', () => gdOpenDiffChipMenu(label));
    chip.appendChild(label);
    const off = document.createElement('button');
    off.className = 'gd-diff-chip-off';
    off.textContent = '×';
    off.title = 'Exit compare mode';
    off.setAttribute('aria-label', 'Exit compare mode');
    off.addEventListener('click', () => gdExitDiffMode());
    chip.appendChild(off);
    mount.appendChild(chip);
  }
  const label = chip.querySelector('.gd-diff-chip-label');
  // An active lens HIDES things — silent filtering is how a user ends
  // up believing two branches are identical. Say it on the chip: the
  // count goes `visible/total` while any lens filter is on (plus the
  // dashed border), and a plain total otherwise.
  const filtering = gdDiffLensFiltering();
  const total = _gdDiffMode.byFnId.size;
  let visible = total;
  if (filtering) {
    visible = 0;
    for (const g of _gdDiffMode.byFnId.values()) {
      if (gdDiffVisibleGroup(g['fn-id'])) visible += 1;
    }
  }
  const count = filtering ? (visible + '/' + total) : String(total);
  // Two spans, not one text node: the NAME ellipsizes on long branch
  // names while the count always stays visible at the right edge.
  label.textContent = '';
  const nameEl = document.createElement('span');
  nameEl.className = 'gd-diff-chip-name';
  nameEl.textContent = 'Δ vs ' + _gdDiffMode.branch;
  label.appendChild(nameEl);
  const countEl = document.createElement('span');
  countEl.className = 'gd-diff-chip-count';
  countEl.textContent = ' · ' + count;
  label.appendChild(countEl);
  chip.classList.toggle('gd-diff-chip-filtered', filtering);
  let inside = 0;
  for (const id of _gdDiffMode.affected.keys()) if (gdDiffAffectedInfo(id)) inside += 1;
  label.title = 'Compare mode — ' + total + ' changed fn'
    + (total === 1 ? '' : 's')
    + (inside ? ' (+' + inside + ' changed inside)' : '') + ' vs "' + _gdDiffMode.branch
    + '", marked in the Explorer and on the canvas. Click for the '
    + 'review actions and the type lens.'
    + (filtering
       ? ' LENS ACTIVE — showing ' + visible + ' of ' + total
         + '; the rest are hidden from the annotations.'
       : '');
}

// The chip's menu — the review COCKPIT for the compared pair: the full
// diff, propose-for-review (the merge-request act) / merge shortcuts,
// and the type lens. Torn down on any outside click.
function gdCloseDiffChipMenu() {
  const pop = document.getElementById('gd-diff-chip-pop');
  if (pop && typeof returnFocusTo === 'function'
      && pop.contains(document.activeElement)) {
    returnFocusTo(document.querySelector('.gd-diff-chip-label'));
  }
  pop?.remove();
  document.getElementById('gd-diff-chip-scrim')?.remove();
  document.querySelector('.gd-diff-chip-label')
    ?.setAttribute('aria-expanded', 'false');
}

// One-time registration of the shared dismissal contract (Escape +
// outside pointer) — the menu exists only transiently, so the hooks
// read the live DOM each time.
let _gdDiffChipDismissInstalled = false;

function gdDiffChipInstallDismiss() {
  if (_gdDiffChipDismissInstalled
      || typeof installPopoverDismiss !== 'function') return;
  _gdDiffChipDismissInstalled = true;
  installPopoverDismiss({
    getEl: () => document.getElementById('gd-diff-chip-pop'),
    getAnchor: () => document.querySelector('.gd-diff-chip-label'),
    isVisible: () => !!document.getElementById('gd-diff-chip-pop'),
    onDismiss: gdCloseDiffChipMenu,
    getReturnFocus: () => document.querySelector('.gd-diff-chip-label'),
  });
}

let _gdDiffChipMenuOpening = false;

async function gdOpenDiffChipMenu(anchorBtn) {
  gdCloseDiffChipMenu();
  if (!_gdDiffMode || _gdDiffChipMenuOpening) return;
  _gdDiffChipMenuOpening = true;
  try {
  const other = _gdDiffMode.branch;
  const cur = getCurrentBranchName();
  // One list fetch resolves both branches' ids + the current proposal
  // state (ids ride /api/branches/:ref/* paths safely — names with "/"
  // can't).
  let rows = [];
  try {
    const r = await window.authFetch(API.api_branches);
    rows = (await r.json())?.branches || [];
  } catch (_) {
    /* menu still renders; MERGE falls back to names, while the Review
       and Propose items need row data (base links) and stay hidden —
       a failed /api/branches fetch means the data plane is down anyway. */
  }
  const curRow = rows.find((b) => b.name === cur);
  const otherRow = rows.find((b) => b.name === other);
  const proposed = curRow?.['review-state'] === 'proposed';

  const scrim = document.createElement('div');
  scrim.id = 'gd-diff-chip-scrim';
  scrim.className = 'gd-pop-scrim';
  scrim.addEventListener('click', gdCloseDiffChipMenu);
  document.body.appendChild(scrim);

  const pop = document.createElement('div');
  pop.id = 'gd-diff-chip-pop';
  pop.className = 'gd-pop';
  const heading = document.createElement('h5');
  heading.textContent = cur + ' vs ' + other;
  pop.appendChild(heading);

  const item = (text, title, onClick) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'gd-pop-item';
    b.textContent = text;
    if (title) b.title = title;
    b.addEventListener('click', () => { gdCloseDiffChipMenu(); onClick(); });
    pop.appendChild(b);
    return b;
  };

  // The dialog frames a branch against its BASE — never open it for a
  // root branch (an empty "Review: main → main"). Prefer the side of
  // the compared pair whose base IS the other side; else any side with
  // a base; a pair of two roots gets no review item at all.
  const basedOn = (row, baseRow) =>
    row?.['base-branch-id'] && baseRow?.id
      && row['base-branch-id'] === baseRow.id;
  const reviewRow = basedOn(otherRow, curRow) ? otherRow
    : basedOn(curRow, otherRow) ? curRow
    : otherRow?.['base-branch-id'] ? otherRow
    : curRow?.['base-branch-id'] ? curRow : null;
  if (reviewRow) {
    item('💬 Review & comments',
         'The proposal conversation — change list, threads, suggestions',
         () => {
           if (typeof showReviewDialog === 'function') {
             showReviewDialog(reviewRow.name, reviewRow.id);
           }
         });
  }
  // Proposing aims at the branch's BASE — the root branch has none.
  if (curRow?.['base-branch-id']) {
    item(proposed ? '📤 Withdraw the proposal'
                : '📤 Propose "' + cur + '" for review',
       proposed ? 'Take the current branch out of review'
                : 'Submit the current branch for review into its base — the merge-request act',
       async () => {
         try {
           const r = await window.authFetch(
             API.api_branches_ref_propose(curRow?.id || cur), {
               method: 'POST',
               headers: { 'Content-Type': 'application/json' },
               body: JSON.stringify({ proposed: !proposed }),
             });
           const d = await r.json().catch(() => ({}));
           if (!d.ok && typeof gdToast === 'function') {
             gdToast(d.message || d.error || ('Could not change the proposal: HTTP ' + r.status));
           } else if (typeof gdToast === 'function') {
             gdToast(proposed ? 'Proposal withdrawn'
                              : '"' + cur + '" proposed for review');
           }
         } catch (e2) {
           if (typeof gdToast === 'function') {
             gdToast('Network error: ' + (e2?.message || e2));
           }
         }
       });
  }
  item('⇢ Merge "' + other + '" into "' + cur + '"',
       'Fold the compared branch into the one you are on',
       async () => {
         // The merge flow reports into the branch popover's error slot —
         // bring the popover up first so failures stay visible.
         if (typeof openBranchPopover === 'function') await openBranchPopover();
         if (typeof mergeBranchInto === 'function') {
           mergeBranchInto(other, cur,
                           null,
                           curRow?.id || _gdDiffMode?.currentId || null);
         }
       });

  // --- the type lens ---
  const lensHead = document.createElement('h5');
  lensHead.textContent = 'Show changes';
  pop.appendChild(lensHead);
  const lensOpt = (key, text, title) => {
    const label = document.createElement('label');
    label.className = 'gd-protect-opt';
    if (title) label.title = title;
    const box = document.createElement('input');
    box.type = 'checkbox';
    box.checked = !!_gdDiffLens[key];
    box.addEventListener('change', () => gdDiffSetLens({ [key]: box.checked }));
    label.appendChild(box);
    const span = document.createElement('span');
    span.textContent = text;
    label.appendChild(span);
    pop.appendChild(label);
  };
  lensOpt('added', '+ added here');
  lensOpt('modified', '± modified');
  lensOpt('missing', '− only on ' + other);
  lensOpt('substantiveOnly', 'Substantive only',
          'Hide edits that touch nothing but names and descriptions');
  lensOpt('effectsOnly', 'Effects touched only',
          'Show only changes that wire an effect-carrying fn in or out');

  const exit = document.createElement('button');
  exit.type = 'button';
  exit.className = 'gd-pop-item';
  exit.textContent = '× Exit compare mode';
  exit.addEventListener('click', () => { gdCloseDiffChipMenu(); gdExitDiffMode(); });
  pop.appendChild(exit);

  const r = anchorBtn.getBoundingClientRect();
  pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - 300)) + 'px';
  pop.style.top = (r.bottom + 6) + 'px';
  document.body.appendChild(pop);
  anchorBtn.setAttribute('aria-expanded', 'true');
  gdDiffChipInstallDismiss();
  if (typeof focusIntoDialog === 'function') focusIntoDialog(pop);
  } finally {
    _gdDiffChipMenuOpening = false;
  }
}
