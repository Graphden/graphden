(ns graphden.accounts.pages
  "Self-contained HTML for the accounts module's own pages — `/login` and
   `/account` — served straight from the accounts router. Keeping the auth UI in
   the module (rather than wiring it into the editor shell) is what makes
   accounts a drop-in opt-in: enable the addon and a self-hosted instance gets a
   working sign-in + account-management surface, brand-matched (ink ground,
   Graphden blue, the λ mark), with no editor changes.

   The pages are plain HTML + inline CSS/JS that call the JSON `/auth/*`
   endpoints. No external assets (the only remote script is the optional
   Telegram login widget).")


(def ^:private brand-css
  "
  :root{--ink:#0D1117;--panel:#161B22;--line:#2A3038;--text:#E6EDF3;
        --muted:#9AA7B4;--blue:#0066CC;--blue-hi:#4D94FF;--danger:#E5534B;--radius:10px}
  *{box-sizing:border-box}
  body{margin:0;background:var(--ink);color:var(--text);
       font:15px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
       display:flex;min-height:100vh;justify-content:center;align-items:flex-start}
  .wrap{width:100%;max-width:400px;padding:48px 20px}
  .brand{display:flex;align-items:center;gap:10px;justify-content:center;margin-bottom:28px}
  .brand svg{width:34px;height:34px}
  .brand b{font-size:20px;letter-spacing:.2px}
  .card{background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);padding:24px}
  h1{font-size:18px;margin:0 0 4px}
  p.sub{color:var(--muted);margin:0 0 20px;font-size:14px}
  label{display:block;font-size:13px;color:var(--muted);margin:14px 0 6px}
  input[type=email],input[type=password],input[type=text]{width:100%;padding:10px 12px;
    background:var(--ink);border:1px solid var(--line);border-radius:8px;color:var(--text);font-size:15px}
  input:focus{outline:none;border-color:var(--blue-hi)}
  button{cursor:pointer;font-size:15px;border-radius:8px;border:1px solid transparent}
  .btn-primary{width:100%;margin-top:20px;padding:11px;background:var(--blue);color:#fff;font-weight:600}
  .btn-primary:hover{background:#0a74e0}
  .social{display:flex;flex-direction:column;gap:10px;margin-top:16px}
  .btn-social{display:flex;align-items:center;justify-content:center;gap:10px;padding:11px;
    background:var(--ink);border:1px solid var(--line);border-radius:8px;color:var(--text);
    text-decoration:none;font-weight:500;font-size:15px}
  .btn-social:hover{border-color:var(--blue-hi)}
  .btn-social svg{width:18px;height:18px;flex:none}
  .divider{display:flex;align-items:center;gap:12px;color:var(--muted);font-size:12px;margin:20px 0}
  .divider::before,.divider::after{content:'';flex:1;height:1px;background:var(--line)}
  .tabs{display:flex;gap:4px;margin-bottom:18px;background:var(--ink);padding:4px;border-radius:8px}
  .tabs button{flex:1;padding:8px;background:transparent;color:var(--muted)}
  .tabs button.on{background:var(--panel);color:var(--text);border:1px solid var(--line)}
  .msg{margin-top:14px;font-size:13px;min-height:18px}
  .msg.err{color:var(--danger)} .msg.ok{color:var(--blue-hi)}
  .row{display:flex;justify-content:space-between;align-items:center;padding:12px 0;border-bottom:1px solid var(--line)}
  .row:last-child{border-bottom:none}
  .row .prov{font-weight:600;text-transform:capitalize}
  .row .em{color:var(--muted);font-size:13px}
  .btn-ghost{padding:6px 12px;background:transparent;border:1px solid var(--line);color:var(--muted)}
  .btn-ghost:hover{border-color:var(--danger);color:var(--danger)}
  .sec{margin-top:22px;padding-top:18px;border-top:1px solid var(--line)}
  .sec h2{font-size:14px;margin:0 0 10px}
  code.secret{display:block;background:var(--ink);border:1px solid var(--line);border-radius:8px;
    padding:10px;margin:10px 0;word-break:break-all;font-size:13px;color:var(--blue-hi)}
  a{color:var(--blue-hi)}")


(def ^:private lambda-svg
  "The Graphden λ mark on the brand-blue disc."
  (str "<svg viewBox='0 0 32 32' xmlns='http://www.w3.org/2000/svg'>"
       "<circle cx='16' cy='16' r='16' fill='#0066CC'/>"
       "<text x='16' y='22' font-size='18' text-anchor='middle' fill='#fff'"
       " font-family='Georgia,serif'>&#955;</text></svg>"))


(defn- page
  [title body]
  (str "<!doctype html><html lang='en'><head><meta charset='utf-8'>"
       "<meta name='viewport' content='width=device-width,initial-scale=1'>"
       "<title>" title " — Graphden</title><style>" brand-css "</style></head>"
       "<body><div class='wrap'>"
       "<div class='brand'>" lambda-svg "<b>Graphden</b></div>"
       body
       "</div></body></html>"))


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
    (when-let [bot (:bot-username telegram)]
      ;; Center the Telegram-injected iframe (a flex column left-aligns an
      ;; intrinsic-width iframe → the button "slid off") and pin `data-lang=en`
      ;; so the label matches the rest of the page instead of the viewer locale.
      (str "<div class='social' style='margin-top:10px;align-items:center'>"
           "<script async src='https://telegram.org/js/telegram-widget.js?22'"
           " data-telegram-login='" bot "' data-size='large' data-lang='en'"
           " data-auth-url='/auth/telegram/callback' data-request-access='write'></script>"
           "</div>"))))


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
      "<label>Email</label><input type='email' id='email' required autocomplete='email'>"
      "<label>Password</label><input type='password' id='password' required autocomplete='current-password'>"
      "<div id='totp-wrap' style='display:none'><label>Authentication code</label>"
      "<input type='text' id='code' inputmode='numeric' autocomplete='one-time-code' placeholder='123456'></div>"
      "<button class='btn-primary' id='go' type='submit'>Sign in</button>"
      "<div class='msg' id='msg'></div>"
      "<p style='margin:10px 0 0;font-size:13px;text-align:center'>"
      "<a href='#' id='forgot' onclick='return forgot()'>Forgot password?</a></p></form>"
      (social-buttons enabled-providers telegram)
      "</div>"
      "<script>"
      "let signup=false,awaitCode=false;"
      "function mode(s){signup=s;awaitCode=false;"
      "document.getElementById('t-up').classList.toggle('on',s);"
      "document.getElementById('t-in').classList.toggle('on',!s);"
      "document.getElementById('hd').textContent=s?'Create your account':'Welcome back';"
      "document.getElementById('sb').textContent=s?'Start building with Graphden.':'Sign in to your Graphden account.';"
      "document.getElementById('go').textContent=s?'Create account':'Sign in';"
      "document.getElementById('totp-wrap').style.display='none';"
      "document.getElementById('password').setAttribute('autocomplete',s?'new-password':'current-password');}"
      "function say(t,ok){let m=document.getElementById('msg');m.textContent=t;m.className='msg '+(ok?'ok':'err');}"
      "async function post(u,b){let r=await fetch(u,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});return[r.status,await r.json().catch(()=>({}))];}"
      "async function submitForm(e){e.preventDefault();"
      "let email=document.getElementById('email').value,password=document.getElementById('password').value;"
      "if(awaitCode){let[s,j]=await post('/auth/totp',{code:document.getElementById('code').value});"
      "if(s===200){location.href='/';}else{say('Invalid code, try again.');}return false;}"
      "let[s,j]=await post(signup?'/auth/signup':'/auth/login',{email,password});"
      "if(s===200&&j.totp_required){awaitCode=true;document.getElementById('totp-wrap').style.display='block';"
      "document.getElementById('go').textContent='Verify';say('Enter the 6-digit code from your authenticator.',true);return false;}"
      "if(s===200){if(signup&&j.verification_sent){say('Account created — check your email to verify. Redirecting…',true);}"
      "setTimeout(()=>location.href='/',700);return false;}"
      "say(j.error==='email-taken'?'That email is already registered.':(signup?'Could not create account.':'Invalid email or password.'));return false;}"
      "async function forgot(){let email=document.getElementById('email').value;"
      "if(!email){say('Enter your email above first.');return false;}"
      "await post('/auth/forgot',{email});say('If that address has an account, a reset link is on its way.',true);return false;}"
      "</script>")))


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
      "`<div class='row'><div><div class='prov'>${i.provider}</div><div class='em'>${i.email||''}${i.provider==='password'&&!i['email-verified?']?\" · unverified\":''}</div></div>`+"
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
      "`<div class='sec' style='border-color:var(--blue-hi)'><h2>Verify your email</h2>`+"
      "`<p class='sub'>Check your inbox for the link. Didn't get it?</p>`+"
      "`<button class='btn-primary' onclick='resendVerify()'>Resend verification email</button></div>`;}}"
      "async function resendVerify(){await post('/auth/resend-verification');say('If your email is unverified, a new link is on its way.',true);}"
      "async function logout(){await post('/auth/logout');location.href='/login';}"
      "whoami();loadIdents();loadTfaState();"
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
      "<label>New password</label>"
      "<input type='password' id='password' required minlength='8' autocomplete='new-password'>"
      "<button class='btn-primary' type='submit'>Set password</button>"
      "<div class='msg' id='msg'></div></form></div>"
      "<script>"
      "function say(t,ok){let m=document.getElementById('msg');m.textContent=t;m.className='msg '+(ok?'ok':'err');}"
      "async function doReset(e){e.preventDefault();"
      "let token=new URLSearchParams(location.search).get('token');"
      "let r=await fetch('/auth/reset',{method:'POST',headers:{'Content-Type':'application/json'},"
      "body:JSON.stringify({token,password:document.getElementById('password').value})});"
      "if(r.status===200){say('Password set — sign in with it now.',true);setTimeout(()=>location.href='/login',900);}"
      "else{say('That link is invalid or expired — request a new one from the sign-in page.');}"
      "return false;}"
      "</script>")))
