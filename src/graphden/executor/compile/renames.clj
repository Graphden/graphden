(ns graphden.executor.compile.renames
  "Compile-time helpers that translate free-arg names between a caller
   fn F and the ref-target fn R it invokes.

   ## Why

   The `:route → :pair → :conj` pattern (see `resources/packages/app/common/
   fns.edn`) is the canonical case. `:route` inherits `:pair`, binds
   `:item2` to a ref, and renames `:item1 → :path`. But `:pair` was
   composed by referencing `:pair-1`, so `:conj` is invoked twice along
   the chain — once via `:pair-1` (for the inner `[item1]` vector) and
   once at `:pair` itself (for the outer append).

   When `:route` fires its `:coll` thunk to call `:pair-1`, it hands over
   free-args keyed by its own external names (`:path`). But `:pair-1`
   expects them keyed by `:item1`. We compute `{R-ext-name → F-ext-name}`
   at compile time here; `apply-renames` does the key rewrite at call
   time.

   `deep-free-ext-names` also lives here because the rename table is
   built against the full list of free-arg names reachable from R's
   bindings."
  (:require
    [graphden.executor.compile.bindings :as b]
    [graphden.executor.compile.lookups :as l]))


(defn- chain-arg-id-for-ext-name
  "On `fn-id`'s inheritance chain, find the first arg whose own ext-name
   (walked via source-id) matches `ext-name`. Returns the arg-id of that
   originating chain-arg — i.e. the arg that introduces the rename
   (`{:as :ext-name}`) or whose primary name is `ext-name` directly.
   This is the structural anchor a caller's source-id must reach to
   bind that free name."
  [fn-id ext-name {:keys [fn-map args-by-fn arg-map]}]
  (let [chain (l/inheritance-chain fn-id fn-map)]
    (some (fn [fid]
            (some (fn [arg]
                    (when (= ext-name (l/arg-ext-name (:id arg) arg-map))
                      (:id arg)))
                  (get args-by-fn fid [])))
          chain)))


(defn deep-free-ext-args
  "Like `deep-free-ext-names` but returns a vector of `[ext-name origin-arg-id]`
   pairs. `origin-arg-id` is the id of the chain-arg that introduces the
   ext-name (via `:as` rename or as a primary), so callers can match
   their own source-id chains against it for structural classification
   (capture vs lambda-param).

   Walks the same shape as `deep-free-ext-names`: through non-HOF refs
   and `:seq` ref-items, with `:is-fn` refs treated as a BOUNDARY (the
   inner hof-wrap consumes its own leftovers, so they don't widen the
   outer interface)."
  [fn-id lookups]
  (let [result (atom [])
        seen (atom #{})]
    (letfn [(walk
              [fid covered]
              (when-not (contains? @seen fid)
                (swap! seen conj fid)
                (let [bindings (b/collect-bindings fid lookups)
                      own-env (set (map :env-name
                                        (b/collect-env-bindings fid lookups)))
                      own-primaries (into #{}
                                          (comp (remove #(= :free (:kind %)))
                                                (map :ext-name))
                                          bindings)
                      next-covered (into (into covered own-env) own-primaries)]
                  (doseq [bnd bindings]
                    (case (:kind bnd)
                      :free (let [n (:ext-name bnd)]
                              (when-not (or (next-covered n)
                                            (not (:required bnd))
                                            (some #(= n (first %)) @result))
                                (when-let [oid (chain-arg-id-for-ext-name fid n lookups)]
                                  (swap! result conj [n oid]))))
                      :ref (when-not (:is-fn bnd)
                             (walk (:ref-id bnd) next-covered))
                      :seq (doseq [item (:items bnd)]
                             (cond
                               ;; Ref-item: descend through (non-HOF
                               ;; refs already bypass the boundary).
                               (:ref-id item)
                               (walk (:ref-id item) next-covered)
                               ;; Named free slot inside the sequence
                               ;; (`{:as :name}` syntax). The item itself
                               ;; introduces the ext-name; its arg-id is
                               ;; the structural origin callers source-id
                               ;; against.
                               (and (:name item)
                                    (nil? (:value item)))
                               (let [n (keyword (:name item))]
                                 (when-not (or (next-covered n)
                                               (some #(= n (first %)) @result))
                                   (swap! result conj [n (:id item)])))))
                      :value nil)))))]
      (walk fn-id #{}))
    @result))


(defn deep-free-ext-names
  "Collect TRULY-unbound free-arg external names reachable from `fn-id`,
   walking across non-HOF ref bindings and descending into :seq items
   via their refs. `:is-fn` refs are a BOUNDARY — see
   `deep-free-ext-args` for the underlying walk and the rationale.

   Convenience wrapper: drops the origin-arg-ids that
   `deep-free-ext-args` carries."
  [fn-id lookups]
  (mapv first (deep-free-ext-args fn-id lookups)))


(defn classify-hof-free
  "For a HOF target `r-fn-id` invoked from `f-fn-id`, partition the
   target's deep-free names into structural CAPTURES (the caller's
   inheritance chain has a BOUND arg whose source-id chain reaches the
   free name's origin) and LAMBDA-PARAMS (no such anchor — the HOF
   call site must inject the value per invocation).

   Returns `{:captured [names…] :lambda-params [names…]}`. Order
   preserved from `deep-free-ext-args`. Empty/missing keys = empty
   vectors.

   Used by `hof-wrap` to pick a static call shape (`(count
   lambda-params)` → 0/1/N) and by the layout to render captured
   names as ordinary cross-HOF edges instead of `λname` lambda-param
   badges."
  [r-fn-id f-fn-id lookups]
  (let [{:keys [fn-map arg-map args-by-fn]} lookups
        f-chain (l/inheritance-chain f-fn-id fn-map)
        bound-source-targets
        (reduce (fn [acc fid]
                  (reduce (fn [a arg]
                            (if (or (some? (:value arg)) (some? (:ref-id arg)))
                              (into a (l/walk-source-chain (:id arg) arg-map))
                              a))
                          acc
                          (get args-by-fn fid [])))
                #{}
                f-chain)
        free-pairs (deep-free-ext-args r-fn-id lookups)]
    (reduce (fn [acc [nm oid]]
              (if (contains? bound-source-targets oid)
                (update acc :captured conj nm)
                (update acc :lambda-params conj nm)))
            {:captured [] :lambda-params []}
            free-pairs)))


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


(defn build-ref-renames
  "For ref R called from F, compute `{R-ext-name → F-ext-name}`. Only
   includes entries where the name actually differs (identity entries are
   elided so callers can skip the rename work when the map is empty)."
  [r-fn-id f-fn-id lookups]
  (let [arg-map (:arg-map lookups)
        r-frees (deep-free-ext-names r-fn-id lookups)]
    (into {}
          (keep (fn [r-ext]
                  (when-let [origin (chain-arg-id-for-ext-name r-fn-id r-ext lookups)]
                    (when-let [f-arg (f-arg-for-r-origin origin f-fn-id lookups)]
                      (let [f-ext (l/arg-ext-name (:id f-arg) arg-map)]
                        (when (and f-ext (not= f-ext r-ext))
                          [r-ext f-ext]))))))
          r-frees)))


(defn apply-renames
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
