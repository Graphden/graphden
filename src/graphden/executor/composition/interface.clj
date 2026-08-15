(ns graphden.executor.composition.interface
  "Data-driven fn definitions and storage sync.

   This component provides a declarative way to define fn entities
   that compose base functions through the graph.

   ## Usage

   ```clojure
   (require '[graphden.executor.composition.interface :as fn-composition])

   ;; Define fns as data
   (def my-fns
     [{:name :router-handler
       :parent :default-router-handler}

      {:name :web-server
       :parent :http-server
       :args {:handler :router-handler
              :port 8080}}])

   ;; Sync to storage (after base-fns are synced)
   (fn-composition/sync-fns-to-storage! storage my-fns)
   ;; => {:router-handler #uuid \"...\"
   ;;     :web-server #uuid \"...\"}
   ```

   ## Definition Format

   Each fn-def is a map:
   - :name - keyword, unique name for this fn
   - :parent - keyword, name of parent base-fn (defines fn-schema)
   - :args - map of {arg-name -> value}
     - keyword value = reference to another fn by name
     - other values = literals

   ## Order Does Not Matter

   Definitions are topologically sorted before sync (`deps/topo-sort`)
   — file order is free; cycles are rejected."
  (:require
    [graphden.executor.composition.core :as core]))


(def ^:dynamic *sync-fns-override*
  "Parallel-test seam: when bound to a fn, `sync-fns-to-storage!`
   delegates to it (same args, any arity) instead of
   `core/sync-fns-to-storage!`. nil (production) = the real sync runs.
   Tests `binding` this instead of `with-redefs`-ing the root var — a
   root rebind is process-global and forced a `^:serial` pin on
   `graphden.system.core-test` (serial-reduction batch 4). Cost on the
   real path: one nil check per package sync — a boot / bootstrap-time
   path, never per-execute."
  nil)


(defn sync-fns-to-storage!
  "Syncs fn definitions to storage.

   Arguments:
   - storage: initialized storage with base-fn schemas already synced
   - fn-defs: vector of fn definition maps

   Returns map of {fn-name -> fn-id} for created fns.

   Throws on:
   - Invalid definitions
   - Unresolved references (parent or fn in args)
   - Circular dependencies

   See namespace docstring for definition format."
  ([storage fn-defs]
   ((or *sync-fns-override* core/sync-fns-to-storage!) storage fn-defs))
  ([storage fn-defs ns-id-map]
   ((or *sync-fns-override* core/sync-fns-to-storage!) storage fn-defs ns-id-map))
  ([storage fn-defs ns-id-map extra-name->id]
   ((or *sync-fns-override* core/sync-fns-to-storage!)
    storage fn-defs ns-id-map extra-name->id))
  ([storage fn-defs ns-id-map extra-name->id extra-defs-by-name]
   ((or *sync-fns-override* core/sync-fns-to-storage!)
    storage fn-defs ns-id-map extra-name->id extra-defs-by-name)))
