(ns graphden.executor.compile.deps
  "Ref-DAG dependency analysis for incremental recompile.

   `forward-deps-of` lists the fn-ids whose mutation invalidates a
   given fn's closure (its parent-ids, the slot's base-fn /
   element-fn / return-type, every binding's ref-fn-id /
   type-override-fn-id, and every binding-list-item's ref-fn-id).
   `build-reverse-deps` inverts the forward graph so
   `delta-recompile!` can ask 'who needs recompile when X changes?'
   in O(degree). `transitive-blast` is the closure walk over those
   reverse-deps.

   Lifted out of the retired `graphden.executor.compile` namespace
   so the rest of that file can go.")


(defn- bindings-of
  [fn-id bindings]
  (filter #(= fn-id (:fn-id %)) bindings))


(defn- items-of
  [binding-ids list-items]
  (filter #(contains? binding-ids (:binding-id %)) list-items))


(defn forward-deps-of
  "Set of fn-ids whose mutation invalidates `fn-id`'s closure.
   Conservative — better to recompile a few extras than to ship a
   stale closure."
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
  "Produce `{fn-id → #{ids that depend on it}}` over the whole
   graph. Inverts `forward-deps-of` once per full rebuild."
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
   included — their own closures need recompile too."
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
