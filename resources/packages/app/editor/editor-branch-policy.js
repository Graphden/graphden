// Editor Branches — the two governance MENUS on a branch row.
//
// `openBranchPolicyMenu` is the
// protected-branch ⛨ mini-menu (→ POST `/api/branches/:ref/policy`, options in
// `BRANCH_POLICY_OPTIONS`); `openProtectionMenu` is the ⚙ menu with its 0…3
// segmented required-approvals control (`postBranchProtection`). Both are
// tenancy-gated via `body.gd-tenancy` and resolve their `/api/branches/:ref/*`
// path through `branchRefFrom` (editor-branches.js — the row's
// `data-branch-id`), so a slash-named branch stays operable.

const BRANCH_POLICY_OPTIONS = [
  ['open', 'Everyone with write access'],
  ['owner', 'Only the owner (org admins can unlock)'],
  ['admins', 'Org admins only'],
];

// Who opened the panel — focus goes back there on close (the row's ⛨ / ⚙),
// so a keyboard reader is not dropped on <body>.
let _branchPolicyTrigger = null;
let _protectTrigger = null;

function closeBranchPolicyMenu() {
  const p = document.getElementById('gd-branch-policy-pop');
  if (p) p.remove();
  const s = document.getElementById('gd-branch-policy-scrim');
  if (s) s.remove();
  if (p && typeof returnFocusTo === 'function') returnFocusTo(_branchPolicyTrigger);
  _branchPolicyTrigger = null;
}

// Mini-menu on the row's ⛨ — pick who may write this branch.
function openBranchPolicyMenu(btn) {
  closeBranchPolicyMenu();
  const row = btn.closest('.branch-row');
  const branchName = btn.getAttribute('data-policy-branch');
  const branchRef = branchRefFrom(btn, branchName);
  const current = row?.getAttribute('data-write-policy') || 'open';
  const scrim = document.createElement('div');
  scrim.id = 'gd-branch-policy-scrim';
  scrim.className = 'gd-pop-scrim';
  scrim.addEventListener('click', closeBranchPolicyMenu);
  document.body.appendChild(scrim);
  const pop = document.createElement('div');
  pop.id = 'gd-branch-policy-pop';
  pop.className = 'gd-pop';
  // Branch name is user-controlled and getAttribute returns it DECODED, so it
  // must go in via textContent — never string-concatenated into innerHTML
  // (would re-inject `<img onerror=…>` live; there is no CSP). The option
  // rows below are built from the static BRANCH_POLICY_OPTIONS constant only,
  // so their markup stays a trusted template.
  _branchPolicyTrigger = btn;
  const heading = document.createElement('h5');
  heading.textContent = 'Who can write ' + branchName;
  pop.appendChild(heading);
  let html = '';
  BRANCH_POLICY_OPTIONS.forEach(([value, label]) => {
    const on = value === (current || 'open');
    html += '<button type="button" class="gd-pop-item' + (on ? ' sel' : '') + '"'
      + ' data-policy-value="' + value + '">'
      + '<span class="gd-pi">' + (on ? '●' : '○') + '</span>' + label + '</button>';
  });
  pop.insertAdjacentHTML('beforeend', html);
  pop.querySelectorAll('[data-policy-value]').forEach((item) => {
    item.addEventListener('click', async () => {
      closeBranchPolicyMenu();
      const err = document.getElementById('branch-popover-error');
      try {
        const resp = await window.authFetch(API.api_branches_ref_policy(branchRef), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ 'write-policy': item.getAttribute('data-policy-value') }),
        });
        const body = await resp.json();
        if (body.ok) {
          openBranchPopover(); // re-render rows with the new lock state
        } else if (err) {
          err.textContent = body.error || 'Could not change the branch protection';
          err.classList.remove('hidden');
        }
      } catch (e2) {
        if (err) {
          err.textContent = 'Network error: ' + (e2?.message || e2);
          err.classList.remove('hidden');
        }
      }
    });
  });
  const r = btn.getBoundingClientRect();
  pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - 300)) + 'px';
  pop.style.top = (r.bottom + 6) + 'px';
  document.body.appendChild(pop);
  // A form over a scrim is a dialog: a visible way out and focus inside
  // (docs/ACCESSIBILITY.md) — Escape and the scrim stay as they were.
  if (typeof ensurePopoverClose === 'function') {
    ensurePopoverClose(pop, pop.id === 'gd-protect-pop' ? closeProtectionMenu : closeBranchPolicyMenu,
                       pop.id === 'gd-protect-pop' ? 'Close protection' : 'Close', { prepend: true });
  }
  if (typeof focusIntoDialog === 'function') focusIntoDialog(pop);
}

// Tear down the ⚙ protection menu (popover + scrim) if open.
function closeProtectionMenu() {
  const p = document.getElementById('gd-protect-pop');
  p?.remove();
  document.getElementById('gd-protect-scrim')?.remove();
  if (p && typeof returnFocusTo === 'function') returnFocusTo(_protectTrigger);
  _protectTrigger = null;
}

// POST a review-policy / protect change and reload the popover. `url` is
// already resolved via window.API. A rejection surfaces in the shared slot.
async function postBranchProtection(url, payload, errMsg) {
  const err = document.getElementById('branch-popover-error');
  try {
    const resp = await window.authFetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const body = await resp.json();
    if (body.ok) {
      closeProtectionMenu();
      openBranchPopover();
    } else if (err) {
      err.textContent = body.error || errMsg;
      err.classList.remove('hidden');
    }
  } catch (e2) {
    if (err) {
      err.textContent = 'Network error: ' + (e2?.message || e2);
      err.classList.remove('hidden');
    }
  }
}

// ⚙ protection menu (open-core): the branch-as-TARGET knobs in one place —
// require-merge (push only via merge), required-approvals (0–3), and whether
// the author's own approval counts. Current state is read off the button's
// data-attrs. required-approvals + allow-self are POSTed together (the
// /review-policy endpoint is a full set, so sending both preserves both).
function openProtectionMenu(btn) {
  closeProtectionMenu();
  const branchName = btn.getAttribute('data-protect-branch');
  const branchRef = branchRefFrom(btn, branchName);
  const requireMerge = btn.getAttribute('data-require-merge') === '1';
  const reqAppr = Number.parseInt(btn.getAttribute('data-reqappr') || '0', 10) || 0;
  // data-allow-self: "off" = explicitly disabled; "on"/"" = counted (default).
  const allowSelf = btn.getAttribute('data-allow-self') !== 'off';
  const rpUrl = API.api_branches_ref_review_policy(branchRef);

  const scrim = document.createElement('div');
  scrim.id = 'gd-protect-scrim';
  scrim.className = 'gd-pop-scrim';
  scrim.addEventListener('click', closeProtectionMenu);
  document.body.appendChild(scrim);

  const pop = document.createElement('div');
  pop.id = 'gd-protect-pop';
  pop.className = 'gd-pop';
  // branchName is user-controlled + decoded → textContent only (no innerHTML).
  _protectTrigger = btn;
  const heading = document.createElement('h5');
  heading.textContent = 'Protect ' + branchName;
  pop.appendChild(heading);

  // require-merge checkbox
  const rmLabel = document.createElement('label');
  rmLabel.className = 'gd-protect-opt';
  const rmBox = document.createElement('input');
  rmBox.type = 'checkbox';
  rmBox.checked = requireMerge;
  rmBox.addEventListener('change', () =>
    postBranchProtection(API.api_branches_ref_protect(branchRef),
                         { 'require-merge': rmBox.checked },
                         'Could not change branch protection'));
  rmLabel.appendChild(rmBox);
  const rmText = document.createElement('span');
  rmText.textContent = 'Push only via merge (no direct writes)';
  rmLabel.appendChild(rmText);
  pop.appendChild(rmLabel);

  // Required approvals — a 0…3 SEGMENTED control, not a <select>: the
  // range is four known values (one tap each beats a two-step native
  // dropdown), and a native dropdown over a floating menu was fragile
  // (the outside-click closer used to swallow it mid-pick).
  const raRow = document.createElement('div');
  raRow.className = 'gd-protect-opt gd-protect-appr';
  const raText = document.createElement('span');
  raText.id = 'gd-protect-appr-label';
  raText.textContent = 'Required approvals';
  raRow.appendChild(raText);
  const seg = document.createElement('div');
  seg.className = 'gd-protect-seg';
  seg.setAttribute('role', 'radiogroup');
  seg.setAttribute('aria-labelledby', 'gd-protect-appr-label');
  let currentAppr = reqAppr;
  const saBox = document.createElement('input');   // created early — posted together
  const pushPolicy = () =>
    postBranchProtection(rpUrl,
                         { 'required-approvals': currentAppr,
                           'allow-self-approval': saBox.checked },
                         'Could not change the review policy');
  for (let n = 0; n <= 3; n++) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'gd-protect-seg-btn' + (n === reqAppr ? ' sel' : '');
    b.textContent = String(n);
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', n === reqAppr ? 'true' : 'false');
    b.title = n === 0 ? 'No review required'
      : ('Merges into ' + branchName + ' need ' + n + ' approval' + (n === 1 ? '' : 's'));
    b.addEventListener('click', () => {
      if (n === currentAppr) return;
      currentAppr = n;
      seg.querySelectorAll('.gd-protect-seg-btn').forEach((x) => {
        x.classList.toggle('sel', x === b);
        x.setAttribute('aria-checked', x === b ? 'true' : 'false');
      });
      pushPolicy();
    });
    seg.appendChild(b);
  }
  raRow.appendChild(seg);
  pop.appendChild(raRow);

  const saLabel = document.createElement('label');
  saLabel.className = 'gd-protect-opt';
  saBox.type = 'checkbox';
  saBox.checked = allowSelf;
  saLabel.appendChild(saBox);
  const saText = document.createElement('span');
  saText.textContent = "Count the author's own approval";
  saLabel.appendChild(saText);
  pop.appendChild(saLabel);
  saBox.addEventListener('change', pushPolicy);

  const r = btn.getBoundingClientRect();
  pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - 280)) + 'px';
  pop.style.top = (r.bottom + 6) + 'px';
  document.body.appendChild(pop);
  // A form over a scrim is a dialog: a visible way out and focus inside
  // (docs/ACCESSIBILITY.md) — Escape and the scrim stay as they were.
  if (typeof ensurePopoverClose === 'function') {
    ensurePopoverClose(pop, pop.id === 'gd-protect-pop' ? closeProtectionMenu : closeBranchPolicyMenu,
                       pop.id === 'gd-protect-pop' ? 'Close protection' : 'Close', { prepend: true });
  }
  if (typeof focusIntoDialog === 'function') focusIntoDialog(pop);
}
