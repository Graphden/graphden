# Error Codes Reference

This document is a reference for the commonly-handled error families in the graphden system — not an exhaustive list (many modules carry their own `:category/specific` types). Errors are thrown as `ExceptionInfo` with a `:type` key in the ex-data map.

## HTTP Status Mapping (audit-8)

`graphden.web.errors/status-for` is the ONE `:type` → HTTP status
table for the whole surface. The top-level `:error-boundary-wrap`
(app/server chain, outermost) maps any uncaught throw; handler
families that build their own envelopes declare `:http-status` at the
rejection builder and render through `web.errors/:json-envelope-response`
(the JSON twin of `:html-action-response`). Messages are returned
verbatim only for author-facing families; everything else gets an
opaque `:ref` correlated with the server log — on every deployment
shape, self-hosted included.

| `:type` | Status |
|---|---|
| `:not-found`, `:user/not-found`, execute fn-not-found | 404 |
| `:constraint-violation/fn-name-collision` | 409 |
| `:constraint-violation/position-collision` | 409 |
| `:constraint-violation/unique` | 409 |
| `:merge-conflict` (POST /api/branches/:ref/merge) | 409 |
| `:user/exists` | 409 |
| `:user/invalid`, `:grant/invalid-capability`, `:domain/unverified` (tenancy control-plane) | 400 |
| execute already-running-as-service | 409 |
| `:authz/forbidden`, `capability/*` (incl. secret-leaf gate) | 403 |
| execute over-capacity | 429 + `Retry-After` |
| `:quota/entity-limit` (tenant fn row-cap, #7) | 429 |
| execute args-too-large (256 KB) | 413 |
| `:vault/not-configured` | 503 |
| `validation-error/*`, `constraint-violation/*` (other), `type-check/*`, `packages/*`, `refinement/*`, `execution-error/*`, `graph-error/*`, `secrets/*`, execute rejected (other) | 400 |
| `branch-router/*` | 404 |
| unknown / internal | 500 (opaque `:ref`) |

Deliberate exceptions: the MCP route is JSON-RPC (spec-mandated
HTTP 200 with in-band error objects); a FAILED execution is a
successful 200 poll (`:status :failed` in the body) — only
SUBMIT-time rejections carry 4xx; the 5 MB result cap is a 200 with
`:result-truncated? true` (success with caveat).

Previously-undocumented types now covered by the table:
`:constraint-violation/reparent-cross-branch` (400),
`:merge-conflict`, `:vault/not-configured`, `:refinement/violated`,
`:capability/secret-leaf-restricted`, `:authz/forbidden`,
`:branch-router/handler-not-found`, `:storage-error/unsupported-opts`
(500 — internal misuse), `:packages/unresolved-ref`,
421 `misdirected-request` (off-shard, tenancy), execute
`:rejected` / `:over-capacity` / `:args-too-large` reasons.
`web.errors/error-codes-doc drift is pinned by
`graphden.web.errors-doc-test`.

## Error Type Naming Convention

Error types follow the pattern `:category/specific-error` where:

- `category` groups related errors (e.g., `constraint-violation`, `execution-error`)
- `specific-error` describes the exact problem

## Configuration Errors

### `:config-error/missing-jdbc-url`

**Component:** postgres-storage
**Description:** JDBC URL is required but not provided in storage configuration.
**Solution:** Provide `:jdbc-url` in the config map.

### `:config-error/missing-username`

**Component:** postgres-storage
**Description:** Database username is required but not provided.
**Solution:** Provide `:username` in the config map.

### `:config-error/missing-password`

**Component:** postgres-storage
**Description:** Database password is required but not provided.
**Solution:** Provide `:password` in the config map.

### `:config-error/invalid-jdbc-url`

**Component:** postgres-storage
**Description:** JDBC URL is invalid (wrong format or protocol).
**Ex-data keys:**

- `:jdbc-url` - The invalid URL (truncated if too long)
**Solution:** Ensure JDBC URL starts with `jdbc:postgresql://`.

### `:config-error/invalid-pool-size`

**Component:** postgres-storage
**Description:** Connection pool size is invalid (non-positive or exceeds maximum).
**Ex-data keys:**

- `:pool-size` - The invalid value
- `:max-allowed` - Maximum allowed value (100)
**Solution:** Provide a positive integer pool size up to 100.

### `:config-error/invalid-min-idle`

**Component:** postgres-storage
**Description:** Minimum idle connections is invalid (non-positive).
**Solution:** Provide a positive integer for `:min-idle`.

### `:config-error/invalid-pool-config`

**Component:** postgres-storage
**Description:** Pool configuration is inconsistent (e.g., min-idle > pool-size, idle-timeout >= max-lifetime).
**Ex-data keys:**

- `:min-idle`, `:pool-size` - For min-idle > pool-size error
- `:idle-timeout`, `:max-lifetime` - For timeout configuration error
**Solution:** Ensure min-idle <= pool-size and idle-timeout < max-lifetime.

### `:config-error/invalid-timeout`

**Component:** postgres-storage
**Description:** Timeout value is invalid (non-positive or below minimum).
**Ex-data keys:**

- `:timeout-ms` - The invalid timeout value
- `:min-timeout-ms` - Minimum allowed (1000ms)
**Solution:** Provide a positive integer timeout of at least 1000ms.

## Constraint Violations

### `:constraint-violation/unique`

**Component:** storage-protocol
**Description:** Attempted to insert a record with a duplicate unique key.
**Ex-data keys:**

- `:entity` - Entity name (`:fn`, `:slot`, `:fn-slot`, `:binding`, `:binding-list-item`)
- `:field` - Field that violated uniqueness
- `:value` - The duplicate value

### `:constraint-violation/dependency-cycle`

**Component:** storage-protocol (GraphConstraints)
**Description:** A binding's `:ref-fn-id` (or list-item's `:ref-fn-id`)
would create a dependency cycle through fn references.
**Ex-data keys:**

- `:owner-fn-id` - Function owning the binding
- `:target-fn-id` - Target function being referenced via ref-fn-id
- `:cycle-path` - Path showing the cycle

### `:constraint-violation/fn-name-collision`

**Component:** versioning (VersionedStorage)
**Description:** A create / rename / namespace-move would leave two LIVE
fns with the same `(namespace-id, name)` on the current branch. Replaces
the retired base-table `UNIQUE (namespace_id, name)` — dead (soft-deleted)
identities no longer block the name, and root fns (NULL namespace) are now
covered too.
**Ex-data keys:**

- `:name` - The colliding fn name
- `:namespace-id` - Target namespace (nil = root)
- `:branch-id` - Branch whose live view collides
- `:colliding-fn-ids` - IDs of the live fns already holding the name

### `:constraint-violation/position-collision`

**Component:** versioning (VersionedStorage)
**Description:** A binding-list-item write would leave two items at the
same `(binding-id, position)` in the current branch's resolved view.
**Ex-data keys:**

- `:binding-id` - Owning binding
- `:position` - The contested position
- `:branch-id` - Branch whose live view collides
- `:colliding-item-ids` - IDs of the items already at that position

### `:constraint-violation/main-branch-undeletable`

**Component:** versioning (VersionedStorage)
**Description:** Attempted to delete the `main` branch.
**Ex-data keys:**

- `:branch-id` - The branch the delete was attempted on

### `:constraint-violation/branch-has-children`

**Component:** versioning (VersionedStorage)
**Description:** Attempted to delete a branch that still has child branches.
**Ex-data keys:**

- `:branch-id` - The branch the delete was attempted on
- `:child-branch-ids` - IDs of the child branches blocking the delete

## Execution Errors

### `:execution/forbidden-effect`

**Component:** executor (effect sandbox)
**Description:** A base-fn impl tried to perform a side effect not
permitted by the execution context's `:allowed-effects` set — the
runtime half of the cloud effect gate (PLATFORM_PLAN §5). Thrown by
`record-effect!` BEFORE the impl performs the effect. Only fires when the
context restricts effects; an unrestricted context (`:allowed-effects`
nil — self-hosted / mixed) never throws this.
**Ex-data keys:**

- `:effect` - The forbidden effect category (`:env` / `:io` / `:network` / …)
- `:allowed` - The set of effects the context does permit

**Solution:** Either the deployment intends to forbid this effect (the
caller's graph must not use that primitive in a restricted context), or
the context's `:allowed-effects` should include the category.

### `:execution-error/invalid-context`

**Component:** executor
**Description:** Execution context is invalid (e.g., missing storage).
**Solution:** Ensure context is created with `create-context` and has a valid storage.

### `:validation-error/type-mismatch`

**Component:** executor (arg-value validation)
**Description:** Provided argument value doesn't match the expected type.
**Ex-data keys:**

- `:arg-name` - Name of the argument
- `:expected-type` - Expected type (`:fn`, `:int`, `:bool`, `:text`, `:numeric`, `:jsonb`, `:bytes`, `:timestamptz`, `:enum`, `:uuid`)
- `:provided-value` - The value that was provided
- `:provided-type` - Actual Java type of the value

### `:execution-error/invalid-args`

**Component:** executor
**Description:** Arg is missing the `:type` field.
**Ex-data keys:**

- `:arg` - The invalid arg

### `:execution-error/fn-not-found`

**Component:** executor
**Description:** Function not found in the execution graph.
**Ex-data keys:**

- `:fn-id` - The missing function's UUID

### `:recursion-error/max-depth-exceeded`

**Component:** executor
**Description:** Maximum recursion depth exceeded (protection against infinite recursion).
**Ex-data keys:**

- `:depth` - Current depth
- `:max-depth` - Maximum allowed depth (default: 1000)

### `:execution-error/graph-too-large`

**Component:** storage-protocol
**Description:** Execution graph exceeded maximum allowed iterations during resolution.
**Ex-data keys:**

- `:iterations` - Number of iterations performed
- `:max-iterations` - Maximum allowed (configurable via `*max-graph-iterations*`)

## Validation Errors

### `:validation-error/required-field-missing`

**Component:** storage-protocol
**Description:** A required field is missing from the data being validated.
**Ex-data keys:**

- `:field` - Name of the missing field
- `:entity` - Entity type (`:fn` or `:arg`)

### `:validation-error/duplicate-ids`

**Component:** storage-protocol
**Description:** Duplicate IDs found in a collection.
**Ex-data keys:**

- `:duplicate-ids` - Set of duplicate UUIDs

## Parse Errors

### `:parse-error/jsonb`

**Component:** postgres-storage
**Description:** Failed to parse JSONB value from database.
**Ex-data keys:**

- `:field` - Field name
- `:value` - Raw value that failed to parse

## Compile & Sync Errors (2026-07 additions)

### `:compile/ambiguous-lambda-params`

A callable fn-def bound to a 1-arg HOF slot has several candidate
call-site parameters and no authored `:lambda-params`. Declare
`:lambda-params [name …]` on the callable fn-def (`[]` = everything
captured). The retired inference guess silently mis-wired captured
callables; the error names every candidate.

### `:compile/invalid-lambda-params`

An authored `:lambda-params` names an arg that is not a free arg of
the fn (typo guard). The error lists the declared names and the
actual frees.

### `:compile/unmigrated-secret-path`

A binding still carries the retired `:override-kind :secret-path`
marker without a `:resolver-fn-id` — the boot migration
(`system.core/migrate-secret-path-bindings!`) did not run (usually:
the `web/vault` package isn't in the sync set). Refused at compile so
the vault PATH is never executed as a literal.

### `:constraint-violation/override-kind-retired`

A binding WRITE carries `:override-kind` — the enum is retired. A
secret binding is `{:resolver-fn-id <vault-get>}` with the path in
`:value`; `:terminal` covers sealing. Legacy residue on stored rows
does NOT trigger this (only an incoming write of the field).

### `:types/ambiguous-alias`

A bare type-alias name is declared by 2+ type-rows in different
namespaces. Reference the qualified form (`:other.ns/name`) in the
`:type` position; the error lists the qualified candidates.

### `:packages/ambiguous-ref`

A bare fn reference resolves to several same-named fns across
namespaces and none is the referencing module's own. Qualify the
reference (`:other.ns/name`).

### `:secrets/vault-get-missing`

A secret binding is being created but the `:vault-get` resolver
base-fn is absent from the graph (vault package not installed) — the
row would be unexecutable.


## Error Handling Example

```clojure
(require '[graphden.executor.interface :as executor])

(try
  (executor/execute context fn-id args)
  (catch clojure.lang.ExceptionInfo e
    (let [{:keys [type]} (ex-data e)]
      (case type
        :execution-error/timeout
        (log/warn "Execution timed out")

        :execution-error/max-depth-exceeded
        (log/warn "Recursion too deep")

        :constraint-violation/unique
        (let [{:keys [entity field value]} (ex-data e)]
          (log/warn "Duplicate" field "in" entity ":" value))

        ;; Re-throw unknown errors
        (throw e)))))
```
