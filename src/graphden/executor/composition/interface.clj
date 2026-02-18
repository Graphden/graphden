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

   ## Order Matters

   Define fns AFTER their dependencies. If order is wrong,
   a warning is printed with suggested fix."
  (:require
    [graphden.executor.composition.core :as core]))


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
  [storage fn-defs]
  (core/sync-fns-to-storage! storage fn-defs))
