# Lesson 41 — The package lifecycle: both sides of a version

**Goal**: publish two versions as an author, install them as a consumer,
keep an old pin, update, roll back, and update again. You play both roles
on two branches of the same instance. The interactive lesson creates and
removes its own branches, graphs, pins, versions and empty namespaces.

This follows [Lesson 32](32-distributing-packages.md) for the individual
package actions. [Lesson 23](23-branches.md) and [Lesson 24](24-review.md)
cover contribution and review on this instance; the last section explains
how a contribution travels from another organization or registry.

## Two roles, two sibling branches

| Branch | Role | Holds |
|---|---|---|
| `tutorial-vendor` | Author | Source namespace `tutorial-greetings` |
| `tutorial-site` | Consumer | A pin on `tutorial-greet` and its own `welcome` graph |

Create both branches from `main`. Neither sees the other's later version
rows. Main stays unchanged. These branches belong to the lesson; do not
reuse an existing branch with either name.

## Publish the first version

1. Switch to `main`, open the branch chip, enter `tutorial-vendor`,
   and click **Create**.
2. Click **New namespace**, type `tutorial-greetings`, and press Enter.
   On its row click **+** → **New graph…**, name it `greet`, Enter.
3. On the new card choose **set parent…** → `core.logic.const`.
   On `:value`, **+** → **Bind literal**, enter `1`, **Save**.
4. Hover `tutorial-greetings`, click **⬆**, enter package name
   `tutorial-greet` and version `1.0.0`, then **Publish**. Keep the
   release private: both roles use your current organization.
5. Close the publish form with **×**.

A publication stores an immutable package version. Editing the source
later changes neither that version nor any consumer's pin.

## Install and build a consumer

1. Switch to `main`; create `tutorial-site` from it.
2. Open **packages** → **+ Install a package** and click **Install**
   on `tutorial-greet 1.0.0`. The branch gets a pin and the Explorer
   gains `tutorial-greetings@1-0-0`. Close the panel with **×**.
3. Create namespace `tutorial-shop` and graph `welcome` within it.
   Set its parent to `core.strings.to-str`.
4. On `:value`, **+** → **Bind fn-ref**, type `greet`, and choose
   `tutorial-greetings@1-0-0.greet`.
5. Select `tutorial-shop.welcome`, click **▶ Run** on its card, then
   **Run** in the Inspector. The displayed result is `1`, a string
   produced by `to-str`.

**Reference or inherit?** This consumer references the package graph
through a binding. A pin update rewrites its own references into the new
version's namespace. Had `greet` been its parent, the parent identity
would have stayed on that exact version. Use inheritance for an exact
parent; use a reference when the consumer should follow its pin.
See [the parent-set identity decision](../adr/ADR-parent-set-identity.md).

## Publish an edit, then follow it

1. Switch to `tutorial-vendor`; select `tutorial-greetings.greet`.
   Change its bound `:value` to `2`, **Save**.
2. Clear the Explorer filter if needed, hover the source namespace,
   click **⬆**, and publish `tutorial-greet 1.0.1`. Close the form.
3. Switch to `tutorial-site`; select `tutorial-shop.welcome` and run
   it again. It still returns `1`: publishing has not moved its pin.
4. Open **packages**. On the installed row enter `1.0.1` and click
   **↑**. Close the panel. Welcome's binding now points at
   `tutorial-greetings@1-0-1.greet`; run it and read `2`.
5. Enter `1.0.0` on that same installed row and click **↑**. Its
   reference returns to `tutorial-greetings@1-0-0.greet`; run it and
   read `1`. An update and a rollback use the same action.
6. Enter `1.0.1`, **↑**, and run once more: `2`.

A changed value with the same functions and argument types is compatible
with a patch release. Removing a function or narrowing an argument needs
a major release; the publish action checks compatibility before saving.
The version box also accepts `latest` and semver constraints, as explained
in [Lesson 32](32-distributing-packages.md).

The lesson checks three things separately: the saved branch pin, the
actual binding's referenced function UUID, and the current run's result.
A typed version, another graph called `greet`, or an old result alone
cannot complete these steps.

## Finish and retry safely

Return to `main`, click **Finish**, then **Delete created entities**.
Cleanup first uninstalls this lesson's exact pin in `tutorial-site`, then
removes the sibling branches, its exact published versions, and its empty
namespaces. The lesson has no merge dependencies, so neither branch needs
to be archived. Deleting a branch alone does not remove its registry pin.

Creation receipts are saved before branch reloads. If a publish or install
reply is lost, the lesson retains that pending item in **Lessons** for
review and retry. It does not claim an existing version or namespace by
name, and it does not withdraw other versions of the same package.
**Keep** ends cleanup tracking and preserves your work deliberately.

On a real project, withdrawal removes a published version only when no
branch pins it; otherwise the action returns `still-installed`. The
organization's package install audit shows which branches still use it.
Uninstalling a pin does not itself withdraw the published version.

## Contributing a fix

The interactive loop uses your own source. To fix a teammate's package
on the same instance, branch from its source, compare the change, propose
it and review it as in Lessons 23 and 24. Merging the source change still
does not publish a new immutable version or move a consumer's pin.

If the source belongs to another organization or another Graphden, there
is no author branch for you to edit. **Fork** copies a published version
into your graph at its original namespace and writes no pin. Edit that
copy and point your own binding at it. Fork refuses a namespace owned by
a built-in package; that source must change on disk, as in Lesson 31.

Sending the fix back depends on access to the author:

| Author | Contribution |
|---|---|
| Teammate on this instance | Source branch and review, Lessons 23–24 |
| Reachable hub | Owner-stamped branch push and compare, [Lesson 34](34-offline-and-push.md) |
| Someone who can receive a file | An EDN bundle applied to a fresh review branch |

For example, the author can import a received bundle into a new branch:

```bash
curl -X POST "http://localhost:9002/api/import/graph?target=contrib/greet&create=true" \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -H "Content-Type: application/edn" \
  --data-binary '{:fns [{:name :greet :namespace "tutorial-greetings"
                         :parent :const :args {:value 2}}]}'
```

Inspect the import's `skipped-owned` and `adopted` results, compare, and
review before merging. Import reports attempts to overwrite built-in
package functions instead of silently taking ownership of them. The
interactive lesson does not simulate a remote registry or a hub push.

## The same version loop over HTTP

Set `X-Graphden-Branch` to the author or consumer branch for each request.
Publish, install, and update accept JSON bodies.

| Action | Request |
|---|---|
| Publish | `POST /api/packages/publish` with `{"name":"tutorial-greet","version":"1.0.0","ns-root":"tutorial-greetings"}` |
| Install | `POST /api/packages/install` with `{"name":"tutorial-greet","version":"1.0.0"}` |
| Update or rollback | `POST /api/packages/update` with the package name and target version |
| Uninstall | `DELETE /api/packages/uninstall?name=tutorial-greet` |
| Withdraw one version | `DELETE /api/packages/withdraw?name=tutorial-greet&version=1.0.0` |

Install and update return the saved pin, version UUID/hash, and namespaces
created by that materialization. A repeated operation does not claim
existing namespaces. Uninstall and withdrawal optionally take an
`expected-id` query parameter: an identity mismatch refuses the operation
before removing anything. This protects cleanup from a same-name replacement.

## When the roles belong to different parties

On the same cloud, another organization sees only a release published
**Public**. Its consumer cannot edit your source, so it uses a fork to
prepare a contribution. Another Graphden can pull a version through the
remote-install form; its contribution travels as a hub push or EDN bundle.
These are real external operations described in Lessons 31 and 34, not
additional local fixtures created by this walkthrough.

Package dependencies must be present before installation. A package that
reads secrets carries the required paths; the consumer defines them in its
own environment, as in [Lesson 16](16-effects-and-secrets.md). For ratings,
metadata and install counts, see [Lesson 40](40-marketplace-themes-keymaps.md).

## Next

[Lesson 42 — AI clients and API tokens](42-ai-clients-and-api-tokens.md)
connects a coding client to the graph on a branch of its own.
[Package distribution](../PACKAGE_DISTRIBUTION.md) documents the registry
and its API in more detail.
