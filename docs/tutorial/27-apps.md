# Lesson 27 — Apps: publishing a fn as a public site

**Goal**: by the end of this lesson you can take a fn that
produces a web response and put it live on the public internet at
`https://<label>.graphden.app` — and point your own custom domain
at it. You'll understand how a graphden *app* differs from the
*editor*, and why your app code runs in a sandbox.

**Concepts introduced**: an **app** (a fn served on the web), the
per-fn **Apps popover** (the ▣ row action), a **named app** (the
`:app-route` behind it), the **apps-domain** (`graphden.app`) vs the
editor domain, the **globally-unique label**, **custom domains**, and
the **FaaS sandbox** your app runs in.

This lesson assumes you can compose a fn that returns a web
response — see [Lesson 07 (Composing pages from
components)](07-components-and-pages.md), whose `:html-page` /
`:router` fns are exactly what an app serves.

## What an app is

An app is nothing new — it's a fn you already know how to build,
one whose return value is an HTTP response. The same
`:html-page` you rendered in Lesson 07, or a `:router` that
dispatches on the request path, IS an app the moment you give it a
public address. graphden runs it for you on each request; you
never run a web server yourself.

Concretely, an app's handler fn is called with the incoming
request as a free argument (`{:request …}`) and returns a Ring
response (a status/headers/body map — an `:html-page` produces one
for you). If you want more than one page, serve a `:router` that
picks a handler by path, exactly like the editor itself does.

## Apps in the editor

On a cloud deployment (the tenancy addon is active) publishing
starts **from the fn**, the same way declaring a service does: the
**⋯** menu on a selected fn's root row has an **Apps** entry (▣).
Its popover lists the hosts this fn already serves — each a live
link, with a **×** to remove it — and a form to add a new one.

> On a single-tenant self-hosted instance there is no ▣ entry —
> apps are a cloud concept, and a self-hoster simply points their
> own reverse proxy at the fn they want to serve.

Apps also show up in the Explorer itself: a fn that serves an app
carries a **▣** marker on its tree row, and the **apps** lens chip
under the sidebar search (Lesson 17) narrows the tree to just
those fns — that lens is the org-wide overview of everything
published. The chip shows a count and appears only when the
deployment has app routing at all.

## Creating an app

In the fn's ▣ popover, the **Add app** form asks for one thing:

a **subdomain label** — the `<label>` in `<label>.graphden.app`.
It must be a DNS-safe subdomain (lower-case letters, digits and
hyphens), it can't be a reserved platform label (`www`, `api`,
`app`, …), and it is **globally unique**: the apps-domain is one
flat namespace, so `shop` is claimed by whoever takes it first
(like a project name on a deployment host). Pick something
specific to you.

The fn it serves is the one you opened the popover on — nothing to
type or copy. Submit, and the host appears in the list. That's the
whole deployment: no build step, no container, no restart — the app
is live the instant the row is written. To serve the same host from
a *different* fn, remove it here and add it on the other fn.

## Your app's address

The app is now served at:

```text
https://<label>.graphden.app
```

Note the domain: apps live on **`graphden.app`**, deliberately a
*different* domain from the editor's `graphden.dev`. That
separation is a security boundary — your app runs your org's code
and is its public face, so it must not share an origin (and thus
cookies / your editor token) with the editor. It's the same reason
GitHub serves user content from `githubusercontent.com`, not
`github.com`.

## Custom domains

A `<label>.graphden.app` address is the free default. For a real
site you'll want your own domain — `shop.acme.com`. In the editor:

1. register the hostname for your org;
2. prove you own it by adding the DNS `TXT` record graphden shows
   you (`graphden-verify=<your-org>`);
3. once verified, that hostname serves the same app.

An unverified domain never routes — you can't hijack a name you
don't control.

## The sandbox (why your app can't do everything)

Your app runs **inside graphden's sandbox** (this is the
"FaaS" — functions-as-a-service — model). On each request the
handler executes effect-gated: it may read and compose graph data
and reach the integrations your plan allows, but it cannot touch
the server's files, environment, or spawn processes — the same
effect gate you met in [Lesson 13 (Effects and
secrets)](13-effects-and-secrets.md), now guarding the public
entry point. It's also time-bounded, so a runaway handler can't
wedge the platform.

The payoff: you deploy a *fn*, not a server, and the platform keeps
it safe, isolated per-org, and always-current with your graph.

## Removing an app

The **×** next to a host in the fn's ▣ popover deletes it (with a
confirm). The subdomain and any custom domain stop serving
immediately; the fn it pointed at is untouched — you only removed
the routing, not your code.

## Recap

- An app is a fn that returns a web response, given a public
  address.
- The fn's **▣ Apps** action maps a globally-unique **label** →
  `<label>.graphden.app` → the fn you opened it on; custom domains
  point at the same app once DNS-verified. The **apps** lens is the
  org-wide overview.
- Apps live on `graphden.app`, isolated from the editor's
  `graphden.dev` origin, and run in the effect-gated FaaS sandbox.
- Deploying, re-pointing, and removing an app are all single
  actions with no build or restart.

Next: [lesson 30 — Working across
organizations](30-working-across-orgs.md) covers the flip side of
the same domain model: where *your editor* lives when you belong
to more than one org.
