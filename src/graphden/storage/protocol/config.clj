(ns graphden.storage.protocol.config
  "Declarative configuration validation and shared runtime settings.

   ## Configuration Schemas
   Provides reusable Malli schemas and validation functions for storage backend
   configurations (PostgreSQL, Datomic).

   ## Runtime Configuration (Dynamic Variables)

   All dynamic variables can be rebound using `binding` or their `with-*` helpers.
   Default values are designed for production use cases.

   ### Query & Execution Limits
   | Variable                        | Default  | Description                           |
   |---------------------------------|----------|---------------------------------------|
   | `*query-timeout-ms*`            | 30000    | Query timeout (min 1000ms for JDBC)   |
   | `*max-batch-size*`              | 1000     | Max entities per batch operation      |
   | `*max-graph-iterations*`        | 10000    | Max BFS iterations for graph resolve  |
   | `*max-recursion-depth*`         | 1000     | Max depth for graph-level `:fix`      |

   ### DoS Prevention Limits
   | Variable                        | Default  | Description                           |
   |---------------------------------|----------|---------------------------------------|
   | `*max-lazy-seq-size*`           | 100000   | Max elements when realizing lazy seq  |
   | `*max-nested-collection-depth*` | 100      | Max recursion depth for collections   |
   | `*max-range-size*`              | 1000000  | Max elements for range function       |
   | `*max-repeat-size*`             | 1000000  | Max elements for repeat function      |

   ### Regex Safety
   | Variable                        | Default  | Description                           |
   |---------------------------------|----------|---------------------------------------|
   | `*max-regex-length*`            | 100      | Max regex pattern length              |
   | `*max-regex-input-length*`      | 100000   | Max input string length for regex     |
   | `*regex-compile-timeout-ms*`    | 100      | Regex compilation timeout             |

   ### Cache Settings (in cache-protocol)
   | Variable                        | Default  | Description                           |
   |---------------------------------|----------|---------------------------------------|
   | `*cache-load-timeout-ms*`       | 5000     | Timeout for cache load operations     |

   ### Executor Settings (in executor/context)
   | Variable                        | Default  | Description                           |
   |---------------------------------|----------|---------------------------------------|
   | `cache-max-size`                | 10000    | Max result cache entries (context)    |
   | `cache-warning-threshold`       | 1000     | Warning threshold for cache size      |
   | `max-depth`                     | 1000     | Max recursion depth (context)         |

   ## Usage Examples

   ```clojure
   ;; Increase query timeout for slow queries
   (config/with-query-timeout 60000
     #(sp/query-entities storage :large-table {}))

   ;; Increase batch size for bulk import
   (binding [config/*max-batch-size* 5000]
     (sp/create-entities storage :entity large-dataset))

   ;; Stricter regex limits for user input
   (config/with-regex-limits {:max-pattern-length 50}
     #(validate-user-regex pattern))
   ```")


;; === Common schemas ===
;;
;; `non-blank-string`, the malli-schema `postgres-pool-config`, the
;; generic `validate-config!`, the postgres-specific
;; `validate-postgres-config!`, and the timeout helper
;; `execute-with-timeout!` were all "protocol-API" left behind for
;; future storage backends to opt into. With only one concrete impl
;; (Postgres) and that impl validating its own pool options through
;; the manual chain in `storage/postgres/pool/validate-pool-options!`,
;; nothing reached these definitions in production. Tests existed but
;; covered the unused surface only. Removed 2026-06-17.

(def positive-int
  "Positive integer (> 0)."
  [:and :int [:> 0]])


(def non-negative-int
  "Non-negative integer (>= 0)."
  [:and :int [:>= 0]])


;; ============================================================================
;; Query Timeout Infrastructure
;; ============================================================================
;;
;; Shared query timeout handling for all storage backends.
;; Each backend can use these primitives to implement timeout consistently.

(def default-query-timeout-ms
  "Default timeout for storage queries in milliseconds.
   Used by PostgreSQL (via JDBC setQueryTimeout) and Datomic backends.
   Value: 30000ms (30 seconds) - reasonable default for most queries."
  30000)


(def ^:dynamic *query-timeout-ms*
  "Timeout for storage queries in milliseconds. Can be rebound per-thread.
   Default is 30000 ms (30 seconds). Use `with-query-timeout` to temporarily change.

   Backend-specific notes:
   - PostgreSQL: Converted to seconds for JDBC setQueryTimeout
   - Datomic: Enforced via future+deref (no native timeout support)
   - Memory: Not applicable (in-memory operations are instant)"
  default-query-timeout-ms)


(def min-query-timeout-ms
  "Minimum allowed query timeout in milliseconds.
   1000ms (1 second) minimum because:
   - JDBC setQueryTimeout uses seconds, sub-second values round to 0
   - SQL queries need time for network roundtrip and query parsing
   - Different from executor timeout (50ms min) which covers overall execution"
  1000)


(defn validate-query-timeout!
  "Validates query timeout value. Throws on invalid timeout.

   Arguments:
   - timeout-ms: Timeout in milliseconds (must be positive integer >= 1000)

   Throws:
   - :config-error/invalid-timeout if timeout is not a positive integer
   - :config-error/invalid-timeout if timeout < min-query-timeout-ms"
  [timeout-ms]
  (when-not (pos-int? timeout-ms)
    (throw (ex-info "Query timeout must be a positive integer (ms)"
                    {:type :config-error/invalid-timeout
                     :timeout-ms timeout-ms})))
  (when (< timeout-ms min-query-timeout-ms)
    (throw (ex-info (str "Query timeout must be at least " min-query-timeout-ms "ms (1 second)")
                    {:type :config-error/invalid-timeout
                     :timeout-ms timeout-ms
                     :min-timeout-ms min-query-timeout-ms}))))


(defn with-query-timeout
  "Executes f with a custom query timeout (in milliseconds).
   Timeout must be a positive integer >= 1000ms.

   Why 1000ms minimum?
   - JDBC setQueryTimeout uses seconds (integer), values <1000ms become 0
   - SQL queries need time for network roundtrip and query parsing
   - Different from executor timeout (50ms min) which covers overall execution

   Example:
   (with-query-timeout 60000
     #(sp/query-entities storage :user {}))"
  [timeout-ms f]
  (validate-query-timeout! timeout-ms)
  (binding [*query-timeout-ms* timeout-ms]
    (f)))


(defn get-query-timeout-seconds
  "Returns the current query timeout in seconds for JDBC calls.
   Reads the dynamic var *query-timeout-ms* and converts to seconds.

   Safety: Throws if timeout is invalid (non-positive OR below minimum)
   to prevent silent timeout disabling. This catches improper direct
   binding of `*query-timeout-ms*` — use `with-query-timeout` instead.

   Delegates the validation to `validate-query-timeout!` so the
   pos-int? check + minimum check stay in one place. The pre-fix
   inline check only verified the minimum, silently letting `0` /
   negative / non-integer bindings through (they'd surface as obscure
   JDBC errors downstream)."
  []
  (validate-query-timeout! *query-timeout-ms*)
  (quot *query-timeout-ms* 1000))


;; ============================================================================
;; Batch Size Configuration
;; ============================================================================
;;
;; Limits for batch operations to prevent OOM from huge batches.

(def ^:dynamic *max-batch-size*
  "Maximum number of entities in a single batch operation.
   Batch operations larger than this will throw an error.
   Default: 10000 entities. Bumped from 5000 once the bundled package
   set grew past that — sync of `core + storage + web + app` writes
   the whole fn-graph (fn + fn-slot + slot + binding + binding-list-
   item rows) in a single batch and the count is now ~5100+; the cap
   exists to guard OOM from user-supplied huge writes, not to throttle
   the sync path."
  10000)


(defn validate-batch-size!
  "Validates that batch size is within allowed limits.
   Throws :batch-error/batch-too-large if batch exceeds *max-batch-size*.

   Arguments:
   - batch-size: number of items in the batch
   - operation: keyword describing the operation (for error messages)
   - context: additional context map for the error"
  [batch-size operation context]
  (when (> batch-size *max-batch-size*)
    (throw (ex-info (str "Batch size " batch-size " exceeds maximum allowed " *max-batch-size*)
                    (merge {:type :batch-error/batch-too-large
                            :batch-size batch-size
                            :max-batch-size *max-batch-size*
                            :operation operation}
                           context)))))


;; ============================================================================
;; Regex Safety Configuration
;; ============================================================================
;;
;; Configurable limits for regex operations to prevent ReDoS attacks.
;; These are defaults that can be overridden via dynamic vars.

(def ^:dynamic *max-regex-length*
  "Maximum length of regex pattern to prevent complex pattern attacks.
   Patterns longer than this are rejected.
   Default: 100 characters."
  100)


(def ^:dynamic *max-regex-input-length*
  "Maximum input string length for regex operations.
   Prevents catastrophic backtracking on large inputs.
   Default: 100000 characters (100KB)."
  100000)


(def ^:dynamic *regex-compile-timeout-ms*
  "Timeout for regex compilation in milliseconds.
   Catches patterns that take too long to compile.
   Default: 100ms."
  100)


(defn with-regex-limits
  "Executes f with custom regex safety limits.

   Arguments:
   - opts: map with optional keys:
     - :max-pattern-length - maximum regex pattern length
     - :max-input-length - maximum input string length
     - :compile-timeout-ms - regex compilation timeout
   - f: zero-arg function to execute

   Example:
   (with-regex-limits {:max-pattern-length 50 :max-input-length 10000}
     #(str-split my-string my-pattern))"
  [opts f]
  (binding [*max-regex-length* (get opts :max-pattern-length *max-regex-length*)
            *max-regex-input-length* (get opts :max-input-length *max-regex-input-length*)
            *regex-compile-timeout-ms* (get opts :compile-timeout-ms *regex-compile-timeout-ms*)]
    (f)))


;; ============================================================================
;; Lazy Sequence Safety Configuration
;; ============================================================================
;;
;; Configurable limits for lazy sequence realization to prevent DoS attacks.
;; User functions that return infinite or very large lazy sequences could
;; exhaust memory when realized.

(def ^:dynamic *max-lazy-seq-size*
  "Maximum number of elements allowed when realizing a lazy sequence.
   Sequences larger than this will throw an error.
   Default: 100000 elements.

   This protects against DoS via functions that return (range) or
   other infinite/large lazy sequences."
  100000)


(def ^:dynamic *max-nested-collection-depth*
  "Maximum depth for recursive collection realization.
   Prevents stack overflow from deeply nested structures.
   Default: 100 levels."
  100)


;; ============================================================================
;; Graph Recursion Configuration
;; ============================================================================
;;
;; Limit for the `:fix`-based graph recursion primitive (`core/recursion`).
;; The step fn invokes `:self` to recurse; the depth counter trips when
;; runaway recursion would blow the JVM stack — closes the door on a
;; naive non-terminating step (`f n → f (n-1)` forgetting the base case)
;; without surrendering useful recursion budget for tree-walks, AST
;; visitors, expansion passes, etc.

(def ^:dynamic *max-recursion-depth*
  "Maximum depth a graph-level `:fix` recursion may reach before
   throwing `:recursion-error/max-depth-exceeded`.
   Default: 1000 levels."
  1000)


;; ============================================================================
;; Collection Generation Limits
;; ============================================================================
;;
;; Configurable limits for collection-generating functions (range, repeat)
;; to prevent memory exhaustion from large collections.

(def ^:dynamic *max-range-size*
  "Maximum number of elements allowed in range to prevent memory exhaustion.
   Default: 1000000 elements (1 million)."
  1000000)


(def ^:dynamic *max-repeat-size*
  "Maximum number of elements allowed in repeat to prevent memory exhaustion.
   Default: 1000000 elements (1 million)."
  1000000)


;; ============================================================================
;; Centralized Limits Registry
;; ============================================================================
;;
;; All hardcoded limits are defined here for easy discovery and configuration.
;; These are grouped by category and documented with rationale.

;; === Limits — canonical sources elsewhere ===
;; Identifier / fn-name / batch / cache / dependency-chain limits live
;; next to their use sites; reach for those namespaces directly:
;;   - `max-identifier-length`    → `schema.fields.types`
;;   - credential-length limits   → `storage.protocol.credential-validation`
;;   - `default-max-dependency-chain-depth` → `storage.protocol.constraints`
