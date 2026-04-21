(ns graphden.executor.composition.core
  "Data-driven fn definitions and storage sync.

   ## 2-Entity Schema

   Composed functions are stored as:
   - fn entity with parent-ids set (inherits from one or more parent fns)
   - arg entities with source-id set (inherits from parent's arg) + value/ref-id

   ## Format

   Fn definitions are a vector of maps (order matters for validation):

   ```clojure
   [{:name :router-handler
     :parent :default-router-handler}

    {:name :web-server
     :parent :http-server
     :args {:handler :router-handler   ; ref to fn
            :port 8080}}]              ; literal value
   ```

   ## Arg Value Syntax

   - `:fn-name` - reference to fn (creates ref-id)

   Behavior (execute vs pass fn-id) is determined by is-fn field on parent arg,
   which is inherited via source-id. The executor checks is-fn to decide.

   ## Name Resolution

   Names in :parent and :args are resolved in this order:
   1. fn from this definition set (by name)
   2. fn already in storage (by name)"
  (:require
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.composition.parsing :as parsing]
    [graphden.executor.composition.validation :as validation]
    [graphden.executor.registry.interface :as registry]
    [graphden.storage.protocol.core :as sp]))


;; === Free Argument Propagation ===

(defn- free-arg?
  "Returns true if the arg is 'free' (has no value and no ref-id)."
  [arg]
  (and (nil? (:value arg))
       (nil? (:ref-id arg))))


(defn- partition-args-by-freedom
  "Partitions args into {:free-args [...] :bound-args [...]} in single pass.
   Free args have no value and no ref-id; bound args have either."
  [args]
  (reduce (fn [acc arg]
            (if (free-arg? arg)
              (update acc :free-args conj arg)
              (update acc :bound-args conj arg)))
          {:free-args [] :bound-args []}
          args))


(defn- resolve-arg-name-cached
  "Resolves arg name by following source-id chain using args-by-id index.
   If arg has :name, returns it. Otherwise follows source-id to find name.
   Returns string name or nil if not resolvable.

   Uses O(1) lookup via args-by-id index instead of O(F×A) scan."
  [args-by-id arg depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Source-id chain too deep while resolving arg name"
                    {:type :fn-composition/source-chain-too-deep
                     :arg-id (:id arg)
                     :max-depth sp/*max-graph-iterations*})))
  (if-let [arg-name (:name arg)]
    arg-name
    (when-let [source-id (:source-id arg)]
      ;; O(1) lookup by arg-id
      (when-let [source-arg (get args-by-id source-id)]
        (recur args-by-id source-arg (inc depth))))))


(defn- collect-source-id-chain
  "Follows source-id chain from an arg-id and collects all arg-ids in the chain.
   Returns a set of arg-ids including the starting arg-id.
   Used to determine which args should be shadowed when a child explicitly binds an arg."
  [args-by-id arg-id]
  (loop [current-id arg-id
         ids #{}
         depth 0]
    (if (or (nil? current-id) (> depth 100))
      ids
      (let [arg (get args-by-id current-id)]
        (recur (:source-id arg)
               (conj ids current-id)
               (inc depth))))))


(defn- terminal-source-id
  "Walks the source-id chain from arg to find the root arg id (one with no source-id).
   Returns the id of the root arg, or arg's own id if it has no source-id chain.
   Used to identify free args that are propagated copies of the same root arg
   across multiple inheritance branches."
  [args-by-id arg]
  (loop [current arg
         depth 0]
    (when (> depth sp/*max-graph-iterations*)
      (throw (ex-info "Source-id chain too deep while finding terminal source"
                      {:type :fn-composition/source-chain-too-deep
                       :arg-id (:id arg)
                       :max-depth sp/*max-graph-iterations*})))
    (if-let [src-id (:source-id current)]
      (if-let [src-arg (get args-by-id src-id)]
        (recur src-arg (inc depth))
        (:id current))
      (:id current))))


(defn- collect-free-args-from-fn
  "Collects all free args from a fn by following ref-id chains.
   Returns a vector of free arg entities.

   Free args come from:
   1. Direct free args on this fn
   2. Free args from fns referenced in arg values (via ref-id)

   args-data contains :by-fn and :by-id indexes."
  [fn-cache args-data fn-id visited-fns depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Free arg collection chain too deep"
                    {:type :fn-composition/chain-too-deep
                     :fn-id fn-id
                     :max-depth sp/*max-graph-iterations*})))
  (if (contains? visited-fns fn-id)
    ;; Cycle detected, return empty to avoid infinite loop
    []
    (let [visited' (conj visited-fns fn-id)
          fn-args (get (:by-fn args-data) fn-id [])
          {:keys [free-args bound-args]} (partition-args-by-freedom fn-args)]
      ;; Free args are:
      ;; 1. Own free args
      ;; 2. Free args from fns referenced via ref-id (recursively)
      (into free-args
            (mapcat (fn [arg]
                      (when-let [ref-fn-id (:ref-id arg)]
                        (collect-free-args-from-fn fn-cache args-data ref-fn-id visited' (inc depth)))))
            bound-args))))


(defn- collect-parent-free-args-for-one
  "Collects free args from a single parent fn (internal helper)."
  [fn-cache args-data parent-fn-id depth]
  (when (> depth sp/*max-graph-iterations*)
    (throw (ex-info "Parent chain too deep while collecting free args"
                    {:type :fn-composition/parent-chain-too-deep
                     :parent-fn-id parent-fn-id
                     :max-depth sp/*max-graph-iterations*})))
  (let [fn-args (get (:by-fn args-data) parent-fn-id [])
        {:keys [free-args bound-args]} (partition-args-by-freedom fn-args)
        ;; Collect free args from referenced fns (only bound args have ref-id)
        ref-free-args (mapcat (fn [arg]
                                (when-let [ref-fn-id (:ref-id arg)]
                                  (collect-free-args-from-fn fn-cache args-data ref-fn-id #{} (inc depth))))
                              bound-args)]
    (into free-args ref-free-args)))


(defn- collect-parent-free-args
  "Collects free args from all parent fns.
   Returns vector of arg entities that are free in the parents.

   Accepts a collection of parent fn-ids (multiple inheritance).
   args-data contains :by-fn and :by-id indexes.

   Dedupes by [terminal-source-id, resolved-name]. With diamond inheritance
   (two parents sharing a common ancestor), the same root free arg is
   reachable via both parents as different propagated arg entities — they
   must collapse to one. But the cascade pattern (pair-1, pair, triple, ...)
   intentionally creates multiple propagated copies of the same root arg
   under different rename targets (item1, item2, item3, ...), so the
   resolved name is part of the dedup key to keep them distinct.

   Dedupes by [terminal-source-id, resolved-name]. With diamond inheritance
   (two parents sharing a common ancestor), the same root free arg is
   reachable via both parents as different propagated arg entities — they
   must collapse to one. But the cascade pattern (pair-1, pair, triple, ...)
   intentionally creates multiple propagated copies of the same root arg
   under different rename targets (item1, item2, item3, ...), so the
   resolved name is part of the dedup key to keep them distinct."
  [fn-cache args-data parent-fn-ids depth]
  (let [args-by-id (:by-id args-data)
        seen (atom #{})]
    (into []
          (comp
            (mapcat (fn [pid]
                      (collect-parent-free-args-for-one fn-cache args-data pid depth)))
            (remove (fn [a]
                      (let [root (terminal-source-id args-by-id a)
                            resolved-name (resolve-arg-name-cached args-by-id a 0)
                            k [root resolved-name]]
                        (if (contains? @seen k)
                          true
                          (do (swap! seen conj k) false))))))
          (remove nil? parent-fn-ids))))


(defn- preload-all-args
  "Loads arg entities for given fn-ids using WHERE IN clause.
   Returns map with three indexes:
   - :by-fn {fn-id -> [args]} for fn-based lookup
   - :by-id {arg-id -> arg} for O(1) arg lookup by id
   - :by-fn-source {[fn-id source-id] -> arg} for O(1) lookup by fn+source"
  [storage fn-ids]
  (if (empty? fn-ids)
    {:by-fn {} :by-id {} :by-fn-source {}}
    ;; Use WHERE IN clause instead of full table scan + filter
    (let [matching-args (sp/query-entities storage :arg {:fn-id (vec fn-ids)})]
      {:by-fn (group-by :fn-id matching-args)
       :by-id (into {} (map (fn [a] [(:id a) a])) matching-args)
       :by-fn-source (into {} (keep (fn [a]
                                      (when-let [sid (:source-id a)]
                                        [[(:fn-id a) sid] a])))
                           matching-args)})))


(defn- get-parent-arg-cached
  "Gets the parent's arg entity for an arg name using cache.
   Follows inheritance graph (via parent-ids) to find the arg.
   Resolves arg names via source-id chain if arg.name is nil.
   Returns the arg entity or throws if not found.

   Accepts a collection of parent fn-ids. With multiple inheritance, walks all
   parents in BFS order (first parent has precedence for arg name conflicts).

   args-data contains :by-fn and :by-id indexes."
  [fn-cache args-data parent-fn-ids arg-name]
  (let [args-by-id (:by-id args-data)
        arg-name-str (name arg-name)
        start (vec (remove nil? parent-fn-ids))]
    (loop [queue start
           visited #{}
           iter 0]
      (when (> iter sp/*max-graph-iterations*)
        (throw (ex-info "Parent chain too deep while resolving arg"
                        {:type :fn-composition/parent-chain-too-deep
                         :arg-name arg-name
                         :max-depth sp/*max-graph-iterations*})))
      (if (empty? queue)
        (throw (ex-info (str "Argument not found in parent chain: " arg-name)
                        {:type :fn-composition/unresolved-arg
                         :parent-fn-ids start
                         :arg-name arg-name}))
        (let [fn-id (first queue)
              rest-q (rest queue)]
          (if (contains? visited fn-id)
            (recur rest-q visited (inc iter))
            (let [fn-args (get (:by-fn args-data) fn-id [])
                  ;; Match by resolved name using O(1) by-id lookup
                  found (some #(when (= (resolve-arg-name-cached args-by-id % 0) arg-name-str) %)
                              fn-args)]
              (or found
                  ;; Not found on this fn, enqueue all parent fns
                  (let [fn-entity (get fn-cache fn-id)
                        next-parent-ids (:parent-ids fn-entity)]
                    (recur (into (vec rest-q) next-parent-ids)
                           (conj visited fn-id)
                           (inc iter)))))))))))


(defn- find-available-arg
  "Finds an arg by name from all available args (parent chain + propagated free args).

   This is used for pass-through args: when child fn-def sets an arg that comes
   from a nested fn (via ref-id chain), not directly from parent chain.

   Search order:
   1. Parents' own args (via parent-ids chain, BFS over multiple inheritance)
   2. Propagated free args from refs (via ref-id chains)

   Accepts a collection of parent fn-ids (multiple inheritance).
   Resolves arg names via source-id chain if arg.name is nil.
   args-data contains :by-fn and :by-id indexes.
   Returns the arg entity or throws if not found."
  [fn-cache args-data parent-fn-ids arg-name]
  (let [arg-name-str (name arg-name)
        args-by-id (:by-id args-data)
        ;; First try direct parent chain lookup
        direct-result (try
                        (get-parent-arg-cached fn-cache args-data parent-fn-ids arg-name)
                        (catch clojure.lang.ExceptionInfo e
                          (when-not (= :fn-composition/unresolved-arg (:type (ex-data e)))
                            (throw e))
                          nil))]
    (or direct-result
        ;; Not in parent chain - search in propagated free args from refs
        ;; Single pass: find free-arg match first, else first any-match
        ;; Use resolved names for matching with O(1) by-id lookup
        (let [parent-free-args (collect-parent-free-args fn-cache args-data parent-fn-ids 0)
              found (reduce (fn [first-match arg]
                              (let [resolved-name (resolve-arg-name-cached args-by-id arg 0)]
                                (if (= resolved-name arg-name-str)
                                  (if (free-arg? arg)
                                    (reduced arg)           ; Free arg - best match, stop
                                    (or first-match arg))   ; Keep first non-free as fallback
                                  first-match)))
                            nil
                            parent-free-args)]
          (or found
              (throw (ex-info (str "Argument not found in available args: " arg-name
                                   ". Checked parent chain and propagated free args.")
                              {:type :fn-composition/unresolved-arg
                               :parent-fn-ids parent-fn-ids
                               :arg-name arg-name
                               :available-args (mapv #(resolve-arg-name-cached args-by-id % 0)
                                                     parent-free-args)})))))))


(defn- resolve-parent-fn-id-cached
  "Resolves a parent name to fn-id using caches.
   Returns UUID or throws if not found."
  [fn-name-cache fn-id-cache created-fns parent-name]
  (or (get created-fns parent-name)
      ;; Try registry (base-fns)
      (let [base-fn-id (registry/fn-uuid parent-name)]
        (when (contains? fn-id-cache base-fn-id)
          base-fn-id))
      ;; Try by name (composed fns)
      (when-let [existing (get fn-name-cache (name parent-name))]
        (:id existing))
      ;; Not found - throw
      (throw (ex-info (str "Parent fn not found: " parent-name
                           ". It must be a base-fn or defined earlier.")
                      {:type :fn-composition/unresolved-parent
                       :parent-name parent-name
                       :available-fns (keys created-fns)}))))


(defn- resolve-fn-id-cached
  "Resolves a fn name to fn-id using caches."
  [fn-name-cache created-fns fn-name]
  (or
    (get created-fns fn-name)
    (when-let [existing (get fn-name-cache (name fn-name))]
      (:id existing))
    (throw (ex-info (str "Referenced fn not found: " fn-name
                         ". It must be defined earlier or exist in storage.")
                    {:type :fn-composition/unresolved-fn-ref
                     :fn-name fn-name
                     :available-fns (keys created-fns)}))))


(defn- fn-def-parent-names
  "Returns a vector of parent names from a fn-def.
   Supports both :parent (single keyword) and :parents (vector of keywords)."
  [fn-def]
  (let [parent-list (:parents fn-def)
        parent (:parent fn-def)]
    (cond
      (seq parent-list) (vec parent-list)
      parent [parent]
      :else [])))


(defn- prepare-fn-record
  "Prepares a fn record for batch upsert.
   Returns {:id :name :parent-ids} or nil if already exists.

   Local fns (names starting with _) are stored with name=nil in DB.
   They can only be referenced within the same package by their local name."
  [fn-name-cache fn-id-cache created-fns fn-def ns-id-map]
  (let [fn-name (:name fn-def)
        fn-name-str (clojure.core/name fn-name)
        is-local? (parsing/local-fn-name? fn-name)]
    ;; Local fns are never "existing" - they're always created fresh
    ;; (since they have name=nil in DB and can't be looked up by name)
    (if (and (not is-local?)
             (get fn-name-cache fn-name-str))
      ;; Already exists (only for non-local fns)
      {:existing (get fn-name-cache fn-name-str)}
      ;; Need to create
      (let [parent-names (fn-def-parent-names fn-def)
            parent-ids (mapv #(resolve-parent-fn-id-cached
                                fn-name-cache fn-id-cache created-fns %)
                             parent-names)
            ;; Local fns get name=nil in DB
            db-name (when-not is-local? fn-name-str)
            ns-id (when-let [ns-path (:namespace fn-def)]
                    (get ns-id-map ns-path))]
        {:new (cond-> {:id (random-uuid)
                       :name db-name
                       :parent-ids (when (seq parent-ids) parent-ids)}
                ns-id (assoc :namespace-id ns-id))}))))


(defn- prepare-propagated-arg-record
  "Prepares an arg record for a propagated free arg.
   Used for free args that 'bubble up' from parent or referenced fns.
   Creates a new arg with source-id pointing to the original free arg.
   Name is nil - inherited via source-id chain.

   args-data contains :by-fn, :by-id, and :by-fn-source indexes."
  [args-data fn-id parent-arg]
  (let [source-id (:id parent-arg)
        ;; O(1) lookup via :by-fn-source index
        existing (get (:by-fn-source args-data) [fn-id source-id])]
    (when-not existing
      ;; Create new propagated arg (free, with no value or ref-id)
      ;; name is nil - will be resolved via source-id chain
      {:new {:id (random-uuid)
             :fn-id fn-id
             :source-id source-id
             ;; name is nil - inherited via source-id chain
             :type (:type parent-arg)
             :is-fn (:is-fn parent-arg)
             :value nil
             :ref-id nil}})))


(defn- parse-arg-value-spec
  "Parses arg value specification.
   Supports:
   - Simple values: 123, \"str\", :keyword (= fn-ref)
   - Map with :as: {:as :new-name} or {:as :new-name :value 123} or {:as :new-name :ref :fn-name}
   - Map with :value (no :as): {:value :keyword} — passes keyword as literal value (not fn-ref)
   - Map with :ref (no :as): {:ref :fn-name} — fn reference without rename
   - Map with :type :fn to mark as HOF argument

   :literal? is true when the value came from an explicit :value slot; the caller must
   skip fn-ref resolution and store it as a literal.

   Returns {:rename nil-or-keyword :value-spec original-or-extracted
            :is-fn bool-or-nil :literal? bool}"
  [arg-value]
  (cond
    ;; Map with :as — rename + optional value/ref
    (and (map? arg-value) (contains? arg-value :as))
    (let [rename (:as arg-value)
          has-value? (contains? arg-value :value)
          has-ref? (contains? arg-value :ref)
          is-fn? (= :fn (:type arg-value))]
      (when-not (keyword? rename)
        (throw (ex-info ":as must be a keyword"
                        {:type :fn-composition/invalid-arg-spec
                         :arg-value arg-value})))
      (cond
        has-value? {:rename rename :value-spec (:value arg-value) :is-fn is-fn? :literal? true}
        has-ref? {:rename rename :value-spec (:ref arg-value) :is-fn is-fn? :literal? false}
        :else {:rename rename :value-spec nil :is-fn is-fn? :literal? false}))

    ;; Map with :value (no :as) — literal value (enables keyword literals)
    (and (map? arg-value) (contains? arg-value :value) (not (contains? arg-value :as)))
    {:rename nil :value-spec (:value arg-value) :is-fn nil :literal? true}

    ;; Map with :ref (no :as) — fn reference without rename
    (and (map? arg-value) (contains? arg-value :ref) (not (contains? arg-value :as)))
    {:rename nil :value-spec (:ref arg-value) :is-fn (= :fn (:type arg-value)) :literal? false}

    ;; Simple value — no rename
    :else
    {:rename nil :value-spec arg-value :is-fn nil :literal? false}))


(declare prepare-scalar-arg-record)


(defn- resolve-sequence-item
  "Resolves one element of a sequence arg's value vector into {:value … :ref-id …}.
   Keywords that name a known fn become refs; other keywords become literal values.
   Maps with :ref or :value behave as one-shot overrides."
  [fn-name-cache created-fns item]
  (cond
    (uuid? item)
    {:value nil :ref-id item}

    (keyword? item)
    (let [nm (name item)]
      (if (parsing/valid-identifier? nm)
        (if-let [entry (or (get created-fns item)
                           (when-let [existing (get fn-name-cache nm)]
                             (:id existing)))]
          {:value nil :ref-id entry}
          {:value item :ref-id nil})
        {:value item :ref-id nil}))

    (and (map? item) (contains? item :ref))
    {:value nil :ref-id (resolve-fn-id-cached fn-name-cache created-fns (:ref item))}

    (and (map? item) (contains? item :value))
    {:value (:value item) :ref-id nil}

    :else
    {:value item :ref-id nil}))


(defn- walk-anchor-chain-ids
  "Walks an anchor arg's next-arg-id chain via args-by-id, returning
   the ordered vector of item arg-ids. Used to reap orphaned items on re-sync."
  [args-by-id anchor]
  (loop [cur (:next-arg-id anchor)
         acc []
         depth 0]
    (cond
      (nil? cur) acc
      (> depth 10000)
      (throw (ex-info "Sequence chain exceeded maximum length while walking"
                      {:type :fn-composition/sequence-chain-too-long
                       :anchor-id (:id anchor)}))
      :else
      (let [nxt (get args-by-id cur)]
        (recur (:next-arg-id nxt) (conj acc cur) (inc depth))))))


(defn- prepare-sequence-arg-chain
  "Builds anchor + item arg records forming a next-arg-id linked list.

   Returns {:new-chain [anchor item1 … itemN]
            :delete-items [existing-item-ids]
            :source-id <template-arg-id>}

   Anchor.source-id points at the base-fn's sequence template arg; its
   next-arg-id points at the first item (or nil for an empty sequence).
   Items have source-id=nil, name=nil, and their own next-arg-id chain."
  [fn-name-cache created-fns args-data fn-id parent-arg items]
  (let [template-id (:id parent-arg)
        existing-anchor (get (:by-fn-source args-data) [fn-id template-id])
        anchor-id (or (:id existing-anchor) (random-uuid))
        element-type (or (:of parent-arg) :any)
        item-records (mapv (fn [item]
                             (let [{:keys [value ref-id]} (resolve-sequence-item
                                                            fn-name-cache created-fns item)]
                               {:id (random-uuid)
                                :fn-id fn-id
                                :source-id nil
                                :name nil
                                :type element-type
                                :value value
                                :ref-id ref-id
                                :is-fn nil
                                :next-arg-id nil
                                :prev-arg-id nil}))
                           items)
        ;; Wire up the doubly-linked list: item[i].next → item[i+1].id and
        ;; item[i+1].prev → item[i].id. Head.prev points back at the anchor,
        ;; tail.next is nil.
        linked (vec (map-indexed
                      (fn [idx rec]
                        (let [next-id (when (< idx (dec (count item-records)))
                                        (:id (nth item-records (inc idx))))
                              prev-id (if (zero? idx)
                                        anchor-id
                                        (:id (nth item-records (dec idx))))]
                          (assoc rec :next-arg-id next-id :prev-arg-id prev-id)))
                      item-records))
        anchor {:id anchor-id
                :fn-id fn-id
                :source-id template-id
                :name nil
                :type :sequence
                :value nil
                :ref-id nil
                :is-fn nil
                :next-arg-id (when (seq linked) (:id (first linked)))
                :prev-arg-id nil}
        delete-items (if existing-anchor
                       (walk-anchor-chain-ids (:by-id args-data) existing-anchor)
                       [])]
    {:new-chain (into [anchor] linked)
     :delete-items delete-items
     :source-id template-id}))


(defn- prepare-arg-record
  "Prepares an arg record for batch upsert.
   Uses find-available-arg to support pass-through args from nested refs.

   Supports arg value as:
   - Simple value: literal or :fn-ref
   - Map with :as: {:as :new-name} to rename, optionally with :value or :ref
   - Vector (when parent arg type is :sequence): expands to anchor + linked
     items. Returns {:new-chain [...] :delete-items [...] :source-id …}.

   args-data contains :by-fn, :by-id, and :by-fn-source indexes."
  [fn-cache args-data fn-name-cache created-fns fn-id parent-fn-ids arg-name arg-value]
  (when-not fn-id
    (throw (ex-info "fn-id cannot be nil when preparing arg record"
                    {:type :fn-composition/internal-error
                     :arg-name arg-name
                     :parent-fn-ids parent-fn-ids})))
  ;; Use find-available-arg which searches both parent chain AND propagated free args
  (let [parent-arg (find-available-arg fn-cache args-data parent-fn-ids arg-name)]
    (if (= :sequence (:type parent-arg))
      (do
        (when-not (vector? arg-value)
          (throw (ex-info (str "Sequence arg '" arg-name "' requires a vector value, got "
                               (type arg-value))
                          {:type :fn-composition/invalid-sequence-value
                           :arg-name arg-name
                           :arg-value arg-value})))
        (prepare-sequence-arg-chain fn-name-cache created-fns args-data fn-id parent-arg arg-value))
      (prepare-scalar-arg-record args-data fn-name-cache created-fns fn-id parent-arg arg-name arg-value))))


(defn- prepare-scalar-arg-record
  "Prepares a scalar (non-sequence) arg record. Returns {:new …} / {:update …} / nil."
  [args-data fn-name-cache created-fns fn-id parent-arg arg-name arg-value]
  (let [;; Parse arg value spec (supports {:as :new-name ...})
        {:keys [rename value-spec is-fn literal?]} (parse-arg-value-spec arg-value)
        ;; Validate: cannot override already-bound argument
        parent-has-value (or (some? (:value parent-arg))
                             (some? (:ref-id parent-arg)))
        child-sets-value (or (some? value-spec)
                             (and (map? arg-value)
                                  (or (contains? arg-value :value)
                                      (contains? arg-value :ref))))
        _ (when (and parent-has-value child-sets-value)
            (let [args-by-id (:by-id args-data)
                  parent-arg-name (resolve-arg-name-cached args-by-id parent-arg 0)]
              (throw (ex-info (str "Cannot override already-bound argument: " parent-arg-name
                                   ". Parent already sets value=" (:value parent-arg)
                                   " ref-id=" (:ref-id parent-arg))
                              {:type :fn-composition/arg-override-forbidden
                               :arg-name arg-name
                               :parent-value (:value parent-arg)
                               :parent-ref-id (:ref-id parent-arg)
                               :child-value-spec value-spec}))))
        source-id (:id parent-arg)
        ;; O(1) lookup via :by-fn-source index
        existing (get (:by-fn-source args-data) [fn-id source-id])
        ;; Resolve arg value
        resolved (cond
                   (nil? value-spec)
                   {:value nil :ref-id nil}

                   (uuid? value-spec)
                   {:value nil :ref-id value-spec}

                   ;; Explicit :value slot — always a literal, skip fn-ref resolution
                   literal?
                   {:value value-spec :ref-id nil}

                   (keyword? value-spec)
                   (if-let [ref-fn-name (parsing/parse-fn-ref value-spec)]
                     (let [ref-fn-id (resolve-fn-id-cached fn-name-cache created-fns ref-fn-name)]
                       {:value nil :ref-id ref-fn-id})
                     {:value value-spec :ref-id nil})

                   :else
                   {:value value-spec :ref-id nil})
        ;; Add name override if specified
        resolved-with-name (if rename
                             (assoc resolved :name (name rename))
                             resolved)
        ;; Determine is-fn: explicit :type :fn in arg-value overrides parent
        effective-is-fn (if is-fn true (:is-fn parent-arg))]
    (if existing
      ;; Check if update needed (including name change, is-fn change)
      (when (or (not= (:value existing) (:value resolved-with-name))
                (not= (:ref-id existing) (:ref-id resolved-with-name))
                (and rename (not= (:name existing) (name rename)))
                (and is-fn (not (:is-fn existing))))
        ;; Merge full existing record with resolved to preserve all required fields
        {:update (merge existing resolved-with-name {:is-fn effective-is-fn})})
      ;; Create new - name is nil unless explicitly renamed via :as
      (let [new-arg (merge {:id (random-uuid)
                            :fn-id fn-id
                            :source-id source-id
                            ;; name is nil by default - will be resolved via source-id chain
                            ;; unless explicitly set via :as
                            :type (:type parent-arg)
                            :is-fn effective-is-fn}
                           resolved-with-name)]
        {:new new-arg}))))


(defn sync-fns-to-storage!
  "Syncs fn definitions to storage using batch operations.

   Arguments:
   - storage: initialized storage with base-fn schemas already synced
   - fn-defs: vector of fn definition maps

   Process:
   1. Validates all definitions
   2. Topologically sorts by dependencies
   3. Pre-loads existing fns and args (2 queries instead of N*M)
   4. Batch upserts all fn entities
   5. Batch upserts all arg entities

   Returns map of {fn-name -> fn-id} for created fns.

   Throws on:
   - Invalid definitions
   - Unresolved references (parent or arg)
   - Circular dependencies"
  ([storage fn-defs] (sync-fns-to-storage! storage fn-defs {}))
  ([storage fn-defs ns-id-map]
   (if (empty? fn-defs)
     {}
     (do
       (validation/validate-all-defs! fn-defs)
       (let [sorted-defs (deps/topological-sort fn-defs)
             _ (deps/check-order-and-warn fn-defs sorted-defs)

             ;; Phase 1: Pre-load existing data (single pass over all-fns)
             all-fns (sp/query-entities storage :fn {})

             ;; Invert ns-id-map for qualified name resolution: {ns-uuid → ns-path}
             ns-path-by-id (into {} (map (fn [[path id]] [id path]) ns-id-map))

             ;; Build caches in a single pass.
             ;; fn-name-cache: simple name → entity (backward compat)
             ;; qualified-fn-cache: "ns.path.name" → entity (for dot-refs)
             {:keys [fn-id-cache fn-name-cache all-fn-ids]}
             (reduce (fn [acc fn-entity]
                       (let [acc (-> acc
                                     (assoc-in [:fn-id-cache (:id fn-entity)] fn-entity)
                                     (assoc-in [:fn-name-cache (:name fn-entity)] fn-entity)
                                     (update :all-fn-ids conj (:id fn-entity)))]
                         ;; Also add qualified entry if fn has namespace
                         (if-let [ns-path (get ns-path-by-id (:namespace-id fn-entity))]
                           (let [qualified (str ns-path "." (:name fn-entity))]
                             (assoc-in acc [:fn-name-cache qualified] fn-entity))
                           acc)))
                     {:fn-id-cache {}
                      :fn-name-cache {}
                      :all-fn-ids []}
                     all-fns)

             ;; Pre-load all args using WHERE IN
             args-cache (preload-all-args storage all-fn-ids)

             ;; Phase 2: Prepare and create fns in topological order
             ;; We need to do this sequentially due to dependencies
             ;; created-fns: {fn-name -> fn-id}
             ;; created-fn-entities: {fn-id -> fn-entity} - needed for parent chain lookup
             {:keys [created-fns created-fn-entities]}
             (loop [remaining sorted-defs
                    created {}
                    created-entities {}
                    new-fns []
                    fn-name-cache' fn-name-cache]
               (if (empty? remaining)
                 ;; Batch upsert new fns
                 (do
                   (when (seq new-fns)
                     (sp/upsert-entities storage :fn new-fns))
                   {:created-fns created
                    :created-fn-entities created-entities})
                 (let [fn-def (first remaining)
                       result (prepare-fn-record fn-name-cache' fn-id-cache created fn-def ns-id-map)
                       fn-entity (or (:existing result) (:new result))
                       fn-id (:id fn-entity)
                       new-created (assoc created (:name fn-def) fn-id)
                       ;; Track full entity for parent-ids lookup
                       new-created-entities (assoc created-entities fn-id fn-entity)
                       new-fns' (if (:new result)
                                  (conj new-fns (:new result))
                                  new-fns)
                       ;; Update cache with new fn
                       fn-name-cache'' (if (:new result)
                                         (assoc fn-name-cache' (name (:name fn-def)) fn-entity)
                                         fn-name-cache')]
                   (recur (rest remaining) new-created new-created-entities new-fns' fn-name-cache''))))

             ;; Update caches with newly created fns (simple + qualified names)
             fn-name-cache-final
             (reduce (fn [cache [kw-name fn-id]]
                       (let [n (name kw-name)
                             entry {:id fn-id :name n}
                             ;; Find namespace from the fn-def
                             fn-def (first (filter #(= (:name %) kw-name) sorted-defs))
                             ns-path (:namespace fn-def)]
                         (cond-> (assoc cache n entry)
                           ns-path (assoc (str ns-path "." n) entry))))
                     fn-name-cache
                     created-fns)
             ;; Include full fn-entities with parent-ids for parent chain lookup
             fn-id-cache-final (merge fn-id-cache created-fn-entities)

             ;; Phase 3: Process each fn-def's args sequentially, updating cache as we go
             ;; This allows later fn-defs to see propagated args from earlier ones
             ;;
             ;; We still collect all records for batch upsert at the end for efficiency.
             ;; args-data contains {:by-fn {fn-id -> [args]}, :by-id {arg-id -> arg}}
             {:keys [all-new-args all-update-args all-delete-items]}
             (loop [remaining sorted-defs
                    args-data args-cache  ; mutable view of args, updated after each fn-def
                    new-args []
                    update-args []
                    delete-items []]
               (if (empty? remaining)
                 {:all-new-args new-args
                  :all-update-args update-args
                  :all-delete-items delete-items}
                 (let [fn-def (first remaining)
                       fn-name (:name fn-def)
                       fn-id (get created-fns fn-name)
                       parent-names (fn-def-parent-names fn-def)
                       parent-fn-ids (mapv #(resolve-parent-fn-id-cached
                                              fn-name-cache-final fn-id-cache-final created-fns %)
                                           parent-names)

                       ;; 3a: Explicit args from fn-def :args
                       ;; Single pass: collect source-id CHAINS, new args, update args,
                       ;; and sequence-chain deletions (items orphaned by re-sync).
                       {:keys [explicit-source-ids explicit-new-args explicit-update-args
                               explicit-arg-names explicit-delete-items]}
                       (reduce (fn [acc [arg-name arg-value]]
                                 (if-let [record (prepare-arg-record
                                                   fn-id-cache-final args-data fn-name-cache-final
                                                   created-fns fn-id parent-fn-ids arg-name arg-value)]
                                   (let [source-id (or (:source-id (:new record))
                                                       (:source-id (:update record))
                                                       (:source-id record))
                                         ;; Collect FULL source-id chain to shadow transitive args
                                         source-chain (when source-id
                                                        (collect-source-id-chain (:by-id args-data) source-id))]
                                     (cond-> acc
                                       ;; Add explicit arg name (as string) to filter free args
                                       true
                                       (update :explicit-arg-names conj (name arg-name))
                                       source-chain
                                       (update :explicit-source-ids into source-chain)
                                       (:new record)
                                       (update :explicit-new-args conj (:new record))
                                       (:update record)
                                       (update :explicit-update-args conj (:update record))
                                       (:new-chain record)
                                       (update :explicit-new-args into (:new-chain record))
                                       (seq (:delete-items record))
                                       (update :explicit-delete-items into (:delete-items record))))
                                   acc))
                               {:explicit-source-ids #{}
                                :explicit-new-args []
                                :explicit-update-args []
                                :explicit-arg-names #{}
                                :explicit-delete-items []}
                               (:args fn-def {}))

                       ;; 3b: Propagated free args from parent fns
                       ;; NOTE: We don't recursively collect from refs in explicit args (removed step 3c).
                       ;; The executor handles transitive arg propagation at runtime via trace-source-to-fn.
                       ;; This prevents internal free args (like html-response.status) from leaking
                       ;; through bound refs (like editor-route.handler).
                       parent-free-args (collect-parent-free-args
                                          fn-id-cache-final args-data parent-fn-ids 0)

                       ;; Use parent-free-args directly (no combination with explicit-ref-free-args)
                       all-free-args parent-free-args

                       ;; Helper to get root name of a free arg
                       args-by-id (:by-id args-data)
                       get-root-name (fn [arg] (resolve-arg-name-cached args-by-id arg 0))

                       propagated-new-args
                       (into []
                             (comp
                               ;; Filter 1: Remove args whose id is in explicit source chain
                               (remove #(contains? explicit-source-ids (:id %)))
                               ;; Filter 2: Remove args whose ROOT NAME matches an explicit arg name
                               ;; This handles cases like editor-routes setting item1-10 should
                               ;; prevent free args from route refs (which also resolve to "item")
                               ;; from being propagated
                               (remove (fn [arg]
                                         (when-let [root-name (get-root-name arg)]
                                           (contains? explicit-arg-names root-name))))
                               (keep #(when-let [rec (prepare-propagated-arg-record args-data fn-id %)]
                                        (:new rec))))
                             all-free-args)

                       ;; Combine explicit and propagated new args
                       fn-new-args (into explicit-new-args propagated-new-args)
                       fn-update-args explicit-update-args

                       ;; Update args-data with new args so next fn-def can see them
                       ;; Update all three indexes: :by-fn, :by-id, :by-fn-source
                       args-data' (reduce (fn [data arg]
                                            (cond-> data
                                              true
                                              (update-in [:by-fn (:fn-id arg)]
                                                         (fnil conj []) arg)
                                              true
                                              (assoc-in [:by-id (:id arg)] arg)
                                              ;; Add to by-fn-source index if has source-id
                                              (:source-id arg)
                                              (assoc-in [:by-fn-source [(:fn-id arg) (:source-id arg)]] arg)))
                                          args-data
                                          fn-new-args)]
                   (recur (rest remaining)
                          args-data'
                          (into new-args fn-new-args)
                          (into update-args fn-update-args)
                          (into delete-items explicit-delete-items)))))]

         ;; Reap orphaned sequence items from prior syncs before writing new chain.
         (when (seq all-delete-items)
           (sp/delete-entities storage :arg all-delete-items))

         ;; Batch upsert new args
         (when (seq all-new-args)
           (sp/upsert-entities storage :arg all-new-args))

         ;; Batch update existing args (if any changed)
         (when (seq all-update-args)
           (sp/upsert-entities storage :arg all-update-args))

         created-fns)))))
