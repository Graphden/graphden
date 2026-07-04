(ns graphden.crud.entities.seq
  "Sequence-binding CRUD — the append / remove / update flows for
   `binding-list-item` rows. Extracted from `crud/entities` so the
   parent ns stays focused on generic CRUD + record/list-type creation
   + update + delete. The `apply-*-core` / `load-*` / `find-*` helpers
   are re-exported through `crud.entities` so `web/crud/impls.clj` can
   call them via the `entities/` alias.

   Each `apply-*-core` is a §3.3 atomic write unit — the synthetic-
   binding materialise + position-compute + pre-rej triplet (in
   append) shares a binding-id that can't be split across graph
   nodes without race risk. The graph composition around each
   primitive dispatches on the returned shape and runs invalidate +
   response."
  (:require
    [clojure.string :as str]
    [graphden.crud.request :as request]
    [graphden.crud.types-api :as types-api]
    [graphden.crud.validation :as validation]
    [graphden.storage.protocol.core :as sp]))


(defn find-sequence-binding
  "Find the binding row that owns the sequence items for `fn-id`. A fn
   that has at least one sequence-typed slot may have an own binding
   on it (with or without `:list-append`); when it doesn't yet, the
   first append creates one. Returns either the existing binding row
   or a synthetic `{:fn-id … :slot-id …}` placeholder pinning where
   the binding will be created.

   Resolves entirely against the in-memory graph cache — five-table
   reads collapse to one cache hit per editor sequence-edit click."
  [ctx fn-id]
  (let [graph (types-api/cached-or-load-graph ctx)
        fns-by-id (into {} (map (juxt :id identity)) (:fns graph))
        slots-by-id (into {} (map (juxt :id identity)) (:slots graph))
        fn-slots-by-fn (group-by :fn-id (:fn-slots graph))
        bindings-by-fn-slot (into {}
                                  (map (fn [b] [[(:fn-id b) (:slot-id b)] b]))
                                  (:bindings graph))
        sequence?
        (fn [slot]
          (= "sequence" (:name (get fns-by-id (:type-fn-id slot)))))
        ;; Walk parent chain in memory.
        chain (loop [acc [], seen #{}, queue [fn-id]]
                (if (empty? queue)
                  acc
                  (let [fid (first queue)
                        rest-q (vec (rest queue))]
                    (if (or (nil? fid) (contains? seen fid))
                      (recur acc seen rest-q)
                      (let [f (get fns-by-id fid)
                            pids (->> (:parent-ids f) (remove nil?) (remove seen))]
                        (recur (conj acc fid) (conj seen fid)
                               (into rest-q pids)))))))
        sequence-slot
        (some (fn [fid]
                (some (fn [fs]
                        (let [s (get slots-by-id (:slot-id fs))]
                          (when (sequence? s) s)))
                      (get fn-slots-by-fn fid [])))
              chain)]
    (when sequence-slot
      (or (get bindings-by-fn-slot [fn-id (:id sequence-slot)])
          {:fn-id fn-id :slot-id (:id sequence-slot) :synthetic true}))))


(defn resolve-sequence-payload
  "Parses a sequence-op JSON body into the `binding-list-item` shape.
   Body shapes:
     {\"ref\":  \"fn-uuid-string\"}
     {\"ref-name\": \"my-fn\"}
     {\"value\": <any JSON>}

   A `\":foo\"`-shaped value string is the wire form of a keyword
   literal (JSON has no keyword type) — restore the keyword and set
   `:literal true`, matching how `records.clj` stores a fn-def's
   `{:value :kw}` item. Without the flag a read would re-emit the
   keyword colon-stripped and the editor would mis-type it as plain
   text. (The legacy `:literal? true` EDN flag was retired; the
   storage `:literal` column is still used to disambiguate keyword
   literals from string text on read-back.)"
  [storage body]
  (cond
    (contains? body :ref)
    {:ref-fn-id (java.util.UUID/fromString (:ref body))}

    (contains? body :ref-name)
    (if-let [target (first (sp/query-entities storage :fn {:name (:ref-name body)}))]
      {:ref-fn-id (:id target)}
      (throw (ex-info (str "Fn not found by name: " (:ref-name body))
                      {:type :sequence-op/fn-not-found :ref-name (:ref-name body)})))

    (contains? body :value)
    (let [v (:value body)]
      (if (and (string? v) (> (count v) 1) (str/starts-with? v ":"))
        {:value (keyword (subs v 1)) :literal true}
        {:value v}))

    :else
    (throw (ex-info "Sequence op body requires :ref, :ref-name, or :value"
                    {:type :sequence-op/invalid-body :body body}))))


(defn find-seq-append-binding
  "Read-only sequence-binding resolution for the C3 graph. Returns
   `find-sequence-binding`'s result (existing binding | synthetic
   placeholder | nil) — does NOT materialize a synthetic binding; the
   `:_seq-append-apply` graph path runs that write only after every
   guard passes."
  [parsed ctx]
  (when-let [fn-id (:fn-id parsed)]
    (find-sequence-binding ctx fn-id)))


(defn apply-seq-append-core
  "§3.3 atomic core of sequence-append: materialise synthetic binding
   if needed, compute next position, run pre-write validation, write
   the binding-list-item row. Returns `{:created <item-id> :position
   <int> :fn-id <fn-id>}` on success or `{:error <reason>}` on
   pre-write validation rejection. The graph composition around this
   primitive dispatches on the returned shape and runs invalidate +
   response.

   The synthetic-binding materialise + position-compute + pre-rej
   triplet share a binding-id that can't be split across graph nodes
   without race risk — hence §3.3."
  [parsed seq-binding ctx]
  (let [storage (request/require-storage ctx)
        fn-id (:fn-id parsed)
        body  (:body parsed)
        seq-binding (if (:synthetic seq-binding)
                      (sp/create-entity storage :binding
                                        {:fn-id (:fn-id seq-binding)
                                         :slot-id (:slot-id seq-binding)
                                         :list-append true})
                      seq-binding)
        binding-id (:id seq-binding)
        used-pos (map :position
                      (sp/query-entities storage :binding-list-item
                                         {:binding-id binding-id}))
        new-pos (inc (apply max -1 used-pos))
        payload (resolve-sequence-payload storage body)
        new-item (merge {:id (random-uuid)
                         :binding-id binding-id
                         :position new-pos}
                        payload)
        pre-rej (validation/write-rej storage :binding-list-item new-item)]
    (if pre-rej
      {:error (:reason pre-rej)}
      (do (sp/create-entity storage :binding-list-item new-item)
          {:created (:id new-item)
           :position new-pos
           :fn-id fn-id}))))


(defn load-seq-remove-item
  "Resolve the binding-list-item row for a parsed sequence-remove
   request. Returns nil when the item-id is invalid OR when no row
   matches — the `:cond` graph fn-def's not-found guard rejects in
   both cases (the invalid-item-id guard runs first, so by the time
   this fires the id is well-formed)."
  [parsed ctx]
  (when-let [item-id (:item-id parsed)]
    (sp/read-entity (request/require-storage ctx) :binding-list-item item-id)))


(defn load-seq-update-item
  "Read the binding-list-item row for a parsed sequence-update
   request. Returns nil when the id is invalid (guard #1 catches it
   first) OR when no row matches (`:_seq-update-item-not-found?`
   catches that)."
  [parsed ctx]
  (when-let [item-id (:item-id parsed)]
    (sp/read-entity (request/require-storage ctx) :binding-list-item item-id)))


(defn apply-seq-update-core
  "§3.3 atomic core of sequence-update: resolve body payload, run
   pre-write validation, write the binding-list-item row. Returns
   `{:updated <item-id>}` on success or `{:error <reason>}` on
   pre-write rejection."
  [parsed item ctx]
  (let [storage (request/require-storage ctx)
        item-id (:item-id parsed)
        payload (resolve-sequence-payload storage (:body parsed))
        changes (merge {:value nil :ref-fn-id nil :literal nil} payload)
        pre-rej (validation/write-rej storage :binding-list-item
                                      (merge item changes {:id item-id}))]
    (if pre-rej
      {:error (:reason pre-rej)}
      (do (sp/update-entity storage :binding-list-item item-id changes)
          {:updated item-id}))))
