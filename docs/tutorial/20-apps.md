# Lesson 20 — Apps: publishing a fn as a public site

**Goal**: by the end of this lesson you can take a fn that
produces a web response and put it live on the public internet at
`https://<label>.graphden.app` — and point your own custom domain
at it. You'll understand how a graphden *app* differs from the
*editor*, and why your app code runs in a sandbox.

**Concepts introduced**: an **app** (a fn served on the web), the
**Apps panel**, a **named app** (the `:app-route` behind it), the
**apps-domain** (`graphden.app`) vs the editor domain, the
**globally-unique label**, **custom domains**, and the **FaaS
sandbox** your app runs in.

This lesson assumes you can compose a fn that returns a web
response — see [Lesson 12 (Composing pages from
components)](12-components-and-pages.md), whose `:html-page` /
`:router` fns are exactly what an app serves.

## What an app is

An app is nothing new — it's a fn you already know how to build,
one whose return value is an HTTP response. The same
`:html-page` you rendered in Lesson 12, or a `:router` that
dispatches on the request path, IS an app the moment you give it a
public address. graphden runs it for you on each request; you
never run a web server yourself.

Concretely, an app's handler fn is called with the incoming
request as a free argument (`{:request …}`) and returns a Ring
response (a status/headers/body map — an `:html-page` produces one
for you). If you want more than one page, serve a `:router` that
picks a handler by path, exactly like the editor itself does.

## The Apps panel

On a cloud deployment (the tenancy addon is active) the editor's
**Organization** surface (open it from your avatar's menu in the top bar) has an **Apps**
section. It lists your org's apps and lets
you add or remove them. Each row shows the app's live URL and a
link to the fn it serves.

> On a single-tenant self-hosted instance there is no Apps panel —
> apps are a cloud concept, and a self-hoster simply points their
> own reverse proxy at the fn they want to serve.

Apps also show up in the Explorer itself: a fn that serves an app
carries a **▣** marker on its tree row, and the **apps** lens chip
under the sidebar search (Lesson 23) narrows the tree to just
those fns — the chip shows a count and appears only when the
deployment has app routing at all.

## Creating an app

In the Apps panel, the **Add app** form asks for two things:

1. a **subdomain label** — the `<label>` in `<label>.graphden.app`.
   It must be a DNS-safe subdomain (lower-case letters, digits and
   hyphens), it can't be a reserved platform label (`www`, `api`,
   `app`, …), and it is **globally unique**: the apps-domain is one
   flat namespace, so `shop` is claimed by whoever takes it first
   (like a project name on a deployment host). Pick something
   specific to you.
2. the **fn it serves** — the handler fn (its id, copyable from any
   fn card). This is the fn graphden executes on every request to
   the app.

Submit, and the row appears. That's the whole deployment: no build
step, no container, no restart — the app is live the instant the
row is written, and re-pointing it at a different fn takes effect
on the next request.

## Your app's address

The app is now served at:

```
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
effect gate you met in [Lesson 07 (Effects and
secrets)](07-effects-and-secrets.md), now guarding the public
entry point. It's also time-bounded, so a runaway handler can't
wedge the platform.

The payoff: you deploy a *fn*, not a server, and the platform keeps
it safe, isolated per-org, and always-current with your graph.

## Removing an app

The **×** on an app row deletes it (with a confirm). The subdomain
and any custom domain stop serving immediately; the fn it pointed
at is untouched — you only removed the routing, not your code.

## Recap

- An app is a fn that returns a web response, given a public
  address.
- The Apps panel maps a globally-unique **label** →
  `<label>.graphden.app` → your **handler fn**; custom domains
  point at the same app once DNS-verified.
- Apps live on `graphden.app`, isolated from the editor's
  `graphden.dev` origin, and run in the effect-gated FaaS sandbox.
- Deploying, re-pointing, and removing an app are all single
  actions with no build or restart.

Next: [Lesson 21 — Working across
organizations](21-working-across-orgs.md) covers the flip side of
the same domain model: where *your editor* lives when you belong
to more than one org.
