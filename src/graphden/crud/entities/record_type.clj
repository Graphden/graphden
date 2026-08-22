(ns graphden.crud.entities.record-type
  "Compound type-row create / update — the journalled multi-row writes
   behind `POST /api/types/record` and friends. A record type is a fn
   row plus one slot + fn-slot pair per field, so each apply is a
   sequence of writes with a rollback journal rather than a single
   entity write.

   Split out of `crud.entities`: it is the largest self-contained topic
   in that tree, and its only tie back is the shared
   `entities.invalidation` seed."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.entities.invalidation :as inval]
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as tc]
    [graphden.storage.protocol.core :as sp]))


;; === Compound type-row create / update ======================================

(defn parse-create-record-type
  "Stage 1 of create-record-type — JSON body → `{:name :ns-id
   :description :fields}`."
  [request]
  (let [body (request/read-json-body request)
        ns-raw (:namespace-id body)]
    {:name (some-> (:name body) str)
     :ns-id (when-not (str/blank? (str ns-raw))
              (request/parse-uuid-or-clear (str ns-raw)))
     :description (:description body)
     :fields (vec (:fields body))}))


;; C19: stage 2 of create-record-type was here as
;; `validate-create-record-type`. Removed — replaced by the
;; `:_create-record-type-validation` `:cond` graph fn-def in
;; `web/crud/fns.edn` (predicates + error consts). Test-side
;; analogue lives in `entities_test/_validate-create-record-type-inline`.


(defn- create-record-type-fn-row!
  "Phase 1 of create-record-type — root `:fn` row + journal entry.
   Returns the new fn-id."
  [storage journal nm ns-id desc]
  (let [own-id (java.util.UUID/randomUUID)]
    (sp/create-entity storage :fn
                      (cond-> {:id own-id
                               :name nm
                               :namespace-id ns-id
                               :parent-ids []
                               :base-fn-id nil
                               :element-fn-id nil
                               :return-type-fn-id nil
                               :anonymous-hash nil
                               :constraint nil}
                        (and desc (seq desc)) (assoc :description desc)))
    (swap! journal conj [:fn own-id])
    own-id))


(defn- create-record-type-fields!
  "Phase 2 of create-record-type — mint a `:slot` + `:fn-slot`
   junction per field, journalling each create for the rollback
   callable. Resolves each field's `:type` to a type-fn-id, throws
   `:type-row/field-missing-name` if any field lacks a name."
  [storage journal own-id fields]
  (doseq [[idx field] (map-indexed vector fields)]
    (let [field-name (some-> (:name field) str)
          type-id (tc/resolve-type-fn-id-or-throw storage (:type field))
          field-desc (:description field)
          required? (if (contains? field :required) (boolean (:required field)) true)
          slot-id (java.util.UUID/randomUUID)
          fn-slot-id (java.util.UUID/randomUUID)]
      (when (str/blank? field-name)
        (throw (ex-info "field name required"
                        {:type :type-row/field-missing-name})))
      (sp/create-entity storage :slot
                        (cond-> {:id slot-id
                                 :name field-name
                                 :type-fn-id type-id
                                 :required required?}
                          (and field-desc (seq field-desc))
                          (assoc :description field-desc)))
      (swap! journal conj [:slot slot-id])
      (sp/create-entity storage :fn-slot
                        {:id fn-slot-id
                         :fn-id own-id
                         :slot-id slot-id
                         :position idx})
      (swap! journal conj [:fn-slot fn-slot-id]))))


(defn apply-create-record-type-body
  "Phases 1-3 of create-record-type's atomic write — fn-row + N
   slot-rows + N fn-slot junctions + cache-invalidate. Mutates
   `journal` (shared atom-of-vector) on each successful storage
   write so the rollback callable can replay in reverse. Throws on
   any storage / type-resolve failure — caught by the surrounding
   `:try` graph node. Reached only after `:_create-record-type-
   validation` passed.

   Body is orchestration; each phase lives in a small private
   helper so the read flows top-to-bottom by phase name."
  [parsed journal ctx]
  (let [storage (request/require-storage ctx)
        {nm :name ns-id :ns-id desc :description fields :fields} parsed
        own-id (create-record-type-fn-row! storage journal nm ns-id desc)]
    (create-record-type-fields! storage journal own-id fields)
    (inval/invalidate! ctx storage :fn {:id own-id})
    {:ok true :id (str own-id) :name nm}))


(defn apply-create-rollback
  "`:try`'s on-throw branch for create-record-type AND create-list-type.
   Derefs `journal` and replays entries in reverse, deleting each
   row best-effort (delete failures are logged, not re-thrown — the
   important contract is that user state stays consistent with what
   the response says). Returns the `{:ok false :error :data}` shape."
  [journal exception ctx]
  (let [storage (request/require-storage ctx)]
    (doseq [[et id] (reverse @journal)]
      (try (sp/delete-entity storage et id)
           (catch Exception e
             (log/warn e "Rollback delete-entity failed for"
                       et id "— manual cleanup may be required")))))
  ;; `Throwable/.getMessage` can return null (Java API contract); wrap
  ;; both branches in `str` so the `:error` field is always a string.
  (let [msg (str (Throwable/.getMessage ^Throwable exception))]
    (cond-> {:ok false :error msg}
      (instance? clojure.lang.ExceptionInfo exception)
      (assoc :data (ex-data exception)))))


;; create-list-type's parse + validation stages are graph fn-defs
;; (`:_create-list-type-parsed` / `:_create-list-type-validation`); the
;; rollback-bearing apply stage is `apply-create-list-type-body` +
;; `apply-create-rollback`, composed by the graph `:try`.


(defn apply-create-list-type-body
  "Phases 1-3 of create-list-type's atomic write — fn-row with
   `:element-fn-id` + synthesised `items` slot + fn-slot junction +
   cache-invalidate. Mutates `journal` (shared atom) for rollback.
   Throws on storage / type-resolve failure — caught by the
   surrounding `:try` graph node."
  [parsed journal ctx]
  (let [storage (request/require-storage ctx)
        {nm :name ns-id :ns-id desc :description element-ref :element-ref} parsed
        own-id (java.util.UUID/randomUUID)
        elem-id (tc/resolve-type-fn-id-or-throw storage element-ref)
        seq-id (tc/resolve-type-fn-id-or-throw storage "sequence")
        slot-id (java.util.UUID/randomUUID)
        fn-slot-id (java.util.UUID/randomUUID)]
    (sp/create-entity storage :fn
                      (cond-> {:id own-id
                               :name nm
                               :namespace-id ns-id
                               :parent-ids []
                               :base-fn-id nil
                               :element-fn-id elem-id
                               :return-type-fn-id nil
                               :anonymous-hash nil
                               :constraint nil}
                        (and desc (seq desc)) (assoc :description desc)))
    (swap! journal conj [:fn own-id])
    (sp/create-entity storage :slot
                      {:id slot-id
                       :name "items"
                       :type-fn-id seq-id
                       :required true})
    (swap! journal conj [:slot slot-id])
    (sp/create-entity storage :fn-slot
                      {:id fn-slot-id
                       :fn-id own-id
                       :slot-id slot-id
                       :position 0})
    (swap! journal conj [:fn-slot fn-slot-id])
    (inval/invalidate! ctx storage :fn {:id own-id})
    {:ok true :id (str own-id) :name nm}))


;; update-record-type's parse stage is a graph fn-def composing
;; `:parse-json-body` + per-field getters + `:contains?` on
;; `:description` for the `:has-description?` distinction; validation is
;; the `:_update-record-type-validation` `:cond`. The rollback-bearing
;; apply stage is `apply-update-record-type-body` + `-rollback` below.


;; === Stage-3 update-record-type apply: journalled txn split for graph ===
;;
;; The 141-line monolith was decomposed so the journalled-write pattern
;; is visible at the graph level: `:_update-record-type-apply` is now a
;; `:try` (core.system) whose body runs phases 2-5 + invalidate + success
;; and whose `on-throw` reads the shared `:atom` journal and replays it
;; in reverse. The atom is a single `:_apply-update-record-type-journal`
;; fn-def referenced from both branches at the `:try`-call's cache
;; level, so body and rollback see the SAME instance.
;;
;; The phases themselves stay as ONE Clojure helper each — per-iteration
;; storage writes are still iteration (not composition), and the inner
;; per-field reuse/mint decision is tightly coupled to the journal.
;; Splitting further would scatter one conceptual operation into N
;; atom-threading hops with no semantic gain (skill graphden-fn-refactor
;; §3 §1). The win here is the OUTER shape — try / journal / rollback —
;; not lower-level per-iteration atomisation.

(defn- load-update-record-type-state
  "Pre-update snapshot the diff-and-apply needs: the current fn-row,
   its fn-slots, the underlying slot-rows, and the `[name type-fn-id]
   → fn-slot` index that drives the reuse-vs-mint decision."
  [storage fn-id]
  (let [existing-fn (first (sp/query-entities storage :fn {:id fn-id}))
        current-fss (sp/query-entities storage :fn-slot {:fn-id fn-id})
        current-slot-ids (mapv :slot-id current-fss)
        current-slots (when (seq current-slot-ids)
                        (sp/query-entities storage :slot
                                           {:id current-slot-ids}))
        slots-by-id (into {} (map (juxt :id identity)) (or current-slots []))
        ;; Match by (name, type-fn-id): retypes must yield a new
        ;; slot since slot rows are immutable.
        slots-by-name+type (into {}
                                 (map (fn [fs]
                                        (let [s (get slots-by-id (:slot-id fs))]
                                          [[(:name s) (:type-fn-id s)] fs])))
                                 current-fss)]
    {:existing-fn existing-fn
     :current-fss current-fss
     :slots-by-name+type slots-by-name+type}))


(defn- resolve-update-fields
  "Resolve every incoming field's type up front so a typo doesn't
   leave the row half-rewritten — all storage writes wait until we
   have the full resolved vector. Throws `:type-row/field-missing-
   name` on a blank name and propagates whatever
   `resolve-type-fn-id-or-throw` raises on a bad type ref."
  [storage fields]
  (mapv (fn [field]
          (let [field-name (some-> (:name field) str)
                _ (when (str/blank? field-name)
                    (throw (ex-info "field name required"
                                    {:type :type-row/field-missing-name})))
                type-id (tc/resolve-type-fn-id-or-throw storage (:type field))
                required? (if (contains? field :required)
                            (boolean (:required field)) true)]
            {:name field-name
             :type-fn-id type-id
             :description (:description field)
             :required required?}))
        fields))


(defn- compute-slot-assignments
  "Decide per-field whether to reuse a current slot (same name + type)
   or mint a fresh one. `:reuse?` flag drives every downstream phase;
   reused entries carry the existing slot-id / fn-slot-id, new ones
   carry pre-allocated UUIDs and the resolved `:spec`."
  [resolved slots-by-name+type]
  (mapv (fn [r]
          (if-let [fs (get slots-by-name+type
                           [(:name r) (:type-fn-id r)])]
            {:slot-id (:slot-id fs)
             :fn-slot-id (:id fs)
             :reuse? true}
            {:slot-id (java.util.UUID/randomUUID)
             :fn-slot-id (java.util.UUID/randomUUID)
             :reuse? false
             :spec r}))
        resolved))


(defn- create-new-slots!
  "Phase 2: insert slot rows for fields the diff classified as new.
   Records each insert in `journal` for the rollback path."
  [storage journal assignments]
  (doseq [a assignments
          :when (not (:reuse? a))]
    (let [{:keys [slot-id spec]} a]
      (sp/create-entity storage :slot
                        (cond-> {:id slot-id
                                 :name (:name spec)
                                 :type-fn-id (:type-fn-id spec)
                                 :required (:required spec)}
                          (and (:description spec) (seq (:description spec)))
                          (assoc :description (:description spec))))
      (swap! journal conj {:op :create :entity-type :slot :id slot-id}))))


(defn- delete-unused-fn-slots!
  "Phase 3: drop every current fn-slot whose id isn't in `kept-fs-ids`.
   Journals the full row so the rollback path can resurrect it."
  [storage journal current-fss kept-fs-ids]
  (doseq [fs current-fss
          :when (not (kept-fs-ids (:id fs)))]
    (sp/delete-entity storage :fn-slot (:id fs))
    (swap! journal conj {:op :delete :entity-type :fn-slot :row fs})))


(defn- rewire-fn-slot-positions!
  "Phase 4: walk `assignments` in target-position order — reused
   entries get a delete+re-create with the new position when they
   moved (UNIQUE constraint on `(fn-id, position)` means we can't
   bump in-place); new entries get a fresh fn-slot insert. Each leg
   is journalled so a downstream failure can fully rewind."
  [storage journal fn-id current-fss assignments]
  (doseq [[idx a] (map-indexed vector assignments)]
    (cond
      (:reuse? a)
      (let [old-fs (first (filter #(= (:id %) (:fn-slot-id a)) current-fss))]
        (when (and old-fs (not= (:position old-fs) idx))
          (sp/delete-entity storage :fn-slot (:id old-fs))
          (swap! journal conj {:op :delete :entity-type :fn-slot :row old-fs})
          (let [new-row (assoc old-fs :position idx)]
            (sp/create-entity storage :fn-slot new-row)
            (swap! journal conj {:op :create :entity-type :fn-slot
                                 :id (:id new-row)}))))

      :else
      (do
        (sp/create-entity storage :fn-slot
                          {:id (:fn-slot-id a)
                           :fn-id fn-id
                           :slot-id (:slot-id a)
                           :position idx})
        (swap! journal conj {:op :create :entity-type :fn-slot
                             :id (:fn-slot-id a)})))))


(defn- apply-fn-row-patch!
  "Phase 5: optional rename / re-description of the fn-row itself.
   No-op when neither field changes. The patch isn't journalled — a
   throw downstream would already have left earlier phases for the
   rollback path to reverse."
  [storage existing-fn fn-id nm has-description? desc]
  (when (or (and nm (not= nm (:name existing-fn)))
            has-description?)
    (let [patch (cond-> {}
                  (and nm (not= nm (:name existing-fn)))
                  (assoc :name nm)
                  has-description?
                  (assoc :description desc))]
      (sp/update-entity storage :fn fn-id patch))))


(defn apply-update-record-type-body
  "Body of `:_update-record-type-apply`'s `:try`. Performs phases 2-5
   of the diff-and-apply (create new slots / delete unused fn-slots /
   rewire positions / optional rename), appending rollback hints to
   `journal` along the way, then invalidates caches and returns the
   success response. Throws on any storage failure or bad-type-resolve
   — caught by `:try`, which hands control to `-rollback`.

   Body itself is the orchestration; each phase lives in a small
   `apply-update-record-type-*` private helper so the read flows
   top-to-bottom by phase name instead of by line range."
  [parsed journal ctx]
  (let [storage (request/require-storage ctx)
        {fn-id :fn-id nm :name desc :description fields :fields
         has-description? :has-description?} parsed
        {:keys [existing-fn current-fss slots-by-name+type]}
        (load-update-record-type-state storage fn-id)
        resolved (resolve-update-fields storage fields)
        assignments (compute-slot-assignments resolved slots-by-name+type)
        kept-fs-ids (into #{} (comp (filter :reuse?) (map :fn-slot-id))
                          assignments)]
    (create-new-slots! storage journal assignments)
    (delete-unused-fn-slots! storage journal current-fss kept-fs-ids)
    (rewire-fn-slot-positions! storage journal fn-id current-fss assignments)
    (apply-fn-row-patch! storage existing-fn fn-id nm has-description? desc)
    ;; The compound write happened through `sp/*-entity` —
    ;; bypassing the defbase wrappers that normally call
    ;; `invalidate!`. Without this nudge the next read of
    ;; `/api/graph/entities` would return the cached pre-
    ;; update graph and the editor would see no change.
    (inval/invalidate! ctx storage :fn-slot {:fn-id fn-id})
    {:ok true :id (str fn-id) :name (or nm (:name existing-fn))}))


(defn apply-update-record-type-rollback
  "Called by `:try`'s `:on-throw` when the body throws. Reads the
   journal atom + replays its entries in reverse: a recorded `:create`
   becomes a delete, a recorded `:delete` becomes a create. Each
   replay step is wrapped in its own try/swallow so one stuck reversal
   doesn't block the rest. Returns the partial Ring response carrying
   the original exception's message + ex-data."
  [journal exception ctx]
  (let [storage (request/require-storage ctx)]
    (doseq [entry (reverse (deref journal))]
      (try
        (case (:op entry)
          :create (sp/delete-entity storage (:entity-type entry) (:id entry))
          :delete (sp/create-entity storage (:entity-type entry) (:row entry))
          nil)
        (catch Exception e
          (log/warn e "Journalled-rollback step failed:" (:op entry)
                    (:entity-type entry) (or (:id entry) (:row entry))
                    "— manual cleanup may be required"))))
    (cond-> {:ok false :error (str (Throwable/.getMessage exception))}
      (instance? clojure.lang.ExceptionInfo exception)
      (assoc :data (ex-data exception)))))
