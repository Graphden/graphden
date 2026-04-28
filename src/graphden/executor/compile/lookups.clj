(ns graphden.executor.compile.lookups
  "Index helpers over fn and arg entities used throughout compile.

   Pure functions on maps — no side effects, no dependency on the compile
   driver. Shared by `compile` (binding resolution, ref rewriting) and
   `compile-runtime` (translating arg-ids back to external names).")


(defn build-lookups
  "Index fns and args for fast lookup during compile."
  [fns args]
  (let [fn-map (into {} (map (juxt :id identity) fns))
        arg-map (into {} (map (juxt :id identity) args))
        args-by-fn (reduce (fn [m a]
                             (update m (:fn-id a) (fnil conj []) a))
                           {}
                           args)]
    {:fn-map fn-map
     :arg-map arg-map
     :args-by-fn args-by-fn}))


(defn inheritance-chain
  "Return vector of fn-ids reachable from F via `parent-ids` in BFS order.
   F is first, then direct parents, then grandparents, etc. Under
   multiple inheritance a fn may have several parents; we collect ALL
   of them so bindings on non-primary parents (e.g. `text-not-found-
   response` inheriting status from `not-found-response` AND content-
   type from `text-content-type`) are both visible.

   `closest-binding` walks this vector in order, so earlier (nearer F)
   bindings still win when multiple ancestors bind the same slot."
  [fn-id fn-map]
  (loop [acc [fn-id]
         seen #{fn-id}
         queue (->> (get-in fn-map [fn-id :parent-ids])
                    (remove nil?)
                    vec)]
    (if (empty? queue)
      acc
      (let [fid (first queue)
            rest-queue (subvec queue 1)]
        (if (contains? seen fid)
          (recur acc seen rest-queue)
          (let [pids (->> (get-in fn-map [fid :parent-ids])
                          (remove nil?)
                          (remove seen))]
            (recur (conj acc fid)
                   (conj seen fid)
                   (into rest-queue pids))))))))


(defn base-fn-of
  "Return the terminal ancestor fn-entity (parent-ids empty) reachable
   from fn-id. Graphden's data model guarantees MI paths all converge
   to the same base-fn, so returning the first one found is correct."
  [fn-id fn-map]
  (let [chain (inheritance-chain fn-id fn-map)]
    (some (fn [fid]
            (let [f (get fn-map fid)]
              (when (empty? (:parent-ids f))
                f)))
          chain)))


(defn primary-arg?
  "An arg is primary (belongs to the base-fn) when it has no source-id."
  [arg]
  (nil? (:source-id arg)))


(defn walk-source-chain
  "Walk an arg's source-id chain upward and return the sequence of arg ids
   from the arg itself to its terminal primary-arg. Stops at source-id=nil."
  [arg-id arg-map]
  (loop [acc [arg-id], id arg-id]
    (let [a (get arg-map id)]
      (if-let [sid (:source-id a)]
        (recur (conj acc sid) sid)
        acc))))


(defn terminal-primary-id
  "Return the id of the primary arg that this arg's source-chain terminates at.
   For a primary arg itself, returns its own id."
  [arg-id arg-map]
  (peek (walk-source-chain arg-id arg-map)))


(defn source-chain-set
  "Set of all arg-ids in `arg-id`'s source-id chain (inclusive)."
  [arg-id arg-map]
  (set (walk-source-chain arg-id arg-map)))


(defn arg-ext-name
  "External name of `arg-id` — first `:name` found walking its source-id
   chain (from `arg-id` toward the terminal primary), falling back to the
   terminal primary's name. Handles propagated args correctly: a nameless
   pass-through arg inherits the name of whichever ancestor first set one
   (typically the rename inside a ref-target)."
  [arg-id arg-map]
  (loop [id arg-id, fallback nil]
    (if-let [a (get arg-map id)]
      (let [nm (some-> (:name a) keyword)]
        (cond
          nm nm
          (:source-id a) (recur (:source-id a) (or fallback nm))
          :else fallback))
      fallback)))


(defn source-chain-stays-within?
  "True iff every step of `arg-id`'s source-id chain lives on a fn that's
   present in `fn-id-set`. Used to distinguish F's own binding args (stay
   in F's inheritance chain) from propagation pass-throughs (chain crosses
   into a ref-target fn)."
  [arg-id fn-id-set arg-map]
  (loop [id arg-id]
    (let [a (get arg-map id)]
      (if (contains? fn-id-set (:fn-id a))
        (if-let [sid (:source-id a)]
          (recur sid)
          true)
        false))))
