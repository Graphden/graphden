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
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as ctx]
    [graphden.executor.registry :as registry]))


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


;; === Execution ===

(defn execute
  "Executes a function by id using the compiled registry.

   Arguments:
   - context: Execution context (created with create-context)
   - fn-id: UUID of the function to execute
   - args: Free args map, keyed by external arg name, or nil/{}.

   Throws:
   - :execution-error/fn-not-found if `fn-id` has no compiled closure
   - :execution-error/invalid-args if `args` is non-nil and not a map"
  [context fn-id args]
  (when (and (some? args) (not (map? args)))
    (throw (ex-info "args must be nil or a map"
                    {:type :execution-error/invalid-args
                     :args args
                     :args-type (type args)})))
  (cr/execute context fn-id (or args {})))


(defn execute-with-named-args
  "Executes a function with `named-args` keyed by external arg name.

   Useful for HOFs that call child fns with dynamic args. Unknown arg
   names throw `:execution-error/unknown-arg-name`. Validation is skipped
   when `fn-id` is a callable (HOF impls that deref a :fn-type arg pass
   the resulting callable back through here)."
  [context fn-id named-args]
  (when (and (some? named-args) (not (map? named-args)))
    (throw (ex-info "named-args must be nil or a map"
                    {:type :execution-error/invalid-args
                     :args named-args
                     :args-type (type named-args)})))
  (when (and (seq named-args) (uuid? fn-id))
    (let [valid (set (cr/free-arg-ext-names context fn-id))]
      (when-let [unknown (first (remove valid (keys named-args)))]
        (throw (ex-info (str "Unknown argument name: " unknown)
                        {:type :execution-error/unknown-arg-name
                         :arg-name unknown
                         :fn-id fn-id
                         :available-args valid})))))
  (cr/execute context fn-id (or named-args {})))


(defn execute-by-name
  "Looks up a fn entity by `fn-name` (string) and executes it with
   `named-args`. Throws `:execution-error/fn-not-found` on miss and
   `:execution-error/invalid-fn-name` when `fn-name` isn't a string."
  [context fn-name named-args]
  (when-not (string? fn-name)
    (throw (ex-info "fn-name must be a string"
                    {:type :execution-error/invalid-fn-name
                     :fn-name fn-name
                     :fn-name-type (type fn-name)})))
  (cr/execute-by-name context fn-name named-args))


;; === HOF Helpers ===

(defn make-single-arg-callable
  "Builds a callable over `fn-id` whose shape mirrors `compile/hof-wrap`'s
   leftover-logic: 0 free args → variadic ignore; 1 free arg → single-arg
   callable (item bound to that name); 2+ → map-callable (caller passes
   `{name value}`). Compiler picks no names — author and caller agree."
  [context fn-id]
  (cr/make-single-arg-callable context fn-id))


;; === Test Fixtures ===

(defn with-clean-registry
  "Test fixture that establishes a thread-local base-fn registry for the
   duration of `f`. Every `register-base-fn!` inside `f` writes to
   this scoped atom instead of the process-global one; reads fall
   through to the global for primitives this test doesn't override.
   Sibling tests on other threads each get their own override — no
   cross-test leak, no global mutation, in-JVM-parallel-safe.

   Wire in via `(use-fixtures :each exec/with-clean-registry)`."
  [f]
  (binding [registry/*registry-override* (atom {})]
    (f)))


(defn with-isolated-rich-types
  "Test fixture that scopes ALL rich-types reads and writes inside
   `f` to a thread-local atom, leaving the process-global
   `rich-types-registry` untouched. Use on ns'es that BOOTSTRAP
   their own packages (e.g. integration tests calling
   `sys/bootstrap-from-packages!`), so a contaminator entry from
   a sibling NS-thread can't crash compile-eager mid-test.

   Earlier symptom (without this fixture, or with the prior
   snapshot/restore implementation under parallel kaocha): a
   foreign `:effects` / `:return` shape on a fn-name landed by a
   sibling test left a builder that returned a closure where an
   Associative was expected, surfacing as
   `AFunction$1 cannot be cast to Associative` deep inside
   compile-eager's arg-builder chain in `execute-http-test`.

   The previous snapshot/restore version (pre-2026-06) was correct
   in serial mode but racy under parallel kaocha: thread A's
   `(restore-fn snap)` could be interleaved with thread B's writes
   on the shared process atom. The thread-local override removes
   the race entirely — each NS-thread reads and writes its own
   isolated registry, so the order of test starts/finishes can't
   leak state across threads.

   The override is PRE-SEEDED with a snapshot of the current
   global registry (via `snapshot-for-isolation`) so reads stay
   O(1) — see the design note above `*rich-types-override*` in
   `registry.core` for why merge-on-read was unworkable.

   `requiring-resolve` defers the registry.core import so this ns
   stays out of the `interface ← registry.core ← interface` cycle
   — registry.core requires interface for `:base-fns` plumbing.

   Wire in via `(use-fixtures :once exec/with-isolated-rich-types)`."
  [f]
  (let [override-var (requiring-resolve 'graphden.executor.registry.core/*rich-types-override*)
        snapshot-fn (requiring-resolve 'graphden.executor.registry.core/snapshot-for-isolation)
        ;; §4 Risk-2: isolate the per-org rich-types slice together with the
        ;; global one, or a tenant test's per-org writes leak across NSes.
        per-org-var (requiring-resolve 'graphden.executor.registry.core/*per-org-rich-override*)
        per-org-snap (requiring-resolve 'graphden.executor.registry.core/per-org-rich-snapshot-for-isolation)]
    (with-bindings {override-var (atom (snapshot-fn))
                    per-org-var (atom (per-org-snap))}
      (f))))
