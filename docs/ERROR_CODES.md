# Error Codes Reference

This document is a reference for the commonly-handled error families in the graphden system — not an exhaustive list (many modules carry their own `:category/specific` types). Errors are thrown as `ExceptionInfo` with a `:type` key in the ex-data map.

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
