# Lesson 26 — Roles: capabilities as a bundle

**Goal**: define a role once, hand it to several people, and change
what all of them can do by editing one row.

**Concepts**: org-management capabilities, `:role`, role membership,
the Roles panel, why a role is not a namespace grant.

> Prefer to be shown? This lesson exists as a guided in-editor tour —
> pick “Interactive tutorial” in the account menu. It needs an
> organization and the `manage-roles` capability; without them the
> catalogue lists it, disabled, with the reason on the row.

## The axis this lives on

Lesson 25 drew the line and this lesson picks up the other side of it.
There are **two independent axes** of permission:

| | **Namespace grants** (Lesson 25) | **Org-management capabilities** |
|---|---|---|
| Answer | may this person touch `acme.billing`? | may this person administer the org? |
| Values | `read`, `write`, `execute`, `admin`, `bind-args`, `append-list`, `view-impl` | `manage-users`, `manage-grants`, `manage-roles`, `manage-apps`, `publish-packages` |
| Scope | one namespace subtree | the whole org |
| Bundled by | — | **a role** |

`admin` on a namespace does NOT imply `manage-users`. Ownership of the
org implies all of the management capabilities, implicitly — that is
what makes the owner the owner.

A **role** is a named bundle on the second axis: `{name, capabilities,
members}`. Nothing more. It exists so you can say "these three people
run the org's membership" once, instead of issuing the same capability
to three people and remembering all three when it changes.

## The panel

Account menu → **Organization** → **Roles** (gated on `manage-roles`,
so an org owner always sees it). One row per role:

```
support     manage-users, manage-grants     alice, bob     ×
```

- **name** — yours to choose; it means nothing to the system.
- **capabilities** — from the fixed five above. The create form is a
  set of checkboxes, not a free-text field, because these five are the
  entire vocabulary.
- **members** — an editable, comma-separated list of usernames. It is
  a **set**, not an append: submitting the field replaces the whole
  membership, so removing a name from the box removes that person from
  the role.
- **×** — delete the role. The members keep their accounts and their
  namespace grants; only what the role conferred goes away.

## Why bundle at all

Because the alternative rots. Issue `manage-users` to three people
individually and the org's answer to "who can invite members?" is
spread across three rows that nothing ties together. Six months later,
tightening the policy means finding all three.

With a role, the answer is one row, and changing it changes everyone's
effective capabilities at once — the next request each of them makes
already reflects it. This is the same argument lesson 02 makes about
`parent-ids` and lesson 29 makes about packages: name the shared thing
once, and let the places that need it point at the name.

## What a role does not do

- **It does not grant namespace access.** A `support` role with
  `manage-users` lets alice invite people; it does not let her read
  `acme.billing`. That is a grant (Lesson 25), and it stays separate on
  purpose — administering an org and reading its code are different
  powers, and plenty of people should have exactly one of them.
- **It does not nest.** A role holds capabilities, not other roles.
- **It is org-scoped.** A role in `acme` means nothing in `globex`,
  even for the same account (lesson 30).

## Reading the effective answer

A member's effective org capabilities are the union of:

- everything their roles confer, plus
- any capability granted to them directly, plus
- **all** of them if they own the org.

The editor reads that union out of the capabilities header on every
response, which is why a panel the reader may not use is simply not
rendered — the Members panel is absent without `manage-users`, the
Roles panel without `manage-roles`. Nothing is hidden that would
otherwise work; the affordance and the permission are the same fact.

## Try it

(An org owner — or a `manage-roles` holder — on an instance with the
tenancy addon.)

1. Account menu → **Organization** → **Roles**.
2. Create a role: name `support`, tick `manage-users`, Create. The row
   appears with an empty member list.
3. Invite a second person if you have not already (lesson 24), then
   type their username into the role's member box and submit. The row
   now names them.
4. Sign in as that person (or ask them to reload): their account menu
   now has **Members** under Organization — a panel that was not there
   a moment ago.
5. Take the capability back: untick nothing, just clear their name from
   the member box and submit. The panel disappears for them again.
6. Delete the role with ×. Their account, their org membership and any
   namespace grants they hold are untouched.

## Where this shows up next

- **Members** (lesson 24) — who is in the org at all; a role only
  matters for someone already in it.
- **Grants** (Lesson 25) — the other axis, per namespace.
- **Across organizations** (lesson 30) — roles do not travel; each org
  answers for itself.
