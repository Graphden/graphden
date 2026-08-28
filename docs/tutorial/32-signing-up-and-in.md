# Lesson 32 — Signing up & signing in: your account

**Goal**: by the end of this lesson you can create an account on a
graphden cloud, sign back into it (by password or a social
provider), verify your email, protect the account with two-factor
authentication — and you'll understand how the same login surface
behaves on a self-hosted instance. This is the on-ramp to the
operator lessons that follow ([16 Members](23-users-admin.md),
[17 Grants](24-grants.md), [18 Plans](33-plans-and-tiers.md)).

**Concepts introduced**: the `/login` page, accounts and sign-in
*identities* (one account, many ways in), email verification,
account linking, personal orgs, the Account settings, TOTP 2FA.

## Accounts and identities

Graphden's identity model (the open `accounts` module —
[docs/ACCOUNTS.md](../ACCOUNTS.md)) separates **who you are** from
**how you sign in**:

- an **account** is you — one stable identity everything else
  (grants, orgs, ownership) keys on;
- an **identity** is one way of signing in: an email + password,
  a Google account, a GitHub account, a Telegram account.

One account can hold several identities. Sign up with a password
today, link Google tomorrow — both land in the same account. If a
social provider vouches for the same **verified email** your
account already owns, the link even happens automatically.

## The `/login` page (cloud)

Open `/login`. One card, two tabs:

- **Sign in** — email + password, or one of the provider buttons
  (Continue with GitHub / Google, or the Telegram widget).
- **Create account** — email + password. Submitting it:
  1. creates your account and emails you a **verification link**
     (from `noreply@graphden.dev`; the link is valid for 24 h);
  2. signs you in right away — but your email counts as *verified*
     only after you click the link, and some things (like being
     found by email for org invites) wait for that;
  3. on your first request the platform provisions a **personal
     org** derived from your email — you own it and hold `:admin`
     over it, so you can start building immediately.

Signing up through a social button skips the password entirely:
the provider proves who you are, and a provider-verified email is
trusted as verified from the start. (Telegram has no email, so a
Telegram-created account starts email-less — you can add one
later from your account settings.)

Sessions ride an HttpOnly cookie — sign in once and the editor,
the API and every page share it. Signed out, `/` bounces you to
`/login`.

## Forgot your password?

The **Forgot password?** link on the Sign-in tab asks for your
email and always answers the same way — "if that address has an
account, a reset link is on its way" — whether or not the account
exists (so addresses can't be probed). The emailed link opens
`/reset`, where you set a new password; doing so **signs you out
everywhere**, on the assumption that a reset means the old
password may be compromised. Then sign back in.

(These endpoints are rate-limited per IP, like sign-in and
sign-up — a burst of attempts quietly gets the same generic
answer.)

## Account settings

Once signed in, your self-service surface lives in the editor:
click your avatar in the top bar → **Settings** → the **Account**
card (deep link: `/#@settings/account`; the old `/account` URL
redirects here). It holds:

- **Sign-in methods** — the identities linked to your account.
  Link GitHub/Google from here; unlink any of them (the last
  remaining method is refused — you'd lock yourself out).
- **Two-factor authentication** — enable TOTP: add the secret to
  any authenticator app, confirm with a 6-digit code, and from
  then on password sign-ins ask for the code. (An org can also
  *require* 2FA — [Lesson 24](24-grants.md) shows the
  `require-2fa` capability.)
- **API tokens** — long-lived scoped keys for MCP/API clients
  (the section appears on cloud/tenancy deployments).

**Sign out** (this device, or everywhere at once) is in the same
account menu.

## The same surface, self-hosted

Run graphden yourself and the sign-in surface depends on what you
enable:

- **accounts addon enabled** (`GRAPHDEN_ADDON_CONFIGS=graphden/accounts/addon.edn`)
  → the same `/login` page + in-editor Account settings as the
  cloud. Each social
  provider turns on only when you configure its credentials; with
  no email provider configured, verification links are printed to
  the server log instead of emailed — everything still works.
- **`AUTH_TOKEN` set** (no accounts addon) → a single **Admin
  password** popover in the editor. No accounts, no orgs.
- **neither** → auth is *off* entirely: the instance is open, no
  login at all (the convenient local-dev mode).

Nothing is hidden; each deployment ships exactly the surface it
needs. (Mechanics: [docs/ACCOUNTS.md](../ACCOUNTS.md) and
[docs/TENANCY_SEAM.md § Auth seam](../TENANCY_SEAM.md#auth-seam).)

## Try it

On a cloud instance:

1. Open `/login`, click **Create account**. Use a real email and
   a password (8+ chars).
2. Submit → you land in the editor with your personal org already
   provisioned; check your inbox and click the verification link.
3. Click your avatar → **Settings** → **Account**: link a social
   provider (e.g. GitHub), then sign out and sign back in with
   that provider instead of the password — same account, same org.
4. Back in the Account card, enable 2FA: scan/enter the secret in
   an authenticator app, confirm the code, sign out, sign in with
   the password — the code is now required.

That's the whole account lifecycle. Managing *other* people in
your org — adding members by email, granting them narrower
capabilities — is [Lesson 23](23-users-admin.md) onward.
