(ns graphden.fn-defs.interface
  "Data-driven fn definitions and storage sync.

   This component provides a declarative way to define fn entities
   that compose base functions through the graph.

   ## Usage

   ```clojure
   (require '[graphden.fn-defs.interface :as fn-defs])

   ;; Define fns as data
   (def my-fns
     [{:name :router-handler-fn
       :parent :default-router-handler}

      {:name :web-server-fn
       :parent :http-server
       :args {:handler :router-handler-fn
              :port 8080}}])

   ;; Sync to storage (after base-fns are synced)
   (fn-defs/sync-fns-to-storage! storage my-fns)
   ;; => {:router-handler-fn #uuid \"...\"
   ;;     :web-server-fn #uuid \"...\"}
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
    [graphden.fn-defs.core :as core]))


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
