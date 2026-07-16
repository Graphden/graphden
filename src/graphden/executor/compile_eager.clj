(ns graphden.executor.compile-eager
  "Eager compile pipeline — the executor's compile half.

   Each fn-def compiles to an ordinary Clojure closure
   `(fn [free-args ctx])`. On invocation the closure performs ONLY:

     1. Read free-arg values by their final external names (renames
        applied at compile time — there is no runtime rename pass).
     2. Invoke pre-captured child callables (captured at compile time
        by topological sort of the ref-DAG; no per-call registry
        lookup).
     3. Call the impl with a `defbase`-shaped args map.

   Lazy semantics come from Clojure-native form evaluation — every
   `:ref` arg is a `delay`, `resolve-arg` `@`-derefs it, so
   `(if test then else)` only forces the picked branch. No
   `:lazy-args` markers or other flags: lazy is built in.

   `graphden.clients.vault` is resolved lazily in the
   `:secret-value` arg-builder so test runs that never touch a
   secret slot don't pay its load cost."
  (:require
    [graphden.executor.compile.bindings :as b]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.renames :as r]
    [graphden.executor.runtime :as rt]
    [graphden.util.counters :as counters]))


;; =============================================================================
;; Per-execute DRY memo
;; =============================================================================
;;
;; One top-level closure invocation = one HashMap stored under
;; `::call-cache` in `ctx`. Every sibling `:ref` invocation in the
;; sub-tree hits the cache on `[ref-id fa]`, so a fn-def that pulls a
;; ref TWICE — once for validation, once for the success branch — only
;; fires the child ONCE. Without this, side-effecting impls like
;; `:create-entity` insert twice → unique-violation; pure impls just
;; waste work but still compute the right value.
;;
;; `always-fresh-fn-ids` carries impls whose `:effects` include `:time`
;; or `:random` — these must fire fresh on every read even within one
;; top-level call (two adjacent clock reads must see different values).
;; `:env` / `:io` / `:db` / `:network` ARE cacheable within one
;; top-level call (env values don't change mid-request, the txn sees a
;; consistent DB snapshot, etc.).

;; ^:dynamic so the parallel kaocha plugin can shadow per-NS-thread.
;; Production hands prod a single shared atom via the root binding; tests
;; running on isolated NS-threads see fresh atoms via
;; `kaocha.plugin.parallel/isolation-vars`. Without this, two sibling
;; NSes both calling `compile-runtime/rebuild!` race on the set —
;; whoever lands last wins, and the loser's `:time`/`:random` fn-ids
;; drop out of always-fresh, masking timing-sensitive tests.
(def ^:dynamic *always-fresh-fn-ids* (atom #{}))


(defn set-always-fresh-fn-ids!
  "Refresh the set of always-fresh (cache-bypass) fn-ids — anything
   whose registered `:effects` intersects `#{:time :random}`. Called
   by `compile_runtime`'s `rebuild!` / `delta-recompile!` after a
   compile pass, since the set drives every `:ref` invocation."
  [ids]
  (reset! *always-fresh-fn-ids* (set ids)))


(defn- fa-key-for-cache
  "Project `fa` to the subset the ref-target actually reads —
   `ref-frees` from `r/cache-projection-frees`. The walker is a
   strict superset of `deep-free-ext-names`: it walks INTO HOF
   bodies (`:is-fn :ref` bindings) and subtracts each boundary's
   `hof-lambda-params`, so closure-captured names a HOF body reads
   from caller's `fa` at wrap time DO land in the cache key.
   Without this, two invocations of the same outer fn-graph with
   different closure-captured values (e.g. each secret's `:fn-row`
   in `_shape-secret-bindings`) collapse to one cache slot and
   every caller sees the FIRST result — `GET /api/secrets` returned
   every row with the first secret's `:path` until this wiring
   landed.

   `nil` → unknown set, full `fa` as the key (defensive fallback)."
  [ref-frees fa]
  (if (nil? ref-frees)
    fa
    (if (empty? ref-frees)
      {}
      (select-keys fa ref-frees))))


(def ^:private call-cache-max-size
  "Cap on the per-execute call-cache. Average entry size observed
   ~180 KB (large maps, JSON strings, etc.) on /api/graph/entities-
   sized requests. Bound by SIZE × N empirically:

   - 10,000 entries (initial guess): hit 1.8 GB heap (OOM verified)
   - 1,000 entries (overcorrection): no OOM but 4 tests degraded
     because the cache cleared too aggressively during legitimate
     working-set repeats (loadSecrets went 5.3 s > 5 s budget)
   - 5,000 entries (chosen): ~900 MB worst-case, fits under 2.25 GB
     heap with room for the rest of the live working set; keeps
     enough hit-rate that no test trips its perf budget.

   When the cap is hit we clear the whole cache — simple, single-
   threaded, no LRU machinery; the next miss repopulates lazily."
  5000)


(defn- call-with-cache
  "Invoke `(child fa ctx)` through the per-execute memo. Cache key is
   `[ref-id projected-fa]` where projected-fa is `fa` restricted to
   the ref-target's declared free args. Cache miss / absent cache /
   always-fresh fn-id all fall through to a fresh call. `::nil`
   sentinel distinguishes a cached `nil` from miss.

   The cache clears itself when it reaches `call-cache-max-size` —
   prevents pathological per-request growth (see the size constant's
   doc for the empirical motivation)."
  [ref-id ref-frees child fa ctx]
  (let [^java.util.HashMap cache (::call-cache ctx)]
    (if (or (nil? cache) (contains? @*always-fresh-fn-ids* ref-id))
      (child fa ctx)
      (let [k [ref-id (fa-key-for-cache ref-frees fa)]
            cached (java.util.HashMap/.get cache k)]
        (if (some? cached)
          (when-not (identical? cached ::nil) cached)
          (let [v (child fa ctx)]
            (when (>= (java.util.HashMap/.size cache) call-cache-max-size)
              (java.util.HashMap/.clear cache))
            (java.util.HashMap/.put cache k (if (nil? v) ::nil v))
            v))))))


(defn- has-impl?
  "Root-fn carries a registered Clojure impl. Type-rows return false
   and never enter the compile pipeline."
  [fn-id {:keys [fn-map base-fns] :as lookups}]
  (boolean (some-> (l/root-fn fn-id fn-map lookups)
                   :name keyword
                   base-fns)))


(defn- supported-shapes?
  "True iff every binding shape `fn-id` carries is supported by the
   current compile-eager stage. With Stage 4 every classify-slot
   kind (`:value` / `:free` / `:ref` / `:seq` / `:secret-value`)
   has a builder, so every fn whose root has an impl is now
   compilable — this check stays here as a guard against future
   `classify-slot` additions until they get their builder."
  [fn-id lookups]
  (every? (fn [bnd]
            (case (:kind bnd)
              (:value :free :seq :ref :secret-value) true
              false))
          (b/collect-bindings fn-id lookups)))


(defn- ref-deps
  "Set of fn-ids that compile of `fn-id` depends on at runtime.

   Looks at `b/collect-bindings` AND `b/collect-env-bindings`
   (the SAME sources of truth `compile-fn` uses), so inherited
   bindings and env-bindings (context-propagation slots like
   `:base-handler`) are included. Anything ref'd inside a `:seq`
   binding is included via the binding-list-item rows that
   `collect-bindings` materialises under `:items`."
  [fn-id lookups]
  (let [from-bnd (fn [acc bnd]
                   (case (:kind bnd)
                     :ref (conj acc (:ref-id bnd))
                     :seq (into acc (keep :ref-fn-id (:items bnd)))
                     acc))]
    (reduce from-bnd
            (reduce from-bnd #{} (b/collect-bindings fn-id lookups))
            (b/collect-env-bindings fn-id lookups))))


(defn- resolve-impl
  [fn-id {:keys [fn-map base-fns] :as lookups}]
  (let [root-name (some-> (l/root-fn fn-id fn-map lookups) :name keyword)]
    (or (get base-fns root-name)
        (throw (ex-info (str "No impl for base-fn " (pr-str root-name))
                        {:type :compile/missing-impl
                         :fn-id fn-id :base-fn-name root-name})))))


(defn- force-value
  "Force a deferred value read out of `free-args`. Refs propagate
   through `free-args` as `rt/thunk`s or `delay`s so the impl's
   `resolve-arg` can short-circuit — anything BUT `resolve-arg`
   that reads a free-arg value (seq item positional renames being
   the only path) must force here so the consumer sees the
   underlying value."
  [v]
  (cond
    (rt/thunk? v) (v)
    (instance? clojure.lang.IDeref v) @v
    :else v))


(defn- seq-item-builder
  "Compile one `binding-list-item` row into a `(fn [fa ctx])`
   producing the item's runtime value. Four shapes:
     - `{:value {:as :name} :literal nil}` — positional free-arg
       substitution (`:route :args :items [{:as :path} ...]`);
       reads via `force-value` so a delay in `free-args` gets
       forced before the consumer sees it.
     - `:value` present — literal value.
     - `:ref-fn-id` — invoke pre-compiled child callable.
     - everything nil — literal `nil`.

   `owner-fn-id` is the fn-id whose seq binding contains this item.
   The positional `{:as :name}` resolves to that owner's OWN rename
   slot (parser creates one per rename); the reader indexes `fa`
   by that rename slot's id — pure slot-id, no name fallback."
  [item child-callables lookups owner-fn-id]
  (cond
    (and (map? (:value item))
         (:as (:value item))
         (not (:literal item)))
    (let [k (keyword (:as (:value item)))
          ;; Phase 4 — rename-aware slot-id from owner's own rename
          ;; slot for this `:as` name. Falls back to name fallback
          ;; below when the rename slot doesn't exist (some seq
          ;; binding shapes don't produce slot rows via parser).
          sid (some-> (get (:slot-by-fn-name lookups) [owner-fn-id k])
                      :id)]
      (if sid
        (fn [fa _ctx]
          (let [v (get fa sid ::miss)]
            (force-value (if (identical? v ::miss) (get fa k) v))))
        (fn [fa _ctx] (force-value (get fa k)))))

    (some? (:value item))
    (constantly (:value item))

    (:ref-fn-id item)
    (let [ref-id (:ref-fn-id item)
          child (or (get child-callables ref-id)
                    (throw (ex-info "compile-eager: seq-item ref-target not compiled"
                                    {:type :compile/missing-child :item item})))
          ref-frees (set (r/cache-projection-frees ref-id lookups))]
      (fn [fa ctx] (call-with-cache ref-id ref-frees child fa ctx)))

    :else (constantly nil)))


(defn make-shape-callable
  "Build the Clojure callable a HOF expects, given a 0/1/many
   `lambda-params` shape (see `r/hof-lambda-params` +
   CLOSURE_CAPTURE.md):

   - 0 → variadic-ignore: `:future :body` / `:loop-until-interrupted`.
   - 1 → single-arg: caller passes one value, target sees it under
         the lambda-param's name. `:map`'s `:func`, `:filter`'s `:pred`.
   - 2+ → map-callable: caller passes `{lambda-name → value}`.

   `invoke-with` is the bridge: it gets a NAME-keyed map of the
   per-call lambda values (`nil` for the 0-arg variant) and returns
   the callable's return value. All three call sites in the executor
   — root-binding `hof-wrap`, env-binding HOF case, and the public
   `make-single-arg-callable` entry — feed different env sources
   through this same shape decision."
  [lambda-params invoke-with]
  (case (count lambda-params)
    0 (fn [& _] (invoke-with nil))
    1 (let [k (first lambda-params)]
        (fn [item] (invoke-with {k item})))
    invoke-with))


(defn- apply-hof-translation
  "Apply HOF wrap-time slot-id translation to fa.

   `translation` is `{R-slot-id F-source-key}` — for each R-slot-id
   the callee R reads, the source key in the captured F-side fa.
   Phase 5 conservative scope: sources are ext-name keywords
   (caller-supplied free args whose name lives in fa). A future
   extension may also map R-slot-ids from F-slot-ids for cross-fn
   rename cascades.

   Empty translation short-circuits — fa passes through.

   PRESENCE — not truthiness — is the copy gate. A caller passing
   `:body nil` or `:flag false` deserves to land under R's slot-id
   just like any other value.

   THUNKS are skipped at copy: an env-binding write puts a deferred
   `(rt/thunk …)` under `fa[name]` whose body calls `call-with-cache`
   on a ref. Copying that thunk under R's slot-id would let an inner
   reader find it via slot-id AND name fallback — and the inner ref
   target may itself trigger the same env-binding chain, causing
   `call-with-cache` to miss (mid-computation) and recurse to
   StackOverflow. Caller-supplied free args are plain values; the
   thunk-skip preserves their copy while keeping env-binding writes
   on their existing name-fallback path."
  [fa translation]
  (if (empty? translation)
    fa
    (reduce-kv (fn [acc r-sid src]
                 (cond
                   (contains? acc r-sid) acc
                   (contains? acc src)
                   (let [v (get acc src)]
                     (if (rt/thunk? v)
                       acc
                       (assoc acc r-sid v)))
                   :else acc))
               fa translation)))


(defn- hof-wrap
  "Root-binding HOF: returns a `(fn [fa ctx])` whose call yields the
   callable. The callable closes over `fa` (the wrap-time snapshot of
   the caller's env). lambda-args are merged name-keyed; the slot-id
   readers in R fall back to name lookups when no slot-id key is
   present, so the dynamic lambda values flow correctly.

   `translation` (Phase 5) propagates F-side slot-id keys (and
   ext-name keys for caller args that didn't reach F's walker surface)
   into R's slot-id namespace — required for cross-fn rename cascades
   (e.g. `:method-map :handler` rename slot → `:assoc-handler :handler`
   rename slot have different ids) to survive the wrap."
  [child lambda-params translation]
  (if (empty? translation)
    (fn [fa ctx]
      (make-shape-callable lambda-params
                           (fn [lambda-args]
                             (child (if lambda-args (merge fa lambda-args) fa)
                                    ctx))))
    (fn [fa ctx]
      (let [fa* (apply-hof-translation fa translation)]
        (make-shape-callable lambda-params
                             (fn [lambda-args]
                               (child (if lambda-args (merge fa* lambda-args) fa*)
                                      ctx)))))))


(def ^:private rich-type-of-fn
  (delay (requiring-resolve 'graphden.executor.registry.core/rich-type-of)))


(defn- compile-time-value-root?
  "True iff `fn-id`'s root base-fn is registered `:compile-time-value?`
   — the marker (from the impls.clj registry, threaded through
   `record-rich-types!`) that says: evaluate this fn ONCE at compile
   time and bake `(constantly result)`. Backs `:cell`'s registry-
   persistent atom."
  [fn-id {:keys [fn-map] :as lookups}]
  (boolean (some-> (l/root-fn fn-id fn-map lookups) :name keyword
                   (@rich-type-of-fn) :compile-time-value?)))


(defn- compile-time-value-closure
  "Decide how to compile a fn whose root base-fn is
   `:compile-time-value?` (e.g. anything parenting `:cell`), given its
   classified `enriched` bindings and its assembled runtime closure
   `run`:

   - EVERY binding a literal (`:value`) → the value is a compile-time
     constant: evaluate `run` ONCE now and bake `(fn [_ _] result)` so
     every invocation, across every `execute` this compiled registry
     serves, hands back the SAME instance (that's `:cell`'s persistent
     atom). Empty `fa` + bare `ctx`: `:value` builders are
     `(constantly v)` and the impl is effect-free by contract.
   - Otherwise (a `:ref` / `:seq` / `:free` binding — a runtime or
     unbound value) → there is no compile-time constant to bake, so
     compile NORMALLY; the fn then behaves like a per-call `:atom`
     (a fresh instance each `execute`). Persistence requires a pinned
     literal — degrade gracefully rather than throw, since a single
     non-literal `:cell` must not fail the whole-registry compile-all."
  [_fn-id enriched run]
  (if (and (seq enriched) (every? #(= :value (:kind %)) enriched))
    (let [baked (run {} {})]
      (fn [_fa _ctx] baked))
    run))


(def ^:private vault-get-secret
  (delay (requiring-resolve 'graphden.clients.vault/get-secret)))


(def ^:private vault-active-client
  ;; JVM-wide fallback for fn-graphs running on a ctx that doesn't
  ;; carry `:vault` (per-branch ctx builds — see
  ;; `system.branch-router/build-branch-ctx`). Lazy resolve preserves
  ;; the "don't load clients.vault until first use" perf optimisation.
  ;; `@vault-active-client` is the Var, `(deref @vault-active-client)`
  ;; is the atom, `@(deref @vault-active-client)` is the client value.
  (delay (requiring-resolve 'graphden.clients.vault/active-client)))


(defn- lazy-seq-of-values
  "Lazy-seq that materialises each item by calling its builder only
   when the consumer pulls the cons-cell — matches Clojure-native
   lazy seqs. This is what makes `:and` / `:or` short-circuit
   through their `:items` seq slot: `every?` / `some` walk the seq
   and stop at the first decisive element, so later builders never
   fire."
  [item-builders fa ctx]
  (letfn [(walk
            [i]
            (lazy-seq
              (when (< i (count item-builders))
                (cons ((nth item-builders i) fa ctx)
                      (walk (inc i))))))]
    (walk 0)))


(defn- arg-builder
  "Return `(fn [free-args ctx])` producing the value for one
   classified binding.

   Lazy semantics are built into the model the way Clojure does
   them — `:ref` bindings ALWAYS produce a `delay`, and the impl
   reads the arg through `rt/resolve-arg` which auto-derefs
   `IDeref`. Inside the impl, `(if test then else)` short-circuits
   because Clojure's native `if` only evaluates the picked form,
   so only its `resolve-arg` call runs, and only its delay forces.
   The un-taken branch's `delay` stays unforced — its side-effects
   never fire. No `:lazy?` flag, no `:lazy-args` registration:
   ordinary Clojure evaluation does it.

   `:seq` materialises as an unchunked lazy-seq of values
   (Clojure-native short-circuit through `every?` / `some`).
   `lazy-seq?` slots wrap each item in `delay` for consumers like
   `cond-fn` that step past unforced items via `nnext`."
  [fn-id
   {:keys [kind ext-name value ref-id is-fn produces-callable? ref-renames
           items lazy-seq? slot-id path binder-fn-id]
    :as bnd}
   child-callables
   lookups]
  (case kind
    :value (constantly value)
    ;; Phase 4 — slot-id reader with name fallback.
    ;;
    ;; The rename-aware reader id (`l/effective-reader-slot-id`) is
    ;; the PRIMARY key: two inline-anons of the same base-fn with
    ;; their own `{:as :X}` renames have the SAME chain-leaf but
    ;; DIFFERENT rename-slot ids, so each anon's reader finds its
    ;; own caller value via slot-id without name collision.
    ;;
    ;; Name fallback covers paths that still write `fa` by name:
    ;;   - env-builder writes `fa[env-name]` (per-fn synthetic
    ;;     shared computations like `:_request-parsed`)
    ;;   - hof-wrap's `make-shape-callable` merges `lambda-args`
    ;;     under lambda-param names (per-call values)
    ;;   - `build-ref-renames` slow path copies caller→callee names
    ;; These name keys flow through the same `fa` and the reader's
    ;; fallback finds them when there's no slot-id key. The fa is
    ;; thus hybrid: slot-id keys distinguish structural ambiguity at
    ;; the boundary translator, name keys cover dynamic flows. This
    ;; is the architecture, not a transitional kludge — the two key
    ;; spaces serve different needs.
    :free  (let [sid (l/effective-reader-slot-id fn-id slot-id lookups)
                 k ext-name]
             (fn [fa _ctx]
               (let [v (get fa sid ::miss)]
                 (if (identical? v ::miss) (get fa k) v))))
    :secret-value
    (let [p path]
      (fn [_fa ctx]
        (rt/thunk
          (fn []
            (let [vault-client (or (:vault ctx)
                                   (some-> @vault-active-client deref deref)
                                   (throw (ex-info "Vault client not configured — set VAULT_ADDR / VAULT_TOKEN"
                                                   {:type :vault/not-configured})))]
              (@vault-get-secret vault-client p))))))
    :ref
    (let [child (or (get child-callables ref-id)
                    (throw (ex-info "compile-eager: ref-target not yet compiled"
                                    {:type :compile/missing-child
                                     :binding bnd :ref-id ref-id
                                     :fn-id fn-id})))
          ref-frees (set (r/cache-projection-frees ref-id lookups))]
      (cond
        ;; HOF binding where the slot's structural shape is
        ;; `[:fn {…} …]` and the target is NOT itself a callable-
        ;; producer: build the Clojure closure the consumer will
        ;; call positionally. Value-shape, not delay-shape — the
        ;; impl invokes it directly.
        (and is-fn (not produces-callable?))
        (let [lambda-params (r/hof-lambda-params ref-id slot-id bnd fn-id lookups)
              translation (r/build-hof-translation ref-id lambda-params lookups)]
          (hof-wrap child lambda-params translation))

        ;; Two collapse into one — both want "invoke child with the
        ;; caller's env, wrap in a thunk for short-circuit":
        ;; - `:produces-callable?`: target's fn-graph evaluates to a
        ;;   Clojure callable (`:_router` → ring-handler). Wrapping
        ;;   means the router builds only when the impl actually
        ;;   reads the arg.
        ;; - non-renamed plain ref: the common case — no caller→
        ;;   callee free-arg translation needed.
        ;;
        ;; `rt/thunk` (a fn with `::thunk` meta) rather than `delay`
        ;; here: `resolve-arg` auto-calls it for impls that read args
        ;; via the `defbase` macro AND impls that read raw
        ;; (`((:body args))` — the closure-capture acceptance test)
        ;; can still invoke the value as a 0-arg fn.
        (or produces-callable? (empty? ref-renames))
        (fn [fa ctx]
          (rt/thunk (fn [] (call-with-cache ref-id ref-frees child fa ctx))))

        :else
        (fn [fa ctx]
          (rt/thunk (fn []
                      (call-with-cache
                        ref-id ref-frees child
                        (reduce-kv (fn [acc callee-name caller-name]
                                     (assoc acc callee-name (get fa caller-name)))
                                   fa ref-renames)
                        ctx))))))
    :seq
    (let [item-builders (mapv #(seq-item-builder % child-callables lookups
                                                 (or binder-fn-id fn-id))
                              items)]
      (if lazy-seq?
        (fn [fa ctx]
          (map (fn [b] (delay (b fa ctx))) item-builders))
        (fn [fa ctx]
          (lazy-seq-of-values item-builders fa ctx))))
    (throw (ex-info (str "compile-eager: unsupported binding kind " kind)
                    {:type :compile/unsupported-kind :binding bnd}))))


(defn- env-arg-builder
  "Build the value that lands under one env-binding's env-name in
   `fa'`. Different shape from `arg-builder` because env-bindings
   need to participate in a SHARED env (sibling env-bindings can
   reference each other in any order).

   Returns `(fn [fa-ref ctx])` — a thunk that reads from the
   volatile `fa-ref` at FORCE time, so the env map it sees is
   the final one (all env-bindings populated), not the partial
   snapshot at construction time. For `:value` bindings we just
   return the literal — no closure needed."
  [fn-id env-bnd child-callables lookups]
  (case (:kind env-bnd)
    :value (let [v (:value env-bnd)] (fn [_fa-ref _ctx] v))

    :ref
    (let [{:keys [ref-id is-fn produces-callable? slot-id]} env-bnd
          child (or (get child-callables ref-id)
                    (throw (ex-info "compile-eager: env-binding ref not yet compiled"
                                    {:type :compile/missing-child
                                     :env-binding env-bnd :fn-id fn-id})))
          ref-frees (set (r/cache-projection-frees ref-id lookups))]
      (cond
        ;; HOF env-binding whose target ISN'T itself a callable-
        ;; producer: build the closure-captured Clojure callable.
        ;; Reads `fa-ref` at FORCE time (sibling env-bindings may
        ;; not have populated yet at construction).
        (and is-fn (not produces-callable?))
        (let [lambda-params (r/hof-lambda-params ref-id slot-id env-bnd fn-id lookups)
              translation (r/build-hof-translation ref-id lambda-params lookups)]
          (fn [fa-ref ctx]
            (make-shape-callable
              lambda-params
              (fn [lambda-args]
                (let [fa* (apply-hof-translation @fa-ref translation)]
                  (child (if lambda-args (merge fa* lambda-args) fa*)
                         ctx))))))

        ;; Target evaluates to a callable (`:_router` → reitit
        ;; ring-handler). Same as the regular `arg-builder` :ref
        ;; path: don't hof-wrap a positional callable.
        produces-callable?
        (fn [fa-ref ctx]
          (rt/thunk (fn [] (call-with-cache ref-id ref-frees child @fa-ref ctx))))

        :else
        (let [renames (r/build-ref-renames ref-id fn-id lookups)]
          (if (empty? renames)
            (fn [fa-ref ctx]
              (rt/thunk (fn [] (call-with-cache ref-id ref-frees child @fa-ref ctx))))
            (fn [fa-ref ctx]
              (rt/thunk (fn []
                          (call-with-cache
                            ref-id ref-frees child
                            (reduce-kv
                              (fn [acc cn cln] (assoc acc cn (get @fa-ref cln)))
                              @fa-ref
                              renames)
                            ctx))))))))))


(defn compile-fn
  "Return `(fn [free-args ctx])` for `fn-id`. `child-callables` is
   `{fn-id → callable}` for ref-targets, populated in topological
   order by `compile-all`.

   Env-bindings (bindings on slots that AREN'T root slots — used to
   propagate values like `:base-handler` through the ref-tree to
   inner consumers) participate in a shared `fa-ref` volatile.
   They evaluate to `delay`s whose closures read the volatile at
   FORCE time, so an env-binding `A` that needs another env-binding
   `B`'s value (forwards-reference, the order they're declared in
   doesn't constrain dependencies) sees the final `fa'` map — same
   semantics as the legacy compile's `augment-env`. Without this,
   `:types-compatible`'s `:_rejected?` closure (which needs
   `:validation` from the same env layer) sees an empty `:validation`
   slot and reports every well-formed request as rejected, even
   though the API path is correct."
  ([fn-id lookups]
   (compile-fn fn-id lookups {}))
  ([fn-id lookups child-callables]
   (let [impl (resolve-impl fn-id lookups)
         enriched (mapv (fn [bnd]
                          (if (and (= :ref (:kind bnd))
                                   (not (:is-fn bnd)))
                            (assoc bnd :ref-renames
                                   (r/build-ref-renames (:ref-id bnd)
                                                        fn-id
                                                        lookups))
                            bnd))
                        (b/collect-bindings fn-id lookups))
         builders (mapv #(arg-builder fn-id % child-callables lookups) enriched)
         keys-vec (mapv :base-name enriched)
         n (count builders)
         env-bnds (b/collect-env-bindings fn-id lookups)
         env-builders (mapv #(env-arg-builder fn-id % child-callables lookups)
                            env-bnds)
         env-names (mapv :env-name env-bnds)
         env-n (count env-bnds)
         ;; Compile-time-derived runtime aliasing for this fn's own
         ;; rename slots. When a rename like `{:as :item}` surfaces
         ;; a deep slot (`:branch-row` from the ref-tree) under a
         ;; renamed outer name, downstream refs still read by the
         ;; deep name — `apply-rename-aliases` copies the
         ;; caller-supplied rename value back to the deep name so
         ;; the lookup succeeds. Empty aliases (fns without own
         ;; rename slots — the common case) short-circuit at apply.
         rename-aliases (r/compute-rename-aliases fn-id lookups)
         ;; Top-level entry: install a fresh per-execute call-cache in
         ;; ctx if none is in scope yet. Nested closure calls inherit
         ;; the outer cache through ctx, so all siblings memoise on
         ;; `(ref-id × fa)`. `HashMap` (not `clojure.lang.PersistentMap`)
         ;; — one-cache-per-call, single-threaded read/write inside one
         ;; top-level closure.
         run
         (fn [fa ctx]
           (let [ctx (if (::call-cache ctx)
                       ctx
                       (assoc ctx ::call-cache (java.util.HashMap.)))
                 fa (r/apply-rename-aliases fa rename-aliases)
                 ;; The `fa-ref` volatile ONLY exists so env-binding delays can
                 ;; read the post-merge map at force time (forward references
                 ;; between env-bindings). Fns with no env-bindings — the common
                 ;; case — never read it, so skip the per-call allocation entirely.
                 fa' (if (zero? env-n)
                       fa
                       (let [fa-ref (volatile! fa)
                             merged (loop [m fa, i 0]
                                      (if (< i env-n)
                                        (recur (assoc m (nth env-names i)
                                                      ((nth env-builders i) fa-ref ctx))
                                               (inc i))
                                        m))]
                         (vreset! fa-ref merged)
                         merged))]
             (impl (persistent!
                     (loop [acc (transient {}), i 0]
                       (if (< i n)
                         (recur (assoc! acc
                                        (nth keys-vec i)
                                        ((nth builders i) fa' ctx))
                                (inc i))
                         acc)))
                   ctx)))]
     ;; `:cell` (and any `:compile-time-value?` base-fn): evaluate once
     ;; here and bake `(constantly result)`, so the atom persists across
     ;; every `execute` this compiled registry serves.
     (if (compile-time-value-root? fn-id lookups)
       (compile-time-value-closure fn-id enriched run)
       run))))


;; =============================================================================
;; compile-all — topological pass over the whole graph
;; =============================================================================

(defn- topo-sort
  "Kahn's algorithm over `{fn-id → #{dep-fn-id}}` — returns a vector
   of fn-ids in compile order (deps first). Throws on cycles (which
   storage-level constraints should already rule out — second line
   of defence)."
  [deps]
  (let [in-deg (into {} (map (fn [[fid ds]] [fid (count ds)])) deps)
        dependents-of (reduce-kv (fn [acc fid ds]
                                   (reduce #(update %1 %2 (fnil conj []) fid) acc ds))
                                 {}
                                 deps)]
    (loop [sorted (transient [])
           in-deg in-deg
           ready (into #{} (keep (fn [[k v]] (when (zero? v) k))) in-deg)]
      (if (empty? ready)
        (if (= (count sorted) (count deps))
          (persistent! sorted)
          (throw (ex-info "compile-eager: cycle in ref-DAG"
                          {:type :compile/cycle
                           :remaining (vec (remove (set (persistent! sorted)) (keys deps)))})))
        (let [fid (first ready)
              [in-deg' ready']
              (reduce (fn [[id rd] d]
                        (let [n (dec (get id d))]
                          [(assoc id d n) (cond-> rd (zero? n) (conj d))]))
                      [in-deg (disj ready fid)]
                      (get dependents-of fid))]
          (recur (conj! sorted fid) in-deg' ready'))))))


(defn- reachable-targets
  "Fixed-point: fn-id is `target` iff (a) its root carries an impl,
   (b) every binding shape it uses is supported by this stage, and
   (c) every fn-id it refs is also `target`. (c) makes the
   exclusion of fns transitively dependent on an unsupported one
   explicit; the seed covers (a) + (b)."
  [lookups]
  (let [seed (into #{}
                   (comp (map :id)
                         (filter #(and (has-impl? % lookups)
                                       (supported-shapes? % lookups))))
                   (vals (:fn-map lookups)))]
    (loop [targets seed]
      (let [next-targets (into #{}
                               (filter (fn [fid]
                                         (every? targets (ref-deps fid lookups))))
                               targets)]
        (if (= next-targets targets)
          targets
          (recur next-targets))))))


(defn- compile-all*
  [lookups]
  (let [targets (reachable-targets lookups)
        deps (into {}
                   (map (fn [fid] [fid (ref-deps fid lookups)]))
                   targets)
        order (topo-sort deps)]
    (reduce (fn [acc fid]
              (assoc acc fid (compile-fn fid lookups acc)))
            {}
            order)))


;; ============================================================================
;; Process-wide cache for `compile-all` output
;; ============================================================================
;;
;; compile-eager closures are ctx-INDEPENDENT (ctx arrives at execute time,
;; not compile time), so two storages that present the same graph + the same
;; base-fn registry compile to the SAME `{fn-id → closure}` map. Cache it
;; per (graph-content × base-fn-name-set) — first JVM-wide call pays the
;; full ~8 s compile pass; sister contexts (test ns's that bootstrap the
;; same package set, sibling branches with identical graph views) hit warm
;; in < 1 ms.
;;
;; Bounded LRU — 4 entries comfortably cover {dev system + a couple of
;; branches + a test bootstrap} without holding stale registries forever.

;; 2 (was 4): each cached registry holds ~3000 closure references,
;; and each closure captures references to its parent lookups
;; (fn-map / slot-map / 4 index maps / 4 lazy atom caches). 4
;; generations was ~10MB of accumulating heap that mostly never got
;; queried — the cache hits are dominated by repeat calls within the
;; SAME compilation window (sister branches share a graph snapshot)
;; rather than across windows. Dropping to 2 cuts heap pressure
;; without losing the dominant hit case (current branch + base
;; branch).
(def ^:private compile-all-cache-max-size 2)


(def ^:private compile-all-cache
  "Bounded LRU `[[key compiled] ...]` — head is freshest, tail is oldest."
  (atom []))


(defn- compile-all-cache-key
  "Hash of (graph shape × base-fn name set). Same key ⇒ same compile
   output. Picks the same per-entity field set the registry already
   relies on for identity (mutable timestamps / generated UUIDs that
   don't affect compile output stay out)."
  [{:keys [fn-map slot-map fn-slots-by-fn bindings-by-fn items-by-binding
           base-fns]}]
  (hash [(set (vals fn-map))
         (set (vals slot-map))
         (set (mapcat val fn-slots-by-fn))
         (set (mapcat val bindings-by-fn))
         (set (mapcat val items-by-binding))
         (set (keys base-fns))]))


(defn compile-all
  "Compile every fn-row whose ref-DAG bottoms out at base-fn impls.
   Returns `{fn-id → (fn [free-args ctx])}`. Cycle in ref-DAG →
   throws (second line of defence over the storage constraint).

   `lookups` MUST already carry `:base-fns` (the impl registry).

   Cached on a process-wide bounded LRU keyed by graph-shape +
   base-fn name set — sister callers (test ns's that bootstrap the
   same package set, sibling branches with identical graph views)
   skip the compile pass entirely and just retrieve the
   ctx-independent closure map.

   The cache holds DELAYS, not values, and that is what makes the
   cache work for CONCURRENT sister callers rather than only
   sequential ones. It used to read the atom, miss, compile, then
   write — check-then-act. Three namespaces released together from
   `ensure-golden!`'s lock therefore all missed the same cold key and
   all compiled the same ~2600-fn graph at once. Measured fingerprint:
   79.1 s / 77.7 s / 75.7 s of fixture, three different workloads
   agreeing to within 4% because they were not different workloads at
   all — they were one compile, run three times, contending.

   With a delay, the `swap!` decides the winner: a loser's swap
   function re-runs against the winner's value, finds the key present,
   and returns the vector untouched, so its own unrun delay is
   discarded. Everyone then derefs the SAME delay — one compile, the
   rest blocked on it. A global lock would also fix the dogpile but
   would serialise compiles of genuinely DIFFERENT graphs, which is
   the case this cache exists to make fast."
  [lookups]
  (let [k (compile-all-cache-key lookups)
        installed? (volatile! false)
        cache (swap! compile-all-cache
                     (fn [v]
                       (if (some (fn [[ck _]] (= ck k)) v)
                         (do (vreset! installed? false) v)
                         (do (vreset! installed? true)
                             (conj (vec (take-last (dec compile-all-cache-max-size) v))
                                   [k (delay (compile-all* lookups))])))))
        d (some (fn [[ck cv]] (when (= ck k) cv)) cache)]
    ;; Counted here, not at `rebuild!`, because `rebuild!` is the ASK and this is
    ;; the WORK. Three namespaces each calling `rebuild!` is three asks and — if
    ;; this cache is doing its job — one compile. A counter on the ask cannot
    ;; tell those apart, which is exactly how the dogpile went unseen: the
    ;; suite's 107 rebuilds looked identical before and after it was fixed.
    ;;
    ;; The `volatile!` reads oddly next to a `swap!` whose function must be pure.
    ;; It is: `swap!` may retry and re-run the fn, and each run overwrites the
    ;; flag, so the LAST run — the one whose value was actually installed — is
    ;; the one that decides. That is precisely the answer we want.
    (counters/count! (if @installed? :compile/all-miss :compile/all-hit))
    (try
      @d
      (catch Exception t
        ;; A delay memoises its exception, so a transient failure would be
        ;; served to every later caller of this key forever. Evict, and let the
        ;; next one compile again — the old code could not cache a failure at
        ;; all (it wrote only after a successful compile), and that property is
        ;; worth keeping. `Exception`, not `Throwable`: an Error here means the
        ;; JVM is already going down, and a poisoned cache entry is not the
        ;; problem worth solving on the way.
        (swap! compile-all-cache (fn [v] (filterv (fn [[ck _]] (not= ck k)) v)))
        (throw t)))))


(defn reset-compile-all-cache!
  "Test hook — drop every cached entry. Useful for tests that
   intentionally mutate the same graph mid-deftest to verify
   compile-time re-classification, since the cache would otherwise
   short-circuit a second compile pass."
  []
  (reset! compile-all-cache []))


(defn compile-subset
  "Recompile a SUBSET of fn-ids on top of `existing-registry`. Used
   by `delta-recompile!`: only the blast radius needs new closures,
   but those closures may reference each other AND existing entries
   from outside the blast.

   Topologically sorts the subset by inter-blast deps so a fn
   compiled later in the subset sees freshly-built children, not
   the pre-mutation copies. Subset entries dependent on each other
   compile in dependency order; entries whose deps live outside
   the subset pick those up from `existing-registry`.

   Skips fn-ids whose root has no registered impl (type-rows,
   anonymous incomplete rows)."
  [lookups existing-registry subset-fn-ids]
  (let [subset (into #{}
                     (filter #(and (has-impl? % lookups)
                                   (supported-shapes? % lookups)))
                     subset-fn-ids)
        ;; Restrict deps to subset members for topo-sort — deps
        ;; outside the subset are pinned through `existing-registry`
        ;; and don't constrain order.
        deps (into {}
                   (map (fn [fid]
                          [fid (into #{}
                                     (filter subset)
                                     (ref-deps fid lookups))]))
                   subset)
        order (topo-sort deps)]
    (reduce (fn [acc fid]
              (assoc acc fid (compile-fn fid lookups acc)))
            existing-registry
            order)))
