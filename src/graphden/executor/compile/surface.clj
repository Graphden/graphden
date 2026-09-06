(ns graphden.executor.compile.surface
  "The fn's free-arg surface AS THE PUBLIC BOUNDARY presents it
   (ADR-inherited-rename-surface) — the closest-chain-rename names over
   the raw walkers of `compile.renames`. Its own namespace because it is
   read from three sides: the executor's public API (`compile-runtime`),
   the Run form / service guard (`crud.fn-execution.lookup`) and the
   canvas (`layout.builder-helpers`) — the latter two would otherwise
   pull in the whole runtime (and `compile-runtime` reads the crud lookup
   namespace: a load cycle)."
  (:require
    [graphden.executor.compile.bindings :as b]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.renames :as r]))


(defn surface-entries
  "The fn's free-arg entries AS THE PUBLIC BOUNDARY presents them —
   `deep-free-ext-entries` with each entry's slot-id mapped through
   the closest-chain rename (`l/chain-rename-for-slot`). An entry a
   chain rename covers gets the renamed `:ext-name` and keeps the
   walker's raw name under `:source-name`, so callers may address the
   slot by either. Entries no chain rename covers pass through
   unchanged.

   This is a PRESENTATION view (ADR-inherited-rename-surface): the
   underlying walk — and every internal consumer of
   `deep-free-ext-entries` (`build-hof-translation`,
   `hof-lambda-params`, `cache-projection-frees`) — stays on raw
   per-fid naming; only name-facing boundaries read this.

   Memoised via `:surface-entries-cache` — this runs on EVERY public
   execute (`translate-named-args`), and the per-entry chain walk
   uncached was a 2.5x graph-layout perf-trend regression."
  [fn-id {:keys [surface-entries-cache] :as lookups}]
  (letfn [(compute
            []
            (mapv (fn [{:keys [ext-name slot-id] :as e}]
                    (let [rn (when slot-id
                               (l/chain-rename-for-slot fn-id slot-id
                                                        lookups))]
                      (if (and rn (not= rn ext-name))
                        (assoc e :ext-name rn :source-name ext-name)
                        e)))
                  (r/deep-free-ext-entries fn-id lookups)))]
    (if-let [cache surface-entries-cache]
      (or (get @cache fn-id)
          (let [res (compute)]
            (swap! cache assoc fn-id res)
            res))
      (compute))))


(defn surface-names
  "`{:names [kw …] :accepted #{kw …}}` for fn-id — the public
   (closest-chain-rename) name vector and the accepted set (public ∪
   raw). Order and MEMBERSHIP of `:names` stay the NAME walker's —
   the entries walker also lists env-covered slots the caller doesn't
   supply, so entries only contribute the rename SUBSTITUTION, never
   new names.

   Memoised via `:surface-names-cache`: `execute-with-named-args`
   validates per call — HOFs call it per ITEM — so this must cost an
   atom lookup, not a rebuild."
  [fn-id {:keys [surface-names-cache] :as lookups}]
  (letfn [(compute
            []
            (let [raw (r/deep-free-ext-names fn-id lookups)
                  rename-of (into {}
                                  (keep (fn [e]
                                          (when-let [src (:source-name e)]
                                            [src (:ext-name e)])))
                                  (surface-entries fn-id lookups))
                  names (into [] (distinct)
                              (map #(get rename-of % %) raw))]
              {:names names
               :accepted (into (set raw) names)}))]
    (if-let [cache surface-names-cache]
      (or (get @cache fn-id)
          (let [res (compute)]
            (swap! cache assoc fn-id res)
            res))
      (compute))))


(defn public-free-entries
  "The fn's free-arg HOLES as the public boundary presents them, slot-id
   keyed — `{:ext-name :slot-id}` (+ `:captured? true` for a HOF
   target's closure capture):

   - MEMBERSHIP and NAMES are the name walker's (`surface-names`) — the
     entries walker also lists env-covered slots the caller never
     supplies, so those are filtered out here, and each name maps to
     the surface entry carrying it (rename applied);
   - plus the closure captures `r/deep-free-entries-with-captures`
     finds inside HOF targets (what a target reads from the caller's fa
     beyond its lambda params, minus what any enclosing scope supplies),
     under names not already on the surface.

   The Run form (`crud.fn-execution.lookup/free-arg-slot-map`), the
   service create-guard (`:skip-root-hofs?` — the root's own callback
   subtrees are the deferred invoker's concern) and the canvas's deep
   placeholders (`layout.builder-helpers/emit-root-deep-frees!`) all
   read this one surface, so they agree by construction."
  ([fn-id lookups] (public-free-entries fn-id lookups nil))
  ([fn-id lookups opts]
   (let [public (set (:names (surface-names fn-id lookups)))
         ;; One hole per slot IDENTITY: a rename-view slot and its source
         ;; share a root (`:source-slot-id` chain) — a binding on either
         ;; end, anywhere in the fn's chain, closes both (#51), and two
         ;; exposures of one root are one hole.
         slot-map (:slot-map lookups)
         root-of (fn [sid]
                   (loop [sid sid seen #{}]
                     (let [src (some-> (get slot-map sid) :source-slot-id)]
                       (if (and src (not (seen src)))
                         (recur src (conj seen sid))
                         sid))))
         ;; …including env-bindings: a binding on a slot the chain does not
         ;; expose (the SOURCE end of a rename that lives in a ref target)
         ;; binds that slot's identity all the same.
         bound-roots (into #{}
                           (comp (filter #(contains? #{:value :seq :resolved-value :ref :fn-ref} (:kind %)))
                                 (keep :slot-id)
                                 (map root-of))
                           (concat (b/collect-bindings fn-id lookups)
                                   (b/collect-env-bindings fn-id lookups)))
         seen-roots (volatile! #{})
         open-hole? (fn [{:keys [slot-id]}]
                      (let [r (root-of slot-id)]
                        (when (and slot-id (not (contains? bound-roots r))
                                   (not (contains? @seen-roots r)))
                          (vswap! seen-roots conj r)
                          true)))
         by-name (reduce (fn [m {:keys [ext-name] :as e}]
                           (let [k (keyword ext-name)]
                             (if (and (contains? public k) (not (contains? m k)))
                               (assoc m k e)
                               m)))
                         {}
                         (surface-entries fn-id lookups))
         surface (mapv (fn [k] (assoc (get by-name k) :ext-name k))
                       (filter #(contains? by-name %)
                               (:names (surface-names fn-id lookups))))
         seen (into #{} (map :ext-name) surface)
         captures (into []
                        (comp (filter :captured?)
                              (map #(update % :ext-name keyword))
                              (remove #(contains? seen (:ext-name %)))
                              (distinct))
                        (r/deep-free-entries-with-captures fn-id lookups opts))]
     (into []
           (filter open-hole?)
           (into surface (map #(select-keys % [:ext-name :slot-id :captured?])) captures)))))
