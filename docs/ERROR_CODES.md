# Error Codes Reference

This document lists all error types used in the graphden system. Errors are thrown as `ExceptionInfo` with a `:type` key in the ex-data map.

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

### `:config-error/invalid-db-name`
**Component:** datomic-storage
**Description:** Database name is invalid (empty, too long, or contains invalid characters).
**Ex-data keys:**
- `:db-name` - The invalid database name
**Solution:** Use a non-empty database name with only alphanumeric characters and hyphens, max 64 chars.

### `:config-error/invalid-client-config`
**Component:** datomic-storage
**Description:** Datomic client configuration is invalid.
**Solution:** Provide a valid client configuration map.

### `:config-error/missing-server-type`
**Component:** datomic-storage
**Description:** Server type is required in Datomic client configuration.
**Solution:** Provide `:server-type` (`:dev-local` or `:cloud`).

### `:config-error/invalid-server-type`
**Component:** datomic-storage
**Description:** Server type is not supported.
**Ex-data keys:**
- `:server-type` - The invalid server type
- `:supported` - List of supported types
**Solution:** Use `:dev-local` or `:cloud` as server type.

### `:config-error/missing-system`
**Component:** datomic-storage
**Description:** System name is required for dev-local server type.
**Solution:** Provide `:system` in the client configuration.

### `:config-error/missing-storage-dir`
**Component:** datomic-storage
**Description:** Storage directory is required for dev-local server type.
**Solution:** Provide `:storage-dir` in the client configuration.

### `:config-error/missing-endpoint`
**Component:** datomic-storage
**Description:** Endpoint is required for cloud server type.
**Solution:** Provide `:endpoint` in the client configuration.

### `:config-error/missing-access-key`
**Component:** datomic-storage
**Description:** Access key is required for cloud server type.
**Solution:** Provide `:access-key` in the client configuration.

### `:config-error/missing-secret`
**Component:** datomic-storage
**Description:** Secret is required for cloud server type.
**Solution:** Provide `:secret` in the client configuration.

## Constraint Violations

### `:constraint-violation/unique`
**Components:** memory-storage, datomic-storage
**Description:** Attempted to insert a record with a duplicate unique key.
**Ex-data keys:**
- `:entity` - Entity name (e.g., `:fn`, `:fn-schema`)
- `:field` - Field that violated uniqueness
- `:value` - The duplicate value

### `:constraint-violation/parent-schema-mismatch`
**Component:** storage-protocol (GraphConstraints)
**Description:** Parent function must have the same fn-schema-id as the child function.
**Ex-data keys:**
- `:fn-id` - The function being created/updated
- `:parent-fn-id` - The parent function
- `:fn-schema-id` - Schema of the child
- `:parent-schema-id` - Schema of the parent

### `:constraint-violation/arg-already-defined`
**Component:** storage-protocol (GraphConstraints)
**Description:** Argument is already defined in the parent chain and cannot be redefined.
**Ex-data keys:**
- `:fn-id` - Current function
- `:arg-schema-id` - The arg-schema being set
- `:defined-in-fn-id` - Function where arg is already defined

### `:constraint-violation/arg-schema-mismatch`
**Component:** storage-protocol (GraphConstraints)
**Description:** Arg-schema does not belong to the fn-schema of this function.
**Ex-data keys:**
- `:fn-id` - The function
- `:arg-schema-id` - The mismatched arg-schema
- `:fn-schema-id` - Expected fn-schema
- `:arg-schema-fn-schema-id` - Actual fn-schema of the arg

### `:constraint-violation/inheritance-cycle`
**Component:** storage-protocol (GraphConstraints)
**Description:** Setting parent-fn-id would create a cycle in the inheritance chain.
**Ex-data keys:**
- `:fn-id` - Function being modified
- `:parent-fn-id` - Proposed parent
- `:cycle-path` - Path showing the cycle

### `:constraint-violation/dependency-cycle`
**Component:** storage-protocol (GraphConstraints)
**Description:** Creating an arg-value reference would create a dependency cycle.
**Ex-data keys:**
- `:owner-fn-id` - Function owning the arg-value
- `:value-fn-id` - Target function being referenced
- `:cycle-path` - Path showing the cycle

## Execution Errors

### `:execution-error/invalid-context`
**Component:** executor
**Description:** Execution context is invalid (e.g., missing storage).
**Solution:** Ensure context is created with `create-context` and has a valid storage.

### `:execution-error/type-mismatch`
**Component:** executor
**Description:** Provided argument value doesn't match the expected type.
**Ex-data keys:**
- `:arg-name` - Name of the argument
- `:expected-type` - Expected type (`:fn`, `:ref`, `:int`, `:bool`, `:text`, `:numeric`, `:jsonb`, `:bytes`, `:timestamptz`, `:enum`, `:uuid`)
- `:provided-value` - The value that was provided
- `:provided-type` - Actual Java type of the value

### `:execution-error/invalid-arg-schema`
**Component:** executor
**Description:** Arg-schema is missing the `:type` field.
**Ex-data keys:**
- `:arg-schema` - The invalid arg-schema

### `:execution-error/nil-arg-value`
**Component:** executor
**Description:** Arg-value cannot be nil (defensive check).

### `:execution-error/nil-arg-schema`
**Component:** executor
**Description:** Arg-schema cannot be nil (defensive check).

### `:execution-error/fn-not-found`
**Component:** executor
**Description:** Function not found in the execution graph.
**Ex-data keys:**
- `:fn-id` - The missing function's UUID

### `:execution-error/fn-schema-not-found`
**Component:** executor
**Description:** Function schema not found in the execution graph.
**Ex-data keys:**
- `:fn-id` - The function ID
- `:fn-schema-id` - The missing schema's UUID

### `:execution-error/missing-required-arg`
**Component:** executor
**Description:** A required argument was not provided and has no default value.
**Ex-data keys:**
- `:arg-schema-id` - The arg-schema ID
- `:arg-name` - Name of the missing argument

### `:execution-error/max-depth-exceeded`
**Component:** executor
**Description:** Maximum recursion depth exceeded (protection against infinite recursion).
**Ex-data keys:**
- `:depth` - Current depth
- `:max-depth` - Maximum allowed depth (default: 1000)

### `:execution-error/timeout`
**Component:** executor
**Description:** Execution timeout exceeded.
**Ex-data keys:**
- `:elapsed-ms` - Time elapsed
- `:timeout-ms` - Maximum allowed time (default: 30000ms)

### `:execution-error/base-fn-not-found`
**Component:** executor
**Description:** Base function not registered in the registry.
**Ex-data keys:**
- `:fn-name` - Name of the missing function
- `:available-fns` - List of registered function names

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
- `:entity` - Entity type

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

## Metadata Errors

### `:metadata-error/rollback-failed`
**Component:** datomic-storage
**Description:** Metadata save failed and rollback also failed. Database may be inconsistent.
**Ex-data keys:**
- `:original-error` - The original error message
- `:rollback-error` - The rollback error message

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
