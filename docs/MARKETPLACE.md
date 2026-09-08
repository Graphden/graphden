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
| `:package-version` + `kind` / `description` / `category` / `tags` / `payload` / `origin` | the artifact. `kind` nil or `"fns"` = a fn-def package (content in `:fns`); `"theme"` / `"keymap"` = the `:payload` IS the artifact (`:fns []`, `:ns-root ""`). `origin` — on a MIRRORED copy, the read-only snapshot of the origin registry's signals `{:url :rating :installs :version-count :as-of}`; nil = published here | org-scoped, `public?` opt-in (unchanged) |
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

Registry package (`registry/marketplace/fns.edn`, served per-branch like the
rest of `/api/packages/*`; all auth-required):

| Route | Purpose |
|---|---|
| `GET /api/marketplace?kind=&q=&category=&tag=&sort=&mine=1` | the cards as JSON — what Settings reads for "my saved themes"; `kind=any` spans every kind (what a remote mirror asks) |
| `GET /api/marketplace/categories` | the vocabulary |
| `POST /api/marketplace/publish` | a THEME or KEYMAP version: `{kind, name, version, description?, category?, tags?, public?, payload}` — refuses `unsupported-kind` (fns publish from a namespace), `missing-name` / `missing-version` / `missing-payload`, `bad-category`, `version-exists`; capability-gated like every publish |
| `POST /api/marketplace/install?name=&version=[&fork=1]` | install (or fork) a fns package; answers the item HTML |
| `POST /api/marketplace/apply?name=&version=` | make a theme / keymap the user's active one (writes the `theme` / `keymap` preference); answers the item HTML |
| `POST /api/marketplace/review` (form `name`, `rating`, `body`) · `DELETE /api/marketplace/unreview?name=` | write / update / delete the current user's review; answer the item HTML. Refused on a mirror (the origin is the authority) |
| `POST /api/marketplace/listing` (form `name`, `description`, `category`, `tags`) | edit the NEWEST version's listing without a new version — own packages published here; the artifact is untouched, the card shows the latest version's listing |
| `GET /partials/marketplace` · `GET /partials/marketplace/item?name=` | the two surface partials |

`POST /api/packages/publish` (namespace publish) accepts the same
`description` / `category` / `tags` fields; the ⬆ popover on a namespace row
offers them.

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
takes effect at once.

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

## 8. Security notes

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

## 9. What is deliberately not here

- **No anonymous storefront.** Every route is auth-required; a demo session
  browses the public rows like any tenant. A signed-out landing catalog is a
  cloud-control-plane page, if ever.
- **No moderation queue.** Unchanged from
  [PACKAGE_DISTRIBUTION.md § 9](PACKAGE_DISTRIBUTION.md#9-moderation--cloudself-hosted-export):
  a public listing is the publisher's call, and withdraw is the remedy.
- **No listing history.** A listing edit rewrites the newest version's
  description / category / tags in place (the artifact itself is immutable);
  older versions keep what they were published with.
- **No review sync.** A mirror snapshots the origin's numbers once, at pull
  time (§ 7); it does not poll the origin, and nothing flows back.

## 10. Tests

- `test/graphden/packages/marketplace_test.clj` — the normaliser, the
  theme / keymap publish route, cards (semver-ordered versions, rating
  aggregate, filters, sort), reviews (upsert-in-place, delete, refusal),
  the install counter, apply → preference, the prefs routes, both partials,
  the roster, mirrors (origin signals, no local review), the listing edit;
  `registry_test` — the remote pull snapshots the origin's card.
- `tools/runtime-test/theme-payload.test.js` — the sanitiser and the apply /
  clear path; `tools/runtime-test/keymap.test.js` — overrides, late
  registration, reset, the which-key footer.
- `tools/browser-test/edit-marketplace.test.js` — the surface end to end:
  browse → apply → review → Settings → rebind → reload.
- `graphden-tenancy`: `storage_test` (author-owned reviews, owner-scoped
  preferences, platform-write-only counter) and `entity_classification_test`.
