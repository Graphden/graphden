(ns graphden.executor.compile
  "Compile graphden fn-defs into Clojure closures at startup.

   For each fn F we produce `(fn [all-fns free-args] result)` where:

   - `all-fns` is the map `{fn-id → compiled-closure}` (same map for every
     compiled closure — they look each other up by id for ref-resolution).

   - `free-args` is the runtime-provided map of values keyed by the caller-
     facing arg-name. An outer caller of F sees arg names the way F
     renames them (via `:as`); compile translates those names to the
     base-fn's own primary-arg names before invoking the impl.

   Stage 3 handled base fns + literal bindings + free args + `:as` renames.
   Stage 4 (this file) adds `:ref-id` handling: refs become 0-arity thunks
   wrapped with `rt/thunk`, captured by the closure, that look up the
   target fn in `all-fns` and invoke it with the same `free-args`. Thunks
   are stored in the args map under the base-name; impls either resolve
   them via `rt/resolve-arg` (normal args — eager by Clojure's rules) or
   leave them unresolved in conditional positions (e.g. `:if`'s `:then`
   branch — thunk only fires for the chosen branch).

   HOF `:fn` args (Stage 5) still throw today; they pass the compiled
   closure raw without the thunk wrap."
  (:require
    [graphden.executor.compile.bindings :as b]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.renames :as r]
    [graphden.executor.runtime :as rt]))


;; =============================================================================
;; Per-call memoization
;;
;; A single top-level invocation may visit the same ref-target via several
;; paths (e.g. `:router-ring-response` pulls `:status`, `:headers`, `:body`
;; — each depends on `:router-result`, which pulls `:internal-request`,
;; which slurps the request body). Without memoization each path re-runs
;; the chain; side-effecting impls like `slurp` read an exhausted stream
;; and return `""`.
;;
;; `*call-cache*` is a dynamic atom `{[fn-id free-args] → value}` scoped
;; to a single top-level closure invocation. Nested closure calls reuse
;; the outer cache; pure-standalone invocations (or test calls without a
;; top-level wrap) just skip caching.
;; =============================================================================

(def ^:dynamic ^{:private true} *call-cache* nil)


(defn- call-with-cache
  "Look up `(callee all-fns free-args)` in the current `*call-cache*` if
   bound; populate + return otherwise. Without a cache in scope, just
   invoke directly."
  [fn-id callee all-fns free-args]
  (if-let [cache *call-cache*]
    (let [k [fn-id free-args]
          cached (get @cache k ::miss)]
      (if (identical? cached ::miss)
        (let [v (callee all-fns free-args)]
          (swap! cache assoc k v)
          v)
        cached))
    (callee all-fns free-args)))


;; =============================================================================
;; Compile
;; =============================================================================
;;
;; Ref-rename translation (`:route → :pair → :conj` pattern) and
;; `deep-free-ext-names` live in `compile.renames`. See that ns for the
;; rationale behind rewriting call-site free-args keys at HOF/ref sites.

(defn- hof-wrap
  "Wrap a compiled closure into a callable for use as a HOF argument
   (`:fn`-type arg). Shape is **statically** determined by the
   classified `lambda-params` count (computed at compile time via
   `r/classify-hof-free`):

   - 0 lambda-params → variadic callable that ignores its input
     (constant-in-this-scope graph).
   - 1 lambda-param → `(fn [item] …)` — single-arg callable; impls
     call as `(f value)`. Item is bound to the lambda-param's name.
   - 2+ lambda-params → `(fn [m] …)` — map-callable; impls pass
     `{name v ...}` (e.g. middleware passes
     `{:request _ :next-handler _}`).

   `outer-free-args` carries CAPTURED names — values bound by the
   caller's chain via source-id. Lambda-param keys override on merge,
   so a name that happens to collide with an outer key still gets the
   per-call value (Clojure closure semantics: lambda-params shadow
   outer)."
  [compiled lambda-params all-fns outer-free-args]
  (case (count lambda-params)
    0 (fn [& _] (compiled all-fns outer-free-args))
    1 (let [n (first lambda-params)]
        (fn [item] (compiled all-fns (assoc outer-free-args n item))))
    (fn [m] (compiled all-fns (merge outer-free-args m)))))


(defn- resolve-seq-item
  "Resolve a single item-arg from a sequence chain into a runtime value.
   Literal items return their :value; ref items call the compiled ref
   with the caller's free-args (memoized via `*call-cache*`); named
   free-slot items (`{:as :name}` syntax — no value, no ref) read the
   value from `free-args` by name."
  [item all-fns free-args]
  (cond
    (some? (:value item)) (:value item)

    (:ref-id item)
    (let [ref-id (:ref-id item)
          callee (get all-fns ref-id)]
      (when-not callee
        (throw (ex-info "Sequence item ref target not found in all-fns"
                        {:type :runtime-error/missing-ref
                         :ref-id ref-id})))
      (call-with-cache ref-id callee all-fns free-args))

    (:name item)
    (let [v (get free-args (keyword (:name item)))]
      ;; Captured value may be a ref-thunk (when caller bound the
      ;; named slot to a fn-ref); deref so the impl receives the
      ;; computed value, matching the identity-wrapper pattern this
      ;; replaces.
      (cond
        (rt/thunk? v) (v)
        (instance? clojure.lang.IDeref v) @v
        :else v))

    :else nil))


(defn- make-ref-entry
  "Runtime value for a `:ref` binding. HOF refs (`is-fn=true`) get
   `hof-wrap`'d immediately against the env snapshot. Non-HOF refs
   become thunks: each invocation resolves env via `env-fn`, applies
   `ref-renames` (R's free-arg names → F's), and calls the compiled
   ref via the shared call-cache."
  [{:keys [ref-id is-fn hof-lambda-params ref-renames]} all-fns env-fn]
  (let [callee (get all-fns ref-id)]
    (when-not callee
      (throw (ex-info "Ref target not found in all-fns"
                      {:type :runtime-error/missing-ref
                       :ref-id ref-id})))
    (if is-fn
      (hof-wrap callee hof-lambda-params all-fns (env-fn))
      (rt/thunk
        #(let [fa (env-fn)
               r-args (if (seq ref-renames) (r/apply-renames fa ref-renames) fa)]
           (call-with-cache ref-id callee all-fns r-args))))))


(defn- make-seq-entry
  "Runtime value for a `:seq` binding — a thunk that materialises the
   linked-list items into a vector. Each item resolves via
   `resolve-seq-item` against the env snapshot at call time."
  [items all-fns env-fn]
  (rt/thunk #(mapv (fn [i] (resolve-seq-item i all-fns (env-fn))) items)))


(defn- build-args-and-aug
  "Produce `{:args _ :aug _}` from a fn's bindings in one pass.

   `:args` is keyed by **base-name** — handed to the impl.
   `:aug` is keyed by **ext-name** — merged into free-args so inner
   ref-chains that reference a parameter by its external name see
   the same value the impl sees. This is how we give fn-defs
   lexical-scope semantics (param is visible both to impl and to
   anything it composes into).

   `fa-ref` is a volatile holding the final free-args map (base +
   aug). Ref-thunks and HOF-wrap callables deref it at invoke time
   so they see every parameter, including ones whose aug entry was
   created in the same pass.

   Call-site rename: when ref R's free-arg ext-names differ from F's
   (e.g. `:path` vs `:item1`), `:ref-renames` translates keys before
   handing `free-args` to R's compiled closure.

   `:fn`-type refs (HOF) go through `hof-wrap` for HOF-style invoke.

   `:seq` bindings (linked-list-encoded sequences) materialise the item
   chain under a thunk wrapper for lazy access."
  [bindings all-fns fa-ref]
  (let [env-fn #(deref fa-ref)]
    (reduce
      (fn [{:keys [args aug] :as acc}
           {:keys [kind base-name ext-name value items] :as b}]
        (case kind
          :value {:args (assoc args base-name value)
                  :aug (assoc aug ext-name value)}

          :free (let [fa @fa-ref]
                  (if (contains? fa ext-name)
                    {:args (assoc args base-name (get fa ext-name))
                     :aug aug}        ; already in fa under ext-name
                    acc))

          :ref (let [entry (make-ref-entry b all-fns env-fn)]
                 {:args (assoc args base-name entry)
                  :aug (assoc aug ext-name entry)})

          :seq (let [entry (make-seq-entry items all-fns env-fn)]
                 {:args (assoc args base-name entry)
                  :aug (assoc aug ext-name entry)})))
      {:args {} :aug {}}
      bindings)))


(defn- enrich-is-fn-ref
  "Attach `:hof-lambda-params` to `binding` (a :ref binding with
   :is-fn=true). The list comes from `r/hof-lambda-params` —
   structurally-classified per-call slots of the HOF target as seen
   from the caller `fn-id`."
  [fn-id lookups binding]
  (assoc binding :hof-lambda-params
         (r/hof-lambda-params (:ref-id binding) fn-id lookups)))


(defn- enrich-ref-bindings
  "Precompute per-binding metadata that depends only on the graph shape:
   - `:hof-lambda-params` for `:is-fn` refs (consumed by `hof-wrap`).
   - `:ref-renames`       for non-HOF refs (consumed at call-site by
     `apply-renames` to map F's free-arg names onto R's). Refs without
     renames get an empty map."
  [fn-id bindings lookups]
  (mapv (fn [b]
          (cond
            (and (= :ref (:kind b)) (:is-fn b))
            (enrich-is-fn-ref fn-id lookups b)

            (= :ref (:kind b))
            (assoc b :ref-renames (r/build-ref-renames (:ref-id b) fn-id lookups))

            :else b))
        bindings))


(defn- augment-env
  "Merge env-bindings into `free-args`. Ref bindings become thunks
   captured over the OUTER (pre-augmentation) env. `:is-fn` refs are
   `hof-wrap`'d into callables so deeper consumers that see the
   binding through their `:fn`-typed primary can invoke it with a
   single arg (matching what inner `build-args-and-aug` would do for
   a directly-reached `:is-fn` primary binding)."
  [env-bindings all-fns free-args]
  (let [env-fn (constantly free-args)]
    (reduce
      (fn [acc {:keys [kind env-name value items] :as b}]
        (case kind
          :value (assoc acc env-name value)
          :ref (assoc acc env-name (make-ref-entry b all-fns env-fn))
          :seq (assoc acc env-name (make-seq-entry items all-fns env-fn))))
      free-args
      env-bindings)))


(defn- resolve-impl
  "Look up the base-fn impl for `fn-id`; throw with context on miss."
  [fn-id {:keys [fn-map base-fns]}]
  (let [base (l/base-fn-of fn-id fn-map)
        base-name-kw (keyword (:name base))]
    (if-let [impl (get base-fns base-name-kw)]
      impl
      (throw (ex-info (str "No impl registered for base fn " base-name-kw)
                      {:type :compile-error/missing-impl
                       :base-fn base-name-kw
                       :fn-id fn-id})))))


(defn- wrap-top-level
  "Every compiled closure acts as a potential top-level entry point, so
   it installs a fresh `*call-cache*` when one isn't already in scope.
   Sub-calls via thunks reuse the outer cache — memoizing side-effecting
   ref targets like `:ring-body` (slurp of a single-use InputStream) for
   the duration of one top-level call."
  [inner]
  (fn [all-fns free-args]
    (if *call-cache*
      (inner all-fns free-args)
      (binding [*call-cache* (atom {})]
        (inner all-fns free-args)))))


(defn- build-closure
  "Produce the compiled closure for one fn-id given its precomputed
   bindings and env-bindings. Wraps the call with the shared call-cache
   entry point.

   Flow:
   1. Start with caller's `free-args`, add env-bindings → `fa-base`.
   2. Point `fa-ref` at `fa-base` so ref-thunks / HOF wraps that
      reference free-args in the next step can already deref something.
   3. Build args-map + aug in one pass (`build-args-and-aug`).
   4. Merge aug into fa-base → the FINAL free-args visible to anything
      inside this fn's body (both impl, and inner ref-chains that
      propagate free-arg names outward).
   5. Reset `fa-ref` to the final map so the thunks / wraps created in
      step 3 see the complete lexical environment when they fire."
  [impl bindings env-bindings ctx]
  (wrap-top-level
    (fn [all-fns free-args]
      (let [fa-base (if (seq env-bindings)
                      (augment-env env-bindings all-fns free-args)
                      free-args)
            fa-ref (volatile! fa-base)
            {:keys [args aug]} (build-args-and-aug bindings all-fns fa-ref)
            ;; Primary bindings go into aug keyed by ext-name so inner
            ;; refs that reference a parameter by its external name see
            ;; the value. We merge WITHOUT shadowing entries already in
            ;; fa-base: a caller-provided free-arg wins over a local
            ;; primary binding that happens to use the same ext-name.
            ;; This matters for chains like :_router-compiled binding
            ;; `:routes :_router-normalized-routes` — the outer
            ;; :routes (the actual routes list from :_router) must
            ;; still flow through to the inner filter, otherwise the
            ;; primary-binding creates a cycle (inner filter sees the
            ;; thunk that computes `normalized-routes`, which itself
            ;; needs routes, which resolves back to the same thunk).
            final-fa (reduce-kv (fn [acc k v]
                                  (if (contains? acc k) acc (assoc acc k v)))
                                fa-base
                                aug)]
        (vreset! fa-ref final-fa)
        (impl args ctx)))))


(defn compile-fn
  "Produce the compiled closure for a single fn-id.

   `lookups` must already carry `:base-fns`. `ctx` is the execution-
   context the impl will receive as its second arg.

   Returns `(fn [all-fns free-args])`."
  [fn-id lookups ctx]
  (let [impl (resolve-impl fn-id lookups)
        bindings (enrich-ref-bindings fn-id (b/collect-bindings fn-id lookups) lookups)
        env-bindings (mapv (fn [b]
                             (if (and (= :ref (:kind b)) (:is-fn b))
                               (enrich-is-fn-ref fn-id lookups b)
                               b))
                           (b/collect-env-bindings fn-id lookups))]
    (build-closure impl bindings env-bindings ctx)))


;; =============================================================================
;; Entry point
;; =============================================================================

(defn compile-all
  "Compile every fn in `fns` into a map `{fn-id → compiled-closure}`.

   `base-fns` is a registry `{fn-name-keyword → impl-fn}` (from
   exec-context). `ctx` is the execution-context the impls will receive."
  [{:keys [fns args base-fns]} ctx]
  (let [lookups (assoc (l/build-lookups fns args) :base-fns base-fns)]
    (into {}
          (map (fn [f]
                 [(:id f) (compile-fn (:id f) lookups ctx)]))
          fns)))
