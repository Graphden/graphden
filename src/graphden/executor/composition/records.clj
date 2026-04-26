(ns graphden.executor.composition.records
  "Record-preparation helpers: given a fn-def and caches, emit the
   concrete `:fn` / `:arg` entity records that `sync-fns-to-storage!`
   batches into the underlying storage. Also houses the parent-chain
   name lookups (`get-parent-arg-cached`, `find-available-arg`) and
   fn-name → fn-id resolution that the prep helpers need."
  (:require
    [graphden.executor.composition.parsing :as parsing]
    [graphden.executor.composition.source-chain :as sc]
    [graphden.executor.registry.interface :as registry]
    [graphden.storage.protocol.core :as sp]))


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
                  found (some #(when (= (sc/resolve-arg-name-cached args-by-id % 0) arg-name-str) %)
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
        (let [parent-free-args (sc/collect-parent-free-args fn-cache args-data parent-fn-ids 0)
              found (reduce (fn [first-match arg]
                              (let [resolved-name (sc/resolve-arg-name-cached args-by-id arg 0)]
                                (if (= resolved-name arg-name-str)
                                  (if (sc/free-arg? arg)
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
                               :available-args (mapv #(sc/resolve-arg-name-cached args-by-id % 0)
                                                     parent-free-args)})))))))


(defn resolve-parent-fn-id-cached
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


(defn fn-def-parent-names
  "Returns a vector of parent names from a fn-def.
   Supports both :parent (single keyword) and :parents (vector of keywords)."
  [fn-def]
  (let [parent-list (:parents fn-def)
        parent (:parent fn-def)]
    (cond
      (seq parent-list) (vec parent-list)
      parent [parent]
      :else [])))


(defn prepare-fn-record
  "Prepares a fn record for batch upsert.
   Returns {:id :name :parent-ids} or nil if already exists.

   Local fns (names starting with `_`) are stored with name=nil in DB.
   Their fn-id is derived deterministically from `namespace.local-name`
   so re-syncs (without DB truncation) reuse the same row instead of
   accumulating fresh anonymous shadows. Without this, every sync added
   N new copies of every local; the layout pass then saw, e.g., 16
   instances of `_text-500-body` after a few rebuilds and rendered each
   as its own duplicated subtree."
  [fn-name-cache fn-id-cache created-fns fn-def ns-id-map]
  (let [fn-name (:name fn-def)
        fn-name-str (clojure.core/name fn-name)
        is-local? (parsing/local-fn-name? fn-name)
        existing-named (when-not is-local?
                         (get fn-name-cache fn-name-str))]
    (cond
      ;; Already in DB with same name (named only).
      existing-named {:existing existing-named}

      :else
      (let [parent-names (fn-def-parent-names fn-def)
            parent-ids (mapv #(resolve-parent-fn-id-cached
                                fn-name-cache fn-id-cache created-fns %)
                             parent-names)
            db-name (when-not is-local? fn-name-str)
            ns-path (:namespace fn-def)
            ns-id (when ns-path (get ns-id-map ns-path))
            ;; Locals: deterministic UUID by namespace+name so the row
            ;; survives re-sync. Named: random UUID (matched by name on
            ;; subsequent syncs, never re-inserted).
            fn-id (if is-local?
                    (registry/local-fn-uuid ns-path fn-name-str)
                    (random-uuid))
            existing-local (when is-local? (get fn-id-cache fn-id))]
        (if existing-local
          {:existing existing-local}
          {:new (cond-> {:id fn-id
                         :name db-name
                         :parent-ids (when (seq parent-ids) parent-ids)}
                  ns-id (assoc :namespace-id ns-id))})))))


(defn prepare-propagated-arg-record
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
  "Resolves one element of a sequence arg's value vector into {:value …
   :ref-id … :name …}. Keywords that name a known fn become refs;
   other keywords become literal values. Maps with :ref or :value
   behave as one-shot overrides. A bare `{:as :name}` (no value/ref)
   produces a NAMED FREE SLOT — at runtime its value is read from
   `outer-free-args` by name; cross-HOF callers source-id directly to
   this item, so identity-wrappers around `{:value {:as :name}}` are
   no longer needed."
  [fn-name-cache created-fns item]
  (cond
    (uuid? item)
    {:value nil :ref-id item :name nil}

    (keyword? item)
    (let [nm (name item)]
      (if (parsing/valid-identifier? nm)
        (if-let [entry (or (get created-fns item)
                           (when-let [existing (get fn-name-cache nm)]
                             (:id existing)))]
          {:value nil :ref-id entry :name nil}
          {:value item :ref-id nil :name nil})
        {:value item :ref-id nil :name nil}))

    (and (map? item) (contains? item :ref))
    {:value nil
     :ref-id (resolve-fn-id-cached fn-name-cache created-fns (:ref item))
     :name (when-let [a (:as item)] (clojure.core/name a))}

    (and (map? item) (contains? item :value))
    {:value (:value item)
     :ref-id nil
     :name (when-let [a (:as item)] (clojure.core/name a))}

    ;; Bare `{:as :name}` — named free slot. No value, no ref.
    (and (map? item) (contains? item :as))
    {:value nil :ref-id nil :name (clojure.core/name (:as item))}

    :else
    {:value item :ref-id nil :name nil}))


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
   Items have source-id=nil and their own next-arg-id chain. Named
   items (`{:as :name}`) carry their name and reuse the existing
   item-id for stable cross-HOF source-id chains across re-syncs;
   unnamed items position-match the existing chain."
  [fn-name-cache created-fns args-data fn-id parent-arg items]
  (let [template-id (:id parent-arg)
        existing-anchor (get (:by-fn-source args-data) [fn-id template-id])
        anchor-id (or (:id existing-anchor) (random-uuid))
        element-type (or (:of parent-arg) :any)
        existing-item-ids (if existing-anchor
                            (walk-anchor-chain-ids (:by-id args-data) existing-anchor)
                            [])
        existing-by-id (:by-id args-data)
        existing-by-name (into {}
                               (keep (fn [eid]
                                       (when-let [e (get existing-by-id eid)]
                                         (when (:name e)
                                           [(:name e) eid]))))
                               existing-item-ids)
        item-records (mapv (fn [item]
                             (let [{:keys [value ref-id name]} (resolve-sequence-item
                                                                 fn-name-cache created-fns item)
                                   ;; Named items reuse their existing id by
                                   ;; name match → stable cross-HOF source-id
                                   ;; targets across re-syncs. Unnamed items
                                   ;; always get a fresh id (positional reuse
                                   ;; would lock callers to position).
                                   reuse-id (when name (get existing-by-name name))]
                               {:id (or reuse-id (random-uuid))
                                :fn-id fn-id
                                :source-id nil
                                :name name
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
        reused-ids (into #{} (map :id) item-records)
        delete-items (if existing-anchor
                       (filterv #(not (contains? reused-ids %)) existing-item-ids)
                       [])]
    {:new-chain (into [anchor] linked)
     :delete-items delete-items
     :source-id template-id}))


(defn prepare-arg-record
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
                  parent-arg-name (sc/resolve-arg-name-cached args-by-id parent-arg 0)]
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
