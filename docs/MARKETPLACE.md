# MARKETPLACE.md — discovery over the registry: packages, themes, keymaps

> The decision-fixed reference for the Marketplace surface and everything it
> lists. The registry it sits on (publish / install / fork / update, the
> org-scoped visibility model) is [PACKAGE_DISTRIBUTION.md](PACKAGE_DISTRIBUTION.md);
> this document adds what a LISTING needs on top — metadata, ratings, install
> counts — and the two non-fns kinds the same artifact row now carries:
> editor **themes** and **keyboard layouts**.

## 1. What it is, in one screen

The **Marketplace** is a surface (`Space v m`, the account menu, or "Browse
marketplace →" on the Build packages chip) with four tabs:

| Tab | Rows | Action per version |
|---|---|---|
| **Packages** | fns-only packages published from a namespace (⬆ on a namespace row, or `POST /api/packages/publish`) | Install (by reference, a per-branch pin) · Fork (copy-on-write) |
| **Themes** | editor themes saved from Settings → Appearance | Apply (becomes the current user's active theme) |
| **Keymaps** | keyboard layouts saved from Settings → Keyboard | Apply (becomes the current user's active layout) |
| **Executor** | the packages THIS executor loaded at boot — bundled or operator-listed impl+fns packages | none: read-only roster |

Every listing card carries the publisher's **description**, one **category**
from a per-kind vocabulary, up to ten **tags**, the **latest** version and
the count of versions, the **rating** (average + number of reviews) and the
**install count**. Search matches name + description + tags; a tag chip
filters; sort is by rating / installs / recency / name. Opening a card shows
every version with its action, the reviews, and the review form (one review
per author per package — posting again updates it).

**Why one marketplace and not three.** A theme and a keymap are, like a
fns package, a versioned, immutable, org-scoped artifact someone publishes
and someone else applies. Splitting them into separate resources would have
meant three publish paths, three visibility models, three review tables.
They share one row and differ by `:kind` (principle #2, minimal entities);
the surface separates them by TAB because the *actions* differ, not the
model. Impl+fns (Type-2) packages are NOT a marketplace kind: they are code
that enters through the build (`executor-packages.edn`), so the Executor tab
lists what this instance runs and says how such a package is added — it
never offers to install one.

## 2. Data model

One artifact entity, three companions (`src/graphden/schema/packages/schema.clj`):

| Entity | Role | Tenancy classification |
|---|---|---|
| `:package-version` + `kind` / `description` / `category` / `tags` / `payload` / `origin` / `publisher-id` | the artifact. `kind` nil or `"fns"` = a fn-def package (content in `:fns`); `"theme"` / `"keymap"` = the `:payload` IS the artifact (`:fns []`, `:ns-root ""`). `origin` — on a MIRRORED copy, the read-only snapshot of the origin registry's signals `{:url :rating :installs :version-count :as-of}`; nil = published here. `publisher-id` — `current-user-id` at publish, who the moderation mail goes to (never an authz key) | org-scoped, `public?` opt-in (unchanged); `UNIQUE (name, version)` |
| `:package-review` `(package-name, rating 1–5, body, author-id, author-label, public?, timestamps)` | one review per author per package; the aggregate is computed in the graph | org-scoped + the `public?` RLS arm (a review is as visible as its package); **author-owned writes** — the addon stamps `author-id` and refuses another member's edit / delete |
| `:package-stat` `(package-name, installs)` | the cumulative install counter — GLOBAL by design: an installer in org B cannot write org A's artifact row, and per-org pins are invisible across orgs | no org; **platform-write-only** (`system-write-entities`) — bumped by the pin write itself around the decorator |
| `:ui-pref` `(owner-id, key, value)` | the current user's active `theme` / `keymap`: `{:source {:name :version} :payload …}` — the payload is COPIED so a withdrawn version leaves the editor as it was | **owner-scoped**: read and written as the current user only; no org axis, a preference follows the person |

Install count = a NEW pin (`upsert-pin!`, `registry/impls.clj`) bumps the
row with an atomic `INSERT … ON CONFLICT DO UPDATE` over the pool — the
usage-stat bump's shape. Moving a pin (update / rollback) is not an install.
The bump is part of the pin write unit, not a separate graph step: a
graph-side "was it pinned?" probe is an effectful read a later ref would
re-run AFTER the pin ([ADR-thunk-once](adr/ADR-thunk-once-and-cache-keys.md)).

The identity behind `owner-id` / `author-id` is `tenancy.context/current-user-id`
— the accounts principal's `:user-id`, or `"anonymous"` on a deployment
without per-user identity. `current-user-label` (display name → email local
part → org → `anonymous`) is what a review is signed with; the whole email
is never shown.

### Names

A package name is **registry-wide once it is public**, the way a pypi.org
project name is: the first org to list a name publicly (any moderation
status — a pending listing already claims it) holds it, and every other
org's publish under that name — public *or* private — is refused
`name-taken` with the `holder` org named, so no catalog ever shows two
orgs under one card. Private names are per org: two orgs may each keep a
private `utils`, and a private name is no claim — someone else may list it
publicly later. When that happens the private holder's **own rows shadow**
the foreign ones under that name (`:_mkg-shadowed`: the card and the item
show its own versions only; an org with no rows of its own sees the public
package as usual), so a catalog still shows one org per card. The check is
`foreign-public-holder` inside `publish-package-apply`
(`registry/impls.clj`), adjacent to the insert like the version check;
under row-level security a tenant sees exactly the other orgs' public rows,
which is the set that matters. The whole check-then-insert runs under a
per-name **advisory lock** (`registry-shared/with-package-name-lock`, a
session lock on a borrowed connection, taken with `pg_try_advisory_lock`
in a short retry loop so a waiter never parks a pool connection —
cluster-wide, so two executors count too): two orgs racing for the same
public name cannot both pass the holder check, which the `(name, version)`
key alone would not prevent for different versions. A name held for the
whole retry window (~5 s) answers `:packages/name-busy`. `(name, version)` is **`UNIQUE` at the DB,
registry-wide** (applied to an existing database by the migration pass); the
publish path keeps its friendly pre-check and answers a constraint
violation the same way (`version-exists`), so a race or another org's
private row — invisible to an org-scoped read — never becomes a 500.

*Why the key is global and not per org (decided 2026-09-09).* A per-org
key `(org, name, version)` would let two orgs hold the same `(name,
version)` privately — and then the shadowing above cannot save every
reader: install, apply, the version resolver and the mirror all address a
row by `(name, version)`, and a caller who holds a private `utils 1.0.0`
while another org lists a public `utils 1.0.0` would see two rows for one
address. The global key makes `(name, version)` a true identity for every
reader. Its price is a small existence oracle: publishing a `(name,
version)` another org holds privately answers `version-exists`, which
reveals that the pair exists somewhere (not who holds it, not what it is).
That is the same class of disclosure as a taken username, and it is
accepted.

## 3. Listing vocabulary

`app.marketplace/:listing-categories` (a graph const, served at
`GET /api/marketplace/categories` for the publish forms):

| kind | categories |
|---|---|
| fns | web · data · integrations · utilities · ai · devops · examples · other |
| theme | light · dark · high-contrast · colorful · minimal · other |
| keymap | vim-like · emacs-like · ide-like · accessibility · other |

`:listing-normalize` is the one validator every publish path shares: kind
defaults to `fns`; a category outside the kind's list is refused
(`bad-category`); tags come in as a comma string, are lower-cased, reduced
to `[a-z0-9-]`, deduplicated and capped at ten; the description is trimmed
and clipped at 2000 characters.

## 4. Routes

Registry package — four modules sharing the `app.marketplace` namespace
(the `app.editor` precedent): `registry/marketplace/fns.edn` (the API
layer: vocabulary, normaliser, the base-fn declarations, cards, filters,
the JSON routes; its base-fns' impls in `marketplace/impls.clj`),
`marketplace-surface` (the editor partials + actions), `marketplace-storefront`
(the anonymous catalog bodies) and `marketplace-moderation` (the operator's
queue). Served per-branch like the rest of `/api/packages/*`; all
auth-required:

| Route | Purpose |
|---|---|
| `GET /api/marketplace?kind=&q=&category=&tag=&sort=&mine=1` | the cards as JSON — what Settings reads for "my saved themes"; `kind=any` spans every kind (what a remote mirror asks) |
| `GET /api/marketplace/categories` | the vocabulary |
| `POST /api/marketplace/publish` | a THEME or KEYMAP version: `{kind, name, version, description?, category?, tags?, public?, payload}` — refuses `unsupported-kind` (fns publish from a namespace), `missing-name` / `missing-version` / `missing-payload`, `bad-category`, `version-exists`, `name-taken` (§ 2 Names); capability-gated like every publish. The share dialog words each code (`editor-marketplace.js`) |
| `POST /api/marketplace/install?name=&version=[&fork=1]` | install (or fork) a fns package; answers the item HTML |
| `POST /api/marketplace/apply?name=&version=` | make a theme / keymap the user's active one (writes the `theme` / `keymap` preference); answers the item HTML |
| `POST /api/marketplace/review` (form `name`, `rating`, `body`) · `DELETE /api/marketplace/unreview?name=` | write / update / delete the current user's review; answer the item HTML. Refused on a mirror (the origin is the authority) |
| `POST /api/marketplace/listing` (form `name`, `description`, `category`, `tags`) | edit the NEWEST version's listing without a new version — own packages published here; the artifact is untouched, the card shows the latest version's listing |
| `GET /partials/marketplace` · `GET /partials/marketplace/item?name=` | the two surface partials |

`POST /api/packages/publish` (namespace publish) accepts the same
`description` / `category` / `tags` fields and answers the same
`name-taken`; the ⬆ popover on a namespace row offers them and words the
refusal.

app-base package (`app-base/prefs/fns.edn`, works without the registry):

| Route | Purpose |
|---|---|
| `GET /api/prefs` | the current user's preferences `{"theme": …, "keymap": …}` |
| `PUT /api/prefs/:key` body `{value}` | store one (`theme` / `keymap`; 400 `unknown-key`, 413 `too-large` over 64 KB) |

HTMX contract of the partials: every root is `div[data-marketplace]` with
`hx-target="closest [data-marketplace]" hx-swap="outerHTML"`, which htmx
inherits — a tab, a card, a tag chip or an action button names only its URL
and every response is the next whole root. `editor-marketplace.js` mounts
the first partial and, after every swap, re-pulls `/api/prefs` so an Apply
takes effect at once — and mirrors the open item into the URL
(`#@marketplace/<name>`, `#@marketplace` on the listing), so a reload or a
copied link lands on the same package.

## 5. Themes

A theme payload (`editor-prefs.js`):

```json
{"mode": "dark",
 "tokens": {"--gd-paper": "#101214", "--gd-ink": "#e6e9e8"},
 "fonts": {"ui": "Inter, sans-serif", "mono": "JetBrains Mono", "body": "Inconsolata"},
 "scale": 110}
```

- `tokens` — the allow-listed custom properties `gdThemeTokens` names (the
  `--gd-*` design system: grounds, ink ramp, lines, the accent, the binding
  chips, status colours; plus the legacy canvas tokens `--bg`, `--fg`,
  `--card-*`, `--sidebar-bg`, `--header-*`). A value must be a colour literal
  (`#hex`, `rgb()`, `hsl()`); anything else — a `url()`, a second
  declaration — is dropped by `gdSanitizeThemePayload`, because a shared
  theme is data from another user.
- `fonts` — `ui` → `--gd-ui-font`, `mono` → `--gd-mono` (and the legacy
  `--mono`), `body` → `--gd-body-font` (the canvas face; the token this
  feature introduced so the three hard-coded `'SF Mono'` stacks became one
  variable).
- `scale` — 70…160, applied as the root font-size percentage (the whole
  stylesheet is rem-based).
- `mode` — which base the tokens sit on; applying a theme sets
  `body.theme-dark` accordingly, and the Settings light/dark toggle writes
  the mode back into an active custom theme.

Applied as INLINE custom properties on `<body>` — inline beats
`body.theme-dark { … }`, so the theme wins in either base and clears cleanly
(`gdApplyThemePayload(null)`). The mirror in `localStorage`
(`graphden.prefs.server`) is applied before first paint; the server copy
reconciles right after boot.

**Settings → Appearance** (`editor-theme.js`): the built-in light / dark
toggle as before; *Customize…* opens the token editor (colour swatches by
group, the three font stacks, the size slider, an ink-on-paper contrast
readout that flags anything under WCAG AA 4.5:1) — every change applies live
and is saved to the preference, debounced; *Saved themes* lists the user's
own versions plus whatever was applied from the marketplace (picking an
older version of the same theme IS the roll-back); *Save / share…* publishes
the working copy as a theme version — private, or **Public** to list it;
*Reset to built-in* clears the preference. Without the registry package the
editor still persists edits per user; saving and sharing are hidden.

## 6. Keymaps

`editor-shortcuts.js` remembers each binding's DECLARED keys / leader the
first time it registers (`_defaults`) and consults the active layout's
overrides (`_overrides`) on every registration, so a module that registers
late (the canvas's `g g`) still lands on the user's keys.
`gdApplyKeymap({id: {keys, leader}})` re-resolves every binding; `null`
restores the defaults; `gdShortcutEntries()` is the raw list (with defaults
and the `when` verdict) Settings renders from.

**Settings → Keyboard** (`editor-keymap.js`): every registered binding as a
table (group, action, keys, *Change*, a "Space" checkbox for behind-the-
leader vs bare, × back to default). *Change* records the next sequence (up to
three keys, Enter keeps, Esc cancels, Backspace edits). Two bindings on the
same keys are flagged as a conflict. Only the REGISTRY's bindings are
rebindable — the canvas arrows / `h j k l`, the Explorer tree keys and the
dialogs' Escape / Enter are the platform's ([ACCESSIBILITY.md](ACCESSIBILITY.md)).
The keymap payload stores overrides only: `{"bindings": {"graph-fit": {"keys": "z z", "leader": true}}}`.
Save / share / roll-back / reset mirror the theme pane.

## 7. Reviews live where the package was published

The PyPI rule: a package has ONE authority for its social signals — the
registry it was published on. A self-hosted graphden reviews and counts
its own packages locally; when it **mirrors** a version from another
registry (the remote-install form, [PACKAGE_DISTRIBUTION.md § 13](PACKAGE_DISTRIBUTION.md#13-self-hosted-install-by-package-type)),
the install worklist also snapshots the origin's marketplace card
(`:remote-package-card` → `GET <origin>/api/marketplace?q=<name>&kind=any`)
into the mirrored row's `:origin` — url, rating, install count, version
count, and when the snapshot was taken. From then on:

- the mirror's card and item show the ORIGIN's rating and installs, marked
  `mirror of <url>` and "as of <date>"; local reviews (and the local
  install counter) never count for it;
- the item links to the origin instead of offering a review form, and
  `POST /api/marketplace/review` refuses a mirror;
- nothing flows back — a mirror is read-only about the package, like PyPI
  showing GitHub's stars without letting you star there.

A remote without a marketplace (an older graphden) still mirrors: the
`:origin` then carries the url only, and the card shows no rating.

## 8. Moderation of public listings

On a deployment that sets `GRAPHDEN_MARKETPLACE_MODERATION=1` (the cloud;
declared as the public deploy setting `:marketplace-moderation`), a
TENANT's public opt-in does not reach the shared catalog by itself:

| who publishes | `public?` | `:status` at publish | who sees it |
|---|---|---|---|
| a tenant, private | false | `approved` | its org |
| a tenant, Public ticked | true | **`pending`** | its org (badge *pending review*) + the platform-admin |
| the platform / single-tenant | true | `approved` | everyone |
| any, moderation off | as published | `approved` | as before |

The operator decides from **Platform → Moderation** (`GET
/partials/moderation-queue`, a cross-org read gated on the platform-admin
right at the base-fn `:moderation-queue`): **Approve** lists it, **Reject**
(with a note) keeps it the org's own — the publisher's card wears
*rejected* and the item shows the note; a corrected next version starts a
new review. `POST /api/marketplace/moderate` is the decision route
(`:moderate-package-version!`, platform-admin only); `GET
/api/marketplace/moderation` the queue as JSON.

**Both sides are told.** Two events go through core's notification seam
(`tenancy.context/notify!`,
[TENANCY_SEAM.md § Notifications](TENANCY_SEAM.md#notifications)), and the
tenancy addon's `:tenancy/notifications` sink turns each into mail through
the accounts Mailer, the same way invites and the inactivity warning go
out:

- `:package-submitted` — a tenant's public opt-in landed `pending`; every
  **platform-admin** (the `platform-admin` grant holders) is told the queue
  has work, with a link to the Platform surface.
- `:package-moderated` — the decision, with the updated row; the
  **publisher** (`publisher-id`, the org's owner when that resolves to no
  email) gets the verdict, the moderator's note on a rejection, and the
  editor deep link.

The bodies are graph (`tenancy-admin.mail/package-submitted-email`,
`package-moderation-decision-email`, each with a byte-identical Clojure
fallback pinned by the parity test); without a mailer, a trusted origin or
a recipient the sink answers a reason and the write stands.

The decision's write runs in the **row's org scope** (`tc/with-org` around
the update): row level security lets an org update only its own rows, and
the operator's decision is an action on that org's row — the platform-admin
gate is the authorization, the scope is how the write reaches the table.
A write that lands on no row throws `:moderation/not-applied` instead of
reporting a decision that never happened (the addon's
`marketplace_moderation_test` found exactly that silent loss).

Enforcement lives in the tenancy decorator's `visible?`: the `public?`
arm counts for OTHER orgs only when `:status` is nil / `approved` (or the
reader is the platform-admin). Row-level security keeps `"public?" IS
TRUE` unchanged — a pending row is public by the publisher's intent, not
a secret; moderation guards the catalog, and the app layer is where the
catalog is read. Pre-moderation rows (nil status) read as approved.

## 9. The anonymous storefront (cloud)

`https://graphden.dev/marketplace` — the catalog without a login, the way
pypi.org shows a project to anyone: kind tabs, search, category / tag /
sort, one page per package (`/marketplace/<name>`: description, tags,
rating, install count, every version, the reviews read-only) and a single
action, **Sign in to install / apply**, which lands in the editor on that
package (`app.graphden.dev/#@marketplace/<name>`). `robots.txt` and
`sitemap.xml` (the landing, the tutorial index, every public package)
make it indexable.

The bodies are graph in the registry package (`:storefront-body`,
`:storefront-item-body`, `:storefront-sitemap-urls`); the cloud's landing
app (`graphden-cloud`, the apex host) wraps them in its page chrome and
declares the bare `:get-route`s. Two rules keep an anonymous page safe:

- The landing runs in the PLATFORM ctx (no principal, every row visible),
  so the storefront filters the index itself to `public? true` AND
  `status approved` (`:storefront-rows`) — a pending or private package
  never renders, and a package page for one answers 404.
- Nothing mutates: plain anchors and a GET search form, no htmx, no
  review form — reviews and installs happen in the editor, signed in.

Every storefront response (the pages, the 404, the sitemap) carries
`Cache-Control: public, max-age=60` — login-less and identical for every
visitor, so the CDN and the browser may hold one a minute; a decision or a
new publish shows within that.

A self-hosted instance keeps its marketplace behind sign-in; the
storefront is the cloud's control-plane page.

## 10. Security notes

- **Reviews cannot be forged.** The generic entity route (`/api/entities/…`)
  reaches `:package-review` too; the tenancy decorator's
  `author-owned-entities` stamps the author on create and refuses a
  foreign update / delete, so the graph's review handler and a raw CRUD call
  meet the same wall.
- **Install counts cannot be set.** `:package-stat` is `system-write` under
  tenancy; the only writer is the pin's raw bump.
- **Preferences are private.** `:ui-pref` is `owner-scoped`: reads are
  filtered to the current user, the owner is stamped, and a foreign row is
  neither readable nor writable — across members of one org and across orgs.
- **A theme is sanitised on apply, not trusted on publish.** The payload is
  stored as published (the graph does not know CSS); the editor's allow-list
  is the boundary at every apply, including the first-paint mirror.
- **Every schema entity is classified.** The addon's
  `entity-classification-test` enumerates the built schema and fails on an
  entity that is in none of the classification sets — a new core entity can
  no longer be global by omission (the gap that this feature's three new
  entities made visible).

## 11. What is deliberately not here

- **No listing history.** A listing edit rewrites the newest version's
  description / category / tags in place (the artifact itself is immutable);
  older versions keep what they were published with.
- **No review sync.** A mirror snapshots the origin's numbers once, at pull
  time (§ 7); it does not poll the origin, and nothing flows back.

## 12. Tests

- `test/graphden/packages/marketplace_test.clj` — the normaliser, the
  theme / keymap publish route, cards (semver-ordered versions, rating
  aggregate, filters, sort), reviews (upsert-in-place, delete, refusal),
  the install counter, apply → preference, the prefs routes, both partials,
  the roster, mirrors (origin signals, no local review), the listing edit,
  moderation (pending → approve / reject with a note, the queue's gate, the
  decision raised through the notification seam, a pending publish raising
  `:package-submitted`), the name rule (`name-taken` for a public or
  private publish under another org's public name; a private name is no
  claim; own rows shadow a foreign card of the same name), the publisher
  stamp and the DB's `(name, version)` key;
  `graphden-cloud` `landing_tutorial_e2e_test` — the storefront pages
  (public rows only, the item page, 404, robots, sitemap, the minute cache);
  `registry_test` — the remote pull snapshots the origin's card, the
  publish envelope's row-derived fields, `name-taken` on the namespace
  publish; `tenancy/context_test` — the notify seam; `graphden-tenancy`
  `notifications_test` (the publisher, else the owner, is mailed the
  decision; every platform-admin the submission; every no-op reason; the
  sink never throws) and `mail_parity_test` (graph templates ≡ Clojure
  copies).
- `tools/browser-test/edit-marketplace.test.js` — the surface, apply,
  review, the keymap, and the `#@marketplace/<name>` deep link;
  `tools/runtime-test/marketplace-shell.test.js` (refusal wording, the URL
  mirror) and `moderation-section.test.js` (the Platform section's gate —
  the section itself exists only with the tenancy addon, so it has no
  browser test on the single-tenant e2e stack). The addon-level flow — the
  real `platform-admin` grant, the org-cap seam, `visible?` + RLS hiding a
  pending row from a rival org, both notification events — is
  `graphden-tenancy` `integration/marketplace_moderation_test`.
- `tools/runtime-test/theme-payload.test.js` — the sanitiser and the apply /
  clear path; `tools/runtime-test/keymap.test.js` — overrides, late
  registration, reset, the which-key footer.
- `tools/browser-test/edit-marketplace.test.js` — the surface end to end:
  browse → apply → review → Settings → rebind → reload.
- `graphden-tenancy`: `storage_test` (author-owned reviews, owner-scoped
  preferences, platform-write-only counter) and `entity_classification_test`.
