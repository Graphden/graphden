'use strict';

// Self-contained so the same strict decoder can run in Node observers and in
// the browser init script. Never turn an envelope's HTTP 200 into item success.
function createComponentPlanDecoder() {
  function componentsForRequest(request) {
    const names = request?.components === undefined ? [request?.component] : request.components;
    if (!Array.isArray(names) || names.length < 1 || names.length > 8
      || names.some(name => typeof name !== 'string' || !name)
      || new Set(names).size !== names.length
      || request.components !== undefined && request.component !== undefined)
      throw Error('Invalid observed component plan request');
    return names;
  }
  function decode(request, status, body) {
    const names = componentsForRequest(request);
    if (request.components === undefined || status !== 200)
      return names.map(component => ({component, status, body}));
    if (body?.ok !== true || !Array.isArray(body.items) || body.items.length !== names.length)
      throw Error('Invalid observed component batch response');
    const items = new Map();
    for (const item of body.items) {
      if (!names.includes(item?.component) || items.has(item.component)
        || !Number.isInteger(item.result?.status) || item.result.status < 200 || item.result.status > 599
        || typeof item.result.body !== 'string')
        throw Error('Invalid observed component batch item');
      let parsed;
      try { parsed = JSON.parse(item.result.body); }
      catch (_) { throw Error('Invalid observed component batch JSON'); }
      items.set(item.component, {component: item.component, status: item.result.status, body: parsed});
    }
    return names.map(component => items.get(component));
  }
  return {componentsForRequest, decode};
}

module.exports = {createComponentPlanDecoder, ...createComponentPlanDecoder()};
