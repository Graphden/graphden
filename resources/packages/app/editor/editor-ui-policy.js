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
