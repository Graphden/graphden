# Lesson 25 — Editing the editor: personal UI graphs

**Goal**: create personal named graphs for the editor, change a shared theme
value and a local menu value, and change a menu keyboard decision. This works
on cloud and self-hosted deployments with ordinary graph permissions.

## Personal UI graphs

Sign in to use the Appearance graph catalog. Access to the lesson means the
interface is available; creation still checks namespace read, view-impl, write
and execute permissions and the current quota on the server.

Open **Settings → Appearance → UI graphs**, then **Create personal graphs**.
Choose an existing namespace where you can write and inspect the source. The
editor creates a fresh child with Theme, Menu and Picker groups and a `ui`
configuration. Its four function references select the menu's initial state,
update and view, and the picker view. Selecting it changes your own editor
preference; it does not select it for other users.

The group buttons open exact function identities. **Configuration** shows the
entry bindings, **Theme** the shared named values, **Menu behavior** the event
and state decisions, **Menu view** the rendered component and styles, and
**Picker view** the candidate presentation. Existing graph controls edit their
dependencies; a rename does not redirect a saved identity to another function.

1. Open **Theme** and follow its values to `theme-canvas-color`. Set its own
   `value` to `#fff7ed`. The copied color wrapper inherits the canonical color
   type, so the existing color form applies. This leaf feeds the canvas and
   the menu background.
2. Open **Menu view** and edit `account-menu-hover` to `#fed7aa`. This leaf has
   its own literal and local menu token; changing it leaves the canvas color
   unchanged. Open the menu to inspect the result.
3. Open **Menu behavior**, follow the event helpers to `account-menu-key-map`,
   and change the third `vals` item, corresponding to Home, from `first` to
   `last`. Open the menu and press Home: both focus and the graph's active
   index move to the last item.
4. Open **Picker view** to inspect its rows, groups and styles. Search,
   compatibility verdicts and selection remain native. The graph authors the
   presentation; it does not replace those decisions.

Changes to a named shared value affect all graphs referencing that value in
this bundle. A local leaf affects its dependent component. Ordinary branch
and ownership rules still apply when you edit a function with other consumers.

The component host keeps the supported operation and DOM contract, account
callbacks, search and navigation. Personal graph dependencies need source
access, and the selected configuration needs execution access. Unsupported,
effectful or unavailable graphs fall back to built-in controls with a reason.
No arbitrary JavaScript, HTML or source-file override is loaded by this flow.

The interactive lesson records the exact server-proposed identities before
creation. Cleanup removes only those functions and empty namespaces; it keeps
changed or inaccessible identities for review. It restores the previous
Appearance selections only while the current selections still point at the
lesson's deleted graphs. **Keep & close** retains the graphs and selection.

## Advanced self-hosted source files

**Organization → Source files** retains the legacy file override tool. It
changes the deployed editor source rather than a personal graph component.
This tool is available on self-hosted instances; shared cloud editor source is
platform-owned. Use UI graphs for the personal component exercises above.

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

Open **Organization → Source files**. Every servable frontend file is
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

Both are readable without DevTools: **Settings → About this build**
shows the frontend hash your browser is running next to the server's
per-section hashes, and its **Reload editor** button drops the cache
and fetches the fresh bundle — the reload step below, as one click.

## The syntax gate

A JS file with a syntax error would break the **whole**
concatenated bundle on the next load — including the Source files panel
you would need in order to fix it. So a JS override is parsed
before it is written, and a broken one is refused with the parse
error instead of being saved. CSS is not gated the same way: a
malformed rule degrades to "that rule doesn't apply", not to a
dead page.

## Try it: restyle the editor, then put it back

1. **Organization → Source files**, find
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

[Lesson 26 — Version history](26-version-history.md): every edit in
this tutorial appended a version row somewhere; the next lesson reads a
fn's timeline back and restores an earlier row.
