(ns graphden.executor.compile.renames
  "Compile-time helpers for translating free-arg names between a caller
   F and a ref-target R, plus deep-walking R's free args to populate
   HOF lambda-param lists.

   In the slot/fn-slot/binding model the rename information lives in
   `binding.rename-to`. F's caller-facing name for slot S is the
   closest binding's `:rename-to` if any, else slot.name. Walking
   non-HOF refs collects R's leftover free slots so the outer F can
   thread them through."
  (:require
    [graphden.executor.compile.bindings :as b]
    [graphden.executor.compile.lookups :as l]))


(defn deep-free-ext-names
  "Collect TRULY-unbound free-arg external names reachable from `fn-id`,
   walking across non-HOF ref bindings. `:is-fn` refs are a BOUNDARY —
   the inner hof-wrap consumes its own leftovers without widening the
   outer interface.

   Returns the names in their first-encountered order, deduped."
  [fn-id lookups]
  (let [result (atom [])
        seen (atom #{})
        visited-fns (atom #{})
        emit! (fn [n]
                (when-not (contains? @seen n)
                  (swap! seen conj n)
                  (swap! result conj n)))]
    (letfn [(walk
              [fid covered]
              (when-not (contains? @visited-fns fid)
                (swap! visited-fns conj fid)
                (let [bindings (b/collect-bindings fid lookups)
                      env-bindings (b/collect-env-bindings fid lookups)
                      own-primaries (into #{}
                                          (comp (remove #(= :free (:kind %)))
                                                (map :ext-name))
                                          bindings)
                      ;; Env-bindings cover names exposed by ref-targets'
                      ;; free args — without including them, the walker
                      ;; would re-emit the same name from inside the
                      ;; ref-tree when it should be considered bound.
                      env-names (into #{} (map :env-name) env-bindings)
                      next-covered (-> covered
                                       (into own-primaries)
                                       (into env-names))]
                  (doseq [bnd bindings]
                    (case (:kind bnd)
                      :free (let [n (:ext-name bnd)]
                              (when-not (or (next-covered n)
                                            (not (:required bnd)))
                                (emit! n)))
                      :ref  (when-not (:is-fn bnd)
                              (walk (:ref-id bnd) next-covered))
                      :seq  (doseq [item (:items bnd)]
                              (cond
                                ;; Ref item — recurse to collect its
                                ;; deep-free names.
                                (:ref-fn-id item)
                                (walk (:ref-fn-id item) next-covered)

                                ;; Positional rename `{:as :name}` —
                                ;; that name is exposed as a free arg
                                ;; from the binding's owner. Emit it
                                ;; unless covered.
                                (and (map? (:value item))
                                     (:as (:value item))
                                     (not (:literal item)))
                                (let [n (some-> (:as (:value item)) keyword)]
                                  (when (and n (not (next-covered n)))
                                    (emit! n)))))
                      :value nil)))))]
      (walk fn-id #{}))
    @result))


(defn- find-slot-id-in-tree
  "Find the slot-id corresponding to free-arg name `ext-name` reachable
   from `fn-id` via inheritance + non-HOF refs + seq items. Returns the
   slot-id or nil. `:is-fn` refs are a boundary — slots inside a HOF
   lambda's body don't bubble up as F-tree slots."
  [fn-id ext-name {:keys [slot-by-fn-name] :as lookups}]
  (let [visited (atom #{})]
    (letfn [(walk
              [fid]
              (when-not (contains? @visited fid)
                (swap! visited conj fid)
                (or (some (fn [chain-fid]
                            (some-> (get slot-by-fn-name [chain-fid ext-name])
                                    :id))
                          (l/inheritance-chain* fid lookups))
                    (some (fn [bnd]
                            (case (:kind bnd)
                              :ref (when-not (:is-fn bnd) (walk (:ref-id bnd)))
                              :seq (some (fn [item]
                                           (when-let [r (:ref-fn-id item)]
                                             (walk r)))
                                         (:items bnd))
                              nil))
                          (b/collect-bindings fid lookups)))))]
      (walk fn-id))))


(defn- inheritance-descendants
  "Set of fn-ids whose inheritance chain reaches `f-fn-id` — F itself
   plus every fn that has F (transitively) as a parent. These are the
   fns whose runtime free-args propagate back into F's body when they
   are invoked through F's chain."
  [f-fn-id {:keys [fn-map]}]
  (let [parent->children (reduce (fn [acc [id f]]
                                   (reduce (fn [a pid]
                                             (update a pid (fnil conj #{}) id))
                                           acc
                                           (or (:parent-ids f) [])))
                                 {}
                                 fn-map)
        out (atom #{f-fn-id})]
    (letfn [(walk
              [fid]
              (doseq [child (get parent->children fid #{})]
                (when-not (contains? @out child)
                  (swap! out conj child)
                  (walk child))))]
      (walk f-fn-id))
    @out))


(defn- slot-bound-by?
  "True iff some fn in `fn-ids` has a value/ref/list-append binding
   targeting `slot-id`. Pure rename-to declarations don't count — we
   ask whether someone supplies a value for the slot at runtime."
  [slot-id fn-ids {:keys [binding-by-fn-slot]}]
  (boolean
    (some (fn [fid]
            (when-let [b (get binding-by-fn-slot [fid slot-id])]
              (or (some? (:value b))
                  (some? (:ref-fn-id b))
                  (true? (:list-append b)))))
          fn-ids)))


(defn hof-lambda-params
  "Lambda-param names of HOF target `r-fn-id` when invoked from
   `f-fn-id`. A deep-free name of R is a LAMBDA PARAM iff nothing in
   F's inheritance chain or descendant set supplies a value for that
   slot. Names a chain-ancestor or inheriting descendant binds flow
   into outer-free-args at runtime and must be captured (Clojure-
   closure semantics — without that classification the lambda sees
   them as per-call params and the impl's `(f val)` invocation loses
   the captured value).

   `hof-wrap` picks its call shape from `(count lambda-params)`:
   0/1/N → variadic / single-arg / map-callable."
  [r-fn-id f-fn-id lookups]
  (let [r-frees (deep-free-ext-names r-fn-id lookups)
        f-bindings (b/collect-bindings f-fn-id lookups)
        f-own-names (into #{} (map :ext-name) f-bindings)
        f-deep (set (deep-free-ext-names f-fn-id lookups))
        static-captured (into f-own-names f-deep)
        relatives (into (set (l/inheritance-chain* f-fn-id lookups))
                        (inheritance-descendants f-fn-id lookups))
        env-captured (into #{}
                           (filter (fn [n]
                                     (when-let [sid (find-slot-id-in-tree
                                                      r-fn-id n lookups)]
                                       (slot-bound-by? sid relatives lookups))))
                           r-frees)
        captured (into static-captured env-captured)]
    (vec (remove captured r-frees))))


(defn build-ref-renames
  "For ref R called from F, produce `{R-ext-name → F-ext-name}` to
   rewrite F's free-arg map before handing it to R. In the new model
   renames are encoded as `binding.rename-to`; if F renames slot S to
   `f-name` and R itself exposes the same slot under `r-name`, then
   when F invokes R it must pass `{r-name (free-args f-name)}`.

   For the composition layer's current emission shape (no cross-fn
   rename inheritance), the rename map is empty for most calls. We
   compute it conservatively: any name shared between R's deep frees
   and a name F exposes through its own slot's rename produces a key
   in the result."
  [r-fn-id f-fn-id lookups]
  (let [r-frees (set (deep-free-ext-names r-fn-id lookups))
        f-bindings (b/collect-bindings f-fn-id lookups)]
    (into {}
          (keep (fn [bnd]
                  (let [b-name (:base-name bnd)
                        e-name (:ext-name bnd)]
                    (when (and (= :free (:kind bnd))
                               (not= b-name e-name)
                               (contains? r-frees b-name))
                      [b-name e-name]))))
          f-bindings)))


(defn apply-renames
  "Apply `{R-name → F-name}`: for each entry, expose F's value under
   R's name and drop the F-name key. Extra keys pass through."
  [free-args renames]
  (reduce-kv (fn [acc r-name f-name]
               (if (contains? acc f-name)
                 (-> acc
                     (assoc r-name (get acc f-name))
                     (dissoc f-name))
                 acc))
             free-args
             renames))
