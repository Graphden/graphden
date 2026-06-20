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


(defn- index-bindings-by-fn
  "Group `bindings` once by `:fn-id`. O(bindings); subsequent
   `forward-deps-of` calls then look up their fn's bindings in O(1)
   instead of re-filtering the whole binding list per fn (which was
   O(fns × bindings) over the full graph — the GC-pressure hotspot
   `prime-compile-deps!` hits on every CRUD write)."
  [bindings]
  (reduce (fn [acc b]
            (update acc (:fn-id b) (fnil conj []) b))
          {}
          bindings))


(defn- index-items-by-binding
  "Group `list-items` once by `:binding-id`. Same motivation as
   `index-bindings-by-fn`."
  [list-items]
  (reduce (fn [acc i]
            (update acc (:binding-id i) (fnil conj []) i))
          {}
          list-items))


(defn forward-deps-of
  "Set of fn-ids whose mutation invalidates `fn-id`'s closure.
   Conservative — better to recompile a few extras than to ship a
   stale closure.

   `indexed-graph` must carry pre-built `:bindings-by-fn` and
   `:items-by-binding` indexes — call `index-graph` once before
   looping over fns. The old shape that took raw `bindings` /
   `list-items` collections did an O(N) filter per call; on a
   3000-fn graph that turned `build-reverse-deps` into a
   billion-operation rebuild on every CRUD write."
  [fn-id {:keys [fns bindings-by-fn items-by-binding]}]
  (let [f (get fns fn-id)
        bs (get bindings-by-fn fn-id [])
        items (mapcat #(get items-by-binding (:id %) []) bs)]
    (into #{}
          (comp cat (filter some?))
          [(:parent-ids f)
           (keep f [:base-fn-id :element-fn-id :return-type-fn-id])
           (keep :ref-fn-id bs)
           (keep :type-override-fn-id bs)
           (keep :ref-fn-id items)])))


(defn index-graph
  "Pre-build the indexes `forward-deps-of` needs. Pulled out so
   `build-reverse-deps` does the indexing ONCE per call instead of
   leaving callers to remember.

   Accepts either a `fns`-collection map (raw `read-graph` shape) or
   one whose `:fns` is already a `{fn-id → fn}` map — the indexes
   end up identical either way."
  [{:keys [fns bindings list-items] :as graph}]
  (let [fns-map (if (map? fns) fns (into {} (map (juxt :id identity)) fns))]
    (assoc graph
           :fns fns-map
           :bindings-by-fn (index-bindings-by-fn bindings)
           :items-by-binding (index-items-by-binding list-items))))


(defn build-reverse-deps
  "Produce `{fn-id → #{ids that depend on it}}` over the whole
   graph. Inverts `forward-deps-of` once per full rebuild.

   O(fns + bindings + list-items) after `index-graph` is called
   once at the top — was O(fns × bindings) before pre-indexing."
  [graph]
  (let [indexed (index-graph graph)
        fns-map (:fns indexed)]
    (reduce
      (fn [acc f]
        (reduce (fn [a dep] (update a dep (fnil conj #{}) (:id f)))
                acc
                (forward-deps-of (:id f) indexed)))
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
