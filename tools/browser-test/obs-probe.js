// Replicate the packages-panel install→update flow, with a MutationObserver on
// the panel root logging every swap, and htmx event logging, so we see whether
// a re-render detaches the update button around the click.
const {chromium} = require('playwright');
const {newContext, nodeApiJson} = require('./edit-test-helpers');
const BASE = process.env.GRAPHDEN_URL;
const RUN = process.pid.toString(36) + Date.now().toString(36);
const PKG = 'obs-' + RUN, BRANCH = 'obsb-' + RUN, BH = {'X-Graphden-Branch': BRANCH};
(async () => {
  // publish two versions
  await nodeApiJson('POST','/api/branches',{name:BRANCH});
  await nodeApiJson('POST','/api/packages/publish',{name:PKG,version:'1.0.0','ns-root':'app.contact-demo'},BH);
  await nodeApiJson('POST','/api/packages/publish',{name:PKG,version:'1.1.0','ns-root':'app.contact-demo'},BH);
  const {browser,page} = await newContext(chromium);
  page.on('console',(m)=>{const t=m.text();if(t.startsWith('EVT '))console.log('  '+t);});
  await page.goto(BASE+'/?branch='+encodeURIComponent(BRANCH));
  await page.evaluate(()=>document.body.classList.remove('sidebar-collapsed'));
  await page.waitForSelector('.sidebar-packages',{timeout:15000});
  // arm observers
  await page.evaluate(()=>{
    const stamp=(s)=>console.log('EVT '+(Date.now()%100000)+' '+s);
    const root=document.querySelector('.sidebar-packages');
    new MutationObserver((muts)=>{for(const m of muts){for(const n of m.addedNodes){if(n.querySelector&&n.querySelector('[data-packages-panel]')||n.matches&&n.matches('[data-packages-panel]'))stamp('PANEL-SWAP');}}}).observe(root,{childList:true,subtree:true});
    document.body.addEventListener('htmx:beforeRequest',(e)=>stamp('htmx:beforeRequest '+(e.detail.requestConfig&&e.detail.requestConfig.path||e.detail.pathInfo&&e.detail.pathInfo.requestPath||'?')));
    document.body.addEventListener('htmx:afterSwap',(e)=>stamp('htmx:afterSwap'));
  });
  // open browse, install 1.0.0
  await page.evaluate(()=>{const d=document.querySelector('.sidebar-packages details.packages-available');if(d)d.open=true;});
  await page.waitForFunction((pkg)=>[...document.querySelectorAll('.sidebar-packages .packages-install-btn')].some(b=>(b.getAttribute('hx-post')||'').includes('version=1.0.0')&&(b.getAttribute('hx-post')||'').includes(pkg)),PKG,{timeout:15000,polling:100});
  await page.evaluate((pkg)=>{const b=[...document.querySelectorAll('.sidebar-packages .packages-install-btn')].find(x=>{const p=x.getAttribute('hx-post')||'';return p.includes('name='+pkg)&&p.includes('version=1.0.0');});b&&b.click();},PKG);
  await page.waitForFunction((pkg)=>{const t=document.querySelector('.sidebar-packages [data-packages-panel] > .packages-panel-table');return t&&[...t.querySelectorAll('tbody tr td:first-child')].some(td=>td.textContent===pkg);},PKG,{timeout:60000,polling:250});
  await page.evaluate(()=>console.log('EVT '+(Date.now()%100000)+' --- about to click UPDATE ---'));
  await page.evaluate((pkg)=>{const root=document.querySelector('.sidebar-packages [data-packages-panel]');const tr=[...root.querySelectorAll(':scope > .packages-panel-table tbody tr')].find(r=>r.querySelector('td')?.textContent===pkg);tr.querySelector('.packages-version-input').value='1.1.0';tr.querySelector('.packages-update-btn').click();},PKG);
  await page.evaluate(()=>console.log('EVT '+(Date.now()%100000)+' --- clicked UPDATE ---'));
  let ok=false;
  try{await page.waitForFunction((pkg)=>{const t=document.querySelector('.sidebar-packages [data-packages-panel] > .packages-panel-table');return t&&[...t.querySelectorAll('tbody tr')].some(tr=>{const td=[...tr.querySelectorAll('td')];return td[0]?.textContent===pkg&&td[1]?.textContent==='1.1.0';});},PKG,{timeout:60000,polling:250});ok=true;}catch(e){}
  console.log('RESULT: '+(ok?'PASS':'FLAKE'));
  await browser.close();
  await nodeApiJson('DELETE','/api/branches/'+encodeURIComponent(BRANCH)).catch(()=>{});
})().catch(e=>{console.error('probe error:',e.message);process.exit(2);});
