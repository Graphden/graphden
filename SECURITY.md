# Security Policy

## Reporting a vulnerability

If you believe you have found a security vulnerability in Graphden,
please report it privately. **Do not open a public GitHub issue for
security problems.**

- Email: **security@graphden.dev**
- Please include: a description of the issue, the affected component
  (host runtime, editor frontend, cloud/tenancy, a specific base-fn or
  route), reproduction steps, and the impact you observed.
- If you have a proof-of-concept, attach or link it. A minimal
  reproduction against a local `docker compose` instance is ideal.

We aim to acknowledge a report within 3 business days and to keep you
updated as we investigate. Please give us a reasonable window to ship a
fix before any public disclosure.

## Scope

Graphden is an execution platform: the host runs user-authored function
graphs. Some behaviours are **intended** and are not vulnerabilities on
their own — please keep these in mind:

- **A deployment with no auth token configured is open by design.** The
  default `docker-compose` ships with authentication OFF for local
  evaluation; exposing that on a public network grants unauthenticated
  graph authoring and execution. See
  [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for the hardening checklist.
  Reports of "the demo compose is open" are expected, not bugs — but
  reports that a *hardened* deployment (auth on, tenancy addon loaded)
  can be bypassed are in scope.
- **The anonymous tier on the hosted demo can author and run graphs in a
  shared public org.** That is the demo product, bounded by per-request
  resource limits.
- **Effects are a capability, not a sandbox escape.** A fn declaring
  `:io` / `:network` / `:process` and running under a principal granted
  those effects is doing what it's allowed to. In scope: a fn that runs
  effects it was **not** granted, or that leaks another tenant's data.

Especially interesting: cross-tenant data exposure, secret-taint
declassification (a `[:secret …]` value reaching a sink un-redacted),
authentication/authorization bypass, and injection through a graph that
a tenant can author.

## Supported versions

Security fixes land on `develop` and ship to the hosted service on the
next release. Self-hosters should track the latest `main`.
