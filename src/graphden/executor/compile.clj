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
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.runtime :as rt]))


;; Lookup helpers moved to `graphden.executor.compile.lookups`. Re-exports
;; keep the 4 externally-used names resolvable at the old path.
(def build-lookups l/build-lookups)
(def base-fn-of l/base-fn-of)
(def terminal-primary-id l/terminal-primary-id)
(def arg-ext-name l/arg-ext-name)


;; =============================================================================
;; Binding collection
;; =============================================================================

(defn- fn-chain-args-for-primary
  "For every fn in F's inheritance chain, find the arg (if any) whose
   source-chain terminates at `primary-id` AND whose chain stays within
   F's inheritance chain (excludes propagation pass-throughs — those are
   args on F-chain fns whose source-id crosses into a ref-target fn and
   shouldn't be treated as F's own bindings for its base primaries).

   Each match is {:fn-id …, :arg <arg-entity>}, ordered from F (closest,
   top of vector) to base (farthest)."
  [primary-id fn-chain fn-chain-set args-by-fn arg-map]
  (vec
    (keep (fn [fid]
            (some (fn [arg]
                    (when (and (= primary-id (terminal-primary-id (:id arg) arg-map))
                               (l/source-chain-stays-within? (:id arg) fn-chain-set arg-map))
                      {:fn-id fid :arg arg}))
                  (get args-by-fn fid [])))
          fn-chain)))


(defn- closest-binding
  "Among the chain matches, return the first (closest to F) arg that has
   a :value or :ref-id set. `nil` if none — meaning the slot is free."
  [matches]
  (some (fn [{:keys [arg]}]
          (when (or (some? (:value arg))
                    (some? (:ref-id arg)))
            arg))
        matches))


(defn- sequence-anchor
  "If `matches` contains a sequence-anchor arg (own arg on F or ancestor
   whose source-id chain terminates at `primary-id`, with `type=:sequence`
   and no value/ref of its own), return it. Such an arg signals that the
   slot is populated via a linked list of item-args walked through
   `:next-arg-id`."
  [matches]
  (some (fn [{:keys [arg]}]
          (when (and (= :sequence (:type arg))
                     (nil? (:value arg))
                     (nil? (:ref-id arg)))
            arg))
        matches))


(defn- walk-anchor-chain
  "Walk a sequence-anchor's `:next-arg-id` chain and return the list of
   item args in order. Each item has either `:value` (literal) or
   `:ref-id` (reference) set, and its own `:next-arg-id` pointer."
  [anchor arg-map]
  (loop [acc []
         id (:next-arg-id anchor)]
    (if-let [item (and id (get arg-map id))]
      (recur (conj acc item) (:next-arg-id item))
      acc)))


(defn- classify-binding
  "For a primary arg `P` of the base-fn, inspect F's inheritance chain and
   classify the slot.

   Returns a map:
     {:kind       :value | :ref | :seq | :free
      :base-name  keyword, what impl expects as the arg-name
      :ext-name   keyword, what F's caller provides in free-args
      :value      the literal (when :kind = :value)
      :ref-id     the target fn-id (when :kind = :ref)
      :is-fn      bool (HOF arg — only relevant for :ref)
      :items      item args (when :kind = :seq) — each has :value or :ref-id}"
  [primary-arg fn-chain fn-chain-set args-by-fn arg-map]
  (let [matches (fn-chain-args-for-primary (:id primary-arg) fn-chain fn-chain-set args-by-fn arg-map)
        bnd (closest-binding matches)
        anchor (sequence-anchor matches)
        base-name (keyword (:name primary-arg))
        ;; Ext-name for the slot: the closest chain-arg's own ext-name
        ;; (walking its source-id chain). Falls back to the base-fn's
        ;; primary name when there is no chain arg.
        closest-chain-arg (some :arg matches)
        ext-name (or (when closest-chain-arg
                       (arg-ext-name (:id closest-chain-arg) arg-map))
                     base-name)]
    (cond
      (and bnd (some? (:value bnd)))
      {:kind :value :base-name base-name :ext-name ext-name
       :value (:value bnd)}

      (and bnd (:ref-id bnd))
      {:kind :ref :base-name base-name :ext-name ext-name
       :ref-id (:ref-id bnd)
       :is-fn (boolean (:is-fn bnd))}

      anchor
      {:kind :seq :base-name base-name :ext-name ext-name
       :items (walk-anchor-chain anchor arg-map)}

      :else
      {:kind :free :base-name base-name :ext-name ext-name})))


(defn collect-bindings
  "For fn F, resolve every primary slot of its base-fn. Returns a vector of
   classified binding entries (see `classify-binding`), in the order of the
   base's primary args (stable for testing)."
  [fn-id {:keys [fn-map args-by-fn arg-map]}]
  (let [base (base-fn-of fn-id fn-map)
        base-primaries (filterv l/primary-arg? (get args-by-fn (:id base) []))
        chain (l/inheritance-chain fn-id fn-map)
        chain-set (set chain)]
    (mapv #(classify-binding % chain chain-set args-by-fn arg-map) base-primaries)))


(defn collect-env-bindings
  "Collect bindings on F (or any ancestor in its inheritance chain) that
   aren't already consumed by F's base-fn primaries — these bindings feed
   ref-target subtrees via the augmented free-args map. Two patterns:

   1. Bindings on a propagated free slot. E.g. `:health-route` binds
      `:path \"/health\"` — that arg's source chain crosses out of
      `:health-route`'s inheritance chain (into the `:pair-1` ref
      propagation path), so it doesn't bind `:conj.item` directly; instead
      it needs to reach `:pair-1` via the call-site free-args rename.

   2. Bindings on a slot whose terminal primary lies outside F's base.
      E.g. `:_app-path-gated-response` binds `:func :_router` — `:if`
      (its base) has no `:func` primary; the binding instead augments
      free-args so the deep `:invoke.func` slot picks it up.

   Dedup by ext-name (each unique external name shows up once). The arg
   whose source chain *stays within F's chain AND terminates at a base
   primary* is handled by `classify-binding`, not here — so we skip it.

   Returns a vector of maps:
     {:kind    :value | :ref | :seq
      :env-name keyword — ext-name at the binding level
      :value/:ref-id/:is-fn/:items — as in classify-binding}"
  [fn-id {:keys [fn-map args-by-fn arg-map]}]
  (let [base (base-fn-of fn-id fn-map)
        base-primary-ids (into #{} (map :id) (filterv l/primary-arg? (get args-by-fn (:id base) [])))
        chain (l/inheritance-chain fn-id fn-map)
        chain-set (set chain)
        seen-ext-names (atom #{})
        out (atom [])]
    (doseq [fid chain
            arg (get args-by-fn fid [])]
      (let [term-id (terminal-primary-id (:id arg) arg-map)
            ext-name (arg-ext-name (:id arg) arg-map)
            in-chain? (l/source-chain-stays-within? (:id arg) chain-set arg-map)
            consumed-by-classify? (and in-chain? (contains? base-primary-ids term-id))]
        (when (and ext-name
                   (not consumed-by-classify?)
                   (not (contains? @seen-ext-names ext-name))
                   (or (some? (:value arg))
                       (:ref-id arg)
                       (and (= :sequence (:type arg))
                            (:next-arg-id arg))))
          (swap! seen-ext-names conj ext-name)
          (swap! out conj
                 (cond
                   (some? (:value arg))
                   {:kind :value :env-name ext-name :value (:value arg)}

                   (:ref-id arg)
                   {:kind :ref :env-name ext-name :ref-id (:ref-id arg)
                    :is-fn (boolean (:is-fn arg))}

                   :else
                   {:kind :seq :env-name ext-name
                    :items (walk-anchor-chain arg arg-map)})))))
    @out))


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

(defn- deep-free-ext-names
  "Collect free-arg external names reachable from `fn-id`, walking across
   ref bindings (but NOT through :seq items — those are closed). Without
   this, a fn whose own primary slots are all bound (e.g.
   `_app-path-gated-response` — all three `:if` slots fixed) would
   appear as 0-arg, even though its ref-chain exposes `:request`."
  [fn-id lookups]
  (let [result (atom [])
        seen (atom #{})]
    (letfn [(walk
              [fid]
              (when-not (contains? @seen fid)
                (swap! seen conj fid)
                (doseq [b (collect-bindings fid lookups)]
                  (case (:kind b)
                    :free (let [n (:ext-name b)]
                            (when-not (some #{n} @result)
                              (swap! result conj n)))
                    :ref (when-not (:is-fn b)
                           (walk (:ref-id b)))
                    :seq (doseq [item (:items b)
                                 :when (:ref-id item)]
                           (walk (:ref-id item)))
                    :value nil))))]
      (walk fn-id))
    @result))


;; =============================================================================
;; Free-args translation at ref call-sites (MI + rename support)
;; =============================================================================
;;
;; The `:route → :pair → :conj` pattern (see resources/packages/app/common/
;; fns.edn) is the canonical case. `:route` inherits `:pair`, binds
;; `:item2` to a ref, and renames `:item1 → :path`. But `:pair` was
;; composed by referencing `:pair-1`, so `:conj` is invoked twice along
;; the chain — once via `:pair-1` (for the inner `[item1]` vector) and
;; once at `:pair` itself (for the outer append).
;;
;; When `:route` fires its `:coll` thunk to call `:pair-1`, it hands over
;; free-args keyed by its own external names (`:path`). But `:pair-1`
;; expects them keyed by `:item1`. We compute `{R-ext-name → F-ext-name}`
;; at compile time and rename keys at call-site.

(defn- r-origin-arg-id
  "Find the arg-id in R's inheritance chain whose `:name` field (walked
   via source-id, first-name-wins) matches `ext-name`. This is the arg
   that *establishes* R's external name — typically a rename arg inside
   R's chain."
  [r-fn-id ext-name {:keys [fn-map args-by-fn arg-map]}]
  (let [chain (l/inheritance-chain r-fn-id fn-map)]
    (some (fn [fid]
            (some (fn [arg]
                    (when (= ext-name (arg-ext-name (:id arg) arg-map))
                      (:id arg)))
                  (get args-by-fn fid [])))
          chain)))


(defn- f-arg-for-r-origin
  "Walk F's inheritance chain and return the arg whose source-id chain
   includes `r-origin-id` — i.e. the arg on F (or an F-ancestor) that
   propagates R's free slot up to F's external interface."
  [r-origin-id f-fn-id {:keys [fn-map args-by-fn arg-map]}]
  (let [chain (l/inheritance-chain f-fn-id fn-map)]
    (some (fn [fid]
            (some (fn [arg]
                    (when (contains? (l/source-chain-set (:id arg) arg-map) r-origin-id)
                      arg))
                  (get args-by-fn fid [])))
          chain)))


(defn- build-ref-renames
  "For ref R called from F, compute `{R-ext-name → F-ext-name}`. Only
   includes entries where the name actually differs (identity entries are
   elided so callers can skip the rename work when the map is empty)."
  [r-fn-id f-fn-id lookups]
  (let [arg-map (:arg-map lookups)
        r-frees (deep-free-ext-names r-fn-id lookups)]
    (into {}
          (keep (fn [r-ext]
                  (when-let [origin (r-origin-arg-id r-fn-id r-ext lookups)]
                    (when-let [f-arg (f-arg-for-r-origin origin f-fn-id lookups)]
                      (let [f-ext (arg-ext-name (:id f-arg) arg-map)]
                        (when (and f-ext (not= f-ext r-ext))
                          [r-ext f-ext]))))))
          r-frees)))


(defn- apply-renames
  "Apply {R-ext-name → F-ext-name} to `free-args`: for each entry, if F
   has a value under `f-ext`, expose it under `r-ext` (and drop the
   `f-ext` key so R's own naming wins). Extra keys in `free-args` pass
   through untouched; R ignores ones it doesn't consume."
  [free-args renames]
  (reduce-kv (fn [acc r-ext f-ext]
               (if (contains? acc f-ext)
                 (-> acc
                     (assoc r-ext (get acc f-ext))
                     (dissoc f-ext))
                 acc))
             free-args
             renames))


(defn- hof-wrap
  "Wrap a compiled closure into a callable for use as a HOF argument
   (`:fn`-type arg). Preference order for the wrap shape:

   - If `:request` is among the free args → single-arg callable feeding
     the item under `:request`. This covers the Ring-handler case
     (`make-request-handler` — many propagated deep free args, but the
     caller only ever has the request to pass).
   - 0 free args → variadic callable that ignores its input. Handlers
     built from `:make-data-handler` still pass `request` through even
     when the data-fn is a constant (e.g. `:list-all-graph-entities`
     takes no args); arity-1 calls to a strict 0-arg fn would blow up.
   - 1 free arg  → `(fn [item] …)` — typical (map/filter reducer).
   - N≥2 free args → `(fn [items-seq] …)` — vec convention (reduce's
     `[acc item]` pair). Items are zipmapped to free arg names in order.

   All wrapped calls pass `outer-free-args` through (merged), so
   propagated inputs from the caller reach the HOF target."
  [compiled free-names all-fns outer-free-args]
  (cond
    (some #{:request} free-names)
    (fn [item] (compiled all-fns (assoc outer-free-args :request item)))

    (zero? (count free-names))
    (fn [& _] (compiled all-fns outer-free-args))

    (= 1 (count free-names))
    (let [n (first free-names)]
      (fn [item] (compiled all-fns (assoc outer-free-args n item))))

    :else
    (let [names (vec free-names)]
      (fn [items] (compiled all-fns (merge outer-free-args (zipmap names items)))))))


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
                                (apply-renames free-args ref-renames)
                                free-args)]
                   (assoc acc base-name
                          (rt/thunk #(call-with-cache ref-id callee all-fns r-args))))))

        :seq (assoc acc base-name
                    (rt/thunk #(mapv (fn [i] (resolve-seq-item i all-fns free-args)) items)))))
    {}
    bindings))


(defn- enrich-ref-bindings
  "Precompute per-binding metadata that depends only on the graph shape:
   - `:hof-free-names` for `:is-fn` refs (used by `hof-wrap`).
   - `:ref-renames`   for non-HOF refs (used at call-site by
     `apply-renames` to map F's free-arg names onto R's). Refs without
     renames get an empty map."
  [fn-id bindings lookups]
  (mapv (fn [b]
          (cond
            (and (= :ref (:kind b)) (:is-fn b))
            (assoc b :hof-free-names (deep-free-ext-names (:ref-id b) lookups))

            (= :ref (:kind b))
            (assoc b :ref-renames (build-ref-renames (:ref-id b) fn-id lookups))

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
