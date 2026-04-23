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


;; Lookup helpers moved to `graphden.executor.compile.lookups`. Re-exports
;; keep the 4 externally-used names resolvable at the old path.
(def build-lookups l/build-lookups)
(def base-fn-of l/base-fn-of)
(def terminal-primary-id l/terminal-primary-id)
(def arg-ext-name l/arg-ext-name)


;; `collect-bindings` + `collect-env-bindings` live in `compile.bindings`.
;; Re-exports below keep existing callers of `compile/collect-bindings`
;; (compile-runtime) and internal users resolvable without path churn.
(def collect-bindings b/collect-bindings)
(def collect-env-bindings b/collect-env-bindings)


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
   (`:fn`-type arg). Shape is determined by the wrapped fn-graph's
   free-arg count (`free-names` — already env-aware via
   `deep-free-ext-names`). The compiler does NOT inspect names;
   whatever the author called their free args is what gets used.

   - 0 free names → variadic callable that ignores its input (impl can
     call with anything, even when the wrapped graph is constant).
   - 1 free name → `(fn [item] …)` — single-arg callable; the item is
     bound to that name. Impls call as `(f value)`. The author's name
     choice becomes the binding key — no compiler magic.
   - 2+ free names → `(fn [m] …)` — map-callable. Impl passes
     `{name v ...}` (e.g. middleware passes
     `{:request _ :next-handler _}`). The user-fn's free arg names
     must match impl's chosen keys (ordinary API contract).

   `outer-free-args` (caller's runtime free-args) is propagated
   through; when the HOF call provides a value for a name already in
   `outer`, the HOF call OVERRIDES (assoc/merge semantics)."
  [compiled free-names all-fns outer-free-args]
  (case (count free-names)
    0 (fn [& _] (compiled all-fns outer-free-args))
    1 (let [n (first free-names)]
        (fn [item] (compiled all-fns (assoc outer-free-args n item))))
    (fn [m] (compiled all-fns (merge outer-free-args m)))))


(defn- resolve-seq-item
  "Resolve a single item-arg from a sequence chain into a runtime value.
   Literal items return their :value; ref items call the compiled ref
   with the caller's free-args (memoized via `*call-cache*`)."
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

    :else nil))


(defn- build-args-map
  "Given enriched bindings plus runtime `all-fns` and `free-args`, build
   the args map passed to the impl (keyed by base-arg-names).

   Ref bindings become 0-arity thunks that, when invoked, look the ref
   target up in `all-fns` and run its compiled closure with the same
   `free-args` (so propagated free values reach the callee). The thunk
   is wrapped with `rt/thunk` so `rt/resolve-arg` knows to call it.

   Call-site rename: when ref R's free-arg ext-names differ from F's
   (e.g. `:path` vs `:item1`), `:ref-renames` translates keys before
   handing `free-args` to R's compiled closure.

   `:fn`-type refs (HOF) bypass the thunk and use `hof-wrap` to produce
   a single-arg/multi-arg callable for the HOF impl to apply.

   `:seq` bindings (linked-list-encoded sequences) materialise the item
   chain: each item is either a literal or a ref-call that runs the
   compiled target. Under a thunk wrapper so impls wanting lazy access
   (e.g. potentially empty sequence collected on demand) still behave."
  [bindings all-fns free-args]
  (reduce
    (fn [acc {:keys [kind base-name ext-name value ref-id is-fn hof-free-names ref-renames items]}]
      (case kind
        :value (assoc acc base-name value)

        :free (if (contains? free-args ext-name)
                (assoc acc base-name (get free-args ext-name))
                acc)

        :ref (let [callee (get all-fns ref-id)]
               (when-not callee
                 (throw (ex-info "Ref target not found in all-fns"
                                 {:type :runtime-error/missing-ref
                                  :base-name base-name
                                  :ref-id ref-id})))
               (if is-fn
                 (assoc acc base-name (hof-wrap callee hof-free-names all-fns free-args))
                 (let [r-args (if (seq ref-renames)
                                (r/apply-renames free-args ref-renames)
                                free-args)]
                   (assoc acc base-name
                          (rt/thunk #(call-with-cache ref-id callee all-fns r-args))))))

        :seq (assoc acc base-name
                    (rt/thunk #(mapv (fn [i] (resolve-seq-item i all-fns free-args)) items)))))
    {}
    bindings))


(defn- enrich-ref-bindings
  "Precompute per-binding metadata that depends only on the graph shape:
   - `:hof-free-names` for `:is-fn` refs (used by `hof-wrap`). These
     are the TRULY-unbound free args of the ref target —
     `deep-free-ext-names` already drops names that intermediate
     env-bindings will fill, so what's left is what HOF callers must
     inject.
   - `:ref-renames`   for non-HOF refs (used at call-site by
     `apply-renames` to map F's free-arg names onto R's). Refs without
     renames get an empty map."
  [fn-id bindings lookups]
  (mapv (fn [b]
          (cond
            (and (= :ref (:kind b)) (:is-fn b))
            (assoc b :hof-free-names (r/deep-free-ext-names (:ref-id b) lookups))

            (= :ref (:kind b))
            (assoc b :ref-renames (r/build-ref-renames (:ref-id b) fn-id lookups))

            :else b))
        bindings))


(defn- augment-env
  "Merge env-bindings into `free-args`. Ref bindings become thunks
   captured over the OUTER (pre-augmentation) env. is-fn refs pass the
   compiled callable raw — consumers decide how to invoke it."
  [env-bindings all-fns free-args]
  (reduce
    (fn [acc {:keys [kind env-name value ref-id is-fn items]}]
      (case kind
        :value (assoc acc env-name value)

        :ref (let [callee (get all-fns ref-id)]
               (if is-fn
                 (assoc acc env-name callee)
                 (assoc acc env-name
                        (rt/thunk #(call-with-cache ref-id callee all-fns free-args)))))

        :seq (assoc acc env-name
                    (rt/thunk #(mapv (fn [i] (resolve-seq-item i all-fns free-args)) items)))))
    free-args
    env-bindings))


(defn- resolve-impl
  "Look up the base-fn impl for `fn-id`; throw with context on miss."
  [fn-id {:keys [fn-map base-fns]}]
  (let [base (base-fn-of fn-id fn-map)
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
   entry point."
  [impl bindings env-bindings ctx]
  (wrap-top-level
    (if (seq env-bindings)
      (fn [all-fns free-args]
        (let [aug (augment-env env-bindings all-fns free-args)]
          (impl (build-args-map bindings all-fns aug) ctx)))
      (fn [all-fns free-args]
        (impl (build-args-map bindings all-fns free-args) ctx)))))


(defn compile-fn
  "Produce the compiled closure for a single fn-id.

   `lookups` must already carry `:base-fns`. `ctx` is the execution-
   context the impl will receive as its second arg.

   Returns `(fn [all-fns free-args])`."
  [fn-id lookups ctx]
  (let [impl (resolve-impl fn-id lookups)
        bindings (enrich-ref-bindings fn-id (collect-bindings fn-id lookups) lookups)
        env-bindings (collect-env-bindings fn-id lookups)]
    (build-closure impl bindings env-bindings ctx)))


;; =============================================================================
;; Entry point
;; =============================================================================

(defn compile-all
  "Compile every fn in `fns` into a map `{fn-id → compiled-closure}`.

   `base-fns` is a registry `{fn-name-keyword → impl-fn}` (from
   exec-context). `ctx` is the execution-context the impls will receive."
  [{:keys [fns args base-fns]} ctx]
  (let [lookups (assoc (build-lookups fns args) :base-fns base-fns)]
    (into {}
          (map (fn [f]
                 [(:id f) (compile-fn (:id f) lookups ctx)]))
          fns)))
