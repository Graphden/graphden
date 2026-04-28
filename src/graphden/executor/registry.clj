(ns graphden.executor.registry
  "Global registry of base-fn implementations.

   Packages register here at startup via `register-base-fns!` in
   `registry/interface`. Tests clear and re-register as needed.

   For isolated tests that need a different registry without touching
   global state, pass `:base-fns` to `create-context`.")


(defonce ^:private default-registry (atom {}))


(defn register-base-fn!
  "Register `f` under `fn-name` (keyword) in the global registry. `f` is
   `(fn [args ctx] …)` — see `exec/register-base-fn!`."
  [fn-name f]
  (swap! default-registry assoc fn-name f)
  nil)


(defn get-base-fn
  "Look up a base-fn by name in the global registry. Returns nil if
   absent."
  [fn-name]
  (get @default-registry fn-name))


(defn clear-base-fns!
  "Reset the global registry to empty. Used by test fixtures."
  []
  (reset! default-registry {})
  nil)


(defn get-default-registry
  "Return the current global registry map."
  []
  @default-registry)


(defn get-base-fn-from-context
  "Look up a base-fn by name in `context`'s `:base-fns` map. Returns nil
   if absent."
  [context fn-name]
  (get (:base-fns context) fn-name))
