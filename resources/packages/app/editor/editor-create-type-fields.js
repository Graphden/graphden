// Editor Create-Type Fields — the per-kind form-field builders for
// the create/edit-type popover: refinement (base-aware op dropdowns,
// :and/:or combinator, advanced-JSON toggle), list, union, and the
// drag-reorderable pair-row lists behind variant / record — plus the
// prefill parsers that reshape an existing type-row back into the
// form. Split out of editor-create-type.js (which keeps the popover
// lifecycle, shell, and submit dispatch).
//
// Depends on: editor-create-type.js globals (TYPE_KINDS context),
// editor-literal-types.js helpers. Loaded immediately BEFORE
// editor-create-type.js in `_editor-script-paths`.

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
