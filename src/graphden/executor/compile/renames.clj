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


(defn deep-free-ext-names
  "Collect TRULY-unbound free-arg external names reachable from `fn-id`,
   walking across ref bindings (but NOT through :seq items — those are
   closed). As we descend into a ref target we accumulate the env-bound
   names of every fn we passed through; if a deeper fn lists a `:free`
   binding whose ext-name was env-bound by an OUTER fn, that slot is
   satisfied by the outer fn's `augment-env` and is NOT a true
   HOF-input.

   Without this, a Ring-handler chain like `:_app-ring-response` would
   report propagated names (`:func`, `:item1`, `:default`, …) that are
   actually bound by env-bindings at intermediate ref levels —
   `hof-wrap` would then think the HOF call must inject them.

   `deep-free-ext-names` exists for the case where a fn's own primary
   slots are all bound but its ref-chain still exposes a genuinely
   unbound free arg (the canonical case: a handler graph propagating
   `:ctx` up from `:ring-request`)."
  [fn-id lookups]
  (let [result (atom [])
        seen (atom #{})]
    (letfn [(walk
              [fid env-covered]
              (when-not (contains? @seen fid)
                (swap! seen conj fid)
                (let [own-env (set (map :env-name
                                        (b/collect-env-bindings fid lookups)))
                      covered (into env-covered own-env)]
                  (doseq [bnd (b/collect-bindings fid lookups)]
                    (case (:kind bnd)
                      :free (let [n (:ext-name bnd)]
                              ;; Optional free args (`:required false` on
                              ;; the base primary, e.g. `:get :default`)
                              ;; don't need to come from the caller — they
                              ;; default to nil if absent. Don't count
                              ;; them toward HOF leftover.
                              (when-not (or (covered n)
                                            (not (:required bnd))
                                            (some #{n} @result))
                                (swap! result conj n)))
                      :ref (when-not (:is-fn bnd)
                             (walk (:ref-id bnd) covered))
                      :seq (doseq [item (:items bnd)
                                   :when (:ref-id item)]
                             (walk (:ref-id item) covered))
                      :value nil)))))]
      (walk fn-id #{}))
    @result))


(defn- r-origin-arg-id
  "Find the arg-id in R's inheritance chain whose `:name` field (walked
   via source-id, first-name-wins) matches `ext-name`. This is the arg
   that *establishes* R's external name — typically a rename arg inside
   R's chain."
  [r-fn-id ext-name {:keys [fn-map args-by-fn arg-map]}]
  (let [chain (l/inheritance-chain r-fn-id fn-map)]
    (some (fn [fid]
            (some (fn [arg]
                    (when (= ext-name (l/arg-ext-name (:id arg) arg-map))
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


(defn build-ref-renames
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
