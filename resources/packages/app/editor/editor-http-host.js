// The graph partial owns markup. This module owns the anchored dialog,
// request lifecycle and pre-request identity staging for exact cleanup.
const HTTP_HOST_API = '/api/http-host'; // api-url-drift-allow: installed by core http-host.lifecycle
let httpHostEl = null;
let httpHostState = null;
window.gdHttpHostAvailable = null;

async function httpHostPublications() {
  const response = await authFetch(HTTP_HOST_API);
  const data = await response.json();
  if (!response.ok || data.ok !== true) throw new Error('Cannot read HTTP publications.');
  window.gdHttpHostAvailable = data.available === true;
  document.body.classList.toggle('gd-no-temporary-http', !window.gdHttpHostAvailable);
  return data;
}

function hideHttpHostPopover() {
  if (httpHostState?.anchor) httpHostState.anchor.setAttribute('aria-expanded', 'false');
  httpHostState = null;
  if (httpHostEl) {
    httpHostEl.classList.remove('visible');
    httpHostEl.style.display = 'none';
  }
}

function httpHostReturnFocus() {
  const id = httpHostState?.fn.id;
  return (id && document.querySelector('.more-actions-trigger[data-root-fn-id="' + id + '"]'))
    || httpHostState?.anchor;
}

function closeHttpHostPopover() {
  const anchor = httpHostReturnFocus();
  hideHttpHostPopover();
  if (anchor && typeof returnFocusTo === 'function') returnFocusTo(anchor);
}

function renderHttpHostState(state) {
  if (state !== httpHostState || !httpHostEl) return;
  const publication = state.publication;
  const link = httpHostEl.querySelector('[data-http-host-url]');
  link.hidden = !publication?.url;
  if (publication?.url) link.href = publication.url;
  else link.removeAttribute('href');
  httpHostEl.querySelector('[data-http-host-expiry]').textContent = publication?.['expires-at']
    ? 'Expires ' + new Date(publication['expires-at']).toLocaleString() : '';
  httpHostEl.querySelector('[data-http-host-publish]').disabled = state.busy || !state.available || !!publication;
  httpHostEl.querySelector('[data-http-host-stop]').disabled = state.busy || !state.available || !publication?.id;
  httpHostEl.querySelector('[data-http-host-status]').textContent = state.message || '';
  httpHostEl.dataset.httpHostStopped = state.stopped ? 'true' : 'false';
}

function httpHostFailure(status) {
  if (status === 429) return 'All publication slots are busy. Stop an existing publication or wait for one to expire, then retry.';
  if (status === 400) return 'Choose a typed response handler. Bind every input except request, and resolve its type errors.';
  if (status === 403) return 'Publishing requires an authenticated owner and permission to execute this handler.';
  if (status === 409) return 'That publication identity is unavailable. Close this window and try again.';
  return 'Publication failed. Its identity is retained so you can stop it safely.';
}

async function publishHttpHost(state) {
  if (state !== httpHostState || state.busy || !state.available || state.publication) return;
  const id = crypto.randomUUID();
  state.publication = {id};
  state.stopped = false;
  state.busy = true;
  state.message = 'Publishing…';
  if (typeof _tourTrackHttpPublication === 'function') _tourTrackHttpPublication(id, state.fn);
  renderHttpHostState(state);
  try {
    const response = await authFetch(HTTP_HOST_API, {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({'create-id': id, 'fn-id': state.fn.id}),
    });
    const data = await response.json();
    if (!response.ok || data.ok !== true) {
      state.message = httpHostFailure(response.status);
      // An explicit rejection creates nothing. A lost/invalid response is
      // different: retain the proposed identity for the Stop action.
      if (response.status >= 400 && response.status < 500) state.publication = null;
    } else {
      state.publication = data.publication;
      state.message = 'Published. Open the public URL to make a real HTTP request.';
    }
  } catch (_) {
    state.message = 'The response was lost. Stop this publication to clean up its exact identity, then retry.';
  } finally {
    state.busy = false;
    renderHttpHostState(state);
  }
}

async function stopHttpHost(state) {
  if (state !== httpHostState || state.busy || !state.available || !state.publication?.id) return;
  const id = state.publication.id;
  state.busy = true;
  state.message = 'Stopping…';
  renderHttpHostState(state);
  try {
    const response = await authFetch(HTTP_HOST_API + '/' + encodeURIComponent(id), {method: 'DELETE'});
    const data = await response.json();
    if (!response.ok || data.ok !== true) throw new Error('Stop rejected');
    state.publication = null;
    state.stopped = true;
    state.message = 'Publication stopped. Its URL no longer invokes the handler.';
  } catch (_) {
    state.message = 'Could not confirm cleanup. The publication identity is retained; retry Stop.';
  } finally {
    state.busy = false;
    renderHttpHostState(state);
  }
}

function gdHandlerPreviewAvailable() {
  return !!window.gdAccount?.id && typeof graphdenTenancyActive === 'function' && graphdenTenancyActive();
}

async function mintHandlerPreview(state) {
  if (state !== httpHostState || state.previewBusy || !gdHandlerPreviewAvailable()) return;
  const session = state.session;
  if (session !== serviceSessionKey()) return;
  const current = () => state === httpHostState && session === serviceSessionKey();
  state.previewBusy = true;
  const previousLink = httpHostEl.querySelector('[data-handler-preview-url]');
  previousLink.hidden = true;
  previousLink.removeAttribute('href');
  const button = httpHostEl.querySelector('[data-handler-preview-mint]');
  button.disabled = true;
  state.message = 'Preparing an isolated HTML preview…';
  renderHttpHostState(state);
  try {
    const response = await authFetch('/api/preview-token', { // api-url-drift-allow: tenancy capsule route
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({'fn-id': state.fn.id, mode: 'handler'}),
    });
    const data = await response.json();
    if (!current()) return;
    if (!response.ok || !data.ok || data.mode !== 'handler') throw new Error(data.error || 'HTML preview unavailable.');
    const url = new URL(data.url);
    if (url.protocol !== 'https:' || url.origin === location.origin || !url.pathname.startsWith('/__preview/handler/')) {
      throw new Error('An isolated HTTPS preview address is required.');
    }
    const link = httpHostEl.querySelector('[data-handler-preview-url]');
    link.href = url.href;
    link.hidden = false;
    state.message = 'HTML preview ready for two minutes. If it expires, click Preview HTML handler again and open the new link.';
  } catch (error) {
    if (current()) state.message = error.message;
  } finally {
    if (current()) {
      state.previewBusy = false;
      button.disabled = false;
      renderHttpHostState(state);
    }
  }
}

async function showHttpHostPopover(fn, anchor) {
  hideHttpHostPopover();
  const state = {fn, anchor, session: serviceSessionKey(), available: false, busy: true, publication: null, message: 'Loading…'};
  httpHostState = state;
  if (!httpHostEl) {
    httpHostEl = document.createElement('div');
    httpHostEl.className = 'service-popover http-host-popover';
    httpHostEl.setAttribute('role', 'dialog');
    httpHostEl.setAttribute('aria-label', 'Publish HTTP');
    document.body.appendChild(httpHostEl);
  }
  try {
    const response = await authFetch('/partials/http-host-popover');
    if (!response.ok) throw new Error('Sign in to publish. Self-hosted installations need accounts or a static token.');
    const html = await response.text();
    if (state !== httpHostState) return;
    httpHostEl.innerHTML = html;
    httpHostEl.querySelector('[data-http-host-name]').textContent = fn.name || fn.id;
    ensurePopoverClose(httpHostEl, closeHttpHostPopover, 'Close HTTP publication', {prepend: true});
    httpHostEl.querySelector('[data-http-host-publish]').onclick = () => publishHttpHost(state);
    httpHostEl.querySelector('[data-http-host-stop]').onclick = () => stopHttpHost(state);
    const preview = httpHostEl.querySelector('[data-handler-preview]');
    if (preview) preview.hidden = !gdHandlerPreviewAvailable();
    const previewButton = httpHostEl.querySelector('[data-handler-preview-mint]');
    if (previewButton) previewButton.onclick = () => mintHandlerPreview(state);
    httpHostEl.style.removeProperty('display');
    httpHostEl.classList.add('visible');
    anchor.setAttribute('aria-expanded', 'true');
    anchorBelowClamped(httpHostEl, anchor, {fallbackW: 320, fallbackH: 320});
    renderHttpHostState(state);
    focusIntoDialog(httpHostEl);
    const data = await httpHostPublications();
    if (state !== httpHostState) return;
    state.available = data.available === true;
    state.publication = data.publications.find(row => row['fn-id'] === fn.id) || null;
    state.message = state.available ? '' : data.reason === 'apps-domain-required'
      ? 'Configure an isolated apps domain to publish HTTP handlers on this deployment.'
      : data.reason === 'execute-scope-required'
        ? 'This API token cannot control execution. Use an execute-scoped token or sign in to manage publications.'
      : 'Sign in to publish. On a self-hosted installation, configure accounts or a static token first.';
  } catch (error) {
    if (state === httpHostState && typeof gdToast === 'function') gdToast(error.message);
    state.message = error.message;
  } finally {
    state.busy = false;
    if (httpHostEl.querySelector('[data-http-host-url]')) renderHttpHostState(state);
  }
}

installPopoverDismiss({
  getEl: () => httpHostEl,
  getAnchor: () => httpHostState?.anchor,
  isVisible: () => !!httpHostState && !!httpHostEl?.classList.contains('visible'),
  onDismiss: hideHttpHostPopover,
  trapFocus: true,
  getReturnFocus: httpHostReturnFocus,
});

window.addEventListener?.('gd-auth-changed', hideHttpHostPopover);
window.showHttpHostPopover = showHttpHostPopover;
window.hideHttpHostPopover = hideHttpHostPopover;
window.gdTemporaryHttpAvailable = () => window.gdHttpHostAvailable === true;

window.gdHandlerPreviewAvailable = gdHandlerPreviewAvailable;
