// Editor Create-Type — popover-driven flow for creating type-rows
// (refinement, record, union, variant, list) from the sidebar.
//
// Surface:
//   1. Per-namespace `+ Type ▾` button (wired from editor-sidebar.js)
//      opens a small kind-picker popover.
//   2. Kind-picker shows 5 options; clicking one swaps the popover
//      to the kind-specific form.
//   3. Form per kind:
//        refinement: name + base-type (text + datalist) + constraint
//                    DSL text (`(:> 0)` / `(:and (:>= 0) (:<= 100))`).
//        list:       name + element-type.
//        union:      name + comma-separated branch types.
//        variant:    name + lines of `tag: type`.
//        record:     name + lines of `field: type`.
//   4. Submit POSTs to the right endpoint:
//        refinement / union / variant → POST /api/entities/fn
//        list → POST /api/types/list
//        record → POST /api/types/record
//      On success: close popover, `initGraph()` to refresh, select
//      the new entity by name.
//
// Globals consumed: graphData, richTypes, authMutate, initGraph,
// selectFnByName, hideAllPopovers (optional).

let typeCreatePopoverEl = null;
let typeCreateContext = null; // {nsId, nsPath, anchorEl}

function hideTypeCreatePopover() {
  if (typeCreatePopoverEl) {
    typeCreatePopoverEl.style.display = 'none';
    typeCreatePopoverEl.textContent = '';
  }
  typeCreateContext = null;
}

// Outside-pointerdown / Esc dismissal — the anchor allowance lets the
// trigger re-open the popover without an intermediate close.
installPopoverDismiss({
  getEl: () => typeCreatePopoverEl,
  getAnchor: () => typeCreateContext?.anchorEl,
  isVisible: () => !!typeCreatePopoverEl && typeCreatePopoverEl.style.display !== 'none',
  onDismiss: hideTypeCreatePopover,
});

function ensureTypeCreatePopover() {
  if (typeCreatePopoverEl) return typeCreatePopoverEl;
  const el = document.createElement('div');
  el.className = 'type-create-popover';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', 'Create type');
  document.body.appendChild(el);
  typeCreatePopoverEl = el;
  return el;
}

// Kind metadata shared between the tab strip (create-flow) and the
// "Edit refinement" / "Edit record" titles. Hint is exposed via
// `title=` on the tab button so it still reaches the user via
// hover/tooltip even after we collapsed the separate kind-picker
// step.
const TYPE_KINDS = [
  { key: 'refinement', label: 'Refinement',
    hint: 'Subtype of a primitive constrained by a predicate.' },
  { key: 'record',     label: 'Record',
    hint: 'Product type — named fields, each with a type.' },
  { key: 'union',      label: 'Union',
    hint: 'Sum type — value is one of N branch types.' },
  { key: 'variant',    label: 'Variant',
    hint: 'Tagged union — branches discriminated by a :tag.' },
  { key: 'list',       label: 'List',
    hint: 'Homogeneous collection of an element type.' },
];

// Entry point — called from the sidebar's `+ Type` button. Opens
// the unified create form at the refinement tab by default; the
// user switches via the tab strip at the top of the form, which
// saves a click compared to the separate kind-picker step we used
// before.
function openTypeCreatePicker(parentNsId, parentNsPath, anchorEl) {
  if (typeof ensureAuth === 'function' && !ensureAuth()) return;
  ensureTypeCreatePopover();
  typeCreateContext = { nsId: parentNsId, nsPath: parentNsPath, anchorEl };
  showTypeCreateForm('refinement');
}

// Resolve a type-row's kind annotation (refinement / record / etc.)
// by name, via lookups.fnMap. Primitives (no role row) get
// "primitive". Unknown / unnamed → null.
function fnKindAnnotationByName(name) {
  if (!name) return null;
  const primitives = new Set(['int', 'numeric', 'text', 'bool', 'keyword',
                               'null', 'jsonb', 'any', 'fn', 'sequence',
                               'uuid', 'bytes', 'timestamptz', 'float']);
  if (primitives.has(name)) return 'primitive';
  if (typeof lookups !== 'object' || !lookups?.fnMap) return null;
  for (const fn of lookups.fnMap.values()) {
    if (fn.name === name) {
      // Backend ships role as `"refinement"` / `:refinement` depending
      // on encoding; tolerate both.
      const r = fn.role;
      return r ? String(r).replace(/^:/, '') : null;
    }
  }
  return null;
}

function buildTypeNameDatalist() {
  const id = 'type-create-typename-list';
  // Rebuild every call — the user may have created or deleted
  // type-rows since the last form open and the previous datalist
  // entries would be stale. Cheap (1 DOM rebuild per popover open).
  const prev = document.getElementById(id);
  if (prev) prev.remove();
  const dl = document.createElement('datalist');
  dl.id = id;

  const names = (typeof richTypes === 'object' && richTypes)
    ? Object.keys(richTypes).filter(n => richTypes[n]?.['type-row?']).sort()
    : [];
  const primitives = ['int', 'numeric', 'text', 'bool', 'keyword',
                      'null', 'jsonb', 'any', 'fn', 'sequence', 'uuid',
                      'bytes', 'timestamptz', 'float'];
  for (const p of primitives) names.push(p);
  for (const n of [...new Set(names)].sort()) {
    const o = document.createElement('option');
    o.value = n;
    // `label` shows on the right of the option in the dropdown,
    // disambiguating refinements / records / unions / etc. at a
    // glance without forcing the user to remember which name is
    // which kind.
    const kind = fnKindAnnotationByName(n);
    if (kind && kind !== 'composed') o.label = kind;
    dl.appendChild(o);
  }
  document.body.appendChild(dl);
  return id;
}

// graph-first-exception: the per-kind form is built client-side because (a)
// the type-name field autocompletes from the in-memory `richTypes` cache as
// the user types (same instant-datalist argument as the fn-picker /
// namespace-picker), and (b) switching kind tabs carries the in-progress
// name+description across without a round-trip. A GET /partials/* form would
// lose both. The submit itself POSTs to the graph (/api/entities/fn etc.).
function showTypeCreateForm(kind) {
  const el = typeCreatePopoverEl;
  if (!el) return;
  el.textContent = '';
  const ctx = typeCreateContext;
  if (!ctx) return;
  const listId = buildTypeNameDatalist();
  const editing = !!ctx.editFnId;

  const head = document.createElement('div');
  head.className = 'type-create-head';
  const title = document.createElement('span');
  title.className = 'type-create-title';
  title.textContent = (editing ? 'Edit ' : 'New ') + kind
    + (!editing && ctx.nsPath ? ' in ' + ctx.nsPath : '');
  head.appendChild(title);
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'type-create-close';
  close.textContent = '×';
  close.title = 'Close';
  close.addEventListener('click', (e) => { e.stopPropagation(); hideTypeCreatePopover(); });
  head.appendChild(close);
  el.appendChild(head);

  // Kind-tab strip — only on the create flow. Editing a refinement
  // can't switch to "make this a variant" mid-flight without a
  // separate convert-kind operation; we surface the tabs only when
  // they actually do something.
  if (!editing) {
    const tabs = document.createElement('div');
    tabs.className = 'type-create-tabs';
    tabs.setAttribute('role', 'tablist');
    for (const k of TYPE_KINDS) {
      const tab = document.createElement('button');
      tab.type = 'button';
      tab.className = 'type-create-tab'
        + (k.key === kind ? ' type-create-tab-active' : '');
      tab.textContent = k.label;
      tab.title = k.hint;
      tab.setAttribute('role', 'tab');
      tab.setAttribute('aria-selected', k.key === kind ? 'true' : 'false');
      tab.addEventListener('click', (e) => {
        e.stopPropagation();
        if (k.key === kind) return;
        // Carry the in-progress name+description across kind switches
        // so the user doesn't lose them when exploring options.
        const nameVal = el.querySelector('.type-create-form input')?.value || '';
        const descVal = el.querySelector('.type-create-form textarea.type-create-textarea')?.value
                     || el.querySelector('.type-create-form .type-create-field textarea')?.value
                     || '';
        const savedPrefill = typeCreateContext.prefill;
        typeCreateContext.prefill = Object.assign({}, savedPrefill || {}, {
          name: nameVal, description: descVal,
        });
        showTypeCreateForm(k.key);
        // Don't keep the bogus prefill after re-render — only the
        // first showTypeCreateForm pass should see it.
        typeCreateContext.prefill = savedPrefill;
      });
      tabs.appendChild(tab);
    }
    el.appendChild(tabs);
  }

  const form = document.createElement('form');
  form.className = 'type-create-form';
  form.addEventListener('submit', (e) => e.preventDefault());

  // Name input — present in every kind.
  const nameLabel = document.createElement('label');
  nameLabel.className = 'type-create-field';
  const nameSpan = document.createElement('span');
  nameSpan.className = 'type-create-field-label';
  nameSpan.textContent = 'name';
  const nameIn = document.createElement('input');
  nameIn.type = 'text';
  nameIn.className = 'type-create-input';
  nameIn.placeholder = kind === 'refinement' ? 'positive-int'
    : kind === 'record' ? 'user'
    : kind === 'union' ? 'nullable-int'
    : kind === 'variant' ? 'result-int'
    : 'list-of-int';
  nameIn.required = true;
  nameLabel.appendChild(nameSpan);
  nameLabel.appendChild(nameIn);
  form.appendChild(nameLabel);

  // Kind-specific fields.
  const extras = buildKindFields(kind, listId, ctx.prefill);
  form.appendChild(extras.el);

  // Description — optional, applies to every kind. Pre-filled in
  // edit mode so the existing text isn't silently dropped.
  const descLabel = document.createElement('label');
  descLabel.className = 'type-create-field';
  const descSpan = document.createElement('span');
  descSpan.className = 'type-create-field-label';
  descSpan.textContent = 'description';
  const descIn = document.createElement('textarea');
  descIn.className = 'type-create-input type-create-textarea';
  descIn.placeholder = '(optional) — what this type means, when to use it';
  descIn.rows = 2;
  // Description prefill: carries across edit mode AND kind switches
  // (the create flow stashes the in-progress description before
  // re-rendering the form for a different kind).
  if (ctx.prefill?.description) descIn.value = ctx.prefill.description;
  descLabel.appendChild(descSpan);
  descLabel.appendChild(descIn);
  form.appendChild(descLabel);

  // Name prefill: edit mode AND kind-switch carry-over.
  if (ctx.prefill?.name) nameIn.value = ctx.prefill.name;

  // Error message slot.
  const errEl = document.createElement('div');
  errEl.className = 'type-create-error';
  errEl.style.display = 'none';
  form.appendChild(errEl);

  // Submit + cancel row.
  const actions = document.createElement('div');
  actions.className = 'type-create-actions';
  const back = document.createElement('button');
  back.type = 'button';
  back.className = 'type-create-back';
  back.textContent = 'Cancel';
  back.addEventListener('click', (e) => {
    e.stopPropagation();
    hideTypeCreatePopover();
  });
  const submit = document.createElement('button');
  submit.type = 'submit';
  submit.className = 'type-create-submit';
  submit.textContent = editing ? 'Save' : 'Create';
  actions.appendChild(back);
  actions.appendChild(submit);
  form.appendChild(actions);
  el.appendChild(form);

  submit.addEventListener('click', async (e) => {
    e.preventDefault();
    e.stopPropagation();
    errEl.style.display = 'none';
    submit.disabled = true;
    try {
      const name = nameIn.value.trim();
      if (!name) throw new Error('Name required');
      const payload = extras.collect(name);
      payload.description = descIn.value.trim();
      if (editing) {
        await putTypeEdit(kind, ctx.editFnId, name, payload);
      } else {
        await postTypeCreate(kind, ctx.nsId, name, payload);
      }
      hideTypeCreatePopover();
      if (typeof initGraph === 'function') await initGraph();
      if (typeof selectFnByName === 'function') selectFnByName(name);
    } catch (err) {
      errEl.textContent = err?.message || String(err);
      errEl.style.display = 'block';
      submit.disabled = false;
    }
  });
  anchorBelowClamped(typeCreatePopoverEl, ctx.anchorEl);
  // Auto-focus first input.
  setTimeout(() => nameIn.focus(), 0);
}

// ---------- per-kind field builders -----------------------------------

function buildKindFields(kind, datalistId, prefill) {
  if (kind === 'refinement') return buildRefinementFields(datalistId, prefill);
  if (kind === 'list')       return buildListFields(datalistId, prefill);
  if (kind === 'union')      return buildUnionFields(datalistId, prefill);
  if (kind === 'variant')    return buildVariantFields(datalistId, prefill);
  if (kind === 'record')     return buildRecordFields(datalistId, prefill);
  throw new Error('Unknown kind: ' + kind);
}

function fieldRow(label, input) {
  const row = document.createElement('label');
  row.className = 'type-create-field';
  const sp = document.createElement('span');
  sp.className = 'type-create-field-label';
  sp.textContent = label;
  row.appendChild(sp);
  row.appendChild(input);
  return row;
}

function typeNameInput(placeholder, datalistId) {
  const i = document.createElement('input');
  i.type = 'text';
  i.className = 'type-create-input';
  i.placeholder = placeholder;
  i.setAttribute('list', datalistId);
  return i;
}

// Refinement constraint builder — replaces the prior raw JSON
// textbox. Default UX: pick an operator from a base-type-aware
// dropdown, then enter a value. Multi-row form → joined via :and
// (with an :or toggle). Advanced mode reveals the original JSON
// input for shapes the builder can't express (nested combinators,
// custom predicate fns, …).
const REFINEMENT_OPS_BY_BASE = {
  int:         ['>', '>=', '<', '<=', '=', '!=', 'in', 'matches'],
  numeric:     ['>', '>=', '<', '<=', '=', '!=', 'in', 'matches'],
  float:       ['>', '>=', '<', '<=', '=', '!=', 'in', 'matches'],
  text:        ['=', '!=', 'in', 'matches'],
  bool:        ['=', '!=', 'in'],
  keyword:     ['=', '!=', 'in'],
  uuid:        ['=', '!=', 'in'],
  timestamptz: ['=', '!=', 'in'],
  null:        ['=', '!='],
};
const REFINEMENT_DEFAULT_OPS = REFINEMENT_OPS_BY_BASE.int;

function refinementOpsFor(baseName) {
  return REFINEMENT_OPS_BY_BASE[baseName] || REFINEMENT_DEFAULT_OPS;
}

// Parse a string value into the right JS primitive for the
// constraint payload. The chosen type follows the input shape so
// `"7"` → 7, `"true"` → true, etc. `:in` is comma-split with the
// same per-element coercion.
function parseRefinementValue(op, raw) {
  const v = (raw || '').trim();
  if (op === ':in' || op === 'in') {
    return v.split(',').map(s => s.trim()).filter(Boolean)
            .map(parseScalarPart);
  }
  return parseScalarPart(v);
}

function parseScalarPart(s) {
  if (/^-?\d+$/.test(s)) return parseInt(s, 10);
  if (/^-?\d+\.\d+$/.test(s)) return parseFloat(s);
  if (s === 'true') return true;
  if (s === 'false') return false;
  if (s === 'null') return null;
  return s;
}

function buildRefinementFields(datalistId, prefill) {
  const base = typeNameInput('int', datalistId);
  if (prefill?.base) base.value = prefill.base;

  const wrap = document.createElement('div');
  wrap.appendChild(fieldRow('base', base));

  // ----- builder body --------------------------------------------------
  const builder = document.createElement('div');
  builder.className = 'refinement-builder';
  const combRow = document.createElement('div');
  combRow.className = 'refinement-combinator';
  combRow.style.display = 'none';
  const combLabel = document.createElement('span');
  combLabel.className = 'refinement-combinator-label';
  combLabel.textContent = 'combine via';
  const combSel = document.createElement('select');
  combSel.className = 'refinement-combinator-select';
  ['and', 'or'].forEach((c) => {
    const o = document.createElement('option');
    o.value = c;
    o.textContent = c;
    combSel.appendChild(o);
  });
  combRow.appendChild(combLabel);
  combRow.appendChild(combSel);
  builder.appendChild(combRow);

  const rowsContainer = document.createElement('div');
  rowsContainer.className = 'refinement-rows';
  builder.appendChild(rowsContainer);

  function refreshOpOptions(opSel) {
    const prev = opSel.value;
    opSel.textContent = '';
    for (const o of refinementOpsFor(base.value.trim())) {
      const opt = document.createElement('option');
      opt.value = ':' + o;
      opt.textContent = o;
      opSel.appendChild(opt);
    }
    if (prev) opSel.value = prev;
  }

  function syncCombinatorVis() {
    combRow.style.display = rowsContainer.children.length > 1 ? '' : 'none';
  }

  function addRow(op, value) {
    const row = document.createElement('div');
    row.className = 'refinement-row';
    const opSel = document.createElement('select');
    opSel.className = 'refinement-op';
    refreshOpOptions(opSel);
    if (op) opSel.value = op;
    const valIn = document.createElement('input');
    valIn.type = 'text';
    valIn.className = 'type-create-input refinement-val';
    valIn.placeholder = base.value.trim() === 'text' ? '"value"' : '0';
    if (value !== undefined && value !== null && value !== '') {
      valIn.value = typeof value === 'string'
        ? value
        : Array.isArray(value)
          ? value.join(', ')
          : String(value);
    }
    const rm = document.createElement('button');
    rm.type = 'button';
    rm.className = 'type-create-pair-rm';
    rm.title = 'Remove this condition';
    rm.setAttribute('aria-label', 'Remove condition');
    rm.textContent = '×';
    rm.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      row.remove();
      syncCombinatorVis();
    });
    row.appendChild(opSel);
    row.appendChild(valIn);
    row.appendChild(rm);
    rowsContainer.appendChild(row);
    syncCombinatorVis();
  }

  // Refresh op dropdowns when base changes (different bases admit
  // different operator sets).
  base.addEventListener('input', () => {
    for (const r of rowsContainer.querySelectorAll('.refinement-op')) {
      refreshOpOptions(r);
    }
  });

  const addBtn = document.createElement('button');
  addBtn.type = 'button';
  addBtn.className = 'type-create-pair-add';
  addBtn.textContent = '+ add condition';
  addBtn.addEventListener('click', (e) => {
    e.preventDefault();
    e.stopPropagation();
    addRow();
  });
  builder.appendChild(addBtn);
  wrap.appendChild(fieldRow('constraint', builder));

  // ----- advanced (raw JSON) toggle ------------------------------------
  const advWrap = document.createElement('div');
  advWrap.className = 'refinement-advanced-wrap';
  const advToggle = document.createElement('button');
  advToggle.type = 'button';
  advToggle.className = 'refinement-advanced-toggle';
  advToggle.textContent = 'Advanced (raw JSON)';
  const advInput = document.createElement('input');
  advInput.type = 'text';
  advInput.className = 'type-create-input type-create-input-mono';
  advInput.placeholder = '[":and", [":>=", 0], [":<=", 100]]';
  advInput.style.display = 'none';
  let advancedMode = false;

  advToggle.addEventListener('click', (e) => {
    e.preventDefault();
    e.stopPropagation();
    advancedMode = !advancedMode;
    builder.style.display = advancedMode ? 'none' : '';
    advInput.style.display = advancedMode ? '' : 'none';
    if (advancedMode && !advInput.value) advInput.value = serializeBuilder();
    advToggle.textContent = advancedMode ? 'Builder' : 'Advanced (raw JSON)';
  });
  advWrap.appendChild(advToggle);
  wrap.appendChild(advWrap);
  wrap.appendChild(advInput);

  function serializeBuilder() {
    const rows = [...rowsContainer.children].map((r) => {
      const op = r.querySelector('.refinement-op').value;
      const v = r.querySelector('.refinement-val').value;
      return [op, parseRefinementValue(op, v)];
    });
    if (rows.length === 0) return '';
    if (rows.length === 1) return JSON.stringify(rows[0]);
    return JSON.stringify([':' + combSel.value, ...rows]);
  }

  // Prefill — try to reshape an existing constraint into builder
  // rows. Falls back to advanced JSON mode for shapes we can't
  // round-trip cleanly.
  let prefilled = false;
  if (prefill?.constraint) {
    try {
      const parsed = JSON.parse(prefill.constraint);
      if (Array.isArray(parsed) && parsed.length) {
        const head = String(parsed[0]).replace(/^:/, '');
        if ((head === 'and' || head === 'or')
            && parsed.slice(1).every(c => Array.isArray(c) && c.length === 2)) {
          combSel.value = head;
          for (const c of parsed.slice(1)) addRow(String(c[0]), c[1]);
          prefilled = true;
        } else if (parsed.length === 2) {
          addRow(parsed[0].startsWith(':') ? parsed[0] : ':' + parsed[0], parsed[1]);
          prefilled = true;
        }
      }
    } catch (_) {
      // fall through to advanced
    }
    if (!prefilled) {
      advancedMode = true;
      builder.style.display = 'none';
      advInput.style.display = '';
      advInput.value = prefill.constraint;
      advToggle.textContent = 'Builder';
      prefilled = true;
    }
  }
  if (!prefilled) addRow();

  return {
    el: wrap,
    collect: () => {
      if (!base.value.trim()) throw new Error('base required');
      let constraintStr;
      if (advancedMode) {
        if (!advInput.value.trim()) throw new Error('constraint required');
        try { JSON.parse(advInput.value); }
        catch { throw new Error('constraint must be JSON (e.g. [">", 0])'); }
        constraintStr = advInput.value;
      } else {
        if (rowsContainer.children.length === 0) {
          throw new Error('at least one condition required');
        }
        for (const r of rowsContainer.children) {
          if (!r.querySelector('.refinement-val').value.trim()) {
            throw new Error('value required on every condition');
          }
        }
        constraintStr = serializeBuilder();
        if (!constraintStr) throw new Error('constraint required');
      }
      return { kind: 'refinement', body: {
        'base-fn-id': base.value.trim(),
        constraint: constraintStr
      }};
    }
  };
}

function buildListFields(datalistId, prefill) {
  const element = typeNameInput('int', datalistId);
  if (prefill?.element) element.value = prefill.element;
  const wrap = document.createElement('div');
  wrap.appendChild(fieldRow('element', element));
  return {
    el: wrap,
    collect: () => {
      if (!element.value.trim()) throw new Error('element type required');
      return { kind: 'list', body: { 'element-type': element.value.trim() } };
    }
  };
}

function buildUnionFields(datalistId, prefill) {
  // No "branches" label here — the placeholder already says
  // "null, text  (comma-separated)", and the surrounding form
  // header reads "New union" / "Edit union", so the field's
  // purpose is unambiguous.
  const branches = document.createElement('input');
  branches.type = 'text';
  branches.className = 'type-create-input type-create-input-fullrow';
  branches.placeholder = 'null, text  (comma-separated)';
  branches.setAttribute('list', datalistId);
  branches.setAttribute('aria-label', 'Union branches');
  if (prefill?.branches) branches.value = prefill.branches;
  const wrap = document.createElement('div');
  wrap.appendChild(branches);
  return {
    el: wrap,
    collect: () => {
      const parts = branches.value.split(',').map(s => s.trim()).filter(Boolean);
      if (parts.length < 2) throw new Error('Union needs ≥ 2 branches');
      const seen = new Set();
      for (const p of parts) {
        if (seen.has(p)) throw new Error('Duplicate branch: ' + p);
        seen.add(p);
      }
      // Backend `parse-fn-from-form` JSON-decodes the `constraint`
      // field and keywordises strings — so a plain JSON array of
      // bare names becomes the right shape on the Clojure side.
      return { kind: 'union', body: {
        constraint: JSON.stringify(['union', ...parts])
      }};
    }
  };
}

// Shared row-list builder used by variant (tag + type) and record
// (field + type). Two-column layout with `+ add` button, `×` on
// each row to remove, and a drag handle for reorder. Prefill:
// array of `{a, b}` objects (key names tied to the parsed shape
// per kind).
function buildPairRowList(opts) {
  const { keyName, valueName, valueDatalistId, keyPlaceholder,
          valuePlaceholder, initialPairs, minRows } = opts;
  const wrap = document.createElement('div');
  wrap.className = 'type-create-pair-list';

  const rowsContainer = document.createElement('div');
  rowsContainer.className = 'type-create-pair-rows';
  wrap.appendChild(rowsContainer);

  // Drag-reorder state — `draggedRow` is captured on dragstart and
  // the dragover handler swaps DOM siblings based on the cursor's
  // vertical midpoint. HTML5 native DnD is enough here; no library.
  let draggedRow = null;
  rowsContainer.addEventListener('dragover', (e) => {
    if (!draggedRow) return;
    e.preventDefault();
    const target = e.target.closest('.type-create-pair-row');
    if (!target || target === draggedRow) return;
    const rect = target.getBoundingClientRect();
    const isAfter = (e.clientY - rect.top) > rect.height / 2;
    if (isAfter && target.nextSibling !== draggedRow) {
      rowsContainer.insertBefore(draggedRow, target.nextSibling);
    } else if (!isAfter && target !== draggedRow.nextSibling) {
      rowsContainer.insertBefore(draggedRow, target);
    }
  });

  const addRow = (initialKey, initialVal) => {
    const row = document.createElement('div');
    row.className = 'type-create-pair-row';
    row.draggable = true;
    row.addEventListener('dragstart', (e) => {
      // Only allow drag from the handle, otherwise the row would
      // start dragging when the user simply tries to click inside
      // an input. e.target distinguishes.
      if (!e.target.classList?.contains('type-create-pair-drag')) {
        e.preventDefault();
        return;
      }
      draggedRow = row;
      row.classList.add('type-create-pair-row-dragging');
      e.dataTransfer.effectAllowed = 'move';
    });
    row.addEventListener('dragend', () => {
      row.classList.remove('type-create-pair-row-dragging');
      draggedRow = null;
    });
    const handle = document.createElement('span');
    handle.className = 'type-create-pair-drag';
    handle.title = 'Drag to reorder';
    handle.setAttribute('aria-label', 'Drag to reorder');
    handle.textContent = '⋮⋮';
    const keyIn = document.createElement('input');
    keyIn.type = 'text';
    keyIn.className = 'type-create-input type-create-pair-key';
    keyIn.placeholder = keyPlaceholder;
    if (initialKey != null) keyIn.value = initialKey;
    const valIn = document.createElement('input');
    valIn.type = 'text';
    valIn.className = 'type-create-input type-create-pair-val';
    valIn.placeholder = valuePlaceholder;
    if (valueDatalistId) valIn.setAttribute('list', valueDatalistId);
    if (initialVal != null) valIn.value = initialVal;
    const rm = document.createElement('button');
    rm.type = 'button';
    rm.className = 'type-create-pair-rm';
    rm.title = 'Remove this row';
    rm.setAttribute('aria-label', 'Remove row');
    rm.textContent = '×';
    rm.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      row.remove();
    });
    row.appendChild(handle);
    row.appendChild(keyIn);
    row.appendChild(valIn);
    row.appendChild(rm);
    rowsContainer.appendChild(row);
  };

  const seed = initialPairs?.length
    ? initialPairs : [{ [keyName]: '', [valueName]: '' }];
  for (const p of seed) addRow(p[keyName], p[valueName]);
  // Keep adding empty rows until minRows is met (helps the
  // first-time user see >1 input).
  while (rowsContainer.children.length < (minRows || 1)) addRow('', '');

  const addBtn = document.createElement('button');
  addBtn.type = 'button';
  addBtn.className = 'type-create-pair-add';
  addBtn.textContent = '+ add row';
  addBtn.addEventListener('click', (e) => {
    e.preventDefault();
    e.stopPropagation();
    addRow('', '');
  });
  wrap.appendChild(addBtn);

  return {
    el: wrap,
    collect: () => {
      const out = [];
      for (const row of rowsContainer.children) {
        const k = row.querySelector('.type-create-pair-key').value.trim();
        const v = row.querySelector('.type-create-pair-val').value.trim();
        if (!k && !v) continue;
        out.push({ [keyName]: k, [valueName]: v });
      }
      return out;
    }
  };
}


function buildVariantFields(datalistId, prefill) {
  const initialPairs = parseVariantPrefill(prefill?.branches);
  const list = buildPairRowList({
    keyName: 'tag',
    valueName: 'type',
    valueDatalistId: datalistId,
    keyPlaceholder: 'ok',
    valuePlaceholder: 'int',
    initialPairs,
    minRows: 2,
  });
  const wrap = document.createElement('div');
  wrap.appendChild(fieldRow('branches', list.el));
  return {
    el: wrap,
    collect: () => {
      const pairs = list.collect();
      if (pairs.length < 1) throw new Error('Variant needs ≥ 1 (tag, type) pair');
      const flat = [];
      for (const p of pairs) {
        if (!p.tag) throw new Error('Tag required on every row');
        if (!p.type) throw new Error('Type required on every row');
        flat.push(p.tag, p.type);
      }
      return { kind: 'variant', body: {
        constraint: JSON.stringify(['variant', ...flat])
      }};
    }
  };
}


function parseVariantPrefill(text) {
  // Edit-mode prefill is a "tag: type\n" textarea string; the
  // create-type-form built it via constraintMemberToName. Parse
  // back into objects for the row-list seed.
  if (!text) return null;
  const out = [];
  for (const line of text.split('\n')) {
    const m = line.match(/^\s*([^:]+):\s*(.+)\s*$/);
    if (!m) continue;
    out.push({ tag: m[1].trim(), type: m[2].trim() });
  }
  return out.length ? out : null;
}

function buildRecordFields(datalistId, prefill) {
  const initialPairs = parseRecordPrefill(prefill?.fields);
  const list = buildPairRowList({
    keyName: 'name',
    valueName: 'type',
    valueDatalistId: datalistId,
    keyPlaceholder: 'id',
    valuePlaceholder: 'uuid',
    initialPairs,
    minRows: 2,
  });
  const wrap = document.createElement('div');
  wrap.appendChild(fieldRow('fields', list.el));
  return {
    el: wrap,
    collect: () => {
      const pairs = list.collect();
      if (pairs.length < 1) throw new Error('Record needs ≥ 1 field');
      for (const p of pairs) {
        if (!p.name) throw new Error('Field name required on every row');
        if (!p.type) throw new Error('Field type required on every row');
      }
      return { kind: 'record', body: { fields: pairs } };
    }
  };
}


function parseRecordPrefill(text) {
  if (!text) return null;
  const out = [];
  for (const line of text.split('\n')) {
    const m = line.match(/^\s*([^:]+):\s*(.+)\s*$/);
    if (!m) continue;
    out.push({ name: m[1].trim(), type: m[2].trim() });
  }
  return out.length ? out : null;
}

// ---------- submit dispatch -------------------------------------------

// Entry point — called from the type-row's canvas card to edit its
// structural definition in place. Maps `role` → form-kind, builds a
// prefill from the existing fn-row, and opens the same popover used
// for create (with `editFnId` set so submit goes through PUT).
function openTypeEditForm(fnId, anchorEl) {
  if (typeof ensureAuth === 'function' && !ensureAuth()) return;
  const fn = (typeof lookups === 'object' && lookups?.fnMap)
    ? lookups.fnMap.get(fnId) : null;
  if (!fn) return;
  const kind = roleToKind(fn.role);
  if (!kind) {
    alert('Editing this type-row kind is not supported yet.');
    return;
  }
  const prefill = buildPrefillFromFn(fn, kind);
  const el = ensureTypeCreatePopover();
  typeCreateContext = {
    nsId: fn['namespace-id'] || null,
    nsPath: null,
    anchorEl,
    editFnId: fnId,
    prefill,
  };
  el.textContent = '';
  showTypeCreateForm(kind);
}

function roleToKind(role) {
  switch (role) {
    case 'refinement': case ':refinement': return 'refinement';
    case 'union':      case ':union':      return 'union';
    case 'variant':    case ':variant':    return 'variant';
    case 'list':       case ':list':       return 'list';
    case 'record':     case ':record':     return 'record';
    default: return null;
  }
}

function fnIdToTypeName(fnId) {
  if (!fnId || !lookups?.fnMap) return null;
  const f = lookups.fnMap.get(fnId);
  return f?.name || null;
}

function constraintMemberToName(x) {
  // Constraints arrive from the backend keywordised — :null, :text,
  // refs etc. — but the form takes plain names. Strip the leading
  // ":" character that JSON encoding preserves.
  if (x == null) return '';
  if (typeof x === 'string') return x.replace(/^:/, '');
  return String(x);
}

function buildPrefillFromFn(fn, kind) {
  const base = { name: fn.name || '', description: fn.description || '' };
  if (kind === 'refinement') {
    return Object.assign(base, {
      base: fnIdToTypeName(fn['base-fn-id']) || '',
      // `constraint` round-trips through parse-fn-from-form's JSON
      // path: store as canonical JSON so the same code that handles
      // create handles edit.
      constraint: fn.constraint ? JSON.stringify(fn.constraint) : '',
    });
  }
  if (kind === 'list') {
    return Object.assign(base, {
      element: fnIdToTypeName(fn['element-fn-id']) || '',
    });
  }
  if (kind === 'union') {
    const c = fn.constraint || [];
    const branches = (Array.isArray(c) ? c.slice(1) : [])
      .map(constraintMemberToName).filter(Boolean);
    return Object.assign(base, { branches: branches.join(', ') });
  }
  if (kind === 'variant') {
    const c = fn.constraint || [];
    const xs = Array.isArray(c) ? c.slice(1) : [];
    const lines = [];
    for (let i = 0; i + 1 < xs.length; i += 2) {
      lines.push(constraintMemberToName(xs[i])
                 + ': ' + constraintMemberToName(xs[i + 1]));
    }
    return Object.assign(base, { branches: lines.join('\n') });
  }
  if (kind === 'record') {
    // Render fields from fn-slots in lookups so the user can see
    // structure + add / remove / rename / retype them. Submit goes
    // through /api/types/record (PUT) which diffs against current.
    const slots = [];
    if (lookups?.fnSlotsByFn && lookups?.slotMap) {
      const fss = lookups.fnSlotsByFn.get(fn.id) || [];
      for (const fs of fss) {
        const slot = lookups.slotMap.get(fs['slot-id']);
        if (!slot) continue;
        const tname = fnIdToTypeName(slot['type-fn-id']) || 'any';
        slots.push((slot.name || '?') + ': ' + tname);
      }
    }
    return Object.assign(base, { fields: slots.join('\n') });
  }
  return base;
}

async function putTypeEdit(kind, fnId, name, payload) {
  // Records take a different path because slot add / remove / retype
  // is a multi-row delta — handled atomically by
  // PUT /api/types/record. Refinement / union / variant / list all
  // mutate a single `fn` row, so they go through generic PUT
  // /api/entities/fn/:id (parse-fn-from-form already accepts the
  // constraint / base-fn-id / element-fn-id keys from the create
  // path).
  // Always send description — empty string clears any prior value.
  const desc = payload.description || '';
  if (payload.kind === 'record') {
    const body = { id: fnId, name, description: desc, fields: payload.body.fields };
    const fetchFn = (typeof authFetch === 'function') ? authFetch : fetch;
    const r = await fetchFn(API.api_types_record_update, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    const data = await r.json().catch(() => ({ ok: false, error: 'HTTP ' + r.status }));
    if (!data.ok) throw new Error(data.error || ('HTTP ' + r.status));
    return r;
  }
  const form = Object.assign({ name, description: desc }, payload.body);
  // PUT /api/entities/fn/:id reads `element-fn-id` from the form (the
  // resolver under the hood accepts UUID-or-name). The create-side
  // payload uses `element-type` because POST /api/types/list takes a
  // distinct JSON-body key; rename so the same builder works for the
  // edit path.
  if (payload.kind === 'list' && form['element-type'] != null) {
    form['element-fn-id'] = form['element-type'];
    delete form['element-type'];
  }
  const r = await authMutate('PUT',
    API.api_entities_type_id('fn', fnId), form);
  if (!(r.status >= 200 && r.status < 300)) {
    const text = await r.text().catch(() => '');
    throw new Error((text || '').slice(0, 200) || ('HTTP ' + r.status));
  }
  return r;
}

async function postTypeCreate(kind, parentNsId, name, payload) {
  const desc = payload.description || '';
  if (payload.kind === 'record') {
    return postRecordOrList(API.api_types_record, name, parentNsId,
                            Object.assign({ fields: payload.body.fields },
                                          desc ? { description: desc } : {}));
  }
  if (payload.kind === 'list') {
    return postRecordOrList(API.api_types_list, name, parentNsId,
                            Object.assign({ 'element-type': payload.body['element-type'] },
                                          desc ? { description: desc } : {}));
  }
  // refinement / union / variant — go through generic
  // POST /api/entities/fn with extended parse-fn-from-form support.
  const form = Object.assign({
    name,
    'namespace-id': parentNsId || '',
  }, payload.body);
  if (desc) form.description = desc;
  const r = await authMutate('POST', API.api_entities_type('fn'), form);
  if (!(r.status >= 200 && r.status < 300)) {
    const text = await r.text().catch(() => '');
    throw new Error((text || '').slice(0, 200) || ('HTTP ' + r.status));
  }
  return r;
}

async function postRecordOrList(url, name, parentNsId, extra) {
  const body = Object.assign({ name }, extra);
  if (parentNsId) body['namespace-id'] = parentNsId;
  const fetchFn = (typeof authFetch === 'function') ? authFetch : fetch;
  const r = await fetchFn(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  const data = await r.json().catch(() => ({ ok: false, error: 'HTTP ' + r.status }));
  if (!data.ok) {
    throw new Error(data.error || ('HTTP ' + r.status));
  }
  return r;
}

window.openTypeCreatePicker = openTypeCreatePicker;
window.openTypeEditForm = openTypeEditForm;
