// Pure graph transitions become host effects only after complete validation.
(() => {
  const api = window.GraphdenBrowser;
  const field = (value, key) => value instanceof Map ? value.get(api.keyword(key)) : undefined;
  function create(runtime, {inputs, validateState, validateView, requests, render = null, onError = error => { throw error; }, rendererOptions = {}}) {
    let evaluator = runtime;
    let state;
    let view;
    let disposed = false;
    let draining = false;
    let generation = 0;
    let cancellation = new AbortController();
    let paint = render;
    const queue = [];
    function checkedView(candidate, next, supplied) {
      validateState(next);
      const output = candidate.run('view', {state: next, inputs: supplied});
      validateView(output);
      window.GraphdenRenderer.vnode(field(output, 'tree'), rendererOptions.idPrefix, rendererOptions.nodeLimit);
      window.GraphdenStyles.normalize(field(output, 'styles'));
      return output;
    }
    function checkedRequests(value) {
      if (!Array.isArray(value) && !api.isSequence(value)) throw new Error('Graph requests must be a sequence');
      const result = [];
      for (const request of value) {
        if (result.length >= 16 || !(request instanceof Map)) throw new Error('Invalid graph request list');
        const kind = field(request, 'kind');
        const handler = Object.hasOwn(requests, kind) && requests[kind];
        if (!handler) throw new Error('Unsupported graph request');
        result.push({handler, parameters: handler.validate(request)});
      }
      return result;
    }
    function cancel() {
      generation++;
      cancellation.abort();
      cancellation = new AbortController();
      queue.length = 0;
    }
    function effect({handler, parameters}) {
      const own = generation;
      const signal = cancellation.signal;
      const result = handler.execute(parameters, {signal});
      if (result && typeof result.then === 'function') {
        Promise.resolve(result).then(event => {
          if (!disposed && own === generation && event != null) controller.dispatch(event);
        }).catch(error => { if (!disposed && own === generation) onError(error); });
      } else if (result != null && !disposed && own === generation) controller.dispatch(result);
    }
    const supplied = inputs();
    state = evaluator.run('initial', {inputs: supplied});
    view = checkedView(evaluator, state, supplied);
    if (paint) paint(view);
    const controller = {
      dispatch(event) {
        if (disposed) return;
        queue.push(event);
        if (draining) return;
        draining = true;
        try {
          while (queue.length && !disposed) {
            const nextEvent = queue.shift();
            const supplied = inputs();
            const update = evaluator.run('update', {state, event: nextEvent, inputs: supplied});
            if (!(update instanceof Map) || update.size !== 2) throw new Error('Invalid graph transition');
            const next = field(update, 'state');
            const output = checkedView(evaluator, next, supplied);
            const effects = checkedRequests(field(update, 'requests'));
            if (paint) paint(output);
            state = next;
            view = output;
            for (const request of effects) {
              if (disposed) break;
              effect(request);
            }
          }
        } catch (error) { queue.length = 0; throw error; }
        finally { draining = false; }
      },
      refresh() {
        if (disposed) return;
        const output = checkedView(evaluator, state, inputs());
        if (paint) paint(output);
        view = output;
      },
      setRender(next) {
        if (disposed) throw new Error('Graph component is disposed');
        if (next) next(view);
        paint = next;
      },
      replaceRuntime(candidate) {
        if (disposed || draining) throw new Error('Graph component cannot replace runtime');
        const output = checkedView(candidate, state, inputs());
        if (paint) paint(output);
        cancel();
        evaluator = candidate;
        view = output;
      },
      getState() { return state; },
      dispose() {
        if (disposed) return;
        disposed = true;
        cancel();
        paint = null;
      },
    };
    return controller;
  }
  window.GraphdenComponent = {create};
})();
