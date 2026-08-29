# Lesson 24 — Members: managing who is in your org

**Goal**: by the end of this lesson you can list your org's
members, add one by email, remove one, and transfer ownership —
from the editor's **Members** panel — and you'll know exactly
*who* is allowed to do each of those and why the panel sometimes
simply isn't there.

**Concepts introduced**: the tenancy addon's capability header,
grant-derived membership, the `:account` entity, tenant-forbidden
entities, the owner's special position, ownership transfer.

## Membership is grants

Under the accounts model ([Lesson 33](33-signing-up-and-in.md)),
people *sign themselves up* — there is no admin-creates-user flow
and no per-org password store. What an org controls is
**membership**, and membership is simply *grants*: an account is
a member of your org iff it holds a `:grant` row there (or owns
the org outright). "Add a member" = write them a grant; "remove a
member" = delete their grants in your org. The person's account —
their email, password, 2FA — stays theirs, untouched.

## When the panel exists at all

The Members panel is part of the **tenancy addon** (the
multi-tenant platform layer). A single-tenant graphden has no
orgs to manage, so the section never mounts.

The client finds out the addon is live from a response header:
the first `/api/*` response that carries
`X-Graphden-Capabilities` flips the editor into tenancy mode.
Only then — and only when you're signed in — does the **Organization**
surface (open it from the account menu in the top bar) grow the admin sections,
among them **Grants**, **Members** and **Packages**.

## The table

On the **Organization** surface, expand **Members**. The body is a
server-rendered partial (`GET /partials/users-admin` — the path
is historical) — a table of your org's members:

```
Member               |       |
owner@acme.com       | owner |
dev@acme.com         |       |  ×
```

Each row is an account, shown by its **verified email**; the
owner is badged and carries no remove button (transfer ownership
first). Nothing secret is in the panel — password hashes and
session tokens live in the accounts tables, which never reach
the browser.

## Add a member — by email

Under the table: one email field and **+ Add member**. The person
must already have an account with that *verified* email (they
sign up themselves at `/login` — [Lesson 33](33-signing-up-and-in.md));
submitting grants them `write` in your org. Refine what they may
actually do in the **Grants** panel (next lesson) — membership
gets them in the door, grants decide the rooms.

## Remove a member

The row's `×` (confirm) deletes every grant that account holds in
*your org only* — their account and their other orgs' memberships
are untouched. The owner is refused.

## Transfer ownership

The owner (only) sees a **Transfer ownership** form: enter the
new owner's email (they must already be a member), confirm. There
is no revoke — ownership only moves.

## Who may actually use this

Adding and removing members requires the `manage-users`
org-management capability — held implicitly by the **owner**, or
delegated via a role ([Lesson 25](25-grants.md)). Everyone else
sees the panel read-only at best: the underlying entities
(`:account`, `:grant`, `:org`) are guarded server-side, so the
affordances are just UX — the enforcement is in storage.

## Try it

(Requires an instance with the tenancy addon.)

1. Sign in as an org owner. Expand **Members** — you're there,
   badged `owner`.
2. Have a second account sign up at `/login` (and verify its
   email).
3. Add it by email — the row appears. Check **Grants**: a `write`
   grant materialized.
4. Remove it (`×`, confirm) — the row and its grants go; the
   person's account is unaffected.

## What we glossed over

- **Where accounts come from** — self-serve signup, social
  sign-in, verification ([Lesson 33](33-signing-up-and-in.md)).
- **Delegating member management** — a role carrying
  `manage-users` (next lesson).
- **Personal namespaces** — every member implicitly owns
  `<prefix>.<name>`; that interacts with grants (next lesson).

## Next

[lesson 25 — Grants](25-grants.md)
