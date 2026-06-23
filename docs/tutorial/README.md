# Graphden Tutorial

> Step-by-step introduction to graphden. Text-only for now —
> per [ROADMAP § Block 0](../ROADMAP.md#block-0--tutorial-framework-continuous)
> the UI integration is a later decision.
>
> **Audience**: someone who can program but has never seen
> graphden before. Each lesson assumes the previous ones and
> nothing else from the project docs.

## How to read this

- Read lessons in order on first pass.
- Each lesson has a **Goal** (what you'll be able to do at the
  end), **Concepts** (vocabulary introduced), and **Try it**
  (something concrete to type into the running editor).
- If a concept needs deeper detail than the lesson gives, the
  lesson links into [ARCHITECTURE.md](../ARCHITECTURE.md),
  [PACKAGES.md](../PACKAGES.md), or wherever it lives.

## Lessons

| # | Lesson | Status |
|---|---|---|
| 01 | [Anatomy of a fn-def](01-fn-defs.md) | ✅ written |
| 02 | [Parents and inheritance — single parent, then multiple](02-parents-and-inheritance.md) | ✅ written |
| 03 | [Slots and bindings — what they are at the data level](03-slots-and-bindings.md) | ✅ written |
| 04 | [Free arguments and how they propagate](04-free-arguments.md) | ✅ written |
| 05 | [Types — atomic, refinement, record, union, variant, list](05-types.md) | ✅ written |
| 06 | [Higher-order functions and `:fn`-typed slots](06-higher-order-functions.md) | ✅ written |
| 07 | [Effects and the `:secret` type-marker](07-effects-and-secrets.md) | ✅ written |
| 08 | [Branches — fork, edit, diff, merge](08-branches.md) | ✅ written |
| 09 | [Executing a fn — free-arg form, history, cancel](09-executing-a-fn.md) | ✅ written |
| 10 | [Services — long-running fns supervised by graphden](10-services.md) | ✅ written |
| 11 | [Packages — namespaces, fns.edn, impls.clj, deps](11-packages.md) | ✅ written |
| 12 | [Composing pages from components](12-components-and-pages.md) | ✅ written |
| 13 | [The `:custom-script` escape hatch](13-custom-script-escape-hatch.md) | ✅ written |
| 14 | [Build and deploy your own site](14-build-and-deploy-a-site.md) | ✅ written |

New lessons are added as features ship. If a lesson would document
a feature that doesn't yet exist or behaves differently from how
it's described, it stays ⏳ planned until the gap closes.

## How to contribute a lesson

A lesson is a short focused walkthrough of **one** concept. Keep:

- ~60-150 lines of markdown total
- One concrete worked example you can paste into the running editor
- Concepts introduced explicitly named (so future lessons can refer)
- No prerequisites beyond the previous lessons in this index

Avoid:

- Re-explaining what earlier lessons already covered (link to them)
- Duplicating reference material that lives in `docs/*.md` (link)
- "Why we did it this way" rationale — that belongs in
  [PHILOSOPHY.md](../PHILOSOPHY.md) and gets linked, not pasted
