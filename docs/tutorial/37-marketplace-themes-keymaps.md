# Lesson 37 — The Marketplace: themes, keyboard layouts, and what others published

**Goal**: by the end of this lesson you can make the editor look and
behave the way you want — your own colours, fonts and text size, your
own keys for the commands you use most — keep several versions of that
and roll back between them, and share it with everyone through the
**Marketplace**. You can also find, rate and review what other people
published there, packages included.

**Concepts introduced**: the **Marketplace** surface (`Space v m`)
and its four tabs, a **listing** (description / category / tags), a
**review** (one per author per package) and the **rating** it feeds,
the **install count**, an editor **theme** as a saved, versioned
artifact, a **keyboard layout** the same way, **Apply** vs Install,
and **preferences** (what stays yours across the instance).

## One marketplace, four tabs

[Lesson 29](29-distributing-packages.md) left you with a registry: a
namespace published as an immutable package version, installed by
reference. The registry is a table. The **Marketplace** is how you
*browse* it.

Open it from the account menu, press `Space` `v` `m`, or click
**Browse marketplace →** at the bottom of the Build surface's
**packages** chip. Four tabs:

| Tab | What is listed | What you can do with a version |
|---|---|---|
| **Packages** | fns-only packages published from a namespace | Install (a per-branch pin) or Fork (editable copies) — exactly Lesson 29's two doors |
| **Themes** | editor themes people saved from Settings → Appearance | Apply |
| **Keymaps** | keyboard layouts saved from Settings → Keyboard | Apply |
| **Executor** | the packages *this* executor loaded at boot | nothing — a read-only roster |

Each card shows the package's **description**, its **category**, its
**tags** (click one to filter by it), the **latest** version and how
many there are, the **★ rating** with the number of reviews, and the
**↓ install count**. The search box matches names, descriptions and
tags; the sort menu orders by rating, installs, recency or name.

> The **Executor** tab answers a question the other three cannot: *which
> packages ship base-fns here?* An impl+fns package (Lesson 28) is
> Clojure code, so it enters through the build — a self-hosted operator
> lists it in `executor-packages.edn` and rebuilds; on the shared cloud
> the platform decides the set. The tab lists them with their origin
> (`bundled` / `manifest`) so you know what your fns can build on. It is
> deliberately not an install surface.

On the cloud the public catalog is also a page anyone can read without
signing in — [graphden.dev/marketplace](https://graphden.dev/marketplace):
the same cards and package pages, with **Sign in to install** where the
buttons would be. A public, approved package of yours is listed there.

## A listing: describe what you publish

The ⬆ **publish** popover on a namespace row (Lesson 29) now has three
more fields: a **description**, a **category** picked from a short
list (for packages: web, data, integrations, utilities, ai, devops,
examples, other) and comma-separated **tags**. They are what your card
shows. The category list is fixed on purpose — a free-text category
would make the category filter useless — and tags are normalised
(lower-case, letters / digits / dashes, at most ten). The same fields
ride the JSON route:

```bash
curl -X POST http://localhost:9002/api/packages/publish \
  -H "Authorization: Bearer $AUTH_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"hello","version":"1.2.0","ns-root":"mycorp.hello",
       "description":"A greeting, the smallest package there is",
       "category":"examples","tags":"greeting, demo"}'
```

A category outside the vocabulary is refused (`bad-category`) before
anything is written.

**Names are first come, first served** once they are public, the way
project names are on pypi.org: if another organization already lists
`hello` publicly, your publish under that name — public or private — is
refused (`name-taken`, and the popover names the holder) so no catalog
ever shows two organizations under one card. A private name is yours
inside your organization only; someone else may list it publicly later.
Pick a name with your org or product in it (`mycorp.hello`) and this never
comes up.

On the cloud a **public** listing does not appear in the shared catalog
by itself: it waits for the operator's review (your card says *pending
review*), and you are emailed the decision — with the moderator's note if
it was declined. A corrected next version starts a new review.

## Reviews and the rating

Open a card. Under its versions is the **Reviews** list and a small
form: a rating from one to five stars and an optional note. Post it and
the card's ★ figure updates — the average over every review, with the
count in brackets. You get **one review per package**: posting again
*updates* yours (the button says so), and **Delete my review** takes it
back. A review is signed with your display name (or the part of your
email before the `@`; never the whole address) and is as visible as the
package it is on — public for a public package, in-org for a private
one. Nobody else can edit or delete it, not even through the raw entity
API: the server stamps the author.

What if the package came from *another* graphden? Lesson 29's
remote-install form pulls a version from, say, `graphden.dev` into your
own registry. That copy is a **mirror**: its card wears a `mirror of
<url>` badge, its ★ and ↓ are the *origin's* numbers as of the moment
you pulled it, and instead of a review form the item links you to the
origin — that is where the package's reviews live, the way PyPI shows a
project's GitHub stars without letting you star it on PyPI. Your own
registry reviews its own packages; nothing syncs either way.

Changed your mind about a description or a tag? On a package you
published here, the item shows a small **Listing** form (description,
category, tags) — **Save listing** rewrites the newest version's listing
in place, no new version needed; older versions keep theirs.

The **↓ install count** is cumulative: every *new* pin of the package on
any branch, in any organization, counts once. Moving an existing pin
(update, rollback) is not an install, and the counter cannot be set by
hand — it lives on a row only the platform writes.

> **On the cloud, Public means "after review".** A public listing from an
> organization waits for the operator's approval before other
> organizations see it; until then your card wears *pending review* and
> your own organization can already install or apply it. A rejection
> comes with a note on the package — publish a corrected version to try
> again. On a self-hosted instance nothing waits: you are the operator.

## Your own theme

Open **Settings → Appearance**. The light / dark toggle is where it
was. Below it, **Custom theme** — click **Customize…**:

- **Base** — light or dark, the stylesheet your colours sit on.
- **Text size** — a slider from 70 % to 160 %; the whole editor is
  measured in `rem`, so everything scales together.
- **UI font / Code font / Canvas font** — three font stacks.
- **Colours**, grouped: *Grounds* (paper, panels), *Ink* (text, four
  weights), *Lines*, *Accent* (the flow colour that marks edges and
  active things), *Bindings* (the literal / reference / free-arg chip
  colours), *Status*, and *Canvas* (the graph's own background, cards,
  Explorer and top bar).

Every change applies **live** and is remembered — it is *your*
preference, kept server-side per user, so it follows you to another
browser and, under an organization, to another org. A contrast readout
under the fonts tells you when your ink on your paper drops below
4.5 : 1, the WCAG AA floor for small text; the editor does not stop
you, but a theme you share will be used by people whose eyes are not
yours.

**Reset to built-in** clears it all.

## Save, share, roll back

A theme you like is worth keeping in more than a browser. **Save /
share…** opens a small dialog: a **name**, a **version** (the next
patch of your last save is suggested), the description / category /
tags of a listing, and a **Public** checkbox. Save publishes the theme
as a **theme package** — the same kind of immutable, versioned row a
namespace publish makes, just with the theme's tokens as its content
instead of fn-defs. Unchecked, it is private to you (in an
organization, to your org); checked, it is listed in the Marketplace's
**Themes** tab for everyone.

Now the **Saved themes** select above the editor lists every version
you saved, grouped by name — and picking an *older* one applies it.
That is the roll-back: versions are immutable, so the one you liked
last week is exactly where you left it. When you apply a theme from the
Marketplace instead (someone else's, or your own from another
instance), its name and version show up here too, and its tokens are
**copied** into your preference — if the publisher later withdraws the
version, your editor does not change.

Apply is not Install: a theme is not pinned to a branch and nothing is
materialised into the graph. It is a preference of *yours*.

## Your own keys

**Settings → Keyboard** lists every command the editor's shortcut
registry knows — the same list `Space` and `?` render — with its
current keys. Click **Change** on a row, press the keys you want (up to
three; `Enter` keeps them, `Esc` cancels), and the row shows the new
sequence with the default in brackets. The **Space** checkbox moves a
command between "behind the leader" and "a bare key"; **×** puts one
command back to its default. Two commands on the same keys are flagged
in red — the first registered wins, so change one.

The layout applies at once and is saved like the theme. **Save /
share…** publishes it as a **keymap package**; the Marketplace's
**Keymaps** tab lists public ones — apply one to try it, apply your own
saved version to go back. **Reset to defaults** clears everything.

What you cannot rebind: the canvas arrows / `h j k l`, the Explorer tree
keys, and `Escape` / `Enter` inside dialogs. Those are the editor's
navigation contract ([lesson 18](18-keyboard-and-accessibility.md)), not
commands, and a layout that moved `Escape` would break every dialog.

## Try it

1. **Settings → Appearance → Customize…**. Change *Paper* and *Flow*,
   drag *Text size* to 110 %. Watch the editor follow.
2. **Save / share…** — name it `my-board`, keep the suggested `1.0.0`,
   tick **Public**, save. The *Saved themes* select now lists
   `my-board@1.0.0`.
3. Change *Paper* again; save as `1.0.1` (suggested). Now pick
   `my-board@1.0.0` in the select — you rolled back.
4. Open the **Marketplace** (`Space` `v` `m`), **Themes** tab. Your
   `my-board` card is there with two versions. Open it, give it five
   stars and a note, post — the card shows ★ 5.0 (1).
5. **Settings → Keyboard**: on *Fit the graph in view*, click
   **Change**, press `f` `f`, `Enter`. Press `Space` `f` `f` on the
   Build surface — the graph fits. Reload the page; the binding is
   still yours.
6. **Reset to defaults** on the Keyboard pane, **Reset to built-in** on
   Appearance. Both preferences are cleared; the published `my-board`
   versions remain in the registry (withdraw them with the API from
   Lesson 29 if you want them gone).

## What we glossed over

- **The JSON side.** `GET /api/marketplace?kind=theme&mine=1` is the
  cards as JSON — what the Settings panes read; `POST
  /api/marketplace/publish` takes `{kind, name, version, payload, …}`
  for a theme or keymap; `GET /api/prefs` / `PUT /api/prefs/:key` are
  the preferences. [docs/MARKETPLACE.md](../MARKETPLACE.md) has the
  whole table, the payload shapes, and the safety rules (what a shared
  theme may and may not set).
- **Where the rows live.** A theme or keymap is a `:package-version`
  with a `kind`; reviews are `:package-review` rows; the counter is
  `:package-stat`; your active choices are `:ui-pref`. Under the
  tenancy addon each has an exact classification — org-scoped,
  author-owned, platform-write-only, owner-scoped — and a test that
  refuses any new entity without one.
- **Without the registry package.** Themes and keymaps still work and
  still persist per user; only *Save / share* and the Marketplace
  itself need the registry.

## Next

This is the last lesson of the chapter. The written docs continue where
the tutorial stops: [docs/MARKETPLACE.md](../MARKETPLACE.md) for the
marketplace, [docs/PACKAGE_DISTRIBUTION.md](../PACKAGE_DISTRIBUTION.md)
for everything else about packages.
