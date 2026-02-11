(ns graphden.graph-protocol.interface
  "Protocols for the Graph Layer.

   The Graph Layer sits between Storage and Executor:

   ```
   EXECUTOR → GraphReader → GRAPH LAYER → StorageCRUD → STORAGE
   ```

   This component defines:
   - `GraphReader` — unified protocol for graph reading (execution graphs + queries)
   - Graph-specific constraint protocols (re-exported from storage-protocol)
   - Execution graph data access protocols (re-exported from storage-protocol)

   ## Architecture Rationale

   Storage should be schema-agnostic (generic CRUD). Graph-specific concerns
   belong here in the Graph Layer:

   | Concern | Layer | Reason |
   |---------|-------|--------|
   | create/read/update/delete | Storage | Generic CRUD |
   | resolve-execution-graph | Graph | Knows about fn→arg-value→fn |
   | GraphConstraints | Graph | Knows about dependency cycles |
   | GraphReader | Graph | Unified interface for executor |

   ## GraphReader Pattern

   GraphReader is a middleware abstraction. Implementations can be stacked:

   ```clojure
   ;; Direct queries (simplest)
   (direct-graph-reader storage)

   ;; With DB-level caching
   (cached-graph-reader storage cache-storage)

   ;; With version resolution
   (versioned-graph-reader versioned-storage)

   ;; Combined (order matters)
   (cached-graph-reader (versioned-graph-reader versioned-storage) cache-storage)
   ```

   ## Re-exports

   For backwards compatibility, this namespace re-exports graph-related
   protocols from storage-protocol. New code should import from graph-protocol.

   ## Migration Guide

   Before (storage-protocol):
   ```clojure
   (require '[graphden.storage-protocol.interface :as sp])
   (sp/resolve-execution-graph storage fn-id)
   ```

   After (graph-protocol):
   ```clojure
   (require '[graphden.graph-protocol.interface :as gp])
   (gp/resolve-execution-graph reader fn-id)
   ```"
  (:require
    [graphden.storage-protocol.interface :as sp]))


;; ============================================================================
;; GRAPH READER PROTOCOL
;; ============================================================================

(defprotocol GraphReader
  "Unified protocol for reading execution graphs.

   This is the primary interface between Executor and Graph Layer.
   Implementations provide graph resolution with various strategies:
   - DirectGraphReader: queries storage directly
   - CachedGraphReader: uses DB-level cache (cached-fn, cached-merged-arg)
   - VersionedGraphReader: resolves versions on branches

   GraphReader combines:
   1. Execution graph resolution (for executing functions)
   2. Entity queries (for graph exploration, validation)

   The executor only depends on GraphReader, not on storage internals."

  (resolve-graph
    [this fn-id]
    "Resolves complete execution graph for a function.

     Returns ExecutionGraphResult containing all data needed to execute fn-id:
     - :fns - Map of fn-id -> fn record
     - :fn-schemas - Map of fn-schema-id -> fn-schema record
     - :arg-schemas - Map of arg-schema-id -> arg-schema record
     - :resolved-args - Map of fn-id -> {arg-schema-id -> arg-value}
     - :call-sites - Map of call-site-id -> call-site record

     Throws if fn-id doesn't exist or graph resolution fails.")

  (get-fn
    [this fn-id]
    "Returns fn record for fn-id, or nil if not found.")

  (get-fn-schema
    [this fn-schema-id]
    "Returns fn-schema record for fn-schema-id, or nil if not found.")

  (get-arg-schemas-for-fn-schema
    [this fn-schema-id]
    "Returns map of {arg-schema-id -> arg-schema} for fn-schema.")

  (get-arg-values-for-fn
    [this fn-id]
    "Returns map of {arg-schema-id -> arg-value} for fn.")

  (query-fns
    [this where]
    "Queries fn entities matching criteria.
     Returns sequence of fn records."))


;; ============================================================================
;; DIRECT GRAPH READER IMPLEMENTATION
;; ============================================================================

(defrecord DirectGraphReader
  [storage]

  GraphReader

  (resolve-graph
    [_ fn-id]
    (sp/resolve-execution-graph storage fn-id))


  (get-fn
    [_ fn-id]
    (sp/read-entity storage :fn fn-id))


  (get-fn-schema
    [_ fn-schema-id]
    (sp/read-entity storage :fn-schema fn-schema-id))


  (get-arg-schemas-for-fn-schema
    [_ fn-schema-id]
    (->> (sp/query-entities storage :arg-schema {:fn-schema-id fn-schema-id})
         (map (juxt :id identity))
         (into {})))


  (get-arg-values-for-fn
    [_ fn-id]
    (->> (sp/query-entities storage :arg-value {:owner-fn-id fn-id})
         (map (juxt :arg-schema-id identity))
         (into {})))


  (query-fns
    [_ where]
    (sp/query-entities storage :fn where)))


(defn direct-graph-reader
  "Creates a DirectGraphReader that queries storage directly.

   This is the simplest GraphReader implementation with no caching
   or version resolution. Suitable for:
   - Tests
   - Simple single-user applications
   - When caching is handled at a higher level

   Usage:
   ```clojure
   (def reader (direct-graph-reader storage))
   (resolve-graph reader fn-id)
   ```"
  [storage]
  (->DirectGraphReader storage))


(defn graph-reader?
  "Returns true if x implements GraphReader protocol."
  [x]
  (satisfies? GraphReader x))


;; ============================================================================
;; RE-EXPORTS FROM STORAGE-PROTOCOL
;; ============================================================================
;;
;; For backwards compatibility, we re-export graph-related items from
;; storage-protocol. New code should use graph-protocol directly.

;; Protocols (can be used for extend-type, satisfies?, etc.)
;; Note: Protocol names use PascalCase per Clojure convention
(def ^{:splint/disable [:naming/lisp-case]} ExecutionGraph
  "Re-export: Protocol for retrieving execution graphs.
   Prefer GraphReader for new code."
  sp/ExecutionGraph)


(def ^{:splint/disable [:naming/lisp-case]} ExecutionGraphReader
  "Re-export: Protocol for reading data from execution graphs.
   Used to access data within an ExecutionGraphResult."
  sp/ExecutionGraphReader)


(def ^{:splint/disable [:naming/lisp-case]} GraphConstraints
  "Re-export: Protocol for graph integrity constraints.
   Validates no dependency cycles, arg-schema belongs to fn-schema."
  sp/GraphConstraints)


(def ^{:splint/disable [:naming/lisp-case]} ConstraintHelpers
  "Re-export: Helper protocol for constraint implementations."
  sp/ConstraintHelpers)


;; Functions
(def resolve-execution-graph
  "Re-export: Resolves execution graph from storage.
   Prefer (resolve-graph reader fn-id) for new code."
  sp/resolve-execution-graph)


(def graph-get-fn
  "Re-export: Gets fn from ExecutionGraphResult."
  sp/graph-get-fn)


(def graph-get-fn-schema
  "Re-export: Gets fn-schema from ExecutionGraphResult."
  sp/graph-get-fn-schema)


(def graph-get-arg-schemas
  "Re-export: Gets arg-schemas from ExecutionGraphResult."
  sp/graph-get-arg-schemas)


(def graph-get-resolved-args
  "Re-export: Gets resolved-args from ExecutionGraphResult."
  sp/graph-get-resolved-args)


(def graph-get-call-site
  "Re-export: Gets call-site from ExecutionGraphResult."
  sp/graph-get-call-site)


;; Constraint implementations
(def validate-arg-schema-belongs-to-fn-impl
  "Re-export: Shared implementation of arg-schema validation."
  sp/validate-arg-schema-belongs-to-fn-impl)


(def validate-no-dependency-cycle-impl
  "Re-export: Shared implementation of cycle detection."
  sp/validate-no-dependency-cycle-impl)


;; Graph utilities
(def ->execution-graph
  "Re-export: Creates ExecutionGraphResult from map."
  sp/->execution-graph)


(def execution-graph?
  "Re-export: Returns true if x is ExecutionGraphResult."
  sp/execution-graph?)


(def traverse-bfs
  "Re-export: Generic BFS traversal utility."
  sp/traverse-bfs)


(def try-parse-uuid
  "Re-export: Attempts to parse value as UUID."
  sp/try-parse-uuid)


;; Graph accessor functions
(def get-graph-fns sp/get-graph-fns)
(def get-graph-fn-schemas sp/get-graph-fn-schemas)
(def get-graph-arg-schemas sp/get-graph-arg-schemas)
(def get-graph-resolved-args sp/get-graph-resolved-args)
(def get-graph-call-sites sp/get-graph-call-sites)


;; Constants
(def default-max-depth sp/default-max-depth)
(def default-max-unknown-types sp/default-max-unknown-types)
(def ^:dynamic *max-graph-iterations* sp/*max-graph-iterations*)
