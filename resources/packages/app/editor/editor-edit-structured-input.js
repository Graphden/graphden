// Structured Bind, Edit and Remove share the inline edit popover.
function openStructuredInputPopover(anchorEl, target, action, field, draft = {}) {
  const {fnId, isCurrent} = target;
  const {path, type, bindingId, versionId} = field;
  const {ref, text = ''} = draft;
  const label = path.join('.');
  const verb = {bind: 'Bind', edit: 'Edit', remove: 'Remove'}[action];
  const expectedType = dereferenceType(type);
  const valueKind = expectedType === 'text' ? 'text' : 'any';
  const modeButtons = [];
  const reopen = (nextDraft) => {
    if (anchorEl.isConnected && isCurrent()) {
      openStructuredInputPopover(anchorEl, target, action, field, nextDraft);
    }
  };
  const request = async (endpoint, body) => {
    const response = await authFetch(endpoint, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(body),
    });
    if (response.status === 401) throw new Error(await responseError(response));
    const result = await response.json().catch(() => null);
    if (!response.ok || !result?.ok) {
      throw new Error(result?.error || 'Structured input request failed (HTTP ' + response.status + ').');
    }
    return result;
  };
  openInlineEditPopover({
    anchorEl,
    ariaLabel: verb + ' ' + label,
    saveLabel: action === 'edit' ? 'Save' : verb,
    saveDanger: action === 'remove',
    makeControl(host) {
      if (action === 'remove') {
        const prompt = document.createElement('p');
        prompt.textContent = 'Remove the binding for ' + label + '?';
        host.appendChild(prompt);
        return null;
      }
      const control = document.createElement('input');
      control.type = 'text';
      control.className = 'arg-value-edit-input';
      control.setAttribute('aria-label', (ref ? 'Function for ' : action === 'edit' ? 'New value for ' : 'Value for ') + label);
      control.value = ref ? ref.name : text;
      control.readOnly = !!ref;
      host.appendChild(control);
      if (typeof openFnPicker === 'function') {
        const addButton = (caption, select) => {
          const button = document.createElement('button');
          button.type = 'button';
          button.className = 'arg-value-edit-btn arg-value-edit-btn-secondary';
          button.textContent = caption;
          button.addEventListener('click', select);
          modeButtons.push(button);
          host.appendChild(button);
        };
        addButton(ref ? 'Change function' : 'Choose function', () => {
          if (!isCurrent()) return;
          const literal = ref ? text : control.value;
          closeInlineEdit();
          openFnPicker({
            anchorEl,
            excludeIds: [fnId],
            expectedType,
            onPick(fn, candidate) {
              const picked = fn?.id ? {id: fn.id, name: candidate?.qualified || fn.name} : ref;
              reopen({text: literal, ref: picked});
            },
            onCancel() { reopen({text: literal, ref}); },
          });
        });
        if (ref) addButton('Use a value', () => reopen({text}));
      }
      return control;
    },
    async doSave(control, dialog) {
      const targetError = {ok: false, error: 'Refresh the selected function to change this input.'};
      if (!isCurrent()) return targetError;
      const previewEndpoint = API.api_structured_input_preview;
      const applyEndpoint = API.api_structured_input_apply;
      if (!previewEndpoint || !applyEndpoint) return {ok: false, error: 'Structured input editing is unavailable.'};
      const command = {action, 'fn-id': fnId};
      if (action === 'bind') command.path = path;
      else if (bindingId && versionId) command['binding-id'] = bindingId;
      else return targetError;
      if (ref) {
        command['ref-fn-id'] = ref.id;
      } else if (control) {
        if (valueKind !== 'text' && !control.value.trim()) return {ok: false, error: 'Enter a value.'};
        const {value, error} = readFieldValue(control, valueKind);
        if (error) return {ok: false, error};
        command.value = value;
      }
      if (control) control.readOnly = true;
      for (const button of modeButtons) button.disabled = true;
      try {
        const preview = await request(previewEndpoint, command);
        if (!dialog.isConnected || !isCurrent()) return targetError;
        const basis = preview['expected-state'];
        if (!basis) return {ok: false, error: 'The input preview could not be verified.'};
        if (bindingId && preview['expected-version'] !== versionId) {
          return {ok: false, error: 'This binding changed. Refresh the selected function.'};
        }
        const proposal = {...command, 'expected-state': basis};
        if (bindingId) proposal['expected-version'] = versionId;
        return await request(applyEndpoint, proposal);
      } finally {
        if (control) control.readOnly = !!ref;
        for (const button of modeButtons) button.disabled = false;
      }
    },
    async onSaved() {
      const restore = document.activeElement === anchorEl;
      const path = fnId + '/structured-input';
      await loadGraphData({keepExpandedTypePath: path});
      if (restore && document.activeElement === document.body) {
        returnFocusTo(document.querySelector('[data-inline-anchor="' + escAttr(path) + '"]'));
      }
      window.gdAnnounce?.({bind: 'Input bound', edit: 'Input binding updated', remove: 'Input binding removed'}[action]);
    },
  });
}
