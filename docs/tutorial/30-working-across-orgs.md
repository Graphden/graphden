# Lesson 30 — Working across organizations

**Goal**: by the end of this lesson you'll know where *your
editor* lives on a graphden cloud, how to belong to more than one
organization, and how to move between them. It's the companion to
[lesson 27 (Apps)](27-apps.md): apps live on `graphden.app`, and
this lesson is about the *other* domain — where you sign in and
edit.

**Concepts introduced**: the **per-org editor subdomain**
(`<org>.graphden.dev`), **per-origin sessions**, the **org
switcher**, and how the apex landing routes a first-time sign-in.

This follows on from [lesson 33 (Signing up & signing
in)](33-signing-up-and-in.md) — you have an account and an org.

## Your org has its own editor address

On a graphden cloud, your organization's editor lives at its own
subdomain:

```text
https://<org>.graphden.dev
```

So the `acme` org edits at `acme.graphden.dev`. That's the URL you
sign in at and work from. It's the mirror image of Lesson 27: the
`graphden.dev` zone is for **editors** (one subdomain per org),
`graphden.app` is for **apps** — kept apart so your public app code
never shares an origin with your editor session.

If you only belong to one org, that's the whole story: bookmark
`<org>.graphden.dev` and go.

## First sign-in: the landing

If you don't yet know your org's subdomain — a brand-new visitor —
start at the apex, `graphden.dev` (the landing), and use its
**Sign in**. Once you authenticate, graphden knows which org(s)
you belong to and sends you to your `<org>.graphden.dev`. Signing
up for a new org ([Lesson 33](33-signing-up-and-in.md)) lands you
in that org's editor the same way.

## Per-origin sessions

Because each org's editor is its own subdomain — its own **origin**
— it has its own session. Your login at `acme.graphden.dev` is
stored only for that origin; it grants no access on
`beta.graphden.dev`. This is the Slack-workspace model: separate
spaces, separate sessions.

The upside is isolation: one org's session can never read another
org's data through your browser, even if you're a member of both.
The trade-off is that belonging to two orgs means signing in on two
subdomains — which is exactly what the switcher is for.

## The org chip

The editor's top bar shows an **org chip** with the current org's
name — always, even when you belong to just one. The org is the
outermost context you write into (org → branch → workspace), so it
stays readable at a glance. Open the chip and you'll see every org
you belong to, each with an **owner** badge where you own it and
its plan. Picking another one **navigates** to that org's
`<org>.graphden.dev` — its own origin, where you sign in (or
already have a session). It doesn't try to carry your current
session across; per-origin isolation means each org gets its own
sign-in.

> How do you *get* a second org? Two ways.
>
> - Someone in another org grants your account a capability in it
>   ([lesson 25 — Grants](25-grants.md)); membership is exactly
>   "holds a grant in that org," so the moment you're granted
>   something there, it appears in the chip.
> - You make one: **New organization…** at the bottom of the chip's
>   list takes a name — lowercase letters, digits and hyphens, 3–40
>   characters; it becomes the org's editor address — and makes you
>   its owner and admin. The new org starts on the free plan. A
>   verified email is required, and an account may own a handful of
>   orgs (the operator sets the cap).
>
> Signing up again is *not* a way: signup only ever makes another
> account ([Lesson 33](33-signing-up-and-in.md)).

## Where each thing lives — the whole map

| Address | What it is |
|---|---|
| `graphden.dev` | the landing + first-time sign-in |
| `<org>.graphden.dev` | that org's editor + login |
| `<label>.graphden.app` | one of the org's apps ([Lesson 27](27-apps.md)) |
| your own domain | a custom domain pointed at an app |

Two `graphden`-owned domains, each a single flat level — editors on
one, apps on the other. Nothing is nested, and adding an org or an
app never needs any DNS change on graphden's side.

## Recap

- Your org's editor is `<org>.graphden.dev`; the apex
  `graphden.dev` is the landing + the front door for a first
  sign-in.
- Each org's editor is its own origin with its own session
  (Slack-workspace isolation).
- The **org chip** always names where you are and navigates you to
  another org's subdomain; you gain membership by being *granted*
  something in that org, or you create one yourself from the chip.
- Editors on `graphden.dev`, apps on `graphden.app` — kept apart on
  purpose.

This closes the domain model: [Lesson 27](27-apps.md) put your fns
on the web as apps; this lesson placed *you* — the editor — on it.
