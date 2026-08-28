# Lesson 29 — Working across organizations

**Goal**: by the end of this lesson you'll know where *your
editor* lives on a graphden cloud, how to belong to more than one
organization, and how to move between them. It's the companion to
[Lesson 26 (Apps)](26-apps.md): apps live on `graphden.app`, and
this lesson is about the *other* domain — where you sign in and
edit.

**Concepts introduced**: the **per-org editor subdomain**
(`<org>.graphden.dev`), **per-origin sessions**, the **org
switcher**, and how the apex landing routes a first-time sign-in.

This follows on from [Lesson 32 (Signing up & signing
in)](32-signing-up-and-in.md) — you have an account and an org.

## Your org has its own editor address

On a graphden cloud, your organization's editor lives at its own
subdomain:

```
https://<org>.graphden.dev
```

So the `acme` org edits at `acme.graphden.dev`. That's the URL you
sign in at and work from. It's the mirror image of Lesson 26: the
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
up for a new org ([Lesson 32](32-signing-up-and-in.md)) lands you
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

## The org switcher

If your account is a member of **more than one** organization, the
editor's top bar shows an **org chip** with the current org's name.
Open it and you'll see your other orgs. Picking one **navigates**
to that org's `<org>.graphden.dev` — its own origin, where you sign
in (or already have a session). It doesn't try to carry your
current session across; per-origin isolation means each org gets
its own sign-in.

A single-org member sees no chip — there's nothing to switch
between.

> How do you *become* a member of a second org? Not by signing up
> again (signup only ever makes a *new* org — Lesson 32). Someone
> in the other org grants your account a capability in it
> ([Lesson 24 — Grants](24-grants.md)); membership is exactly
> "holds a grant in that org," so the moment you're granted
> something there, it appears in your switcher.

## Where each thing lives — the whole map

| Address | What it is |
|---|---|
| `graphden.dev` | the landing + first-time sign-in |
| `<org>.graphden.dev` | that org's editor + login |
| `<label>.graphden.app` | one of the org's apps ([Lesson 26](26-apps.md)) |
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
- The **org switcher** appears when you belong to 2+ orgs and
  navigates you to another org's subdomain; you gain membership by
  being *granted* something in that org, not by signing up again.
- Editors on `graphden.dev`, apps on `graphden.app` — kept apart on
  purpose.

This closes the domain model: [Lesson 26](26-apps.md) put your fns
on the web as apps; this lesson placed *you* — the editor — on it.
