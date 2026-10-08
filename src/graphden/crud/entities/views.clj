(ns graphden.crud.entities.views
  "Explorer filter evaluation and decoding views stored as ordinary fn-defs."
  (:require
    [clojure.string :as str]
    [graphden.crud.entities.list :as entity-list]
    [graphden.crud.fn-execution.errors :as execution-errors]
    [graphden.crud.secret-shape :as secret-shape]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.registry.core :as registry]
    [graphden.lint.graph :as lint]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.util.ns-path :as ns-path]
    [graphden.versioning.storage.core :as vs]))


(defn- reverse-ref-adjacency
  "target-fn-id → [user-fn-ids] over EVERY composition edge: parent-ids,
   binding ref/resolver, list-item refs (through their owner binding).
   One pass over the base rows — the `uses:` rule BFSes this map."
  [{:keys [fns bindings list-items]}]
  (let [owner-of-binding (into {} (map (juxt :id :fn-id)) bindings)
        add (fn [m target user]
              (if (and target user)
                (update m target (fnil conj []) user)
                m))]
    (as-> {} m
          (reduce (fn [m f] (reduce #(add %1 %2 (:id f)) m (:parent-ids f))) m fns)
          (reduce (fn [m b]
                    (-> m
                        (add (:ref-fn-id b) (:fn-id b))
                        (add (:resolver-fn-id b) (:fn-id b))))
                  m bindings)
          (reduce (fn [m li]
                    (add m (:ref-fn-id li)
                         (get owner-of-binding (:binding-id li))))
                  m list-items))))


(defn- transitive-user-ids
  "Every fn-id that transitively USES `target-id` (children, callers,
   callers-of-callers …). Cycle-guarded BFS over `reverse-ref-adjacency`."
  [base target-id]
  (let [adj (reverse-ref-adjacency base)]
    (loop [seen #{} queue (vec (get adj target-id))]
      (if-let [id (first queue)]
        (if (seen id)
          (recur seen (subvec queue 1))
          (recur (conj seen id)
                 (into (subvec queue 1) (get adj id))))
        seen))))


(def ^:private view-result-cap
  "Smart views answer \"which fns belong to this virtual group\" — a
   bounded list keeps a graph-wide rule (`effect:io`) renderable."
  500)


(defn- secret-shaped-pred
  "`(fn [f] bool)` — a secret-shaped fn (parents = exactly a
   `:secret-shape`-tagged base-fn); the same rule `ns-kind-counts` uses,
   with the leaf ids resolved once."
  [base]
  (let [names (into #{} (map name) (registry/fn-names-with-tag :secret-shape))
        leaf-ids (into #{}
                       (comp (filter (comp names str :name)) (map :id))
                       (:fns base))]
    (fn [f] (boolean (some #(secret-shape/secret-fn? f %) leaf-ids)))))


(defn- ns-path-pred
  "`(fn [path] bool)` — `path` is one of `wants` or under it (segment
   match: `core` covers `core.strings`, not `coreutils`). `wants` are
   dotted paths; `/` is accepted as a separator too."
  [wants]
  (let [wants (into #{} (map #(str/replace (str/lower-case (str %)) "/" ".")) wants)]
    (fn [path]
      (let [p (some-> path str/lower-case)]
        (boolean (and p (some #(or (= p %) (str/starts-with? p (str % "."))) wants)))))))


(defn- kind-preds
  "The Explorer's KIND classification, server-side: `{kind (fn [f] bool)}`
   over roled rows. App routes come from the same authorized addon callback
   as the Apps panel; without the addon this kind has no members."
  [{:keys [base namespaces]} storage]
  (let [paths (ns-path/path-map @namespaces)
        secret? (secret-shaped-pred base)
        type? (fn [f] (boolean (types-api/type-lens-roles (:role f))))
        test-ns? (fn [f] (boolean (some #{"tests"} (str/split (str (get paths (:namespace-id f))) #"\."))))
        service-ids (delay (into #{} (map :fn-id) (sp/query-entities storage :service {})))
        app-ids (delay (into #{} (map :handler-fn-id)
                             (tenancy/tenant-app-routes (tenancy/current-org))))]
    {:types type?
     :secrets secret?
     :tests test-ns?
     :services (fn [f] (contains? @service-ids (:id f)))
     :fn (fn [f]
           (not (or (type? f) (secret? f) (test-ns? f)
                    (contains? @service-ids (:id f)) (contains? @app-ids (:id f)))))
     :apps (fn [f] (contains? @app-ids (:id f)))}))


(defn normalise-filters
  "The wire/impl shape of an Explorer filter set → the internal one:
   `{:name text :uses [uuid…] :effects [kw…] :kinds [kw…]
     :namespaces [path…] :exclude [path…] :unused bool :views [uuid…]}`
   (`:views` — ids of `:explorer-view` fn-defs whose members this set
   intersects with; the `also` slot on the wire). Accepts
   strings or keywords for kinds/effects, strings or uuids for ids,
   nil / missing for \"no filter on this axis\"."
  [filters]
  (let [kw (fn [v] (when v (keyword (str/replace (name (if (keyword? v) v (str v))) #"^:" ""))))
        ->uuid (fn [v]
                 (cond (uuid? v) v
                       (string? v) (try (java.util.UUID/fromString v) (catch Exception _ nil))
                       :else nil))
        vec-of (fn [f xs] (into [] (comp (map f) (remove nil?) (distinct)) (if (sequential? xs) xs (when xs [xs]))))
        text (fn [v] (let [t (some-> v str str/trim)] (when (seq t) t)))]
    {:name (text (:name filters))
     :uses (vec-of ->uuid (:uses filters))
     :effects (vec-of kw (:effects filters))
     :kinds (vec-of kw (:kinds filters))
     :problems (vec-of kw (:problems filters))
     :namespaces (vec-of text (:namespaces filters))
     :exclude (vec-of text (:exclude filters))
     :unused (boolean (:unused filters))
     :views (vec-of ->uuid (or (:views filters) (:also filters)))}))


(defn- suppression-keys
  "Read the same branch-local root const used by the Lint panel."
  [{:keys [fns bindings]}]
  (let [owner (some #(when (and (= "lint-suppressions" (:name %))
                                (nil? (:namespace-id %))) (:id %)) fns)
        entries (some #(when (= owner (:fn-id %)) (:value %)) bindings)]
    (into #{}
          (map (fn [entry]
                 [(keyword (:rule entry)) (vec (sort (map str (:fn-ids entry))))]))
          entries)))


(defn- problem-members
  [ctx base]
  {:failed (delay (into #{} (map :fn-id)
                        (execution-errors/unresolved-failure-counts
                          ctx (or (:pool (:pg-storage ctx))
                                  (:pool (vs/unwrap (:storage ctx))))
                          (tenancy/current-org) 7)))
   :lint (delay (into #{} (mapcat :fn-ids)
                      (lint/lint-branch ctx (suppression-keys base))))})


(defn- view-filter-preds
  "One `(fn [f] bool)` per active axis of a normalised filter set.
   Axes AND; within `:kinds` and `:namespaces` the values OR (a row is
   one of the kinds / under one of the roots); `:uses` and `:effects`
   AND (uses ALL of, carries ALL of) — the chips-AND rule, one chip per
   fn / effect."
  [{:keys [base] memberships :problem-members :as env} storage
   {:keys [name uses effects kinds problems namespaces exclude unused]}]
  (let [paths (delay (ns-path/path-map @(:namespaces env)))
        qualified (fn [f]
                    (let [p (get @paths (:namespace-id f))]
                      (if (seq p) (str p "." (:name f)) (:name f))))
        adj (delay (reverse-ref-adjacency base))
        kinds-of (delay (kind-preds env storage))]
    (cond-> []
      name (conj (let [needle (str/lower-case name)]
                   (fn [f] (str/includes? (str/lower-case (qualified f)) needle))))
      (seq uses) (into (map (fn [target]
                              (let [users (transitive-user-ids base target)]
                                (fn [f] (contains? users (:id f)))))
                            uses))
      (seq effects) (into (map (fn [kind]
                                 (fn [f]
                                   (contains? (set (:effects (registry/rich-type-of-id (:id f))))
                                              kind)))
                               effects))
      (seq kinds) (conj (let [preds (keep @kinds-of kinds)]
                          (fn [f] (boolean (some #(% f) preds)))))
      (seq problems) (conj (fn [f]
                             (boolean
                               (some (fn [problem]
                                       (if (= :type-errors problem)
                                         (pos? (or (:type-error-count f) 0))
                                         (when-let [ids (get memberships problem)]
                                           (contains? @ids (:id f)))))
                                     problems))))
      (seq namespaces) (conj (let [in? (ns-path-pred namespaces)]
                               (fn [f] (in? (get @paths (:namespace-id f))))))
      (seq exclude) (conj (let [out? (ns-path-pred exclude)]
                            (fn [f] (not (out? (get @paths (:namespace-id f)))))))
      unused (conj (fn [f] (empty? (get @adj (:id f))))))))


;; ---------------------------------------------------------------------------
;; Views saved in the graph — fn-defs extending the `:explorer-view`
;; base-fn. Listing them (with their bound filters decoded) is a READ
;; projection over the cached graph, like every other scope here.
;; ---------------------------------------------------------------------------

(def explorer-view-base-name
  "Name of the base-fn a graph-saved view extends. Base-fn names are
   globally unique (name-keyed impls registry), so the id is resolved
   over the in-memory graph without a query."
  "explorer-view")


(declare static-binding-value)


(defn constant-value
  "Decode an ordinary const composition without executing a target or any
   user-defined code. Other computations remain runnable, but cannot be
   projected into editable filter chips without losing their meaning."
  [indices fn-id seen]
  (if (contains? seen fn-id)
    ::unsupported
    (let [root (lookups/root-fn fn-id (:fn-map indices) indices)]
      (if (= "const" (:name root))
        (if-let [value (some #(when (= :value (:base-name %)) %)
                             (bindings/collect-bindings fn-id indices))]
          (static-binding-value indices value (conj seen fn-id))
          ::unsupported)
        ::unsupported))))


(defn- static-binding-value
  [indices binding seen]
  (case (:kind binding)
    :value (:value binding)
    :fn-ref (:ref-id binding)
    :free nil
    :ref (constant-value indices (:ref-id binding) seen)
    :seq (let [values (mapv (fn [item]
                              (if-let [ref (:ref-fn-id item)]
                                (constant-value indices ref seen)
                                (:value item)))
                            (:items binding))]
           (if (some #{::unsupported} values) ::unsupported values))
    ::unsupported))


(defn- identity-value
  [value]
  (or (when (uuid? value) value)
      (when (string? value) (parse-uuid value))))


(defn- supported-axis-value?
  [axis value]
  (cond
    (nil? value) true
    (= axis :name) (string? value)
    (= axis :unused) (boolean? value)
    (contains? #{:uses :also} axis) (some? (identity-value value))
    (contains? #{:uses-all :also-all} axis)
    (and (sequential? value) (every? identity-value value))
    (contains? #{:effects :kinds :problems :namespaces :exclude} axis)
    (and (sequential? value) (every? #(or (string? %) (keyword? %)) value))
    :else false))


(defn decode-view
  "Project the effective slots of one graph view into the listing wire shape.
   Uses compiler binding classification for inheritance/list append/renames.
   Unknown computations are explicit unsupported axes, never dropped filters."
  [indices fn-id]
  (let [classified (bindings/collect-bindings fn-id indices)
        values (into {} (map (fn [binding]
                               [(:base-name binding)
                                (static-binding-value indices binding #{fn-id})])) classified)
        unsupported (into [] (keep (fn [[axis value]]
                                     (when-not (supported-axis-value? axis value) axis))) values)
        values (apply dissoc values unsupported)
        refs (fn [scalar multiple]
               (into [] (comp (map identity-value) (distinct))
                     (concat (when-let [id (get values scalar)] [id]) (get values multiple))))
        filters (cond-> {}
                  (some? (:name values)) (assoc :name (:name values))
                  (seq (refs :uses :uses-all)) (assoc :uses (refs :uses :uses-all))
                  (seq (refs :also :also-all)) (assoc :also (refs :also :also-all))
                  (true? (:unused values)) (assoc :unused true))
        filters (reduce (fn [m axis]
                          (if (seq (get values axis))
                            (assoc m axis (mapv #(if (keyword? %) (name %) %) (get values axis))) m))
                        filters [:effects :kinds :problems :namespaces :exclude])]
    (cond-> {:filters filters}
      (seq unsupported) (assoc :unsupported (vec (sort unsupported))))))


(defn- explorer-views-decoded
  "`list-explorer-views` over an already-built list env (the base graph
   it holds) — what `view-members*` reads for the `:views` axis."
  [{:keys [base]}]
  (let [fns (:fns base)
        base-id (some #(when (and (= explorer-view-base-name (:name %))
                                  (empty? (:parent-ids %)))
                         (:id %))
                      fns)]
    (if-not base-id
      []
      (let [children-of (reduce (fn [m f] (reduce #(update %1 %2 (fnil conj []) (:id f)) m (:parent-ids f)))
                                {} fns)
            by-id (into {} (map (juxt :id identity)) fns)
            view-ids (loop [queue (vec (get children-of base-id)) seen #{}]
                       (if-let [id (first queue)]
                         (if (seen id)
                           (recur (subvec queue 1) seen)
                           (recur (into (subvec queue 1) (get children-of id)) (conj seen id)))
                         seen))
            indices (lookups/build-lookups base)]
        (->> view-ids
             (keep by-id)
             (filter :name)
             (sort-by :name)
             (mapv (fn [f]
                     (merge (select-keys f [:id :name :namespace-id])
                            (decode-view indices (:id f))))))))))


(defn list-explorer-views
  "Every NAMED fn that extends the `:explorer-view` base-fn (any depth),
   with its filters decoded — `[{:id :name :namespace-id :filters} …]`,
   the Explorer's \"views saved in the graph\" list (`:filters` carries
   `:also`, the ids of the views it intersects with). Empty when the
   base-fn isn't loaded (no `app/views` module) or nothing extends it."
  [ctx]
  ;; Over the graph AS THE VIEWER SEES IT: a view the viewer may not see
  ;; the internals of (another org's shared one) lists with no filters —
  ;; its bound axes ARE its composition.
  (explorer-views-decoded {:base (entity-list/apply-view-impl-filter (types-api/cached-or-load-graph ctx))}))


(defn- has-clauses?
  [by-id view-id seen]
  (when-not (contains? seen view-id)
    (when-let [view (get by-id view-id)]
      (let [filters (normalise-filters (:filters view))]
        (or (:name filters) (:unused filters)
            (some seq (vals (dissoc filters :views :name :unused)))
            (some #(has-clauses? by-id % (conj seen view-id)) (:views filters)))))))


(defn- expand-view-filters
  "Flatten intersections before applying any result limit. A visited view is
   already a conjunct, so cycles and repeated references do not duplicate work."
  [by-id filters]
  (loop [pending [(normalise-filters filters)] seen #{} expanded [] missing #{} unsupported #{} empty-view? false]
    (if-let [current (first pending)]
      (let [ids (remove seen (:views current))
            found (keep by-id ids)]
        (recur (into (vec (rest pending)) (map (comp normalise-filters :filters)) found)
               (into seen ids) (conj expanded current)
               (into missing (remove by-id ids))
               (into unsupported (comp (filter :unsupported) (map :id)) found)
               (or empty-view? (boolean (some #(not (has-clauses? by-id (:id %) #{})) found)))))
      {:filters expanded :missing-views missing :unsupported-views unsupported :empty-view? empty-view?})))


(defn view-members
  "Evaluate every filter axis and referenced view over the current branch and
   viewer. Kinds/problems/namespaces OR within; all axes and uses/effects/views
   AND. Only the final projection is capped at 500, after intersections."
  [ctx filters]
  (let [storage (:storage ctx)
        env (entity-list/filter-env ctx)
        env (assoc env :problem-members (problem-members ctx (:base env)))
        by-id (into {} (map (juxt :id identity)) (explorer-views-decoded env))
        {:keys [filters missing-views unsupported-views empty-view?]} (expand-view-filters by-id filters)
        preds (into [] (mapcat #(view-filter-preds env storage %)) filters)
        rows @(:roled-fns env)
        known (into #{} (map :id) rows)
        missing-uses (into #{} (comp (mapcat :uses) (remove known)) filters)
        matches (if (or empty-view? (empty? preds) (seq missing-views) (seq unsupported-views))
                  []
                  (filterv (fn [row]
                             (and (:name row) (not (str/starts-with? (:name row) "_anon-"))
                                  (every? #(% row) preds))) rows))
        missing (cond-> {}
                  (seq missing-uses) (assoc :uses (vec (sort missing-uses)))
                  (seq missing-views) (assoc :views (vec (sort missing-views))))]
    (cond-> {:fns (mapv (partial entity-list/light-fn-row @(:rev-index env))
                        (take view-result-cap matches))
             :total (count matches) :truncated? (> (count matches) view-result-cap)}
      (seq missing) (assoc :missing missing)
      (seq unsupported-views) (assoc :unsupported-views (vec (sort unsupported-views))))))
