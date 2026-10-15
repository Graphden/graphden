'use strict';

// VM editor fixtures need the browser window's event-target contract.
function installWindowEvents(host) {
  const listeners = new Map();
  host.addEventListener = (type, listener) => {
    if (!listeners.has(type)) listeners.set(type, new Set());
    listeners.get(type).add(listener);
  };
  host.removeEventListener = (type, listener) => listeners.get(type)?.delete(listener);
  host.dispatchEvent = event => {
    for (const listener of listeners.get(event.type) || []) listener(event);
    return true;
  };
  return host;
}

module.exports = {installWindowEvents};
