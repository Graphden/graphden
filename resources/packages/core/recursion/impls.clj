(ns graphden.packages.core.recursion.impls
  "Implementation for `:fix` — graph-level recursion primitive.

   Design: `:fix` synthesises a self-referential callable at runtime
   so the user's `:step` fn can recurse by invoking `:self` — without
   ever introducing a structural cycle in the fn-graph. The cycle
   invariants (per-binding + topological-sort) stay strict; recursion
   is a runtime mechanism, not a graph-level one. See
   `docs/RECURSION.md § Approach A` for the full design rationale.

   Depth-bounded: `*max-recursion-depth*` (default 1000) caps how
   deep `:self` may re-enter step. A runaway non-terminating step
   throws `:recursion-error/max-depth-exceeded` instead of blowing
   the JVM stack."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.config :as config]))


(defbase fix-fn
  "Invoke `step` with `{:input <initial> :self <self-callable>}`,
   where `:self` is a 1-arg Clojure callable that re-enters `step`
   with a new input. Returns whatever `step` returns at the base
   case."
  [step input]
  (let [self-ref (atom nil)
        depth (atom 0)
        max-depth config/*max-recursion-depth*
        ;; `f` is the runtime `:self`. It accepts the next input as
        ;; a single positional arg (so step's body can invoke it via
        ;; `:invoke :func :self :arg <next>`), bumps the depth
        ;; counter, then calls step with the map `{:input next :self
        ;; f}` — step is hof-wrap'd as a map-callable because its
        ;; slot type declares 2 call-site args (`:input` + `:self`).
        f (fn [next-input]
            (when (> (swap! depth inc) max-depth)
              (throw (ex-info (str ":fix recursion exceeded *max-recursion-depth* ("
                                   max-depth ")")
                              {:type :recursion-error/max-depth-exceeded
                               :max-depth max-depth})))
            (try
              (step {:input next-input :self @self-ref})
              (finally
                (swap! depth dec))))]
    (reset! self-ref f)
    (f input)))


(def impls
  {:fix fix-fn})
