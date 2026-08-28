# Lesson 21 — Editing the editor: asset overrides

**Goal**: by the end of this lesson you can change the editor's
own JavaScript and CSS from inside the editor, see the change
live, diff it against what shipped, and put it back.

**Concepts introduced**: the **Assets panel** (Organization →
Assets), the `:resource-override` row, **per-branch** overrides,
the rolling `?v=` asset hash, **Diff vs baseline**, **Revert to
baseline**, and the **JS syntax gate**.

> **Scope: single-tenant instances.** The Assets panel is your own
> deployment's, and it edits the frontend every session on that
> instance loads. On the multi-tenant cloud it is hidden and its
> writes are platform-only — an editable, shared frontend would be
> a stored-XSS surface across tenants. So this lesson is for a
> self-hosted graphden (`bb rebuild` + `localhost:9002`, or your
> own server); the interactive tour is offered only there.

## What an override is

The editor is served from files inside the platform's own
resources — `packages/app/editor/editor-styles.css`,
`editor-graph-model.js`, and some seventy more. An **override** is
a database row that shadows one of those files by path: when it
exists, the server serves your version instead of the shipped one.

Three properties follow from it being an ordinary row:

- it is **per branch**, like every other versioned row — fork a
  branch, restyle the editor there, and `main` is untouched;
- it is **revertible**: deleting the row brings the shipped file
  back, byte for byte, with nothing to reinstall;
- it **survives upgrades as an override**, not as a patch — your
  row keeps shadowing that path after a new release, which is the
  thing to remember before overriding a file that changes often.

## The panel

Open **Organization → Assets**. Every servable frontend file is
listed with a chip: `baseline` (serving what shipped) or
`override` (serving yours). `edit` opens the file in a code
editor — the same CodeMirror the `:js-source` slots use, with
syntax highlighting for the file's language.

Three actions sit under the editor:

| Action | What it does |
|---|---|
| **Save override** | Writes (or updates) the row for this path |
| **Diff vs baseline** | Shows your version against the shipped one |
| **Revert to baseline** | Deletes the row — the shipped file serves again |

## Seeing your change

Assets are served with a cache-busting `?v=<hash>` on every URL.
Saving an override **rolls that hash** for the whole bundle, so
the next page load fetches the new code rather than a cached
copy — which is also why you have to **reload** to see a change:
the file already running in your tab is the old one.

`window.BUILD_HASH` and `GET /version` report the effective
frontend hash, so you can tell a stale tab from a stale deploy
(see [DEPLOYMENT.md](../DEPLOYMENT.md) § `bb verify`).

## The syntax gate

A JS file with a syntax error would break the **whole**
concatenated bundle on the next load — including the Assets panel
you would need in order to fix it. So a JS override is parsed
before it is written, and a broken one is refused with the parse
error instead of being saved. CSS is not gated the same way: a
malformed rule degrades to "that rule doesn't apply", not to a
dead page.

## Try it: restyle the editor, then put it back

1. **Organization → Assets**, find
   `packages/app/editor/editor-styles.css`, click `edit`.
2. Scroll to the end and append a rule you will notice — for
   example:

   ```css
   /* my override */
   .gd-tour-title { letter-spacing: 0.08em; }
   ```

3. **Save override**. The row's chip flips from `baseline` to
   `override`.
4. **Reload the page** — the `?v=` hash rolled, so the new CSS is
   what loads. Open any tour step: the title is now letter-spaced.
5. Click `edit` again and use **Diff vs baseline** to see exactly
   what you added.
6. **Revert to baseline**, reload once more — the shipped file is
   back and the chip reads `baseline` again.

## When to reach for this

Good uses: a house style (colours, density, fonts) for your
deployment; a small affordance your team wants and upstream
doesn't have; a quick instrumentation patch while you diagnose
something.

Bad uses: anything you would rather send upstream (an override
silently diverges and you maintain it forever), and anything
security-relevant — the frontend is not where a rule belongs, and
the server enforces its own regardless.

## Recap

- An override is a per-branch DB row shadowing one shipped
  frontend file; `Revert to baseline` deletes it.
- Saving rolls the `?v=` hash; reload to run the edited code.
- JS overrides are syntax-gated so a typo can't take the editor
  (and the panel) down; CSS isn't.
- Single-tenant only: on the multi-tenant cloud the panel is
  hidden and its writes are platform-only.

## Next

That is the last of the platform lessons. The
[end-to-end worked example](../TUTORIAL_API_POLL.md) builds a real
integration out of everything you have seen.
