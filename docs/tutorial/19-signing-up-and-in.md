# Lesson 19 — Signing up & signing in: your cloud account

**Goal**: by the end of this lesson you can create your own
organization on a graphden cloud, sign back into it, and you'll
understand how the same login surface behaves on a self-hosted
instance. This is the on-ramp to the operator lessons that
follow ([16 Users](16-users-admin.md), [17 Grants](17-grants.md),
[18 Plans](18-plans-and-tiers.md)).

**Concepts introduced**: the `/login` page, self-serve org
creation (`signup`), the org-creator admin grant, the single
sign-in surface across deployment shapes, and where the login
*form* comes from (it's a graph partial, like everything else).

## The `/login` page (cloud)

On a cloud deployment (the tenancy addon is active), open
`/login`. It's one card with two tabs:

- **Sign in** — username + password for an existing account.
- **Create account** — username + password + **organization
  name**. Submitting it:
  1. creates a brand-new org (the name is also its identity —
     it can't be one that already exists, and reserved platform
     labels like `app` / `www` / `api` are refused);
  2. creates you as its first user (password stored only as a
     bcrypt hash; an 8-character minimum, checked on the server —
     the strength meter next to the field is just a hint);
  3. **grants you `:admin` over your whole org** (root
     namespace), so you can edit immediately — you are the owner
     of the org you just made;
  4. logs you in and drops you into the editor.

Signup can *only ever create a new org* — it never joins an
existing one. To add teammates to your org you invite them
([Lesson 17 covers grants](17-grants.md)); a fresh signup with an
existing org name is refused, not merged.

Once signed in, the bearer token lives in your browser and rides
every request. There is **no anonymous view of the graph** on a
cloud instance: unauthenticated, `/` bounces you to `/login`.

## The same surface, self-hosted

Run graphden yourself and the *same* lock affordance appears, but
the form is different — because the login form is a graph partial
(`GET /partials/auth-form`) and each deployment serves its own:

- **`AUTH_TOKEN` set** → a single **Admin password** field. Paste
  the token value; there are no usernames or orgs to manage.
- **`AUTH_TOKEN` unset** → auth is *off* entirely: the instance is
  open, no login at all (the convenient local-dev mode).

So the tenant username/org fields you see on the cloud simply do
not exist in a self-hosted build — the tenancy package that
carries them isn't loaded. Nothing is hidden; each deployment
ships exactly the form it needs. (The mechanics live in
[docs/TENANCY_SEAM.md § Auth seam](../TENANCY_SEAM.md#auth-seam) and
[docs/DEPLOYMENT.md § Authentication](../DEPLOYMENT.md).)

## Try it

On a cloud instance:

1. Open `/login`, click **Create account**.
2. Pick a username, a password (8+ chars), and an org name that's
   yours — e.g. `acme-<yourname>`.
3. Submit → you land in the editor, already able to create
   namespaces and fns (you're your org's admin).
4. Sign out (the lock icon → confirm), then **Sign in** with the
   same username/password to confirm the round-trip.

That's the whole account lifecycle. Managing *other* people in
your org — adding users, granting them narrower capabilities,
seeing your plan's limits — is [Lesson 16](16-users-admin.md)
onward.
