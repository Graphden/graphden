(ns graphden.accounts.pages
  "BUILT-IN (fallback) HTML for the accounts module's own pages — `/login`,
   `/account`, `/reset`. The PRIMARY render path is the `app.auth-pages`
   GRAPH fn-defs (round-2 decomposition audit): when `:accounts/routes-install`
   is wired with the platform `:ctx`, the router renders the pages from the
   graph so a deployment re-themes them in the editor. THIS namespace serves
   when no ctx is wired or the graph cannot render — the login page must
   survive a graph outage — so it must stay a working, dependency-free shell.
   Behavioural parity with the graph copies is pinned byte-for-byte by
   `graphden.packages.app.auth-pages-test`; edit BOTH sides together —
   but only the HTML structure lives twice now. The CSS and the page
   scripts are read from the same classpath resources the graph copies
   use (see `asset` below), so restyling happens in one place.

   The pages are plain HTML + inline CSS/JS that call the JSON `/auth/*`
   endpoints. No external assets (the only remote script is the optional
   Telegram login widget)."
  (:require
    [clojure.java.io :as io]))


(defn- asset
  "One of the auth surface's shipped assets, read off the CLASSPATH — the
   same file the `app.auth-pages` graph copies pull through
   `:read-resource`. These used to be string literals here, char-for-char
   duplicates of the resource, kept in step only by
   `packages.app.auth-pages-test`; reading the one file makes that half of
   the parity structural. Still dependency-free: a jar resource is there
   whether or not the graph can render."
  [path]
  (or (some-> (io/resource path) slurp) ""))


(def ^:private brand-css (delay (asset "packages/app/auth-pages/auth.css")))
(def ^:private login-js (delay (asset "packages/app/auth-pages/login.js")))
(def ^:private reset-js (delay (asset "packages/app/auth-pages/reset.js")))


(def ^:private lambda-svg
  "The Graphden graph-λ mark — the SAME mark as the editor rail brand (two
   strokes forming λ + three node discs), teal via `.brand svg{color}` +
   currentColor. Replaces the old serif-λ-on-blue-disc so the auth surface
   reads as the same product as the editor."
  (str "<svg aria-hidden='true' viewBox='0 0 32 32' fill='none' xmlns='http://www.w3.org/2000/svg'>"
       "<g stroke='currentColor' stroke-width='3' stroke-linecap='round'>"
       "<line x1='9' y1='6' x2='25' y2='26'/><line x1='17' y1='16' x2='8' y2='26'/></g>"
       "<g fill='currentColor'>"
       "<circle cx='9' cy='6' r='3'/><circle cx='25' cy='26' r='3'/><circle cx='8' cy='26' r='3'/></g>"
       "</svg>"))


(defn- page
  [title body]
  (str "<!doctype html><html lang='en'><head><meta charset='utf-8'>"
       "<meta name='viewport' content='width=device-width,initial-scale=1'>"
       "<title>" title " — Graphden</title><style>" @brand-css "</style></head>"
       "<body><main class='wrap'>"
       "<div class='brand'>" lambda-svg "<b>Graphden</b></div>"
       body
       "</main></body></html>"))


;; Provider marks so GitHub/Google read as branded buttons alongside the
;; Telegram login widget (an official iframe we can't restyle) — otherwise the
;; row was two plain text buttons next to one branded blue one.
(def ^:private github-mark
  (str "<svg viewBox='0 0 24 24' fill='currentColor' aria-hidden='true'><path d='M12 .5C5.7.5.5"
       " 5.7.5 12c0 5.1 3.3 9.4 7.9 10.9.6.1.8-.3.8-.6v-2c-3.2.7-3.9-1.5-3.9-1.5-.5-1.3-1.3-1.7-1.3-1.7-1.1-.7.1-.7.1-.7"
       " 1.2.1 1.8 1.2 1.8 1.2 1 1.8 2.7 1.3 3.4 1 .1-.8.4-1.3.7-1.6-2.6-.3-5.3-1.3-5.3-5.8"
       " 0-1.3.5-2.3 1.2-3.1-.1-.3-.5-1.5.1-3.1 0 0 1-.3 3.3 1.2a11.5 11.5 0 0 1 6 0C17 4.7 18 5 18 5c.6 1.6.2"
       " 2.8.1 3.1.8.8 1.2 1.8 1.2 3.1 0 4.5-2.7 5.5-5.3 5.8.4.4.8 1.1.8 2.2v3.3c0 .3.2.7.8.6 4.6-1.5 7.9-5.8"
       " 7.9-10.9C23.5 5.7 18.3.5 12 .5z'/></svg>"))


(def ^:private telegram-mark
  (str "<svg viewBox='0 0 24 24' fill='currentColor' aria-hidden='true'><path d='M23.1 3.8"
       " 19.6 20c-.26 1.16-.95 1.44-1.92.9l-5.32-3.92-2.57 2.47c-.28.28-.52.52-1.07.52l.38-5.42"
       " 9.85-8.9c.43-.38-.09-.6-.67-.22L6.03 13.1 1.4 11.65c-1.15-.36-1.17-1.15.24-1.7L21.6"
       " 2.2c.95-.35 1.79.22 1.5 1.6z'/></svg>"))


(def ^:private google-mark
  (str "<svg viewBox='0 0 48 48' aria-hidden='true'>"
       "<path fill='#4285F4' d='M45.1 24.5c0-1.6-.1-3.1-.4-4.5H24v8.5h11.8c-.5 2.7-2 5-4.4 6.6v5.5h7.1c4.1-3.8 6.6-9.4 6.6-16.1z'/>"
       "<path fill='#34A853' d='M24 46c5.9 0 10.9-2 14.5-5.4l-7.1-5.5c-2 1.3-4.5 2.1-7.4 2.1-5.7 0-10.5-3.8-12.2-9h-7.3v5.7C6.1 41.1 14.4 46 24 46z'/>"
       "<path fill='#FBBC05' d='M11.8 28.2c-.4-1.3-.7-2.7-.7-4.2s.2-2.9.7-4.2v-5.7H4.5C3 17.1 2 20.4 2 24s1 6.9 2.5 9.9l7.3-5.7z'/>"
       "<path fill='#EA4335' d='M24 10.7c3.2 0 6.1 1.1 8.4 3.3l6.3-6.3C34.9 4.1 29.9 2 24 2 14.4 2 6.1 6.9 4.5 14.1l7.3 5.7c1.7-5.2 6.5-9.1 12.2-9.1z'/></svg>"))


(defn- social-buttons
  [providers telegram]
  (str
    (when (seq providers)
      (str "<div class='divider'>or</div><div class='social'>"
           (when (contains? providers "github")
             (str "<a class='btn-social' href='/auth/github/start'>" github-mark "Continue with GitHub</a>"))
           (when (contains? providers "google")
             (str "<a class='btn-social' href='/auth/google/start'>" google-mark "Continue with Google</a>"))
           "</div>"))
    ;; Telegram: instead of the official widget (an iframe with its own fixed
    ;; blue button we can't restyle), load telegram-widget.js only for its
    ;; `Telegram.Login.auth` fn and render OUR OWN `.btn-social` button so all
    ;; three providers share one design. The auth callback returns the SAME
    ;; signed fields the widget's redirect would; we forward them to
    ;; `/auth/telegram/callback`. First we `GET /auth/telegram/start` to plant
    ;; the HttpOnly `gd_link_intent` nonce (same-origin fetch honors Set-Cookie)
    ;; and echo it back as the callback's `state` — the per-request intent proof
    ;; the server now requires before it will LINK the identity to a signed-in
    ;; account (an attacker's captured payload can't forge this cookie).
    ;; `Telegram.Login.auth` needs the NUMERIC bot id — the token's prefix.
    (when-let [token (:bot-token telegram)]
      (let [ci (String/.indexOf token ":")
            bot-id (if (pos? ci) (subs token 0 ci) token)]
        (str "<script async src='https://telegram.org/js/telegram-widget.js?22'></script>"
             "<div class='social' style='margin-top:10px'>"
             "<button type='button' class='btn-social' id='tg-login'>" telegram-mark
             "Continue with Telegram</button></div>"
             "<script>document.getElementById('tg-login').addEventListener('click',function(){"
             "if(!window.Telegram||!window.Telegram.Login){return;}"
             "fetch('/auth/telegram/start').then(function(r){return r.json();}).then(function(s){"
             "window.Telegram.Login.auth({bot_id:'" bot-id "',request_access:'write'},function(u){"
             "if(!u){return;}var q=Object.keys(u).map(function(k){"
             "return encodeURIComponent(k)+'='+encodeURIComponent(u[k]);}).join('&');"
             "window.location='/auth/telegram/callback?'+q+'&state='+encodeURIComponent(s.state);});});});</script>")))))


(defn login-page
  "The /login + /signup page. `enabled-providers` is a set like #{\"github\"};
   `telegram` is `{:bot-username …}` or nil."
  [enabled-providers telegram]
  (page
    "Sign in"
    (str
      "<div class='card'>"
      "<div class='tabs'><button id='t-in' class='on' onclick='mode(false)'>Sign in</button>"
      "<button id='t-up' onclick='mode(true)'>Create account</button></div>"
      "<h1 id='hd'>Welcome back</h1><p class='sub' id='sb'>Sign in to your Graphden account.</p>"
      "<form id='f' onsubmit='return submitForm(event)'>"
      "<label for='email'>Email</label><input type='email' id='email' required autocomplete='email'>"
      "<label for='password'>Password</label><input type='password' id='password' required autocomplete='current-password'>"
      "<div id='totp-wrap' style='display:none'><label for='code'>Authentication code</label>"
      "<input type='text' id='code' inputmode='numeric' autocomplete='one-time-code' placeholder='123456'></div>"
      "<button class='btn-primary' id='go' type='submit'>Sign in</button>"
      "<div class='msg' id='msg' role='alert'></div>"
      "<p style='margin:10px 0 0;font-size:0.8125rem;text-align:center'>"
      "<button type='button' class='linklike' id='forgot' onclick='forgot()'>Forgot password?</button></p></form>"
      (social-buttons enabled-providers telegram)
      "</div>"
      "<script>" @login-js "</script>")))


(defn account-page
  "The /account settings page. All data is fetched client-side from the JSON
   endpoints against the caller's session cookie."
  [enabled-providers]
  (page
    "Account"
    (str
      "<div class='card'>"
      "<h1>Your account</h1><p class='sub' id='who'>&nbsp;</p>"
      "<div id='verify-banner'></div>"
      "<div class='sec'><h2>Sign-in methods</h2><div id='idents'>Loading…</div>"
      "<div class='social' style='margin-top:12px'>"
      (when (contains? enabled-providers "github") (str "<a class='btn-social' href='/auth/github/start'>" github-mark "Link GitHub</a>"))
      (when (contains? enabled-providers "google") (str "<a class='btn-social' href='/auth/google/start'>" google-mark "Link Google</a>"))
      "</div></div>"
      "<div class='sec'><h2>Two-factor authentication</h2><div id='tfa'>Loading…</div></div>"
      ;; API-tokens section — hidden until /api/my-tokens/list answers 200
      ;; (the routes exist only where the tenancy addon is active).
      "<div class='sec' id='tok-sec' style='display:none'><h2>API tokens</h2>"
      "<p class='sub' style='margin:0 0 6px'>Long-lived keys for MCP and API clients, sent as <code>Authorization: Bearer</code>. A token can only narrow your access: pick its scopes and lifetime.</p>"
      "<div id='tok-reveal'></div><div id='toks'>Loading…</div>"
      "<div class='scopes' id='tok-scopes'>"
      "<label><input type='checkbox' value='write' checked> Edit graph &amp; branches</label>"
      "<label><input type='checkbox' value='execute' checked> Execute functions</label>"
      "<label><input type='checkbox' value='merge'> Merge branches</label>"
      "<label><input type='checkbox' value='services'> Manage services</label>"
      "<label><input type='checkbox' value='secrets'> Write secrets</label>"
      "<label><input type='checkbox' value='packages'> Publish packages</label></div>"
      "<label style='margin:10px 0 6px'>Expires</label>"
      "<select id='tok-ttl'><option value='7'>7 days</option><option value='30'>30 days</option><option value='90' selected>90 days</option><option value='365'>1 year</option><option value=''>Never</option></select>"
      "<div style='display:flex;gap:8px;margin-top:12px'>"
      "<input type='text' id='tok-label' placeholder='Label (e.g. laptop MCP)'>"
      "<button class='btn-primary' style='width:auto;margin-top:0;white-space:nowrap' onclick='mintToken()'>Create</button></div></div>"
      "<button class='btn-primary' style='background:transparent;border:1px solid var(--line);color:var(--muted)' onclick='logout()'>Sign out</button>"
      "<div class='msg' id='msg'></div></div>"
      "<script>"
      "function say(t,ok){let m=document.getElementById('msg');m.textContent=t;m.className='msg '+(ok?'ok':'err');}"
      "async function get(u){let r=await fetch(u);return[r.status,await r.json().catch(()=>({}))];}"
      "async function post(u,b){let r=await fetch(u,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b||{})});return[r.status,await r.json().catch(()=>({}))];}"
      "async function loadIdents(){let[s,j]=await get('/auth/identities');"
      "if(s!==200){location.href='/login';return;}"
      "let n=j.identities.length;"
      "document.getElementById('idents').innerHTML=j.identities.map(i=>"
      "`<div class='row'><div><div class='prov'>${i.provider}</div><div class='em'>${esc(i.email||'')}${i.provider==='password'&&!i['email-verified?']?\" · unverified\":''}</div></div>`+"
      "(n>1?`<button class='btn-ghost' onclick=\"unlink('${i.provider}')\">Unlink</button>`:'')+`</div>`).join('');}"
      "async function unlink(p){let[s,j]=await post('/auth/unlink',{provider:p});if(s===200){loadIdents();say('Unlinked '+p,true);}else{say('Could not unlink.');}}"
      "async function enroll(){let[s,j]=await post('/auth/totp/enroll');if(s!==200){say('Could not start enrollment.');return;}"
      "document.getElementById('tfa').innerHTML=`<p class='sub'>Add this key to your authenticator app (or scan the URI), then enter the code.</p>`+"
      "`<code class='secret'>${j.secret}</code>`+"
      "`<input type='text' id='ecode' inputmode='numeric' placeholder='123456'>`+"
      "`<button class='btn-primary' onclick='confirmTotp()'>Confirm &amp; enable</button>`;}"
      "async function confirmTotp(){let[s,j]=await post('/auth/totp/confirm',{code:document.getElementById('ecode').value});"
      "if(s===200){say('Two-factor enabled.',true);loadTfaState();}else{say('That code did not match.');}}"
      "async function disableTotp(){let c=prompt('Enter a current authenticator code to disable 2FA:');if(!c)return;"
      "let[s,j]=await post('/auth/totp/disable',{code:c});if(s===200){say('Two-factor disabled.',true);loadTfaState();}else{say('That code did not match.');}}"
      "async function loadTfaState(){let[s,j]=await get('/auth/tfa-state');"
      "document.getElementById('tfa').innerHTML=(j&&j.enabled)?"
      "`<p class='sub'>Two-factor is <b>on</b>.</p><button class='btn-ghost' onclick='disableTotp()'>Disable 2FA</button>`:"
      "`<p class='sub'>Add a second factor with an authenticator app.</p><button class='btn-primary' onclick='enroll()'>Enable 2FA</button>`;}"
      "async function whoami(){let[s,j]=await get('/auth/me');if(s!==200||!j.account){location.href='/login';return;}"
      "document.getElementById('who').textContent=j.account.email||'(email not verified yet)';"
      ;; primary-email is null until a verification link is clicked — offer a resend.
      "if(!j.account.email){document.getElementById('verify-banner').innerHTML="
      "`<div class='sec' style='border-color:var(--accent-hi)'><h2>Verify your email</h2>`+"
      "`<p class='sub'>Check your inbox for the link. Didn't get it?</p>`+"
      "`<button class='btn-primary' onclick='resendVerify()'>Resend verification email</button></div>`;}}"
      "async function resendVerify(){await post('/auth/resend-verification');say('If your email is unverified, a new link is on its way.',true);}"
      "async function logout(){await post('/auth/logout');location.href='/login';}"
      ;; API-tokens panel: probe /api/my-tokens/list; 200+array → reveal the
      ;; section. Labels/ids go through esc() before landing in innerHTML.
      ;; mint/revoke POST form-urlencoded — the graph routes parse the body
      ;; with :parse-form-body-kw, not JSON.
      "function esc(s){return String(s==null?'':s).replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]));}"
      "async function postForm(u,b){let r=await fetch(u,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(b).toString()});return[r.status,await r.json().catch(()=>({}))];}"
      "async function loadTokens(){let[s,j]=await get('/api/my-tokens/list');if(s!==200||!Array.isArray(j)){return;}"
      "document.getElementById('tok-sec').style.display='';"
      "document.getElementById('toks').innerHTML=j.length?j.map(t=>"
      "`<div class='row'><div><div class='prov' style='text-transform:none'>${esc(t.label)||'(unlabeled)'}</div><div class='em'>${esc(t.scopes||'unscoped')} · ${t['expires-at']?'expires '+new Date(t['expires-at']).toISOString().slice(0,10):'no expiry'}</div></div><button class='btn-ghost' onclick=\"revokeToken('${esc(t.id)}')\">Revoke</button></div>`"
      ").join(''):`<p class='sub' style='margin:0'>No API tokens yet.</p>`;}"
      "async function mintToken(){let l=document.getElementById('tok-label').value.trim();"
      "let sc=[...document.querySelectorAll('#tok-scopes input:checked')].map(c=>c.value).join(' ');"
      "if(!sc){say('Pick at least one scope.');return;}"
      "let[s,j]=await postForm('/api/my-tokens',{label:l,scopes:sc,'ttl-days':document.getElementById('tok-ttl').value});"
      "if(s!==200||!j.token){say('Could not create a token.');return;}"
      "document.getElementById('tok-label').value='';"
      "document.getElementById('tok-reveal').innerHTML=`<p class='sub' style='margin:10px 0 6px'>Copy your new token now — it will not be shown again:</p><code class='secret'>${esc(j.token)}</code><button class='btn-ghost' onclick='copyToken(this)'>Copy</button>`;"
      "loadTokens();say('Token created.',true);}"
      "function copyToken(b){let c=document.querySelector('#tok-reveal code');if(c){navigator.clipboard.writeText(c.textContent).then(()=>{b.textContent='Copied';});}}"
      "async function revokeToken(id){if(!confirm('Revoke this token? Anything still using it will stop working.'))return;"
      "let[s,j]=await postForm('/api/my-tokens/revoke',{id});"
      "if(s===200){document.getElementById('tok-reveal').innerHTML='';loadTokens();say('Token revoked.',true);}else{say('Could not revoke that token.');}}"
      "whoami();loadIdents();loadTfaState();loadTokens();"
      "</script>")))


(defn reset-page
  "The /reset?token=… page the emailed link opens: one new-password field,
   POSTs /auth/reset with the token from the query string."
  []
  (page
    "Reset password"
    (str
      "<div class='card'>"
      "<h1>Choose a new password</h1>"
      "<p class='sub'>After resetting you'll be signed out everywhere.</p>"
      "<form onsubmit='return doReset(event)'>"
      "<label for='password'>New password</label>"
      "<input type='password' id='password' required minlength='8' autocomplete='new-password'>"
      "<button class='btn-primary' type='submit'>Set password</button>"
      "<div class='msg' id='msg' role='alert'></div></form></div>"
      "<script>" @reset-js "</script>")))
