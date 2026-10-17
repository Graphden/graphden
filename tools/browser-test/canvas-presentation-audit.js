'use strict';
const fs = require('node:fs');

// Export the actual retained widget and its existing stylesheet scopes for an
// out-of-process accessibility audit. No application state, source model,
// request headers or scripts enter this standalone review artifact.
async function savePresentationAudit(page, selector, filename, title) {
  const data = await page.evaluate(({selector, title}) => {
    const widget = document.querySelector(selector);
    if (!widget) throw Error('Presentation audit widget missing');
    const escape = value => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;');
    const styles = [...document.querySelectorAll('link[rel="stylesheet"]')]
      .map(link => '<link rel="stylesheet" href="' + escape(link.href) + '">').join('')
      + [...document.head.querySelectorAll('style')].map(style => style.outerHTML).join('');
    const clone = widget.cloneNode(true);
    clone.querySelectorAll('script').forEach(script => script.remove());
    return '<!doctype html><html lang="en"><head><meta charset="utf-8"><title>' + escape(title)
      + '</title>' + styles + '</head><body class="' + escape(document.body.className)
      + '"><main><h1>' + escape(title) + '</h1>' + clone.outerHTML + '</main></body></html>';
  }, {selector, title});
  fs.writeFileSync(filename, data, {mode: 0o600});
}
module.exports = {savePresentationAudit};
