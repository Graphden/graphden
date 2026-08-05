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
    [clojure.tools.logging :as log]
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as tc]
    [graphden.crud.types-api :as types-api]
    [graphden.crud.validation :as validation]
    [graphden.storage.protocol.core :as sp]))


(defn- post-write-rej
  "Error-tolerance Phase 3 (Gap A): the same post-write aggregate
   type-check the binding create/update cores run — records / clears
   the owner's per-branch diagnostic and returns the full rej map
   (or nil when clean). A `:secret? true` rej is the SECURITY
   CARVE-OUT (secret-flow violation, NOT recorded in the store) —
   the caller must roll the write back and return `{:error …}`;
   non-secret rejs surface additively as `:type-warnings
   [(:diagnostic rej)]` on the success envelope."
  [storage owner-fn-id]
  (when owner-fn-id
    (tc/type-check-fn-after-mutation! storage owner-fn-id
                                      {:reject-secret? true})))


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
        ;; Resolve the `:sequence` base-fn's id ONCE (name→id at the
        ;; boundary), then test each slot's `:type-fn-id` by ID — internal
        ;; dispatch keys on the stable id, not a per-slot name compare.
        sequence-type-fn-id (some (fn [f] (when (= "sequence" (:name f)) (:id f)))
                                  (:fns graph))
        sequence?
        (fn [slot]
          (and sequence-type-fn-id
               (= sequence-type-fn-id (:type-fn-id slot))))
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
   text. (The storage `:literal` column disambiguates keyword
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
   <int> :fn-id <fn-id>}` on success (plus `:type-warnings
   [<diagnostic> …]` when the item landed but the owning fn now fails
   the aggregate type-check — error-tolerance Phase 3) or `{:error
   <reason>}` on pre-write validation rejection OR on a secret-flow
   type failure (hard reject: item + synthetic binding rolled back —
   the security carve-out). The graph composition
   around this primitive dispatches on the returned shape and runs
   invalidate + response.

   The synthetic-binding materialise + position-compute + pre-rej
   triplet share a binding-id that can't be split across graph nodes
   without race risk — hence §3.3."
  [parsed seq-binding ctx]
  (let [storage (request/require-storage ctx)
        fn-id (:fn-id parsed)
        body  (:body parsed)
        synthetic? (:synthetic seq-binding)
        synth-data (when synthetic?
                     {:fn-id (:fn-id seq-binding)
                      :slot-id (:slot-id seq-binding)
                      :list-append true})
        ;; The synthetic `:list-append` binding used to be created with a
        ;; DIRECT `sp/create-entity`, bypassing `write-rej` (list-closed /
        ;; terminal guards). Validate it FIRST so an append onto a sealed
        ;; slot is rejected before anything is written.
        synth-rej (when synthetic? (validation/write-rej storage :binding synth-data))]
    (if synth-rej
      {:error (:reason synth-rej)}
      (let [seq-binding (if synthetic?
                          (sp/create-entity storage :binding synth-data)
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
          (do
            ;; Roll back the synthetic binding created just to host the item
            ;; — otherwise a rejected append leaves an orphan `:list-append`
            ;; binding on the slot.
            (when synthetic?
              (try (sp/delete-entity storage :binding binding-id)
                   (catch Exception e
                     (log/warn e "seq-append: synthetic-binding rollback failed"
                               {:binding-id binding-id}))))
            {:error (:reason pre-rej)})
          (do (sp/create-entity storage :binding-list-item new-item)
              ;; Post-write whole-fn type-check (Phase 3, Gap A) — the
              ;; item is KEPT on an ordinary failure (recorded in the
              ;; per-branch diagnostics store, surfaced additively).
              ;; SECURITY CARVE-OUT: a secret-flow failure rolls the
              ;; item (and the synthetic host binding) back and hard-
              ;; rejects — see docs/SECRETS.md.
              (let [rej (post-write-rej
                          storage (or (:fn-id seq-binding) fn-id))]
                (if (:secret? rej)
                  (do (try (sp/delete-entity storage :binding-list-item
                                             (:id new-item))
                           (catch Exception e
                             (log/warn e "seq-append: item rollback failed after secret-flow rejection"
                                       {:item-id (:id new-item)})))
                      (when synthetic?
                        (try (sp/delete-entity storage :binding binding-id)
                             (catch Exception e
                               (log/warn e "seq-append: synthetic-binding rollback failed"
                                         {:binding-id binding-id}))))
                      {:error (:reason rej)})
                  (cond-> {:created (:id new-item)
                           :position new-pos
                           :fn-id fn-id}
                    rej (assoc :type-warnings [(:diagnostic rej)]))))))))))


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
   `{:updated <item-id>}` on success (plus `:type-warnings` — the
   Phase-3 post-write check, record-or-clear) or `{:error <reason>}`
   on pre-write rejection OR on a secret-flow type failure (hard
   reject: the item's fields are restored — the security carve-out)."
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
          ;; Post-write whole-fn type-check (Phase 3, Gap A) — same
          ;; record-or-clear semantics as the binding update core.
          ;; SECURITY CARVE-OUT: a secret-flow failure restores the
          ;; item's pre-update fields and hard-rejects.
          (let [owner-fn-id (some->> (:binding-id item)
                                     (sp/read-entity storage :binding)
                                     :fn-id)
                rej (post-write-rej storage owner-fn-id)]
            (if (:secret? rej)
              (do (try (sp/update-entity
                         storage :binding-list-item item-id
                         (select-keys (merge {:value nil :ref-fn-id nil
                                              :literal nil}
                                             item)
                                      (keys changes)))
                       (catch Exception e
                         (log/warn e "seq-update: item restore failed after secret-flow rejection"
                                   {:item-id item-id})))
                  {:error (:reason rej)})
              (cond-> {:updated item-id}
                rej (assoc :type-warnings [(:diagnostic rej)]))))))))
