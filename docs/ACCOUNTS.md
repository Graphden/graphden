# Accounts — the open identity module

Last verified against code: 2026-08-17.

`accounts` is the **open-core**, **opt-in** identity layer: it gives a
self-hosted Graphden real users, passwords, sessions and — the point of the
whole design — **account linking** (one person, many sign-in methods). The
multi-tenant *policy* on top (org membership, operator-by-email, enforced 2FA)
stays in the private `graphden-tenancy` addon; `accounts` is everything that is
safe and useful without it.

It is **off by default**. Turn it on by naming its config fragment in
`GRAPHDEN_ADDON_CONFIGS`:

```bash
GRAPHDEN_ADDON_CONFIGS=graphden/accounts/addon.edn
```

Omit it and core runs exactly as before (single-token bearer, or fully open with
no `AUTH_TOKEN`). Env vars the addon fragment reads (mailer, social-provider
credentials, public origin) are listed in
[CONFIGURATION.md](CONFIGURATION.md#environment-variables) and
[DEPLOYMENT.md](DEPLOYMENT.md#environment-variables).

## Data model (three non-versioned entities)

| Entity | Is | Key fields |
|--------|----|-----------|
| `:account` | a **person** — the stable authz subject downstream grants/roles key on | `id`, `display-name?`, `primary-email?` (UNIQUE-when-present; the linking + operator-by-email lookup, set only from a **verified** email), `status` (`active`/`suspended`), `created-at` |
| `:identity` | **one way to sign in**, bound to an account (1 account ↔ N identities → linking) | `account-id`, `provider` (`password`/`google`/`github`/`telegram`), `subject` (provider's stable id; the lower-cased email for `password`), `secret-data?` (bcrypt hash for `password`), `email?`, `email-verified?`, `created-at`. **UNIQUE (provider, subject)** |
| `:session` | an authenticating token, org-agnostic | `token-hash` (SHA-256, UNIQUE — the raw token is never stored), `account-id`, `expires-at?` (nil = never), `kind?` (nil/`api` authenticate), `label?`, `scopes?` (space-separated scope names for an `api` bearer; nil = unscoped — accounts stores and surfaces them on the principal as `:token-scopes`/`:api-token?`, the tenancy layer enforces them as a ceiling over the account's grants), `created-at`, `last-used-at?` (touched by `authenticate-token` at most hourly — what "in use" means for a long-lived cookie or an API token; the tenancy inactivity marker reads it) |

All three are non-versioned platform entities (like the tenancy identity
entities) — no version mirror, the cheap schema path.

## Secrets

- Passwords: **bcrypt**, cost 12 (`accounts.core/hash-password`).
- Sessions: only the **SHA-256 hash** of the token is stored. The raw token
  exists exactly once — the return value of `mint-session!` handed to the client
  (as an HttpOnly `gd_session` cookie for the browser, or a bearer for API/CLI).

## The provider

`accounts.provider/AccountsAuthProvider` implements the core
`graphden.auth.provider/AuthProvider` seam. It reads the bearer **or** the
`gd_session` cookie, resolves it through `accounts.core/authenticate-token` to an
ACTIVE account, and returns an **org-agnostic** principal
`{:authenticated? :user-id :user :email :totp-enabled?}` — the last two ride
along so a policy layer (tenancy's operator-by-email, enforced 2FA) can act
without re-reading the account. Open core has no orgs; when the tenancy addon
is present it resolves the org from the account's membership at request-scope
time.

Wired at `:accounts/provider` and swapped into `:exec/context`'s
`:auth-provider` — the same swap-point the tenancy `storage-token-provider` uses.

## The `/auth/*` HTTP surface

Served by `accounts.routes/make-router`, a plain Ring router installed
through the route-collection seam (runs before the graph app-router;
answers on a match, falls through on nil). Sessions travel as the
HttpOnly `gd_session` cookie (Max-Age 24 h); cookies are written as raw
`Set-Cookie` headers — there is no ring cookie middleware in the chain.

| Route | Behavior |
|-------|----------|
| `GET /login` | Self-contained sign-in page (email/password + enabled social buttons) |
| `GET /account` | Account management. With the app package present (the graph page) this redirects into the editor's Settings → Account section (`/#@settings/account`) — identities, 2FA, verify banner, API-tokens panel with per-token scopes + expiry (the panel reveals itself only where `/api/my-tokens/*` answers, i.e. the tenancy addon is active; see [SECURITY_MODEL.md § API-token scopes](SECURITY_MODEL.md)). Headless deployments get the self-contained built-in fallback page with the same sections. |
| `GET /auth/providers` | Enabled oauth providers as `{"providers": {"github": true, …}}` — public (the `/login` HTML exposes the same set); consumed by the editor's Account section to render link buttons |
| `GET /reset` | Password-reset form (consumes the emailed token) |
| `POST /auth/signup` | `{email,password}` → account + session cookie, sends verify mail |
| `POST /auth/login` | `{email,password}` → session cookie, or `{totp-required}` + short-lived `gd_2fa` cookie |
| `POST /auth/totp` | Second login step: pending-2FA cookie + `{code}` → full session |
| `POST /auth/logout` | Revoke THIS session + clear the cookie |
| `POST /auth/logout-all` | Revoke EVERY session of the signed-in account |
| `POST /auth/forgot` | `{email}` → email a reset link; always the same 200 (never enumerates) |
| `POST /auth/reset` | `{token,password}` → set new password, sign out everywhere |
| `GET /auth/verify?token=` | Consume the email-verification link → redirect |
| `POST /auth/resend-verification` | Re-send verify mail for the signed-in, still-unverified identity; always 200 |
| `GET /auth/me` | The signed-in account `{id,email,display-name}`, or 401 |
| `GET /auth/identities` | The signed-in account's linked identities |
| `POST /auth/unlink` | `{provider}` → detach an identity; 409 `last_identity` guards lockout |
| `GET /auth/tfa-state` | `{enabled}` — is TOTP on for the signed-in account |
| `POST /auth/totp/enroll` | Begin TOTP enrollment (secret + otpauth URI) |
| `POST /auth/totp/confirm` | `{code}` → activate TOTP |
| `POST /auth/totp/disable` | `{code}` → turn TOTP off |
| `GET /auth/{github,google}/start` | 302 to the provider + `gd_oauth` state cookie |
| `GET /auth/{github,google}/callback` | State check + code exchange → session (or LINK when already signed in) |
| `GET /auth/telegram/start` | Telegram login-widget page (404 `telegram_disabled` when the provider is off) |
| `GET /auth/telegram/callback` | Verify the login-widget HMAC → session (or LINK) |

A social callback on an **already signed-in** request LINKs the identity to
the current account (identity conflicts redirect to
`/settings?error=identity_conflict`) instead of switching accounts.

## Password reset & rate limiting

Reset is the standard token-by-email flow with two hard properties:
`POST /auth/forgot` returns the identical 200 body whether or not the
email exists (no account enumeration), and a successful
`POST /auth/reset` **signs the account out everywhere** (every session
revoked) so a stolen session doesn't survive a recovery.

Three **per-IP fixed-window limiters** (`crypto/fixed-window-limiter`)
guard the abuse-prone endpoints. The client IP is the entry **N
positions from the END** of `X-Forwarded-For`, where
`N = GRAPHDEN_TRUSTED_PROXIES` (default `0`): only the rightmost N hops
(the ones your own trusted proxies appended) are trusted, and any hops
a client injected to its left are ignored. With **0 trusted proxies the
header is ignored entirely** and the socket `remote-addr` is used — so a
spoofed `X-Forwarded-For:` never bypasses the limiter on a
directly-reachable deployment. The cloud runs behind Caddy with
`GRAPHDEN_TRUSTED_PROXIES=1`. The limited endpoints:

| Endpoint | Limit | Over-quota response |
|----------|-------|---------------------|
| `POST /auth/login` | 10/min | 401 `invalid_credentials` (same as a bad password) |
| `POST /auth/signup` | 20/min | 429 `rate_limited` |
| `POST /auth/forgot` + `/auth/resend-verification` | 5/min | the normal 200 body |

Login/forgot over-quota answers mirror the ordinary failure/success shape
on purpose — the limiter's existence isn't probeable.

## Login & account pages

`/login` and `/reset` are self-contained HTML served straight from the
accounts router — inline CSS/JS calling the JSON `/auth/*` endpoints,
brand-matched, with no editor coupling: enable the addon and a
self-hosted instance has a working auth surface. Account management
lives in the EDITOR (Settings → Account section, `/#@settings/account`,
2026-08-15) when the app package is present; `/account` redirects
there, and the self-contained built-in page remains the headless
fallback.

The PRESENTATION is graph composition: the primary render path is the
`app.auth-pages` fn-defs (page shell, social section, behavior JS as
assets; also the verification/reset **email bodies**), executed on the
platform ctx when `:accounts/routes-install` is wired with the optional
`:ctx #ig/ref :exec/context` — re-theme the auth surface in the editor,
no Clojure fork. The module still stays a drop-in: routes depend on an
injected `page-renderer`/`email-renderer` callback, and with no ctx (or
any graph render failure) they fall back to the built-in Clojure shell
in `accounts.pages` / templates in `accounts.email`, so login survives
a graph outage. The two sides are pinned byte-for-byte by
`graphden.packages.app.auth-pages-test` — edit both together. The
pages render on the platform ctx, which tenant graph writes never
reach (org-scoped versions + the version-plane authz arm), so only a
platform-trusted principal can vary this surface. Social sign-in renders **one unified button style**
for GitHub, Google and Telegram — for Telegram the official widget's
un-restylable iframe button is skipped; its script is loaded only for
`Telegram.Login.auth`, wired to an own-styled button, and the payload
goes through the unchanged server-side HMAC verify at
`/auth/telegram/callback`. Each social provider appears only when its
credentials are configured.

## Editor integration (accounts-mode + org-switcher)

The editor detects the addon with one boot probe of `GET /auth/me`
(`editor-auth.js`): a JSON answer ⇒ `accountsMode` — the token popover is
replaced by the cookie session and the `/login` page, an account chip
renders, and the sidebar re-paints once the cookie session resolves (it
boots before the async probe). The probe result is published as the
`window.gdAccountsReady` promise so accounts-only UI can wait on it.

`editor-org-switcher.js` (tenancy addon present): fetches
`GET /api/memberships` (tenancy auth-routes; the session cookie
authenticates; `:orgs` carries `{name owner? plan}` per row) and mounts
the top-bar **org chip** for every member — single-org accounts too, the
org being the outermost write-context. The chip's popover lists the
account's orgs (owner badge, plan) and ends with **New organization…**:
`POST /api/my-orgs {name}` creates the org with the caller as owner +
`admin` (verified email required, per-account ownership cap
`GRAPHDEN_MAX_ORGS_PER_ACCOUNT`, default 5; refusals answer
`{ok:false, error, message}` — `org/invalid-name` / `org/reserved` 400,
`org/name-taken` 409, `org/limit` / `org/email-unverified` 403; never
reachable with an API token). Picking an org navigates to that org's
subdomain origin; on a single-host dev instance it falls back to
`POST /api/switch-org` (the `gd_org` selector cookie) — sessions are
org-agnostic, no re-mint involved.

## Org invites (tenancy addon)

Under the tenancy addon an org's Members panel mints **invites**
(`:invite` rows, `graphden.tenancy.invites`): by email or as a bare
link, pinned to one address or open to anyone, carrying the rights the
inviter chose (org-wide `write` by default, plus a role), a prefilled
name / department, an expiry (7 days by default, 90 at most) and a use
count. The raw token lives only in the link (`/join/<token>`; the row
keeps a SHA-256), shown once at creation; a pinned invite is also
emailed through the accounts mailer on the trusted `GRAPHDEN_APP_ORIGIN`
— the same origin rule as verification links.

`GET /join/<token>` (tenancy auth-routes) is the whole redemption
surface: signed out → a page naming the org and the inviter with
**Create account** / **Sign in** buttons, both `/login?next=/join/…`;
signed in → grants + roles written, the account's display name filled
in when it had none, `303` into `<org>.<base-domain>` with the `gd_org`
selector set. A pinned invite requires the account's verified primary
email to match. First login with a pinned email redeems the invite
without the link (`accounts-bridge/claim-email-invites!`). Expired
invites are swept by the demo-gc reaper.

Alongside membership the org keeps a **directory entry** per member
(`:member`, `graphden.tenancy.members`): the org-local display name,
`profile` (department, …), when and through which invite they joined.
Written at every way in (first-login personal org, self-serve org,
add-by-email, invite redeemed — the invite's name / profile seed it),
edited from the Members panel (`POST /api/org-members/profile`,
`manage-users`), deleted with the membership. Membership itself stays
grant-derived; the entry is what the org knows beyond the account.

`/login` honours `?next=<same-origin path>` after sign-in / signup (any
scheme or host is ignored) and `?signup=1` opens the Create-account
tab.

## Sequencing (this module vs tenancy)

`accounts` is authoritative for identity/credentials/sessions. The
Phase-4 cutover has landed: the private tenancy addon resolves the org
from the account's membership at request-scope time (no denormalized
`:token.org`), and operator is a capability looked up by the account's
verified `primary-email`. There is no permanent dual model; `accounts`
stands alone for self-hosted.

## Roadmap of the epic

All phases shipped:

0. **Identity foundation** — schema, password provider, sessions, linking
   primitive, `AuthProvider`. ✅
1. **Email + verification** (Resend; LogMailer fallback when no API key) —
   promotes a verified email to `:account.primary-email`. ✅
2. **Social providers** — Google (OIDC), GitHub (OAuth), Telegram (login
   widget); each config-gated, resolving to an `:identity` row,
   auto-linking by **verified** email where the provider vouches one. ✅
3. **Account-linking UI** — the `/login` page + the editor's Account
   card (formerly the `/account` page);
   attach/detach identities on a signed-in account. ✅
4. **Operator-by-email + tenancy cutover** onto `accounts` (policy side
   lives in the private `graphden-tenancy` repo). ✅
5. **2FA (TOTP)** — self-serve enroll/confirm/disable + the two-step
   login; tenancy-side enforcement is the policy layer's job. ✅

Later additions beyond the original roadmap: password reset, per-IP rate
limits, `logout-all`, `resend-verification`, and the editor
accounts-mode / org-switcher integration (all documented above).
