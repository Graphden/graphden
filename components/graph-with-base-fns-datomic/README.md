# graph-with-base-fns-datomic

Complete graphden stack with Datomic backend. Includes storage, graph schema, executor, and all base functions pre-registered.

## Usage

```clojure
(require '[graphden.graph-with-base-fns-datomic.interface :as gwbf]
         '[graphden.storage-protocol.interface :as sp]
         '[graphden.executor.interface :as exec])

;; Create complete environment (Datomic Local, in-memory)
(def storage (gwbf/create-storage {:db-name "graphden"}))

;; Or with file storage
(def storage (gwbf/create-storage
               {:db-name "graphden"
                :client-config {:server-type :datomic-local
                                :storage-dir "/path/to/data"
                                :system "my-system"}}))

;; Base functions are already registered and synced
(sp/query-entities storage :fn-schema {})
;; => [{:name "add", :returned-type :numeric, ...} ...]

;; Create and execute functions
;; ... same as memory version ...

;; Cleanup
(sp/close storage)
```

## Configuration

```clojure
{:db-name "database-name"
 :client-config {:server-type :datomic-local  ; or :peer-server
                 :storage-dir "/path/to/data" ; for file storage
                 :system "system-name"}}
```

## What's Included

1. **Datomic storage** - Immutable database with full history
2. **Graph schema** - Datomic attributes for fn, fn-schema, arg-schema, arg-value, fn-result-value
3. **Base functions** - 50+ functions registered (same as memory version)

## When to Use

- Need immutable history / audit trail
- Time-travel queries required
- Complex graph queries via Datalog
- Systems requiring ACID with full history
