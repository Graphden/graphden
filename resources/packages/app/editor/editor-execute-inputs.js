// The v2 Run form follows the authorized residual input tree. Argument
// names are data, including inside records. Slot forms belong to v1.
function structuredRunValueKind(type) {
  let expanded = dereferenceType(type);
  while (Array.isArray(expanded) && expanded[0] === 'refine') {
    expanded = dereferenceType(expanded[1]);
  }
  if (expanded === 'text') return 'text';
  if (expanded === 'bool') return 'bool';
  if (expanded === 'int') return 'int';
  if (expanded === 'numeric' || expanded === 'float') return 'number';
  return 'json';
}

function structuredRunLeaf(field, host, path) {
  const kind = structuredRunValueKind(field.type);
  const control = document.createElement(kind === 'bool' ? 'select' : kind === 'json' ? 'textarea' : 'input');
  const label = document.createElement('label');
  label.className = 'value-form-field';
  const name = document.createElement('span');
  name.className = 'value-form-label';
  name.textContent = field.name + (field.required === false ? ' (optional)' : '');
  control.className = 'arg-value-edit-input';
  if (field['required-now?']) control.setAttribute('aria-required', 'true');
  if (kind === 'int' || kind === 'number') {
    control.type = 'number';
    control.step = kind === 'int' ? '1' : 'any';
  } else if (kind === 'json') {
    control.rows = 2;
    control.placeholder = 'JSON';
  } else if (kind === 'bool') {
    for (const [value, text] of [['', 'Choose a value'], ['true', 'True'], ['false', 'False']]) {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = text;
      control.appendChild(option);
    }
  }
  label.appendChild(name);
  label.appendChild(control);
  host.appendChild(label);

  // An explicit empty string is a value. An optional empty text entry
  // needs a way to opt back out without submitting that empty value.
  let edited = false;
  let include;
  if (kind === 'text' && !field['required-now?']) {
    include = document.createElement('input');
    include.type = 'checkbox';
    const toggle = document.createElement('label');
    toggle.className = 'execute-option-label';
    toggle.appendChild(include);
    toggle.appendChild(document.createTextNode('Supply ' + field.name));
    host.insertBefore(toggle, label);
    const optionalInput = document.createElement('div');
    host.insertBefore(optionalInput, label);
    optionalInput.appendChild(label);
    optionalInput.hidden = true;
    include.addEventListener('change', () => {
      optionalInput.hidden = !include.checked;
      include.removeAttribute('aria-invalid');
    });
  }
  control.addEventListener('input', () => {
    edited = true;
    control.removeAttribute('aria-invalid');
  });
  const supplied = () => include ? include.checked
    : control.value !== '' || control.validity?.badInput || (kind === 'text' && edited);
  const fail = (message, target = control) => ({error: path.join('.') + ': ' + message, control: target});
  return {
    supplied,
    read(required) {
      control.removeAttribute('aria-invalid');
      include?.removeAttribute('aria-invalid');
      if (!supplied()) return required ? fail('Enter a value.', include) : {};
      if (control.validity?.badInput) return fail('Enter a number.');
      if (kind === 'bool') return {value: control.value === 'true'};
      if (kind === 'text') return {value: control.value};
      const {value, error} = readFieldValue(control, kind === 'int' ? 'number' : kind);
      if (error) return fail(error);
      if (kind === 'int' && !Number.isSafeInteger(value)) return fail('Enter an integer within the supported range.');
      if (kind === 'number' && !Number.isFinite(value)) return fail('Enter a finite number.');
      if (kind === 'json' && control.value.trim() === '') return fail('Enter a JSON value.');
      return {value};
    },
  };
}

function structuredRunGroup(fields, host, path = []) {
  if (!Array.isArray(fields) || path.length > 64) return null;
  const names = new Set();
  const entries = [];
  for (const field of fields) {
    if (!field || typeof field.name !== 'string' || !field.name || names.has(field.name)) return null;
    names.add(field.name);
    const fieldPath = [...path, field.name];
    let entry;
    if (Object.hasOwn(field, 'fields')) {
      if (!['input-field', 'reference-inputs'].includes(field.kind)) return null;
      const group = document.createElement('fieldset');
      group.className = 'value-form-group';
      const legend = document.createElement('legend');
      legend.textContent = field.name + (field.required === false ? ' (optional)' : '');
      group.appendChild(legend);
      host.appendChild(group);
      entry = structuredRunGroup(field.fields, group, fieldPath);
    } else if (field.kind === 'input-field' && field.type != null) {
      entry = structuredRunLeaf(field, host, fieldPath);
    }
    if (!entry) return null;
    entries.push({field, entry});
  }
  const supplied = () => entries.some(({entry}) => entry.supplied());
  const requiredNow = entries.some(({field, entry}) => field['required-now?'] === true || entry.requiredNow);
  return {
    supplied,
    requiredNow,
    read(required) {
      if (!required && !requiredNow && !supplied()) return {};
      const pairs = [];
      for (const {field, entry} of entries) {
        const result = entry.read(field['required-now?'] === true || field.required === true);
        if (result.error) return result;
        if (Object.hasOwn(result, 'value')) pairs.push([field.name, result.value]);
      }
      return {value: Object.fromEntries(pairs)};
    },
  };
}

function mountStructuredRunInputs(body, descriptor) {
  if (!body) return null;
  body.replaceChildren();
  if (descriptor?.status === 'available' && descriptor['run-available'] === true
      && descriptor.kind === 'input-record') {
    const root = structuredRunGroup(descriptor.fields, body);
    if (root) {
      const status = document.createElement('div');
      status.className = 'execute-arg-form-error';
      status.setAttribute('role', 'status');
      body.appendChild(status);
      body.addEventListener('input', () => { status.textContent = ''; });
      if (!descriptor.fields.length) {
        const note = document.createElement('div');
        note.className = 'execute-no-args-note';
        note.textContent = 'No remaining inputs.';
        body.appendChild(note);
      }
      return () => {
        const {value, error, control} = root.read(true);
        status.textContent = error || '';
        if (error) {
          control?.setAttribute('aria-invalid', 'true');
          control?.focus();
        }
        return value;
      };
    }
  }
  body.textContent = 'Structured inputs are unavailable for this function.';
  return null;
}
