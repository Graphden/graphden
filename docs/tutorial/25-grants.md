# Lesson 25 — Grants: who may touch what

**Goal**: by the end of this lesson you can read the **Grants**
panel, add and revoke a grant, and predict which editor
operations a given user can perform — because you'll know the
seven capabilities, how namespace scope works, and where the
checks actually fire.

**Concepts introduced**: the `:grant` entity, the capability
vocabulary, capability implication, namespace-scoped
authorization, default-deny, personal namespaces.

## The model in one paragraph

A grant is a row: **subject** (a member's email, stored as their stable account id) + **capability** +
**namespace**. Authorization is default-deny: an operation is
allowed iff some grant covers it. "Covers" is generous in two
directions — a grant on a parent namespace covers all its
descendants (a grant on `acme` covers `acme.billing.invoices`;
a blank namespace is a root grant covering everything), and
some capabilities imply others.

## The seven capabilities

The closed vocabulary (anything else is rejected at create time
with a 400):

| Capability | Lets the subject… |
|---|---|
| `read` | discover a fn + see its SIGNATURE (slots, types, return) |
| `view-impl` | see a fn's INTERNAL COMPOSITION (parent chain + bindings) — withhold it and the fn stays executable but its impl is hidden |
| `write` | create / move / edit fns there |
| `execute` | run fns there |
| `admin` | everything above, within the scope |
| `bind-args` | edit only a binding's VALUE (not structure) |
| `append-list` | append items to list-typed bindings |

Implication: `admin` implies all of them; `write` implies
`view-impl` (you can't edit a fn you can't see) plus the two
narrow edit caps (`bind-args`, `append-list`). The narrow
caps exist so you can hand someone "tune the parameters of my
app" without handing them "restructure my app".

(The panel's capability `<select>` deliberately offers only six
of these — every one except `view-impl`, which is set through the
graph-read filter rather than picked by hand — but the
`POST /api/grants` API accepts all seven.)

One freebie needs no grant row at all: every user implicitly
holds `admin` over their **personal namespace**
(`<prefix>.<account-id>` — built from the stable id, so it survives an email change).

## Where the checks fire

Three enforcement layers read the same grant table:

- **Write gate** — creating a fn or moving it into a namespace
  needs `write` there; a value-only binding edit passes with
  `bind-args`; appending a list item with `append-list`.
- **Execute gate** — running a fn needs `execute` on its
  namespace.
- **Request gate** — a cheap per-request check: reads pass,
  mutations require the subject to hold SOME write-family
  capability, `/api/execute` requires `execute`.

The editor also *reads* the grant table indirectly: the
`X-Graphden-Capabilities` header (Lesson 24) that unlocks
tenant-mode UI is computed from these same rows.

## The panel

Expand **Grants** on the **Organization** surface (open it from the
account menu; same gating as Users: signed in + tenancy addon). The partial (`GET /partials/grants-admin`)
renders:

```text
Subject | Capability | Namespace     |
alice   | write      | acme.billing  |  ×
bob     | execute    | acme          |  ×
```

Below it, the add form: a `subject` email input (with type-ahead
over the org's members), a capability
`<select>` (the six pickable values — every capability except
`view-impl`), a `namespace` input, and **+ Add grant**
(`POST /api/grants`).

One subtlety worth knowing: the form takes an *email*, but
enforcement keys on the account's stable id — the create handler
resolves the email to that id at write time and stores only the
id (the email is re-joined from the account row for display).
Typo the email and you get a "dead" grant that matches no one —
its subject cell renders the raw id — but it doesn't throw.

One special capability rides the same rows: `require-2fa`.
Granted to a user (or, with subject-kind `org`, to a whole org)
it doesn't *allow* anything — it *requires* the subject to enroll
two-factor authentication before any other request passes
([Lesson 33](33-signing-up-and-in.md) shows enrollment).

Revoke is the row's `×` (confirm: *"Delete this grant?"*) —
this one goes through the generic entity endpoint
(`DELETE /api/entities/grant/:id`), and the row just drops out
of the table.

## Who may manage grants

Grants are **org-scoped RBAC**, administered from WITHIN the org —
not by the platform operator. The **Grants** panel mounts, and its
create / revoke endpoints authorize, only for a user who may hand
out grants in their own org: the org **owner**, or a holder of the
`manage-grants` org-management capability. Every other member sees
no panel. (The operator, on the public platform org, holds no
`manage-grants` and does not administer a tenant's grants.) Tenants
are both the *subjects* AND the *administrators* of grants within
their org.

### Roles — bundling capabilities

A **role** is a named bundle of grants an org owner (or a
`manage-roles` holder) defines once and assigns to members, so you
don't re-issue the same capability set per person. It has its own
**Roles** sidebar panel (`GET /partials/roles-admin`, gated on
`manage-roles`), parallel to Grants. Lesson 24's "delegate a
capability via a role" points here. (A full roles walk-through is
still a planned lesson; for now, know the panel exists and is
org-scoped like Grants.)

## Try it

(An org owner — or a `manage-grants` holder — on a tenancy-addon
instance.)

1. Add `carol` to your org `acme` (Lesson 24).
2. In **Grants**, grant `carol` / `bind-args` / `acme.settings`. As
   carol: editing a binding VALUE under `acme.settings` works;
   renaming the fn or changing its parent is denied — that needs
   `write`.
3. Replace it with `write` on `acme` — now structural edits pass
   anywhere under `acme`, including `acme.settings` (parent-path
   coverage).
4. Remove carol (Lesson 24) and watch her grant rows vanish with
   her — the cascade from the other side.

## What we glossed over

- **How `can?` composes implication + scope** — the pure
  decision function and its default-deny shape
  (`graphden.tenancy.grant`, in the private `graphden-tenancy` repo).
- **The two-layer tenant effect gate** — grants say *who may*,
  the effect gate says *what kinds of side effects* cloud code
  may perform at all
  ([docs/TENANCY_SEAM.md § Effect gate](../TENANCY_SEAM.md#effect-gate)).
- **Groups** — the design sketch allows group subjects; the
  panel today handles user subjects only.

## Next

[lesson 34 — Plans & tiers](34-plans-and-tiers.md): what the cloud
grants each account, and how an operator changes an org's tier.
