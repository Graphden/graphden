// Explicit server requests are opaque graph values until an owned async host
// invokes them. Generation and scope checks cover both network and body parsing.
((host) => {
  'use strict';
  const requests = new WeakSet();
  function call(descriptor, input) {
    if (!descriptor || Object.keys(descriptor).length !== 1 || typeof descriptor.token !== 'string') throw new Error('Invalid server descriptor');
    const request = Object.freeze({descriptor, input});
    requests.add(request);
    return request;
  }
  function createLifetime({transport, scope, onState = () => {}}) {
    if (typeof transport !== 'function' || typeof scope !== 'function') throw new Error('Server lifetime requires transport and scope');
    let generation = 0;
    let pending = null;
    let disposed = false;
    function retire() {
      generation++;
      pending?.abort();
      pending = null;
    }
    async function invoke(query, input) {
      if (disposed) throw new Error('Server lifetime is disposed');
      retire();
      const own = generation;
      const stamp = scope();
      const controller = new AbortController();
      pending = controller;
      const current = () => !disposed && own === generation && stamp === scope() && !controller.signal.aborted;
      onState({phase: 'loading', value: null});
      try {
        const request = host.GraphdenBrowser.invoke(query, input);
        if (!requests.has(request)) throw new Error('Expected an explicit server call');
        const response = await transport({descriptor: request.descriptor,
          input: host.GraphdenBrowser.encode(request.input)}, {signal: controller.signal, cache: 'no-store'});
        if (!current()) return;
        const result = await response.json();
        if (!current()) return;
        if (!response.ok || result.ok !== true || Object.keys(result).some(key => !['ok', 'value'].includes(key))) throw new Error('Server call is unavailable');
        const value = host.GraphdenBrowser.decode(result.value);
        if (!current()) return;
        onState({phase: 'ready', value});
        return value;
      } catch (error) {
        if (!current() || error.name === 'AbortError') return;
        onState({phase: 'error', value: null});
      } finally {
        if (pending === controller) pending = null;
      }
    }
    return {invoke, cancel() {
      const waiting = pending !== null;
      retire();
      if (waiting && !disposed) onState({phase: 'cancelled', value: null});
    }, dispose() { disposed = true; retire(); }};
  }
  const api = Object.freeze({call, createLifetime});
  host.GraphdenServer = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window === 'undefined' ? globalThis : window);
