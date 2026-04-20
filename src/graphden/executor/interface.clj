(ns graphden.executor.interface
  "Function graph executor — public API.

   The compile-at-startup executor lives under the hood (see
   `graphden.executor.compile` and `graphden.executor.compile-runtime`).
   Base-fn impls are authored with the `defbase` macro in
   `graphden.executor.defbase` and registered in the global registry via
   `register-base-fn!`. The executor wraps each composed fn into a
   Clojure closure at startup; `execute` looks the closure up by fn-id
   and invokes it with free-args.

   Within an impl body, use `graphden.executor.runtime/resolve-arg` to
   read arg values (or let `defbase` inline the calls for you)."
  (:require
    [graphden.executor.context :as ctx]
    [graphden.executor.core :as core]
    [graphden.executor.registry :as registry]
    [graphden.executor.types :as types]))


;; === Execution Context ===

(defn create-context
  "Creates an execution context.

   Options (see `graphden.executor.context/create-context` for the full
   contract):
   - :storage   Storage instance (required).
   - :base-fns  Map of fn-name → impl-fn (optional; defaults to the
                global registry).
   - :clock     Zero-arg fn returning current time in ms (test hook)."
  [opts]
  (ctx/create-context opts))


;; === Base Functions Registry ===

(defn register-base-fn!
  "Registers a base function in the global registry.
   fn-name is a keyword (e.g. :add, :if, :map).
   f is a 2-arity function `(fn [args ctx] …)` receiving the raw args
   map (no delay wrapping) and the execution context.

   Use `rt/resolve-arg` to read arg values, or prefer the `defbase`
   macro in `graphden.executor.defbase` which inlines the calls for
   you. A nil impl is stored as-is (matches fn-defs where `:impl` is
   absent)."
  [fn-name f]
  (registry/register-base-fn! fn-name f))


(defn get-base-fn
  "Gets a base function from the registry by name.
   Returns nil if not found."
  [fn-name]
  (registry/get-base-fn fn-name))


(defn clear-base-fns!
  "Clears all registered base functions from the global registry.
   Useful for testing."
  []
  (registry/clear-base-fns!))


(defn get-default-registry
  "Returns the current state of the default global registry as a map.
   Useful for passing to create-context.

   Example:
   (create-context {:storage s
                    :base-fns (get-default-registry)})"
  []
  (registry/get-default-registry))


(defn get-base-fn-from-context
  "Gets a base function from the context's registry by name.
   Returns nil if not found."
  [context fn-name]
  (registry/get-base-fn-from-context context fn-name))


;; === Type Hints ===

(defn register-type-hint!
  "Registers a human-readable hint for a custom type.
   The hint is shown in type mismatch error messages to help users understand
   what value format is expected.

   Custom hints take precedence over built-in hints for the same type.
   This is useful when you add custom types via field-types extension.

   Example:
   (register-type-hint! :email \"string in email format (e.g., user@example.com)\")
   (register-type-hint! :phone \"string with international format (e.g., +1-555-123-4567)\")"
  [type-keyword hint-string]
  (types/register-type-hint! type-keyword hint-string))


;; === Execution ===

(defn execute
  "Executes a function by id using the compiled registry.

   Arguments:
   - context: Execution context (created with create-context)
   - fn-id: UUID of the function to execute
   - args: Free args map, keyed by external arg name (preferred), by
           arg-id UUID (legacy test-style — auto-translated), or nil/{}.

   Throws:
   - :execution-error/fn-not-found if `fn-id` has no compiled closure
   - :execution-error/invalid-args if `args` is non-nil and not a map"
  [context fn-id args]
  (core/execute context fn-id args))


(defn execute-with-named-args
  "Executes a function with `named-args` keyed by external arg name.

   Useful for HOFs that call child fns with dynamic args. Unknown arg
   names throw `:execution-error/unknown-arg-name`."
  [context fn-id named-args]
  (core/execute-with-named-args context fn-id named-args))


(defn execute-by-name
  "Looks up a fn entity by `fn-name` (string) and executes it with
   `named-args`. Throws `:execution-error/fn-not-found` on miss and
   `:execution-error/invalid-fn-name` when `fn-name` isn't a string."
  [context fn-name named-args]
  (core/execute-by-name context fn-name named-args))


;; === HOF Helpers ===

(defn make-single-arg-callable
  "Builds a `(fn [value] result)` callable over `fn-id`. Routes `value`
   to the target's single free arg — or to `:request` when present (Ring
   handler convention). Multi-free-arg targets receive a vector routed
   by position."
  [context fn-id]
  (core/make-single-arg-callable context fn-id))


(defn make-named-arg-callable
  "Builds a callable that routes the incoming value to the specific free
   arg named `arg-name` (string or keyword). Used when the target has
   several free args and the caller knows the input's semantic name
   (e.g. `:request` for Ring handlers)."
  [context fn-id arg-name]
  (core/make-named-arg-callable context fn-id arg-name))


(defn make-optional-arg-callable
  "Like `make-single-arg-callable` but also accepts 0-free-arg targets
   (the incoming value is ignored). Throws when the target has more than
   one free arg."
  [context fn-id]
  (core/make-optional-arg-callable context fn-id))


;; === Test Fixtures ===

(defn with-clean-registry
  "Test fixture that clears the global base-fn registry before and after
   each test. Prevents leftover registrations from polluting other tests.
   Wire in via `(use-fixtures :each exec/with-clean-registry)`."
  [f]
  (clear-base-fns!)
  (try
    (f)
    (finally
      (clear-base-fns!))))
