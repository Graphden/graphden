'use strict';

// Only the isolated loopback candidate uses this self-signed TLS mapping.
// Keep capsule URLs on their real HTTPS origin; never intercept app traffic.
function handlerPreviewTestOptions(env = process.env) {
  if (env.GRAPHDEN_PREVIEW_TEST_TLS !== '1') return {};
  const base = new URL(env.GRAPHDEN_URL || '');
  if (!['127.0.0.1', 'localhost'].includes(base.hostname)) {
    throw new Error('Preview test TLS requires a loopback editor');
  }
  return {
    ignoreHTTPSErrors: true,
    launchArgs: [
      '--host-resolver-rules=MAP *.gdcloud-candidate.localhost 127.0.0.1:9970',
      '--no-proxy-server',
    ],
  };
}

module.exports = {handlerPreviewTestOptions};
