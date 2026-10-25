// Personal graph reads wait only for explicitly classified policy changes.
// Their owners bound attempts and discard replies after selection changes.
window.gdWaitForGraphPolicy = signal => {
  if (signal?.aborted) return Promise.reject(new DOMException('Selection changed', 'AbortError'));
  return new Promise((resolve, reject) => {
    const finish = aborted => {
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
      if (aborted) reject(new DOMException('Selection changed', 'AbortError'));
      else resolve();
    };
    const abort = () => finish(true);
    const timer = setTimeout(() => finish(false), 1000);
    signal?.addEventListener('abort', abort, {once: true});
  });
};

// Read-only graph plans share one bounded transport/policy retry. A lost
// response to a mutation must never be retried here: its commit is unknown.
window.gdReadGraphResponse = async (request, signal, current, retryResponse, maxResponseRetries = Number.POSITIVE_INFINITY) => {
  const deadline = performance.now() + 30000;
  let networkRetries = 0;
  let responseRetries = 0;
  const assertCurrent = () => {
    if (signal?.aborted || !current()) throw new DOMException('Graph read changed', 'AbortError');
  };
  while (true) {
    assertCurrent();
    let response;
    try {
      response = await request();
    } catch (error) {
      assertCurrent();
      if (error?.name !== 'TypeError' || networkRetries >= 3 || performance.now() >= deadline) throw error;
      networkRetries++;
      await window.gdWaitForGraphPolicy(signal);
      continue;
    }
    const result = await response.json();
    assertCurrent();
    if (retryResponse?.(response, result) && responseRetries < maxResponseRetries
      && performance.now() < deadline) {
      responseRetries++;
      await window.gdWaitForGraphPolicy(signal);
      assertCurrent();
      if (performance.now() < deadline) continue;
    }
    return {response, result};
  }
};
