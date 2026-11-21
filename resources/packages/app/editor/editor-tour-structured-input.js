// Lesson 43 uses the ordinary branch factory, Extend and its creation receipt.
// A package can offer the sample for this viewer through the subtree read.
const STRUCTURED_TOUR_ID = '43';
const STRUCTURED_TOUR_CHILD = 'tutorial-structured-profile';
const structuredLessonProofs = new WeakMap();

function _tourStructuredBranchMatches(saved) {
  return saved?.lessonId === STRUCTURED_TOUR_ID && !!_tourOwnedBranch(saved)
    && saved.sandboxBranch === _tourSessionBranch() && _tourPrincipalMatches(saved)
    && saved.created?.some(row => row.type === 'branch' && row.id === saved.sandboxBranchId
      && row.name === saved.sandboxBranch && row['base-branch-id'] === saved.sandboxBaseBranchId
      && row.receipt === 'created');
}

function _tourStructuredChild(saved, parentId) {
  return _tourStructuredBranchMatches(saved) && saved.created.find(row =>
    row.type === 'fn' && row.name === STRUCTURED_TOUR_CHILD && row.id
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

async function _tourScopeStructuredLesson(payload, current) {
  const lesson = payload?.lessons?.find(item => item.id === STRUCTURED_TOUR_ID);
  if (!lesson) return payload;
  const saved = _tourState || _tourLoadState();
  const offers = await _tourStructuredAvailable(lesson, saved, current);
  const ready = offers && current();
  if (ready) structuredLessonProofs.set(lesson, _tourStartTicket(false));
  else structuredLessonProofs.delete(lesson);
  return ready ? payload : {...payload, lessons: payload.lessons.filter(item => item !== lesson)};
}

function _tourStructuredLessonProved(lesson) {
  return _tourStartCurrent(structuredLessonProofs.get(lesson));
}

async function _tourStructuredAvailable(lesson, saved, current) {
  if (!lesson?.['template-id'] || typeof isAuthenticated !== 'function' || !isAuthenticated()
      || !window.API?.api_graph_entities || !API.api_branches
      || typeof switchToBranch !== 'function') return false;
  const ownsBranch = _tourStructuredBranchMatches(saved);
  if (!ownsBranch && _tourSessionBranch() !== 'main') return false;
  const child = ownsBranch && _tourStructuredChild(saved, lesson['template-id']);
  if (ownsBranch && !child) {
    const setup = lesson.steps?.findIndex(step => step.creates?.name === STRUCTURED_TOUR_CHILD);
    if (setup >= 0 && saved.step > setup) return false;
  }
  const id = child ? child.id : lesson['template-id'];
  try {
    if (ownsBranch && (await _tourBranchStatus(saved, saved.sandboxBranch) !== 'available' || !current())) return false;
    const response = await authFetch(API.api_graph_entities + '?scope=subtree&root-id=' + encodeURIComponent(id));
    if (!response.ok || !current()) return false;
    const data = await response.json();
    if (!current()) return false;
    const row = data.fns?.find(item => item.id === id);
    const input = row?.['structured-input'];
    if (!_tourStructuredProfile(input, !!child)) return false;
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
  return _tourStructuredBranchMatches(_tourState) && target?.type === 'fn'
    && target.name === STRUCTURED_TOUR_CHILD && target['parent-id'] === fn.id ? target : null;
}
