let signup=false,awaitCode=false;
// Where to go after sign-in / signup: `?next=` when it is a path on THIS
// origin (an org invite's /join/<token> sends people here and wants them
// back), else the editor. Anything with a scheme or a host is ignored —
// an open redirect is how a phishing mail borrows a trusted domain.
function nextPath(){const n=new URLSearchParams(location.search).get('next');return(n&&/^\/(?!\/)/.test(n)&&!/[\\]/.test(n))?n:'/';}
function goNext(){location.href=nextPath();}
function mode(s){signup=s;awaitCode=false;document.getElementById('t-up').classList.toggle('on',s);document.getElementById('t-in').classList.toggle('on',!s);document.getElementById('hd').textContent=s?'Create your account':'Welcome back';document.getElementById('sb').textContent=s?'Start building with Graphden.':'Sign in to your Graphden account.';document.getElementById('go').textContent=s?'Create account':'Sign in';document.getElementById('totp-wrap').style.display='none';document.getElementById('password').setAttribute('autocomplete',s?'new-password':'current-password');}function say(t,ok){let m=document.getElementById('msg');m.textContent=t;m.className='msg '+(ok?'ok':'err');}async function post(u,b){let r=await fetch(u,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});return[r.status,await r.json().catch(()=>({}))];}async function submitForm(e){e.preventDefault();let email=document.getElementById('email').value,password=document.getElementById('password').value;if(awaitCode){let[s,j]=await post('/auth/totp',{code:document.getElementById('code').value});if(s===200){goNext();}else{say('Invalid code, try again.');}return false;}let[s,j]=await post(signup?'/auth/signup':'/auth/login',{email,password});if(s===200&&j.totp_required){awaitCode=true;document.getElementById('totp-wrap').style.display='block';document.getElementById('go').textContent='Verify';say('Enter the 6-digit code from your authenticator.',true);return false;}if(s===200){if(signup&&j.verification_sent){say('Account created — check your email to verify. Redirecting…',true);}setTimeout(goNext,700);return false;}say(j.error==='email-taken'?'That email is already registered.':(signup?'Could not create account.':'Invalid email or password.'));return false;}async function forgot(){let email=document.getElementById('email').value;if(!email){say('Enter your email above first.');return false;}await post('/auth/forgot',{email});say('If that address has an account, a reset link is on its way.',true);return false;}
// Social login lands here with ?totp=1 (+ a pending-2fa cookie) when the
// account has 2FA: drive the form straight into TOTP-entry mode. The
// email/password fields are `required`, so they must be un-required and hidden
// or the browser blocks submitting just the code (which POSTs /auth/totp,
// reusing the pending-2fa cookie — no email/password needed).
function initSocialTotp(){
  if(new URLSearchParams(location.search).get('totp')!=='1')return;
  awaitCode=true;
  const tw=document.getElementById('totp-wrap');if(tw)tw.style.display='block';
  const go=document.getElementById('go');if(go)go.textContent='Verify';
  const tabs=document.querySelector('.tabs');if(tabs)tabs.style.display='none';
  for(const id of ['email','password']){
    const el=document.getElementById(id);
    if(el){el.required=false;el.style.display='none';if(el.previousElementSibling)el.previousElementSibling.style.display='none';}
  }
  const hd=document.getElementById('hd');if(hd)hd.textContent='Two-factor authentication';
  const sb=document.getElementById('sb');if(sb)sb.textContent='Enter the 6-digit code from your authenticator app.';
  const code=document.getElementById('code');if(code)code.focus();
}
if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',initSocialTotp);}else{initSocialTotp();}
// `?signup=1` opens the page on the Create-account tab — the invite page's
// "Create account" button lands here for someone without an account yet.
function initFromQuery(){
  const q=new URLSearchParams(location.search);
  if(q.get('signup')==='1'&&typeof mode==='function')mode(true);
  if(q.get('next')&&nextPath()!=='/'){const sb=document.getElementById('sb');if(sb)sb.textContent+=' You will be taken back to the invitation afterwards.';}
}
if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',initFromQuery);}else{initFromQuery();}
