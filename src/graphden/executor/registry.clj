(ns graphden.executor.registry
  "Registry of base-fn implementations. Has two tiers:

   1. **Global** (`default-registry`) — the process-wide map populated
      at package-load time. Hot-path callers (compile-runtime,
      `exec/get-base-fn`) read from it when no thread-local override
      is active. Mutated by `register-base-fn!` / `register-base-fns!`.

   2. **Thread-local override** (`*registry-override*` dynamic atom) —
      established by `with-clean-registry` so test fixtures can
      register stub impls into a SCOPED map that doesn't touch the
      process-global atom. Concurrent test invocations on different
      threads each see their own override; the global stays untouched.
      All read/write helpers below transparently consult the override
      first when bound.

   For isolated tests, the supported paths are:
   - `(use-fixtures :each exec/with-clean-registry)` — pre-existing
     fixture, now backed by the thread-local override.
   - Pass `:base-fns` to `create-context` directly — the fully ctx-
     scoped pattern wired through integrant `:exec/base-fns` and the
     `:base-fns` ref on `:exec/context`.")


(defonce ^:private default-registry (atom {}))


(def ^:dynamic *registry-override*
  "Thread-local registry atom. When bound (by `with-clean-registry`),
   every `register-base-fn!` write goes here and every
   `get-base-fn` / `get-default-registry` read prefers this over the
   global. nil = no override → fall through to the global atom."
  nil)


(defn- target-atom
  "Returns the atom that should receive the next write OR be read for
   the current registry view — the thread-local override when bound,
   the global otherwise."
  []
  (or *registry-override* default-registry))


(defn register-base-fn!
  "Register `f` under `fn-name` (keyword) in the active registry
   (thread-local override when bound, global otherwise). `f` is
   `(fn [args ctx] …)` — see `exec/register-base-fn!`."
  [fn-name f]
  (swap! (target-atom) assoc fn-name f)
  nil)


(defn get-base-fn
  "Look up a base-fn by name in the active registry. Returns nil if
   absent. When a thread-local override is bound, falls through to
   the global registry for names the override doesn't carry — keeps
   primitives reachable in tests that only register a few stubs."
  [fn-name]
  (or (when *registry-override*
        (get @*registry-override* fn-name))
      (get @default-registry fn-name)))


(defn clear-base-fns!
  "Reset the active registry to empty. The thread-local override (when
   bound) is reset alone — the global is left untouched, so a
   `with-clean-registry`-scoped test can clear and re-populate
   without disturbing sibling tests on other threads."
  []
  (reset! (target-atom) {})
  nil)


(defn get-default-registry
  "Return the current registry map. Merges the global atom under the
   thread-local override (override wins on conflict) so callers see
   the full base-fn set — packages-loaded primitives plus any
   test stubs."
  []
  (if-let [override *registry-override*]
    (merge @default-registry @override)
    @default-registry))


(defn get-base-fn-from-context
  "Look up a base-fn by name in `context`'s `:base-fns` map. Returns nil
   if absent."
  [context fn-name]
  (get (:base-fns context) fn-name))
