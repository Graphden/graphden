# Graphden Package and Embedding Exception

*An additional permission under section 7 of the GNU Affero General Public
License, version 3 (AGPL-3.0), granted by the copyright holder of Graphden.*

Copyright (c) 2026 Artem Markov

## 1. Purpose

Graphden is licensed under the AGPL-3.0 (see [LICENSE](LICENSE)). Its
copyleft is meant to keep the **core** open: anyone who modifies Graphden
and runs it as a network service must publish those modifications.

It is **not** meant to reach into the things people build *with* Graphden.
Package authors wrap third-party APIs, and embedders drop a Graphden-generated
form into their own website. Neither should have to license their own work
under the AGPL just because it runs inside, or next to, Graphden. This
exception says so explicitly, in the spirit of the GNU Classpath Exception.

## 2. Definitions

- **"Graphden"** means the Program covered by the AGPL-3.0 in this
  repository: the executor, storage and schema layers, the editor, and the
  first-party packages shipped under `resources/packages/`.
- **"Package"** means a set of files — a `package.edn` manifest, `fns.edn`
  declarations, `impls.clj` implementations and their resources — that is
  loaded into a Graphden instance **through Graphden's documented package
  interface** (`docs/PACKAGES.md`, `docs/PACKAGE_DISTRIBUTION.md`) and that
  is not itself derived from Graphden's source code. Calling Graphden's
  runtime API from a Package's base-function implementations does not make
  the Package derived from Graphden.
- **"Graph"** means function definitions, bindings, and other content that a
  user authors in a Graphden instance. Graphs are the user's own data and
  are not covered by the AGPL in any case; they are named here only to
  remove doubt.
- **"Embedded Surface"** means the HTML, JavaScript, JSON, or other output
  that a Graphden instance generates from a Graph for inclusion in a web
  page or application that is not itself Graphden — including the
  standalone runtime asset Graphden serves for that purpose.
- **"Independent Work"** means a Package, a Graph, or a web page or
  application that includes an Embedded Surface.

## 3. Additional permission

As a special exception to the AGPL-3.0, the copyright holder gives you
permission to combine Graphden with Independent Works, regardless of the
license terms of those Independent Works, and to convey or run the
resulting combination — including as a network service — **without** the
Independent Works becoming covered works under the AGPL-3.0. In particular:

1. You may license a Package under any terms you choose, including
   proprietary terms. Its license does not extend to Graphden, and
   Graphden's license does not extend to it.
2. You may include an Embedded Surface, and the runtime asset it depends
   on, in a web page or application under any terms you choose. The runtime
   asset itself remains covered by the AGPL-3.0; you must keep its license
   and copyright notices intact, and you may not remove them from the
   served file.
3. This permission does not relieve you of the AGPL-3.0's obligations for
   **Graphden itself**: if you modify Graphden and convey it or run it as a
   network service, you must make the corresponding source of Graphden and
   of your modifications available as the AGPL-3.0 requires.

## 4. What is not an Independent Work

The permission in section 3 applies only to genuinely independent works.
The following are **not** Packages or Independent Works for the purposes of
this exception, and remain subject to the AGPL-3.0 in full:

1. Modifications to Graphden's own source: anything under `src/`, the
   editor frontend, the build, or the first-party packages shipped in this
   repository.
2. Implementations of, or decorators over, Graphden's internal protocols and
   extension seams, including the storage protocols (`StorageCRUD`,
   `ExecutionGraph`, `GraphConstraints`) and any storage decorator placed
   in the storage chain, the data-schema protocols, and the executor,
   compile pipeline, registry, and branch router.
3. Code that fills Graphden's deployment and policy seams: Integrant
   init-keys under `:exec/*`, `:app/*`, or `:tenancy/*`, addons loaded
   through `:graphden/require`, and implementations of the request-scope,
   execute-guard, grant-store, user-ops, auth-provider, app-router,
   effect-gate, or tenancy-context seams described in
   `docs/TENANCY_SEAM.md`. A multi-tenant, billing, or control-plane layer
   built on those seams is a work based on Graphden, whatever its file
   layout.
4. A work that reproduces a substantial portion of Graphden's code, or
   that is labelled a "Package" while functioning as one of the above.

## 5. Scope

This exception is granted for the versions of Graphden that the copyright
holder distributes with this file. Under AGPL-3.0 section 7, anyone who
conveys a modified version of Graphden may remove this additional
permission from their version, but may not narrow the AGPL-3.0 itself.

If you are unsure whether your work is an Independent Work under this
exception, ask before relying on it: legal@graphden.dev.
