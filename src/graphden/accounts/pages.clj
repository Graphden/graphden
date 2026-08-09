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
  .btn-social{display:flex;align-items:center;justify-content:center;gap:8px;padding:10px;
    background:var(--ink);border:1px solid var(--line);color:var(--text);text-decoration:none}
  .btn-social:hover{border-color:var(--blue-hi)}
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


(defn- social-buttons
  [providers telegram]
  (str
    (when (seq providers)
      (str "<div class='divider'>or</div><div class='social'>"
           (when (contains? providers "github")
             "<a class='btn-social' href='/auth/github/start'>Continue with GitHub</a>")
           (when (contains? providers "google")
             "<a class='btn-social' href='/auth/google/start'>Continue with Google</a>")
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
      (when (contains? enabled-providers "github") "<a class='btn-social' href='/auth/github/start'>Link GitHub</a>")
      (when (contains? enabled-providers "google") "<a class='btn-social' href='/auth/google/start'>Link Google</a>")
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
