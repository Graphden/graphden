# Lesson 25 — Editing the editor: personal UI graphs

**Goal**: personalize the account menu with a small inherited graph, bind its
open theme input, and preserve shared definitions and other component identities.
This works on cloud and self-hosted deployments with ordinary graph permissions.

## Personal UI graphs

Sign in to use the Appearance graph catalog. Access to the lesson means the
interface is available; creation still checks namespace read, view-impl, write
and execute permissions and the current quota on the server.

Open **Settings → Appearance → UI graphs**. Normally use **Personalize account
menu**, **Personalize function picker**, **Personalize recent functions**,
**Personalize Inspector**, or **Personalize Explorer toolbar**. They create small
ordinary children inheriting the selected role definitions and a profile `editor`
graph merging their entries with shared defaults and your previous configuration.
Shared dependency bodies are retained by reference, not copied. Existing own
writable roles open for editing; a shared read-only custom role gets a new child.
Theme selection stays unchanged. Inheritance does not redirect deeply referenced
helpers: their identities remain shared until you explicitly replace a reference.

The lesson uses **Personalize account menu**. Its view inherits the shared menu
and exposes an unbound `theme` input. Binding that input on your own view changes
its theme tokens without copying or modifying the shared helpers.

The explicit **Create personal graphs** bundle remains an advanced compatibility
action. It copies Theme, Menu, Picker, Recents, Inspector and Explorer groups into
a namespace where you can write and inspect source. Unlike the smaller
Personalize actions, it also selects its copied theme.

The profile namespace comes from the deployment's personal-namespace authority.
Without tenancy there is one operator profile named `personal`, not anonymous
user isolation. Explicit setup materializes its namespace without creating an
extra editor function. An unavailable configured home is reported rather than
silently borrowing another user's namespace. See
[ordinary editor configuration](../UI_CONFIGURATION.md) for the source and
provisioning boundaries.

The group buttons open exact function identities. **Configuration** shows the
entry bindings, **Theme** the shared named values, **Menu behavior** the event
and state decisions, **Menu view** the rendered component and styles, and
**Picker view** the candidate presentation. Existing graph controls edit their
dependencies; a rename does not redirect a saved identity to another function.

The Inspector's header, kind label, four tabs, keyboard state and scoped styles
are ordinary `initial`, `update` and `view` graphs. Their finite requests ask the
editor to load a tab, focus its button or close the narrow sheet. Server-rendered
tab bodies, Run forms, comparison discussions and CodeMirror retain their native
owners; changing graph chrome does not replace those controls.

The Explorer's selected view also presents each loaded function row. Its typed
`surface` input selects the toolbar or row body, so an inherited Explorer view
can change marker Hiccup and scoped styles through the same graph editor. The
tree still owns function IDs, keyboard navigation, selection and action buttons.
Rows read only the display fields and status summaries already authorized for
that tree. Older personal Explorer views continue to control their toolbar;
the shipped row presentation appears with a section-specific diagnostic.

1. Choose **Personalize account menu**, then **Menu view** to open your own
   `account-menu-view` child.
2. On the root's free `theme` input, choose **+ → Bind literal**. Set `accent`
   to `#7c3aed` and `canvas-background` to `#f5f3ff`, then save the record.
3. Open the account menu and inspect the changed colors. The binding belongs
   to your view; the referenced shared helpers and the selected Theme preference
   remain unchanged. Already-filled ancestor bindings stay sealed.
4. Open **Picker view**. Its identity remains the same: personalizing the menu
   does not copy or replace unrelated components.

If you already have an own writable menu, Personalize opens it. The lesson
uses an inspection-only path, keeps its existing inputs and creates no deletion
receipt for that graph.

A free input captures a value through ordinary graph composition. To change a
filled shared helper, replace an exposed reference or explicitly create a local
path; inheritance alone does not redirect deeply referenced identities.

The component host keeps the supported operation and DOM contract, account
callbacks, search and navigation. Personal graph dependencies need source
access, and the selected configuration needs execution access. Unsupported,
effectful or unavailable graphs fall back to built-in controls with a reason.
No arbitrary JavaScript, HTML or source-file override is loaded by this flow.

The interactive lesson records exact server-proposed identities and original
source expectations before creation. These receipts never adopt later edits.
Finish shows a separate current-state deletion proposal, including intentional
lesson changes and the root to restore. Confirming it deletes exactly those new
identities and restores the previous editor root atomically. An edit after review
refuses cleanup and requires another review; uncertain replies retain their
original witnesses for read-only reconciliation. Multiple activations are reviewed
one at a time in reverse order. The full bundle's theme restoration is separate.
Profile infrastructure and retained prior roots are kept. **Keep & close** retains
the graphs and selection.

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
