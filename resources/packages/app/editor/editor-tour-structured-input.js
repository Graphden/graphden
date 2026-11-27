// Structured lessons use the ordinary branch factory, Extend and its creation receipt.
// A package can offer the sample for this viewer through the subtree read.
const structuredLessonSamples = new Map([
  ['43', {child: 'tutorial-structured-profile', checkShape: _tourStructuredProfile}],
  ['44', {child: 'tutorial-contact-route', checkShape: _tourStructuredContacts}],
  ['45', {child: 'tutorial-optional-label', checkShape: _tourStructuredOptional}],
  ['46', {child: 'tutorial-whole-profile', checkShape: _tourStructuredWholeProfile}],
  ['47', {child: 'tutorial-computed-greeting', checkShape: _tourStructuredComputedGreeting}],
]);
const structuredLessonProofs = new WeakMap();

function _tourStructuredBranchMatches(saved) {
  return structuredLessonSamples.has(saved?.lessonId) && !!_tourOwnedBranch(saved)
    && saved.sandboxBranch === _tourSessionBranch() && _tourPrincipalMatches(saved)
    && Array.isArray(saved.created) && saved.created.some(row => row?.type === 'branch' && row.id === saved.sandboxBranchId
      && row.name === saved.sandboxBranch && row['base-branch-id'] === saved.sandboxBaseBranchId
      && row.receipt === 'created');
}

function _tourStructuredChild(saved, parentId, templateId = _tourLessons?.['structured-templates']?.[saved?.lessonId]) {
  const sample = structuredLessonSamples.get(saved?.lessonId);
  return sample && templateId && templateId === parentId && _tourStructuredBranchMatches(saved) && saved.created.find(row =>
    row?.type === 'fn' && row.name === sample.child && row.id
    && row['parent-id'] === parentId && row['namespace-id'] === null
    && row['branch-id'] === saved.sandboxBranchId && row.receipt === 'created');
}

function _tourStructuredProfile(input, editable) {
  if (input?.status !== 'available' || input['run-available'] !== true
      || !Array.isArray(input.fields) || input.fields.length !== 1) return false;
  const [profile] = input.fields;
  if (profile.name !== 'profile' || !Array.isArray(profile.fields)) return false;
  const leaves = new Map(profile.fields.map(field => [field.name, field]));
  if (leaves.size !== profile.fields.length || ![2, 3].includes(leaves.size)) return false;
  const text = name => leaves.get(name)?.kind === 'input-field' && leaves.get(name).type === 'text';
  if (!text('id') || !text('email')) return false;
  if (!editable) return text('name');
  if (leaves.has('name')) return text('name') && JSON.stringify(leaves.get('name')['bind-path']) === '["profile","name"]';
  return leaves.size === 2 && Array.isArray(input.occupied) && input.occupied.some(field => field.type === 'text'
    && field['binding-id'] && field['version-id'] && JSON.stringify(field.path) === '["profile","name"]');
}

function _tourStructuredWholeProfile(input, editable) {
  if (input?.status !== 'available' || input['run-available'] !== true || !Array.isArray(input.fields)) return false;
  if (editable && input.fields.length === 0 && Array.isArray(input.occupied) && input.occupied.length === 1) {
    const [profile] = input.occupied;
    return profile?.type != null && JSON.stringify(profile.path) === '["profile"]'
      && !!profile['binding-id'] && !!profile['version-id'];
  }
  if (!_tourStructuredProfile(input, editable)) return false;
  const [profile] = input.fields;
  return !editable || JSON.stringify(profile['bind-path']) === '["profile"]'
    || (Array.isArray(input.occupied) && input.occupied.some(
      field => JSON.stringify(field?.path) === '["profile","name"]'));
}

function _tourStructuredContacts(input, editable) {
  if (input?.status !== 'available' || input['run-available'] !== true
      || !Array.isArray(input.fields) || input.fields.length !== 2) return false;
  const text = field => field?.kind === 'input-field' && field.type === 'text';
  return ['sender', 'recipient'].every(side => {
    const fields = input.fields.find(field => field?.name === side)?.fields;
    if (!Array.isArray(fields) || ![1, 2].includes(fields.length)
        || !text(fields.find(field => field?.name === 'name'))) return false;
    const address = fields.find(field => field?.name === 'address')?.fields;
    if (fields.length === 2 && !Array.isArray(address)) return false;
    const [city] = address || [];
    const path = JSON.stringify([side, 'address', 'city']);
    if (city) return address.length === 1 && city.name === 'city' && text(city)
      && (!editable || JSON.stringify(city['bind-path']) === path);
    return editable && !address?.length && Array.isArray(input.occupied)
      && input.occupied.some(field => field?.type === 'text' && field['binding-id']
        && field['version-id'] && JSON.stringify(field.path) === path);
  });
}

function _tourStructuredOptional(input, editable) {
  if (input?.status !== 'available' || input['run-available'] !== true || !Array.isArray(input.fields)) return false;
  const nullableText = field => JSON.stringify(field.type) === '["union","null","text"]';
  const labelPath = path => Array.isArray(path) && path.length === 1 && path[0] === 'label';
  if (input.fields.length === 1) {
    const [label] = input.fields;
    return label?.name === 'label' && label.kind === 'input-field' && label.required === false
      && label['required-now?'] === false && nullableText(label) && (!editable || labelPath(label['bind-path']));
  }
  if (!editable || input.fields.length || input.occupied?.length !== 1) return false;
  const [label] = input.occupied;
  return label && nullableText(label) && labelPath(label.path) && !!label['binding-id'] && !!label['version-id'];
}

function _tourStructuredComputedGreeting(input, editable) {
  if (input?.status !== 'available' || !Array.isArray(input.fields) || input.fields.length !== 2) return false;
  const profile = input.fields.find(field => field?.name === 'profile');
  const style = input.fields.find(field => field?.name === 'style');
  if (!Array.isArray(profile?.fields) || profile.fields.length !== 1 || !Array.isArray(style?.fields)) return false;
  const occupiedPath = path => Array.isArray(input.occupied) && input.occupied.some(field =>
    field?.['binding-id'] && field['version-id'] && JSON.stringify(field.path) === path);
  const builderBound = profile.kind === 'reference-inputs';
  const source = builderBound ? profile.fields[0] : profile;
  if (builderBound && (!editable || !occupiedPath('["profile"]') || source?.name !== 'profile')) return false;
  const [name] = source?.fields || [];
  if (source?.fields?.length !== 1 || name?.name !== 'name' || name.type !== 'text') return false;
  if (editable && !builderBound && JSON.stringify(profile['bind-path']) !== '["profile"]') return false;
  if (style.fields.find(field => field?.name === 'suffix')?.type !== 'text') return false;
  const callback = style.fields.find(field => field?.name === 'callback');
  if (style.fields.length !== 2) return false;
  if (callback?.kind === 'reference-inputs') {
    const [mark] = callback.fields || [];
    return editable && input['run-available'] === true && occupiedPath('["style","callback"]')
      && callback.fields?.length === 1 && mark?.kind === 'input-field' && mark.name === 'mark'
      && mark.type === 'text' && mark.required === true;
  }
  return callback?.type?.[0] === 'fn'
    && (!editable || JSON.stringify(callback['bind-path']) === '["style","callback"]');
}

async function _tourScopeStructuredLesson(payload, current) {
  const lessons = payload?.lessons?.filter(item => structuredLessonSamples.has(item.id));
  if (!lessons?.length) return payload;
  const saved = _tourState || _tourLoadState();
  const offers = await Promise.all(lessons.map(lesson => {
    if (lesson.id === '47') {
      const refs = payload['structured-template-refs']?.[lesson.id];
      if (!refs?.builder || !refs.callback || refs.builder === refs.callback) return false;
    }
    return _tourStructuredAvailable(lesson, saved, current, payload['structured-templates']?.[lesson.id]);
  }));
  const hidden = new Set();
  lessons.forEach((lesson, index) => {
    if (offers[index] && current()) structuredLessonProofs.set(lesson, _tourStartTicket(false));
    else {
      structuredLessonProofs.delete(lesson);
      hidden.add(lesson);
    }
  });
  return hidden.size ? {...payload, lessons: payload.lessons.filter(item => !hidden.has(item))} : payload;
}

function _tourStructuredLessonProved(lesson) {
  return _tourStartCurrent(structuredLessonProofs.get(lesson));
}

async function _tourStructuredAvailable(lesson, saved, current, templateId) {
  const sample = structuredLessonSamples.get(lesson?.id);
  if (!sample || !templateId || lesson['template-id'] !== templateId || typeof isAuthenticated !== 'function' || !isAuthenticated()
      || !window.API?.api_graph_entities || !API.api_branches
      || typeof switchToBranch !== 'function') return false;
  const ownsBranch = saved?.lessonId === lesson.id && _tourStructuredBranchMatches(saved);
  if (!ownsBranch && _tourSessionBranch() !== 'main') return false;
  const child = ownsBranch && _tourStructuredChild(saved, templateId, templateId);
  if (ownsBranch && !child) {
    const setup = lesson.steps?.findIndex(step => step.creates?.name === sample.child);
    if (setup >= 0 && saved.step > setup) return false;
  }
  const id = child ? child.id : templateId;
  try {
    if (ownsBranch && (await _tourBranchStatus(saved, saved.sandboxBranch) !== 'available' || !current())) return false;
    const response = await authFetch(API.api_graph_entities + '?scope=subtree&root-id=' + encodeURIComponent(id),
      {cache: 'no-store'});
    if (!response.ok || !current()) return false;
    const data = await response.json();
    if (!current()) return false;
    const row = data.fns?.find(item => item.id === id);
    const input = row?.['structured-input'];
    if (!sample.checkShape(input, !!child)) return false;
    if (child) return (row['namespace-id'] ?? null) === child['namespace-id']
      && row['parent-ids']?.length === 1 && row['parent-ids'][0] === child['parent-id'];
    return input['extend-available'] === true
      && Object.hasOwn(input, 'extend-namespace-id') && input['extend-namespace-id'] === null;
  } catch (_) {
    return false;
  }
}

function _tourStructuredExtend(fn) {
  const target = _tourStep()?.creates;
  const sample = structuredLessonSamples.get(_tourState?.lessonId);
  return sample && _tourLessons?.['structured-templates']?.[_tourState?.lessonId] === fn.id
    && _tourStructuredBranchMatches(_tourState) && target?.type === 'fn'
    && target.name === sample.child && target['parent-id'] === fn.id ? target : null;
}
