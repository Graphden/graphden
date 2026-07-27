(ns graphden.system.core
  "Loader for the Integrant init-key implementations of all system
   components.

   The implementations were split by concern into the sibling namespaces
   below; this ns `:require`s each one purely for its `defmethod` side
   effects, so every `ig/init-key` / `ig/halt-key!` / `ig/suspend-key!`
   is registered before `graphden.system.interface` calls `ig/init`.
   Requiring `graphden.system.core` (as `interface` does) therefore still
   registers the whole system, exactly as when the defmethods lived here.

   Package→storage sync (NOT integrant wiring — it is package-domain
   logic) lives in `graphden.packages.sync`; the `:exec/base-fns` /
   `:exec/fn-entities` init-keys in `init.packages` are thin shells over
   it, and out-of-band callers reach it directly.

   | Concern                          | Namespace                         |
   |----------------------------------|-----------------------------------|
   | schema / storage / notify / SSE  | `graphden.system.init.storage`    |
   | package load + graph bootstrap   | `graphden.system.init.packages`   |
   | executor context + boot checks   | `graphden.system.init.exec`       |
   | service reconciler               | `graphden.system.init.services`   |
   | fleet placement controller       | `graphden.system.init.fleet`      |
   | execution cleanup scheduler      | `graphden.system.init.cleanup`    |
   | package→storage sync (non-wiring)| `graphden.packages.sync`          |

   Component dependency graph:
   :db/schema        → (pure function, no deps)
   :db/postgres      → [:db/schema]
   :db/versioned     → [:db/postgres]
   :app/packages     → (pure, loads package definitions)
   :exec/base-fns    → [:db/versioned, :app/packages]
   :exec/fn-entities → [:db/versioned, :exec/base-fns, :app/packages]
   :exec/context     → [:db/versioned]
   :exec/compiled-registry  → [:exec/context, :exec/fn-entities]
   :exec/service-reconciler → [:exec/context, :app/packages, :exec/compiled-registry]
   :exec/cleanup-scheduler  → [:exec/context]"
  (:require
    [graphden.system.init.cleanup]
    [graphden.system.init.exec]
    [graphden.system.init.fleet]
    [graphden.system.init.packages]
    [graphden.system.init.services]
    [graphden.system.init.storage]))
