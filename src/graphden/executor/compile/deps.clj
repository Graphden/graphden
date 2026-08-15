(ns graphden.executor.compile.deps
  "Ref-DAG dependency analysis for incremental recompile.

   `forward-deps-of` lists the fn-ids whose mutation invalidates a
   given fn's closure (its parent-ids, the slot's base-fn /
   element-fn / return-type, every binding's ref-fn-id /
   type-override-fn-id, and every binding-list-item's ref-fn-id).
   `build-reverse-deps` inverts the forward graph so
   `delta-recompile!` can ask 'who needs recompile when X changes?'
   in O(degree). `transitive-blast` is the closure walk over those
   reverse-deps; `forward-closure` is the same walk over the FORWARD
   deps — 'what does X transitively depend on?' — which is the content
   of a fleet CELL (docs/FLEET_RFC.md §3).")


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
           ;; Resolver-backed value bindings (vault secrets): the
           ;; resolver graph-fn runs at arg-resolution time, so it IS
           ;; part of the closure. Without this edge a fleet cell
           ;; (`load-cell!` → forward-closure) compiled a fn with a
           ;; resolver binding WITHOUT the resolver's closure — first
           ;; force → registry miss → fn-not-found; evict-cell!'s
           ;; refcount had the same blind spot.
           (keep :resolver-fn-id bs)
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


(defn build-deps-state
  "Full rebuild of both forward + reverse dep maps. Returned shape
   matches what `incremental-update` consumes / produces, so the
   first call AFTER a cold start can hand its output straight to
   subsequent incremental updates.

   `{:forward-deps {fn-id → #{ids-it-depends-on}}
     :reverse-deps {fn-id → #{ids-that-depend-on-it}}}`"
  [graph]
  (let [indexed (index-graph graph)
        fns-map (:fns indexed)
        forward (reduce (fn [acc f]
                          (assoc acc (:id f)
                                 (forward-deps-of (:id f) indexed)))
                        {}
                        (vals fns-map))
        reverse (reduce-kv
                  (fn [acc fid fwds]
                    (reduce (fn [a dep] (update a dep (fnil conj #{}) fid))
                            acc
                            fwds))
                  {}
                  forward)]
    {:forward-deps forward :reverse-deps reverse}))


(defn incremental-update
  "Delta-update `{:forward-deps :reverse-deps}` for `changed-fn-ids`.
   Re-derives each changed fn's forward-deps from `graph`, then walks
   the diff against its stored forward-deps to add / drop edges in
   reverse-deps. Returns the new state.

   Cost is O(changed × avg-deps-per-fn) — sub-millisecond per CRUD
   on typical graphs, vs `build-deps-state`'s O(fns + bindings +
   list-items) full sweep on every write.

   Deleted fns are detected by absence from `graph`'s `fns`; their
   forward-deps entry is dropped and their reverse-deps edges are
   removed. Created / updated fns recompute fwd-deps cleanly."
  [state graph changed-fn-ids]
  (let [indexed (index-graph graph)
        fns-map (:fns indexed)]
    (reduce
      (fn [{:keys [forward-deps reverse-deps]} fid]
        (let [old-fwd (get forward-deps fid #{})
              new-fwd (if (contains? fns-map fid)
                        (forward-deps-of fid indexed)
                        #{})
              added   (reduce disj new-fwd old-fwd)
              removed (reduce disj old-fwd new-fwd)
              ;; Edge maintenance in reverse-deps:
              ;; - REMOVE: dep that fid no longer points at loses fid
              ;;   from its dependents set.
              ;; - ADD: dep that fid newly points at gains fid.
              rd-after-removes
              (reduce (fn [acc dep]
                        (let [updated (disj (get acc dep #{}) fid)]
                          (if (empty? updated)
                            (dissoc acc dep)
                            (assoc acc dep updated))))
                      reverse-deps
                      removed)
              rd-after-adds
              (reduce (fn [acc dep] (update acc dep (fnil conj #{}) fid))
                      rd-after-removes
                      added)
              ;; A delete (fid no longer in fns-map) also drops its own
              ;; reverse-deps entry — nothing depends on a deleted fn.
              rd-final (if (contains? fns-map fid)
                         rd-after-adds
                         (dissoc rd-after-adds fid))
              fwd-final (if (contains? fns-map fid)
                          (assoc forward-deps fid new-fwd)
                          (dissoc forward-deps fid))]
          {:forward-deps fwd-final :reverse-deps rd-final}))
      state
      changed-fn-ids)))


(defn- reachable-closure
  "Transitive reachability over an adjacency map `{id → #{neighbours}}`,
   seeded at `seed-ids` and INCLUDING them. The generic walk shared by the
   reverse (`transitive-blast`) and forward (`forward-closure`) closures — the
   only difference between them is which dep map they walk."
  [adjacency seed-ids]
  (loop [seen #{}
         q (vec seed-ids)]
    (if (empty? q)
      seen
      (let [x (peek q), q' (pop q)]
        (if (contains? seen x)
          (recur seen q')
          (recur (conj seen x)
                 (into q' (get adjacency x #{}))))))))


(defn transitive-blast
  "Inverse-closure walk over `reverse-deps`. Returns every fn-id that
   transitively depends on at least one of `seed-ids`. The seeds are
   included — their own closures need recompile too."
  [reverse-deps seed-ids]
  (reachable-closure reverse-deps seed-ids))


(defn forward-closure
  "Forward-closure walk over `forward-deps` (`{fn-id → #{ids-it-depends-on}}`,
   as built by `build-deps-state`): every fn-id that `root-ids` transitively
   DEPEND ON, roots included. This is the CONTENT of a cell (docs/FLEET_RFC.md
   §3) — the self-contained set of fns that must be compiled together for a
   root to run, and the unit a fleet executor loads / evicts. Mirror of
   `transitive-blast`; only the walked dep-map differs."
  [forward-deps root-ids]
  (reachable-closure forward-deps root-ids))
