(ns graphden.services.port-check
  "Sync-time port-collision detector for `:http-server`-rooted
   fn-defs.

   The reconciler relies on OS-level `Address already in use` to
   surface port collisions at start time — fine when there's one
   service per port, breaks down when an admin accidentally
   configures two web-servers binding `:port 8080`. The winner
   reports `:start-failed-at`, the loser hides behind a `failed`
   badge in the UI, and the admin has to dig through service-locks
   to figure out what's happening.

   This ns walks the fn-defs at sync time, groups
   `:http-server`-descendants by their bound `:port` literal, and
   logs a single WARN per colliding port with the offending fn-
   names. Catches the misconfiguration before any reconcile pass
   runs.

   Limitation: only LITERAL ports are inspected. A fn-def with
   `:port :some-env-driven-fn-ref` bypasses the check — without
   running the executor we can't know what value that ref
   eventually produces."
  (:require
    [clojure.set :as set]
    [clojure.tools.logging :as log]))


(defn- ancestor-chain-includes?
  "True iff `target-name` appears anywhere in `fn-name`'s
   `:parent-ids` closure (interpreted by-name via `defs-by-name`).
   BFS — each name visited at most once."
  [defs-by-name fn-name target-name]
  (loop [to-visit #{fn-name}
         visited #{}]
    (if (empty? to-visit)
      false
      (let [current (first to-visit)
            rest-set (disj to-visit current)]
        (cond
          (visited current) (recur rest-set visited)
          (= current target-name) true
          :else
          (let [parents (or (some-> (get defs-by-name current) :parents)
                            (some-> (get defs-by-name current) :parent vector)
                            [])
                new-visited (conj visited current)
                new-to-visit (set/difference (set parents) new-visited)]
            (recur (set/union rest-set new-to-visit)
                   new-visited)))))))


(defn- literal-port
  "Extract the literal `:port` value bound by `fn-def`, or nil
   when `:port` is unbound, a ref, or absent. The binding shape
   from fns.edn varies (`{:port 8080}` vs `{:port {:value 8080}}`
   vs structured map); only the bare integer + the `:value`-map
   shape count as literal."
  [fn-def]
  (let [v (get-in fn-def [:args :port])]
    (cond
      (integer? v) v
      (and (map? v) (integer? (:value v))) (:value v)
      :else nil)))


(defn scan-port-collisions
  "Return a map `{port-int [fn-name …]}` for every `:port` value
   that's bound by two-or-more `:http-server`-descendant fn-defs.
   Singletons are omitted — the caller wants only the conflicting
   sets."
  [fn-defs]
  (let [defs-by-name (into {}
                           (keep (fn [d] (when-let [n (:name d)] [n d])))
                           fn-defs)
        port-bindings (for [d fn-defs
                            :let [fn-name (:name d)
                                  port (literal-port d)]
                            :when (and fn-name port
                                       (ancestor-chain-includes?
                                         defs-by-name fn-name :http-server))]
                        [port fn-name])
        by-port (group-by first port-bindings)]
    (into {}
          (keep (fn [[port pairs]]
                  (when (> (count pairs) 1)
                    [port (mapv second pairs)])))
          by-port)))


(defn warn-on-collisions!
  "Side-effect: log a single WARN per colliding port. Call from
   the sync pipeline once all fn-defs are visible. Returns the
   collision map (per `scan-port-collisions`) so callers can
   surface it elsewhere if they want — empty map means clean."
  [fn-defs]
  (let [collisions (scan-port-collisions fn-defs)]
    (doseq [[port fn-names] collisions]
      (log/warn "Port collision detected — multiple :http-server-rooted "
                "fn-defs bind the same literal port. The first reconciler "
                "start wins; the rest fail with `Address already in use`."
                {:port port :fn-names fn-names}))
    collisions))
