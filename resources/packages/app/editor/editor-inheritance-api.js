// Inheritance writes use one server preview and one atomic apply. The preview
// owns compatibility and the orphan set; client lookups are presentation only.

async function inheritanceRequest(stage, command) {
  const endpoints = { preview: API.api_inheritance_preview, apply: API.api_inheritance_apply };
  const endpoint = endpoints[stage];
  if (!endpoint) throw new Error('Inheritance API is unavailable.');
  const response = await authFetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(command),
  });
  const result = await response.json().catch(() => null);
  if (!response.ok || !result?.ok) {
    const message = response.status === 401
      ? 'Sign-in expired. Re-authenticate from the top bar.'
      : result?.reason || result?.error || 'Inheritance request failed (HTTP ' + response.status + ').';
    throw new Error(message);
  }
  return result;
}

function inheritanceOrphanLabel(bindingId) {
  const binding = lookups?.bindingMap?.get(bindingId);
  const slot = binding && lookups?.slotMap?.get(binding['slot-id']);
  return slot?.name ? slot.name + ' (' + bindingId + ')' : bindingId;
}

function confirmInheritanceOrphans(preview) {
  const ids = preview['orphan-binding-ids'] || [];
  if (!ids.length) return true;
  return confirm('Changing parents will delete these own bindings and their list items:\n\n'
    + ids.map(inheritanceOrphanLabel).join('\n') + '\n\nContinue?');
}

async function applyInheritancePreview(preview, beforeApply) {
  if (!preview.allowed) throw new Error(preview.reason || 'This inheritance change is unavailable.');
  if (!confirmInheritanceOrphans(preview)) return null;
  beforeApply?.(preview);
  // Echo the exact consent snapshot. A stale 409 is shown to the user; it
  // never triggers a refreshed preview with silently accepted new deletions.
  return inheritanceRequest('apply', {
    ...preview.request,
    'expected-state': preview['expected-state'],
    'accepted-orphan-binding-ids': preview['orphan-binding-ids'] || [],
  });
}

async function runInheritanceCommand(command) {
  const preview = await inheritanceRequest('preview', command);
  return applyInheritancePreview(preview);
}
