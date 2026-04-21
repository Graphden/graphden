(ns graphden.executor.compile.bindings
  "Static binding analysis: inspect a fn F's inheritance chain and
   classify each primary arg slot as `:value` | `:ref` | `:seq` | `:free`.

   Two public entry points:

   - `collect-bindings` — one entry per base-fn primary, in declaration
     order. Feeds `compile-fn`'s impl call.

   - `collect-env-bindings` — bindings whose terminal primary lies
     outside F's base, or whose source chain crosses into a ref-target
     fn. Feeds the augmented free-args map that reaches deep free args
     through `hof-wrap` call sites."
  (:require
    [graphden.executor.compile.lookups :as l]))


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
                    (when (and (= primary-id (l/terminal-primary-id (:id arg) arg-map))
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
                       (l/arg-ext-name (:id closest-chain-arg) arg-map))
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
  (let [base (l/base-fn-of fn-id fn-map)
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
  (let [base (l/base-fn-of fn-id fn-map)
        base-primary-ids (into #{} (map :id) (filterv l/primary-arg? (get args-by-fn (:id base) [])))
        chain (l/inheritance-chain fn-id fn-map)
        chain-set (set chain)
        seen-ext-names (atom #{})
        out (atom [])]
    (doseq [fid chain
            arg (get args-by-fn fid [])]
      (let [term-id (l/terminal-primary-id (:id arg) arg-map)
            ext-name (l/arg-ext-name (:id arg) arg-map)
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
