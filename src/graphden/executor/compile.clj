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


;; Set of fn-ids that must ALWAYS fire fresh, bypassing the per-top-level
;; *call-cache* even when one is bound. Refreshed on every `compile-all`
;; from the registry: any fn whose `:effects` intersects
;; #{:time :random} lands here.
;;
;; Other effectful categories (`:env`, `:io`, `:db`, `:network`) ARE
;; cacheable within one top-level invocation — env values don't
;; change mid-request, DB rows are consistent under one txn, etc. —
;; so e.g. `:ring-body`'s single-use InputStream slurp gets shared
;; across sibling consumers as before. Wall-clock and rng are the
;; only categories where two adjacent reads in the same request must
;; see different values.
(def ^:private always-fresh-fn-ids (atom #{}))


(defn set-always-fresh-fn-ids!
  "Replace the set of always-fresh (cache-bypass) fn-ids. Called by
   `compile-all` after collecting `:effects` from the registry —
   anything tagged `:time` / `:random`. Idempotent."
  [ids]
  (reset! always-fresh-fn-ids (set ids)))


(defn- call-with-cache
  "Look up `(callee all-fns free-args)` in the current `*call-cache*`
   if one is bound and the target isn't always-fresh; populate +
   return otherwise. Time / random fns ALWAYS fire fresh — see
   `always-fresh-fn-ids`."
  [fn-id callee all-fns free-args]
  (cond
    (contains? @always-fresh-fn-ids fn-id)
    (callee all-fns free-args)

    (some? *call-cache*)
    (let [cache *call-cache*
          k [fn-id free-args]
          cached (get @cache k ::miss)]
      (if (identical? cached ::miss)
        (let [v (callee all-fns free-args)]
          (swap! cache assoc k v)
          v)
        cached))

    :else (callee all-fns free-args)))


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
   outer).

   This function IS the wrap-time-capture half of graphden's closure-
   capture model — see `docs/CLOSURE_CAPTURE.md`. Each invocation of
   the parent fn snapshots `outer-free-args` at this point, and the
   returned callable carries that snapshot forward (into a `:future`
   thread, into `:map`'s lambda call site, etc.). The fn-graph it
   wraps thus resolves CAPTURED arg names against the binding-chain
   at wrap site, while CALL-SITE arg names flow in via the
   lambda-param merge."
  [compiled lambda-params all-fns outer-free-args]
  (case (count lambda-params)
    0 (fn [& _] (compiled all-fns outer-free-args))
    1 (let [n (first lambda-params)]
        (fn [item] (compiled all-fns (assoc outer-free-args n item))))
    (fn [m] (compiled all-fns (merge outer-free-args m)))))


(defn- resolve-seq-item
  "Resolve a single binding-list-item into a runtime value:
   - `{:value v :literal true}` → literal v (codec keyword pass-through).
   - `{:value {:as :name} :literal nil}` → POSITIONAL FREE SLOT —
     read `name` from free-args, substituting the caller-supplied
     value at this position. Used by `:route :args {:items [{:as :path}
     :method-map]}` so descendants binding `:path \"/health\"` flow
     into the list.
   - `:ref-fn-id` → execute the ref via call-cache.
   - Plain `:value` → literal."
  [item all-fns free-args]
  (cond
    ;; Positional rename: free-arg substitution.
    (and (map? (:value item)) (:as (:value item)) (not (:literal item)))
    (let [k (some-> (:as (:value item)) keyword)
          v (get free-args k)]
      (cond
        (graphden.executor.runtime/thunk? v) (v)
        (instance? clojure.lang.IDeref v) @v
        :else v))

    (some? (:value item)) (:value item)

    (:ref-fn-id item)
    (let [ref-id (:ref-fn-id item)
          callee (get all-fns ref-id)]
      (when-not callee
        (throw (ex-info "Sequence item ref target not found in all-fns"
                        {:type :runtime-error/missing-ref
                         :ref-id ref-id})))
      (call-with-cache ref-id callee all-fns free-args))

    :else nil))


(defn- make-ref-entry
  "Runtime value for a `:ref` binding. Three cases:

   - `:produces-callable?` — the bound fn-graph's `:return-type` is
     itself a fn-type, so EVALUATING the graph produces a Clojure
     callable. Thunk it: each invocation resolves env, runs the
     fn-graph, and the result IS the callable the consumer needs.
     `:_router` → reitit ring-handler is the canonical case. Skip
     `hof-wrap` (it would double-wrap a value that's already a
     positional callable).

   - HOF ref (`:is-fn=true`, not callable-producer) — the fn-graph
     IS the callable; wrap it into the positional shape Clojure
     consumers expect via `hof-wrap`.

   - Plain ref — thunk: each invocation resolves env, applies
     `ref-renames` (R's free-arg names → F's), calls the compiled
     ref via the shared call-cache."
  [{:keys [ref-id is-fn produces-callable? hof-lambda-params ref-renames]}
   all-fns env-fn]
  (let [callee (get all-fns ref-id)]
    (when-not callee
      (throw (ex-info "Ref target not found in all-fns"
                      {:type :runtime-error/missing-ref
                       :ref-id ref-id})))
    (if (and is-fn (not produces-callable?))
      (hof-wrap callee hof-lambda-params all-fns (env-fn))
      (rt/thunk
        #(let [fa (env-fn)
               r-args (if (seq ref-renames) (r/apply-renames fa ref-renames) fa)]
           (call-with-cache ref-id callee all-fns r-args))))))


(defn- resolve-seq-items
  "Lazily resolve binding-list-items into runtime values. Hand-rolled
   UNCHUNKED lazy-seq — deliberately NOT `map`, whose chunking would
   realise up to 32 elements (and execute their ref-items) at once.
   Each element is realised only when the consumer actually reaches
   it. This is what makes `:and` / `:or` short-circuit: `every?` /
   `some` stop at the first decisive element, so a side effect in a
   later element never runs.

   Note the limit: reaching element N executes element N. A consumer
   that must STEP PAST an element without running it (`:cond` skipping
   an un-taken clause's result) needs `resolve-seq-thunks` instead."
  [items all-fns env-fn]
  (lazy-seq
    (when-let [s (seq items)]
      (cons (resolve-seq-item (first s) all-fns (env-fn))
            (resolve-seq-items (rest s) all-fns env-fn)))))


(defn- resolve-seq-thunks
  "Like `resolve-seq-items`, but each element is a `delay` over its
   resolution rather than the resolved value. Realising a spine cell
   only BUILDS the delay — the ref executes solely when the consumer
   `force`s that delay.

   This is what a flat `:cond` needs: `cond-fn` forces a clause's test
   delay, and on a falsy test steps past the result delay via `nnext`
   WITHOUT forcing it — so an un-taken clause's result is never
   executed. With plain `resolve-seq-items` the step-past (`rest`)
   would realise — and thereby execute — that result.

   Used only for slots a base-fn marked `:lazy-seq-args`."
  [items all-fns env-fn]
  (lazy-seq
    (when-let [s (seq items)]
      (cons (delay (resolve-seq-item (first s) all-fns (env-fn)))
            (resolve-seq-thunks (rest s) all-fns env-fn)))))


(defn- make-seq-entry
  "Runtime value for a `:seq` binding — a thunk that materialises the
   linked-list items into an unchunked lazy-seq so consumers only
   force the elements they reach. Each item resolves against the env
   snapshot at call time.

   `lazy?` true (slot is in its root base-fn's `:lazy-seq-args`) →
   elements are `delay`s (`resolve-seq-thunks`); the consumer forces
   them selectively. Otherwise elements are resolved values
   (`resolve-seq-items`)."
  [items all-fns env-fn lazy?]
  (rt/thunk #(if lazy?
               (resolve-seq-thunks items all-fns env-fn)
               (resolve-seq-items items all-fns env-fn))))


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
                 ;; Don't add :ref bindings to `aug`. The aug map's job
                 ;; is to expose KNOWN VALUES under their renamed names
                 ;; so inner ref-chains see them as free-args. A :ref
                 ;; binding is a deferred call — putting its thunk in
                 ;; aug under ext-name makes downstream consumers
                 ;; resolve `ext-name` by re-invoking the same ref,
                 ;; which then re-reads `ext-name` from free-args, and
                 ;; cycles. (`:method-map :value {:as :handler :ref
                 ;; :assoc-handler}` was the canonical bug.)
                 {:args (assoc args base-name entry)
                  :aug aug})

          :seq (let [entry (make-seq-entry items all-fns env-fn
                                           (:lazy-seq? b))]
                 ;; Same reasoning as :ref — `:seq` items resolve
                 ;; through their own thunks; exposing the same thunk
                 ;; under ext-name in free-args is just an alias that
                 ;; doesn't help any consumer that needs an evaluated
                 ;; sequence value.
                 {:args (assoc args base-name entry)
                  :aug aug})))
      {:args {} :aug {}}
      bindings)))


(defn- enrich-is-fn-ref
  "Attach `:hof-lambda-params` to `bnd` (a :ref binding with
   :is-fn=true). The list comes from `r/hof-lambda-params` —
   structurally-classified per-call slots of the HOF target as seen
   from the caller `fn-id`. The slot id + binding row let
   `hof-lambda-params` read the slot's structural `[:fn {ARGS} _]`
   shape (when present) and use it as the authority for what's
   call-site vs captured (closure-capture; docs/CLOSURE_CAPTURE.md)."
  [fn-id lookups bnd]
  (assoc bnd :hof-lambda-params
         (r/hof-lambda-params (:ref-id bnd) (:slot-id bnd) bnd fn-id lookups)))


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
  "Merge env-bindings into `free-args`. Ref/seq bindings become thunks
   that read free-args via `env-fn` at fire time — passing `fa-ref`
   here (rather than a `(constantly free-args)` snapshot) lets every
   env-binding's thunk see SIBLING env-bindings that landed in the
   same augment pass. Without that, thunks captured by `augment-env`
   only see the pre-augmentation slice, so e.g. an inner `:_action-
   status` looking up `:result` on a sibling env-binding would read
   nil and fall back to its `:default 200` instead of seeing the
   process-update-entity result.

   `:is-fn` refs are `hof-wrap`'d immediately against the live env
   snapshot — that callable is then stable for descendants that
   invoke it through their `:fn`-typed primary slot."
  [env-bindings all-fns free-args fa-ref]
  (let [env-fn #(deref fa-ref)]
    (reduce
      (fn [acc {:keys [kind env-name value items] :as b}]
        (case kind
          :value (assoc acc env-name value)
          :ref (assoc acc env-name (make-ref-entry b all-fns env-fn))
          :seq (assoc acc env-name (make-seq-entry items all-fns env-fn
                                                   (:lazy-seq? b)))))
      free-args
      env-bindings)))


(defn- resolve-impl
  "Look up the impl for `fn-id`'s root. The root is either a base-fn
   (impl-hash set) or a type-row (record / refinement / list) with a
   synthesised impl registered under the same name."
  [fn-id {:keys [fn-map base-fns]}]
  (let [root (l/root-fn fn-id fn-map)
        root-name (some-> (:name root) keyword)]
    (cond
      (nil? root)
      (throw (ex-info (str "No root fn reachable from " fn-id)
                      {:type :compile-error/missing-root
                       :fn-id fn-id}))

      (nil? root-name)
      (throw (ex-info (str "Root fn for " fn-id " has no name — anonymous "
                           "type-rows aren't directly executable")
                      {:type :compile-error/anonymous-root
                       :fn-id fn-id
                       :root-id (:id root)}))

      :else
      (if-let [impl (get base-fns root-name)]
        impl
        (throw (ex-info (str "No impl registered for " root-name)
                        {:type :compile-error/missing-impl
                         :base-fn root-name
                         :fn-id fn-id}))))))


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
      (let [fa-ref (volatile! free-args)
            fa-base (if (seq env-bindings)
                      (augment-env env-bindings all-fns free-args fa-ref)
                      free-args)
            _ (vreset! fa-ref fa-base)
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
  "Compile every fn into a map `{fn-id → compiled-closure}`.

   Input: `{:fns :slots :fn-slots :bindings :list-items :base-fns}` —
   the slot/fn-slot/binding model entities plus the impl registry.
   `ctx` is the execution-context impls will receive.

   Skips fns whose root has no impl (anonymous types, primitives) —
   those aren't directly executable. They still get stored as data in
   the graph for type-checking and editor display."
  [{:keys [fns base-fns] :as graph} ctx]
  (let [lookups (assoc (l/build-lookups graph) :base-fns base-fns)
        compilable? (fn [f]
                      (let [root (l/root-fn (:id f) (:fn-map lookups))
                            root-name (some-> (:name root) keyword)]
                        (and root-name (contains? base-fns root-name))))]
    (into {}
          (keep (fn [f]
                  (when (compilable? f)
                    [(:id f) (compile-fn (:id f) lookups ctx)])))
          fns)))


;; =============================================================================
;; Forward / reverse dependency index — foundation for delta-invalidation.
;; =============================================================================
;;
;; A fn F's compiled closure becomes stale when ANY of these change:
;;   - F's own row fields the compiler reads (`parent-ids`, `base-fn-id`,
;;     `element-fn-id`, `return-type-fn-id`).
;;   - F's bindings + binding-list-items.
;;   - The same data on any ancestor of F (inheritance flows in at
;;     compile time via `inheritance-chain`).
;;   - The rich-type/return-type metadata of any ref target F binds
;;     (the `:produces-callable?` flag is baked in at compile time).
;;
;; Each item above is reachable from F via at most one of:
;;   - parent-ids (junction)
;;   - fn FK columns (base/element/return-type)
;;   - bindings whose `fn-id = F`           → ref-fn-id, type-override-fn-id
;;   - binding-list-items under those       → ref-fn-id
;;
;; `forward-deps-of` returns this raw edge set for a single fn (not the
;; inheritance closure — `transitive-blast` handles that walk by
;; following the index). `build-reverse-deps` inverts it so a mutation
;; on X can find every F that mentions X.
;;
;; The reverse index is recomputed on every `rebuild!` and replaces the
;; legacy "drop the whole compiled-registry on every invalidation"
;; behaviour: `delta-recompile!` walks the inverse closure of the
;; changed fn-ids and rebuilds ONLY those entries.

(defn- bindings-of
  [fn-id bindings]
  (filter #(= fn-id (:fn-id %)) bindings))


(defn- items-of
  [binding-ids list-items]
  (filter #(contains? binding-ids (:binding-id %)) list-items))


(defn forward-deps-of
  "Set of fn-ids whose mutation invalidates `fn-id`'s closure. Edge
   sources mirror what `compile-fn` reads at compile time. Conservative
   — better to recompile a few extras than to ship a stale closure."
  [fn-id {:keys [fns bindings list-items]}]
  (let [f (get fns fn-id)
        bs (bindings-of fn-id bindings)
        binding-ids (into #{} (map :id) bs)
        items (items-of binding-ids list-items)]
    (into #{}
          (comp cat (filter some?))
          [(:parent-ids f)
           (keep f [:base-fn-id :element-fn-id :return-type-fn-id])
           (keep :ref-fn-id bs)
           (keep :type-override-fn-id bs)
           (keep :ref-fn-id items)])))


(defn build-reverse-deps
  "Produce `{fn-id → #{ids that depend on it}}` over the whole graph.
   Inverts `forward-deps-of` once so delta-invalidation can answer
   \"who needs to recompile when X changes?\" in `O(degree)` per
   level. Recomputed on every full rebuild."
  [{:keys [fns] :as graph}]
  (let [fns-map (if (map? fns) fns (into {} (map (juxt :id identity)) fns))
        graph' (assoc graph :fns fns-map)]
    (reduce
      (fn [acc f]
        (reduce (fn [a dep] (update a dep (fnil conj #{}) (:id f)))
                acc
                (forward-deps-of (:id f) graph')))
      {}
      (vals fns-map))))


(defn transitive-blast
  "Inverse-closure walk over `reverse-deps`. Returns every fn-id that
   transitively depends on at least one of `seed-ids`. The seeds are
   included in the result — their own closures need recompile too."
  [reverse-deps seed-ids]
  (loop [seen #{}
         q (vec seed-ids)]
    (if (empty? q)
      seen
      (let [x (peek q), q' (pop q)]
        (if (contains? seen x)
          (recur seen q')
          (recur (conj seen x)
                 (into q' (get reverse-deps x #{}))))))))
