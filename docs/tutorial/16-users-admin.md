# Lesson 16 — Users: the operator's account panel

**Goal**: by the end of this lesson you can list, create,
reset-password and delete platform users from the editor's
**Users** panel, and you'll know exactly *who* is allowed to do
that (spoiler: the platform operator, not tenants) and why the
panel sometimes simply isn't there.

**Concepts introduced**: the tenancy addon's capability header,
pseudo-namespace admin sections, the `:user` entity,
tenant-forbidden entities, cascade delete, session invalidation
on password reset.

## When the panel exists at all

The Users panel is part of the **tenancy addon** (the
multi-tenant platform layer — see
[docs/SECURITY_MODEL.md](../SECURITY_MODEL.md)). A single-tenant
graphden has no `:user` rows to manage, so the section never
mounts.

The client finds out the addon is live from a response header:
the first `/api/*` response that carries
`X-Graphden-Capabilities` flips the editor into tenancy mode.
Only then — and only when you're signed in — does the sidebar
grow the admin pseudo-namespaces, in this order: **Grants**,
**Users**, **Packages**.

So if you don't see a **Users** section: either you're not
signed in, or this instance runs without the tenancy addon.
(The sections also hide while the sidebar filter is active.)

## The table

Expand **Users**. The body is a server-rendered partial
(`GET /partials/users-admin`) — a table of every user:

```
Username | Org        |
alice    | acme       |  [new password] [Reset pw]  ×
bob      | public     |  [new password] [Reset pw]  ×
```

Password hashes never reach the browser — the listing strips
them server-side and ships only `username`, `org` and the row
id.

If the addon isn't actually active on the backend, the panel
degrades to a notice: *"Tenancy addon not active — no users to
manage."*

## Create a user

Under the table there's a three-field form — `username`,
`password`, `org` — and a **+ Add user** button. Submit posts
to `POST /api/users`; the password is bcrypt-hashed by the
addon's user-ops seam before it touches storage, and the panel
re-renders with the new row.

The `org` field is the tenant this account belongs to. `public`
is the platform org — accounts there are operator accounts.

## Reset a password

Each row carries its own tiny form: type a new password, click
**Reset pw** (`POST /api/users/:id/password`). Two things
happen: the hash is replaced, and every session token the user
held is invalidated — they're signed out everywhere.

## Delete a user

The row's `×` (confirm: *"Delete this user?"*) calls a
dedicated cascade route — `DELETE /api/users/:id` — which
removes the user AND their `:token` and `:grant` rows in one
operation. It's deliberately not the generic
`/api/entities/user/:id` endpoint: a bare entity delete would
leave orphaned tokens and grants behind.

## Who may actually use this

Here's the part the panel itself won't tell you. The routes are
merely *auth-required* — any signed-in user gets past the 401.
But `:user` (like `:token`, `:grant`, `:org`) is a
**tenant-forbidden entity**: the org-scoped storage layer denies
reads and writes for every org except the platform (`public`)
org.

Practical upshot: a tenant user may see the section mount
(their responses carry the capability header too), but every
listing and mutation comes back denied. User management is an
**operator** activity. Provisioning whole orgs, tokens and
custom domains is likewise operator-only, via the registration
API (`POST /api/orgs`, `/api/tokens`, `/api/domains`) — that
API has no editor UI at all.

## Try it

(Requires an instance with the tenancy addon; on a plain dev
stack the panel degrades to the not-active notice — that
degradation itself is worth seeing.)

1. Sign in as an operator (public-org account). Expand
   **Users**.
2. Add `test-user` / a password / org `public`. The row
   appears.
3. Reset their password from the row form — note the button
   relabels nothing; the sessions just die server-side.
4. Delete the row (`×`, confirm). Their tokens and grants go
   with it — check Grants (next lesson) if you'd granted them
   anything.

## What we glossed over

- **Where accounts come from besides this panel** — the
  registration flow and org provisioning (lesson
  [19 — Signing up & in](19-signing-up-and-in.md)).
- **Session/token mechanics** — how bearer tokens are hashed at
  rest and matched per-request.
- **Personal namespaces** — every user implicitly owns
  `<prefix>.<username>`; that interacts with grants (next
  lesson).

## Next

[Lesson 17 — Grants](17-grants.md)
