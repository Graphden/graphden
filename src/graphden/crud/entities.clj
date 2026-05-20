(ns graphden.crud.entities
  "Heavy CRUD logic for the web/crud base functions — the bodies
   behind the generic `create/update/delete-entity`, the form
   parsers, the `process-*` request dispatchers, the sequence-arg
   operations, the fn-type / effects tightening flow, and the
   graph dump / single-entity query.

   Top of the crud.* DAG: may require every other `graphden.crud.*`
   namespace. It does NOT — and must not — depend on any
   `graphden.packages.*` package: the rendering code that does stays
   in `web/crud/impls.clj`."
  (:require
    [cheshire.core :as json]
    [clojure.set]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as tc]
    [graphden.crud.types-api :as types-api]
    [graphden.crud.validation :as validation]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records :as records]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]))


;; === Affected-fn-id derivation for delta invalidation =======================
;;
;; Every CRUD mutation either lands ON a fn-row (e.g. `:fn` create), TOUCHES a
;; specific fn-row's closure (e.g. binding / fn-slot / binding-list-item under
;; some owner fn), OR cuts across many fns (e.g. `:slot` rename — slots are
;; shared; `:ns` rename — namespaces don't affect closures at all). For the
;; first two cases we can name the affected seed and let
;; `compile-runtime/delta-recompile!` walk the reverse-deps index to recompile
;; just the blast radius. For the cross-cutting cases we hand back nil and the
;; 1-arity `invalidate-graph-cache!` falls through to a full rebuild.
;;
;; The seed is just the directly-mutated fn-id (or, for binding-list-item, the
;; fn that OWNS the parent binding). Descendants are picked up by the
;; reverse-deps walk in `delta-recompile!` — no need to expand them here.

(defn affected-fn-ids
  "Returns the seed set of fn-ids whose closure may be invalidated by a
   write to `entity-type` carrying `entity-data`. nil ⇒ caller must
   invoke 1-arity `invalidate-graph-cache!` (full clear)."
  [storage entity-type entity-data]
  (case entity-type
    :fn
    (when-let [id (:id entity-data)] #{id})

    (:fn-slot :binding)
    (when-let [fid (:fn-id entity-data)] #{fid})

    :binding-list-item
    ;; Items live under a binding; the binding's fn-id is the owner.
    ;; On delete we pre-read the item before the row is gone so
    ;; `entity-data` carries `:binding-id`; on create the caller
    ;; supplies it directly.
    (when-let [bid (:binding-id entity-data)]
      (some-> (sp/read-entity storage :binding bid) :fn-id hash-set))

    ;; :slot is shared across many fns; :ns doesn't touch closures —
    ;; both fall through to a full clear.
    nil))


(defn invalidate!
  "Convenience wrapper: derive the affected fn-id seeds and call
   `invalidate-graph-cache!` with the right arity. Pass `entity-data`
   that already includes `:id` (so :fn deletes pre-read the row,
   binding-list-item deletes pre-read the item)."
  [ctx storage entity-type entity-data]
  (if-let [seeds (affected-fn-ids storage entity-type entity-data)]
    (exec-ctx/invalidate-graph-cache! ctx seeds)
    (exec-ctx/invalidate-graph-cache! ctx)))


;; === Context-aware Query Functions ===

(defn list-entities
  [entity-type where ctx]
  (vec (sp/query-entities (request/require-storage ctx)
                          (keyword entity-type) (or where {}))))


(defn get-entity
  [entity-type id ctx]
  (sp/read-entity (request/require-storage ctx) (keyword entity-type) id))


(defn create-entity
  [entity-type data ctx]
  (let [storage (request/require-storage ctx)
        et (keyword entity-type)
        ;; For :fn create the row may not have an `:id` yet; the
        ;; cycle check still wants it (parent / FK targets need to
        ;; know who's "owner"). Synthesize one so the check sees a
        ;; stable owner — `sp/create-entity` honours a pre-supplied
        ;; `:id` so the synthesized value is what lands in storage.
        data' (if (and (= et :fn) (nil? (:id data)))
                (assoc data :id (random-uuid))
                data)]
    (when-let [rej (validation/write-rej storage et data')]
      (throw (ex-info (:reason rej)
                      {:type (:type rej)
                       :entity-type et :data data'})))
    (let [result (sp/create-entity storage et data')]
      (invalidate! ctx storage et result)
      result)))


(defn update-entity
  [entity-type id data ctx]
  (let [storage (request/require-storage ctx)
        et (keyword entity-type)
        check-data (assoc data :id id)]
    (when-let [rej (validation/write-rej storage et check-data)]
      (throw (ex-info (:reason rej)
                      {:type (:type rej)
                       :entity-type et :id id :data data})))
    (let [result (sp/update-entity storage et id data)]
      (invalidate! ctx storage et result)
      result)))


(defn delete-entity
  [entity-type id ctx]
  (let [storage (request/require-storage ctx)
        et (keyword entity-type)
        ;; Pre-read so we know the parent fn-id for binding /
        ;; fn-slot / binding-list-item before the row is gone.
        ;; For :fn the row's :id IS the seed; we synthesize one
        ;; rather than pay the read.
        snapshot (if (= et :fn)
                   {:id id}
                   (sp/read-entity storage et id))]
    (sp/delete-entity storage et id)
    (invalidate! ctx storage et snapshot)
    true))


(defn list-all-graph-entities
  [ctx]
  ;; Slot/fn-slot/binding model: dump every storage row the editor
  ;; needs to render the graph. Routes through the shared graph-cache
  ;; (populated by layout / compile-runtime) so editor refreshes after
  ;; mutations don't re-query the same five tables every time.
  ;;
  ;; Each fn-row is augmented with a `:role` field so the sidebar can
  ;; group entries into Types vs Functions sections without an extra
  ;; round-trip through `/api/types`.
  (let [storage (request/require-storage ctx)
        base (types-api/cached-or-load-graph ctx)
        fn-slots-by-fn (group-by :fn-id (:fn-slots base))
        rich-snapshot (registry/rich-types-snapshot)
        roled-fns (mapv (fn [f]
                          (assoc f :role
                                 (types-api/compute-fn-role
                                   f
                                   (boolean (seq (get fn-slots-by-fn (:id f))))
                                   rich-snapshot)))
                        (:fns base))]
    (-> base
        (assoc :fns roled-fns)
        (assoc :namespaces (vec (sp/query-entities storage :ns {}))))))


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


(defn validate-create-record-type
  "Stage 2 of create-record-type. Returns the `{:ok false :error …}`
   rejection response, or nil when the request is well-formed."
  [parsed]
  (cond
    (str/blank? (:name parsed))
    {:ok false :error "name required"}

    (empty? (:fields parsed))
    {:ok false :error "fields required (a record needs ≥1 field)"}

    :else nil))


(defn apply-create-record-type
  "Stage 3 of create-record-type — atomically create the record
   type-row (one fn-row + N slot-rows + N fn-slot-junctions); rolls
   every partial write back on failure. Reached only after
   `validate-create-record-type` passes."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        {nm :name ns-id :ns-id desc :description fields :fields} parsed
        own-id (java.util.UUID/randomUUID)
        created (atom [])
        cleanup (fn []
                  (doseq [[et id] (reverse @created)]
                    (try (sp/delete-entity storage et id) (catch Exception _ nil))))]
    (try
      (sp/create-entity storage :fn
                        (cond-> {:id own-id
                                 :name nm
                                 :namespace-id ns-id
                                 :parent-ids []
                                 :impl-hash nil
                                 :base-fn-id nil
                                 :element-fn-id nil
                                 :return-type-fn-id nil
                                 :anonymous-hash nil
                                 :constraint nil}
                          (and desc (seq desc)) (assoc :description desc)))
      (swap! created conj [:fn own-id])
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
          (swap! created conj [:slot slot-id])
          (sp/create-entity storage :fn-slot
                            {:id fn-slot-id
                             :fn-id own-id
                             :slot-id slot-id
                             :position idx})
          (swap! created conj [:fn-slot fn-slot-id])))
      (invalidate! ctx storage :fn {:id own-id})
      {:ok true :id (str own-id) :name nm}
      (catch clojure.lang.ExceptionInfo e
        (cleanup)
        {:ok false :error (Throwable/.getMessage e) :data (ex-data e)})
      (catch Exception e
        (cleanup)
        {:ok false :error (str (Throwable/.getMessage e))}))))


(defn parse-create-list-type
  "Stage 1 of create-list-type — JSON body → `{:name :ns-id
   :description :element-ref}`."
  [request]
  (let [body (request/read-json-body request)
        ns-raw (:namespace-id body)]
    {:name (some-> (:name body) str)
     :ns-id (when-not (str/blank? (str ns-raw))
              (request/parse-uuid-or-clear (str ns-raw)))
     :description (:description body)
     :element-ref (:element-type body)}))


(defn validate-create-list-type
  "Stage 2 of create-list-type. Returns the `{:ok false :error …}`
   rejection response, or nil when the request is well-formed."
  [parsed]
  (cond
    (str/blank? (:name parsed))
    {:ok false :error "name required"}

    (nil? (:element-ref parsed))
    {:ok false :error "element-type required"}

    :else nil))


(defn apply-create-list-type
  "Stage 3 of create-list-type — atomically create the list type-row
   (one fn-row with `element-fn-id` plus the synthesised `items`
   slot); rolls partial writes back on failure. Reached only after
   `validate-create-list-type` passes."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        {nm :name ns-id :ns-id desc :description element-ref :element-ref} parsed
        own-id (java.util.UUID/randomUUID)
        created (atom [])
        cleanup (fn []
                  (doseq [[et id] (reverse @created)]
                    (try (sp/delete-entity storage et id) (catch Exception _ nil))))]
    (try
      (let [elem-id (tc/resolve-type-fn-id-or-throw storage element-ref)
            seq-id (tc/resolve-type-fn-id-or-throw storage "sequence")
            slot-id (java.util.UUID/randomUUID)
            fn-slot-id (java.util.UUID/randomUUID)]
        (sp/create-entity storage :fn
                          (cond-> {:id own-id
                                   :name nm
                                   :namespace-id ns-id
                                   :parent-ids []
                                   :impl-hash nil
                                   :base-fn-id nil
                                   :element-fn-id elem-id
                                   :return-type-fn-id nil
                                   :anonymous-hash nil
                                   :constraint nil}
                            (and desc (seq desc)) (assoc :description desc)))
        (swap! created conj [:fn own-id])
        (sp/create-entity storage :slot
                          {:id slot-id
                           :name "items"
                           :type-fn-id seq-id
                           :required true})
        (swap! created conj [:slot slot-id])
        (sp/create-entity storage :fn-slot
                          {:id fn-slot-id
                           :fn-id own-id
                           :slot-id slot-id
                           :position 0})
        (swap! created conj [:fn-slot fn-slot-id])
        (invalidate! ctx storage :fn {:id own-id})
        {:ok true :id (str own-id) :name nm})
      (catch clojure.lang.ExceptionInfo e
        (cleanup)
        {:ok false :error (Throwable/.getMessage e) :data (ex-data e)})
      (catch Exception e
        (cleanup)
        {:ok false :error (str (Throwable/.getMessage e))}))))


(defn parse-update-record-type
  "Stage 1 of update-record-type — JSON body → `{:fn-id :name
   :has-description? :description :fields}`. `:has-description?`
   distinguishes a submitted-nil description (clear) from an absent
   key (leave untouched)."
  [request]
  (let [body (request/read-json-body request)
        fn-id-raw (:id body)]
    {:fn-id (when fn-id-raw
              (try (java.util.UUID/fromString (str fn-id-raw))
                   (catch Exception _ nil)))
     :name (some-> (:name body) str)
     :has-description? (contains? body :description)
     :description (:description body)
     :fields (vec (:fields body))}))


(defn validate-update-record-type
  "Stage 2 of update-record-type. Returns the `{:ok false :error …}`
   rejection response, or nil. The final guard reads storage to
   confirm the target fn-row exists."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        {:keys [fn-id fields]} parsed]
    (cond
      (nil? fn-id)
      {:ok false :error "id required (UUID)"}

      (empty? fields)
      {:ok false :error "fields required (a record needs ≥1 field)"}

      (nil? (first (sp/query-entities storage :fn {:id fn-id})))
      {:ok false :error (str "fn " fn-id " not found")}

      :else nil)))


(defn apply-update-record-type
  "Stage 3 of update-record-type — compute the diff of the submitted
   field list against the row's current fn-slots and atomically apply
   it (journalled rollback on failure). Reached only after
   `validate-update-record-type` passes."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        {fn-id :fn-id nm :name desc :description fields :fields
         has-description? :has-description?} parsed
        existing-fn (first (sp/query-entities storage :fn {:id fn-id}))
        current-fss (sp/query-entities storage :fn-slot {:fn-id fn-id})
        current-slot-ids (mapv :slot-id current-fss)
        current-slots (when (seq current-slot-ids)
                        (sp/query-entities storage :slot
                                           {:id current-slot-ids}))
        slots-by-id (into {} (map (juxt :id identity)) (or current-slots []))
        ;; Match by (name, type-fn-id): retypes must yield a new
        ;; slot since slot rows are immutable.
        slots-by-name+type (into {} (map (fn [fs]
                                           (let [s (get slots-by-id (:slot-id fs))]
                                             [[(:name s) (:type-fn-id s)] fs])))
                                 current-fss)
        journal (atom [])
        cleanup (fn []
                  (doseq [entry (reverse @journal)]
                    (try
                      (case (:op entry)
                        :create (sp/delete-entity storage (:entity-type entry) (:id entry))
                        :delete (sp/create-entity storage (:entity-type entry) (:row entry))
                        nil)
                      (catch Exception _ nil))))]
    (try
      ;; Phase 1: resolve every incoming field's type up front
      ;; so a typo doesn't leave us with a half-rewritten row.
      (let [resolved (mapv (fn [field]
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
                           fields)
            ;; Decide per-field whether to reuse a current slot
            ;; (same name+type) or mint a new one.
            kept-fs-ids (atom #{})
            assignments (mapv (fn [r]
                                (if-let [fs (get slots-by-name+type
                                                 [(:name r) (:type-fn-id r)])]
                                  (do (swap! kept-fs-ids conj (:id fs))
                                      {:slot-id (:slot-id fs)
                                       :fn-slot-id (:id fs)
                                       :reuse? true})
                                  {:slot-id (java.util.UUID/randomUUID)
                                   :fn-slot-id (java.util.UUID/randomUUID)
                                   :reuse? false
                                   :spec r}))
                              resolved)]
        ;; Phase 2: create slots for new entries.
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
            (swap! journal conj {:op :create :entity-type :slot :id slot-id})))
        ;; Phase 3: delete every existing fn-slot that we're
        ;; not keeping. Journal the full row so cleanup can
        ;; resurrect it on failure downstream.
        (doseq [fs current-fss
                :when (not (@kept-fs-ids (:id fs)))]
          (sp/delete-entity storage :fn-slot (:id fs))
          (swap! journal conj {:op :delete :entity-type :fn-slot :row fs}))
        ;; Phase 4: bump positions on kept fn-slots that
        ;; moved, and create fresh fn-slots for new entries.
        ;; Position is unique per (fn-id, position) so we
        ;; never re-use a position already on a row we kept.
        (doseq [[idx a] (map-indexed vector assignments)]
          (cond
            (:reuse? a)
            (let [old-fs (first (filter #(= (:id %) (:fn-slot-id a)) current-fss))]
              (when (and old-fs (not= (:position old-fs) idx))
                ;; Two-step shuffle: delete + re-create with new
                ;; position. The journal records both legs so a
                ;; later failure can rewind to the pre-update row.
                (sp/delete-entity storage :fn-slot (:id old-fs))
                (swap! journal conj {:op :delete :entity-type :fn-slot :row old-fs})
                (let [new-row (assoc old-fs :position idx)]
                  (sp/create-entity storage :fn-slot new-row)
                  (swap! journal conj {:op :create :entity-type :fn-slot :id (:id new-row)}))))

            :else
            (do
              (sp/create-entity storage :fn-slot
                                {:id (:fn-slot-id a)
                                 :fn-id fn-id
                                 :slot-id (:slot-id a)
                                 :position idx})
              (swap! journal conj {:op :create :entity-type :fn-slot :id (:fn-slot-id a)}))))
        ;; Phase 5: optional rename / re-description of the fn-row.
        (when (or (and nm (not= nm (:name existing-fn)))
                  has-description?)
          (let [patch (cond-> {}
                        (and nm (not= nm (:name existing-fn)))
                        (assoc :name nm)
                        has-description?
                        (assoc :description desc))]
            (sp/update-entity storage :fn fn-id patch)))
        ;; The compound write happened through `sp/*-entity` —
        ;; bypassing the defbase wrappers that normally call
        ;; `invalidate!`. Without this nudge the next read of
        ;; `/api/graph/entities` would return the cached pre-
        ;; update graph and the editor would see no change.
        (invalidate! ctx storage :fn-slot {:fn-id fn-id})
        {:ok true :id (str fn-id) :name (or nm (:name existing-fn))})
      (catch clojure.lang.ExceptionInfo e
        (cleanup)
        {:ok false :error (Throwable/.getMessage e) :data (ex-data e)})
      (catch Exception e
        (cleanup)
        {:ok false :error (str (Throwable/.getMessage e))}))))


;; === Form Parsing ===
;;
;; All parse-*-from-form impls are permissive — fields are only
;; assoc'd when the key is actually present in the form. That way
;; both create (full form) and update (partial form, e.g.
;; description-only) flow through the same code without partial
;; updates blanking the unsent fields. Empty strings are kept (so
;; a submitted-empty `description=` clears the field rather than
;; leaving the old value).

(defn parse-fn-from-form
  [form-data ctx]
  (let [storage (request/require-storage ctx)]
    (cond-> {}
      (contains? form-data :name)
      (assoc :name (str (:name form-data)))
      (not (str/blank? (:parent-id form-data)))
      (assoc :parent-id (java.util.UUID/fromString (:parent-id form-data)))
      ;; namespace-id follows the empty-as-clear convention so a user
      ;; can move a fn back to the unnamespaced root via the editor.
      (contains? form-data :namespace-id)
      (assoc :namespace-id (when-not (str/blank? (:namespace-id form-data))
                             (java.util.UUID/fromString (:namespace-id form-data))))
      (contains? form-data :description)
      (assoc :description (:description form-data))
      ;; `return-type` form field accepts either a known type-row's
      ;; name (`"ring-response-shape"`) or its UUID. Resolves via
      ;; storage; `nil` reaches the create path which rejects since
      ;; the FK won't validate against a non-existent fn-id.
      (contains? form-data :return-type)
      (assoc :return-type-fn-id
             (tc/resolve-type-fn-id storage (:return-type form-data)))
      ;; `parent-ids` is the multi-valued ref-many field. Form encoding
      ;; reserves form-keys to single values, so the list comes in as a
      ;; comma-separated UUID string. Empty clears (base-fn).
      (contains? form-data :parent-ids)
      (assoc :parent-ids
             (let [v (:parent-ids form-data)]
               (if (str/blank? v)
                 []
                 (mapv #(java.util.UUID/fromString (str/trim %))
                       (str/split v #",")))))
      ;; Type-row creation fields. Accept either UUID or named-type
      ;; ref for `base-fn-id` / `element-fn-id`; `constraint` arrives
      ;; as a JSON-encoded vector (e.g. `["union","null","text"]`).
      ;; Parsed back into a Clojure vector with keywordised heads /
      ;; primitives — same shape the loader / type-checker expect.
      (contains? form-data :base-fn-id)
      (assoc :base-fn-id
             (when-not (str/blank? (:base-fn-id form-data))
               (tc/resolve-type-fn-id storage (:base-fn-id form-data))))
      (contains? form-data :element-fn-id)
      (assoc :element-fn-id
             (when-not (str/blank? (:element-fn-id form-data))
               (tc/resolve-type-fn-id storage (:element-fn-id form-data))))
      ;; `expects-effects` — authored effect-set contract. Storage
      ;; holds nil (= no contract) or a JSONB array of bare effect
      ;; names (e.g. `["db" "io"]`). Form field arrives as
      ;; comma-separated bare names; a literal "[]" or "null" lets
      ;; the user pin "no contract" / "explicit no-effects" distinct
      ;; from "unset" (cleared field = unset / nil).
      (contains? form-data :expects-effects)
      (assoc :expects-effects
             (let [raw (str (:expects-effects form-data))]
               (cond
                 (or (str/blank? raw) (= raw "null")) nil
                 (= raw "[]")                         []
                 :else
                 (vec (->> (str/split raw #",")
                           (map str/trim)
                           (remove str/blank?)
                           (map #(str/replace-first % #"^:" "")))))))
      (contains? form-data :constraint)
      (assoc :constraint
             (let [raw (:constraint form-data)]
               (when-not (str/blank? raw)
                 ;; JSON arrays / strings re-keywordised: `:union`,
                 ;; `:variant`, `:and`, `:or`, `:>=` etc. live on the
                 ;; Clojure side as keywords, with type-name members
                 ;; (`"null"` `"int"`) also coerced to keywords.
                 (let [parsed (try (json/parse-string raw)
                                   (catch Exception _ raw))]
                   (letfn [(re-kw
                             [x]
                             (cond
                               (and (string? x)
                                    (or (str/starts-with? x ":")
                                        ;; Alpha-leading identifiers (`union`,
                                        ;; `int`, `matches`, etc.) and bare
                                        ;; comparison operators (`>`, `>=`,
                                        ;; `<`, `<=`, `=`, `!=`) — without
                                        ;; the second branch any constraint
                                        ;; whose head is a non-alphanumeric
                                        ;; op would stay as a string and
                                        ;; downstream contains? checks (which
                                        ;; key on `:>` keywords) would fail.
                                        (re-matches #"[a-zA-Z][a-zA-Z0-9_-]*" x)
                                        (re-matches #"[!<>=]+" x)))
                               (keyword (str/replace-first x #"^:" ""))
                               (or (vector? x) (sequential? x)) (mapv re-kw x)
                               :else x))]
                     (re-kw parsed)))))))))


(defn parse-ns-from-form
  [form-data]
  (cond-> {}
    (contains? form-data :name)
    (assoc :name (str (:name form-data)))
    (not (str/blank? (:parent-id form-data)))
    (assoc :parent-id (java.util.UUID/fromString (:parent-id form-data)))
    (contains? form-data :description)
    (assoc :description (:description form-data))))


(defn parse-slot-from-form
  "Form-data → slot-row fields. `:type-fn-id` is the slot's declared
   type (a fn-id pointing at a primitive / refinement / record). All
   slot fields except `:id` (auto-generated) and `:name` are optional
   on update; on create, `:name` and `:type-fn-id` are typically both
   present."
  [form-data]
  (cond-> {}
    (contains? form-data :name)
    (assoc :name (str (:name form-data)))
    (contains? form-data :type-fn-id)
    (assoc :type-fn-id (request/parse-uuid-or-clear (:type-fn-id form-data)))
    (contains? form-data :description)
    (assoc :description (:description form-data))
    (contains? form-data :required)
    (assoc :required (= "true" (:required form-data)))))


(defn parse-fn-slot-from-form
  "Form-data → fn-slot junction row fields. Both refs are required on
   create; `:position` is optional (defaults to 0)."
  [form-data]
  (cond-> {}
    (contains? form-data :fn-id)
    (assoc :fn-id (request/parse-uuid-or-clear (:fn-id form-data)))
    (contains? form-data :slot-id)
    (assoc :slot-id (request/parse-uuid-or-clear (:slot-id form-data)))
    (contains? form-data :position)
    (assoc :position (Integer/parseInt (:position form-data)))))


(defn parse-binding-from-form
  "Form-data → binding-row fields. Empty-as-clear convention applies
   to every nullable slot (`:value`, `:ref-fn-id`,
   `:type-override-fn-id`, `:description`) so an editor can drop an
   override by sending an empty form value. `:fn-id` and `:slot-id`
   are required for create; treated as updates of the existing row
   for PUT.

   `:rename-to` is intentionally NOT a binding field anymore — Phase
   6c moved rename info onto a dedicated renamed-view slot row
   (`slot.source-slot-id` FK link). The UI wire format still uses a
   `rename-to` form field as a single API entrypoint:
   `process-update-entity` drops it from the binding write and
   forwards it to `ensure-rename-slot!`, which creates / updates the
   renamed-view slot directly. (The storage move is transparent to
   the editor.)"
  [form-data]
  (cond-> {}
    (contains? form-data :fn-id)
    (assoc :fn-id (request/parse-uuid-or-clear (:fn-id form-data)))
    (contains? form-data :slot-id)
    (assoc :slot-id (request/parse-uuid-or-clear (:slot-id form-data)))
    (contains? form-data :value)
    (assoc :value (when-not (str/blank? (:value form-data))
                    (json/parse-string (:value form-data) true)))
    (contains? form-data :ref-fn-id)
    (assoc :ref-fn-id (request/parse-uuid-or-clear (:ref-fn-id form-data)))
    (contains? form-data :override-kind)
    (assoc :override-kind (when-not (str/blank? (:override-kind form-data))
                            (keyword (:override-kind form-data))))
    (contains? form-data :type-override-fn-id)
    (assoc :type-override-fn-id (request/parse-uuid-or-clear (:type-override-fn-id form-data)))
    (contains? form-data :description)
    (assoc :description (:description form-data))
    (contains? form-data :terminal)
    (assoc :terminal (= "true" (:terminal form-data)))
    (contains? form-data :list-append)
    (assoc :list-append (= "true" (:list-append form-data)))
    (contains? form-data :list-closed)
    (assoc :list-closed (= "true" (:list-closed form-data)))
    (contains? form-data :required)
    (assoc :required (when-not (str/blank? (:required form-data))
                       (= "true" (:required form-data))))))


(defn parse-binding-list-item-from-form
  "Form-data → binding-list-item row fields. `:binding-id` and
   `:position` are required for create; the value is either a literal
   `:value` (JSON-decoded) or a `:ref-fn-id`, but not both."
  [form-data]
  (cond-> {}
    (contains? form-data :binding-id)
    (assoc :binding-id (request/parse-uuid-or-clear (:binding-id form-data)))
    (contains? form-data :position)
    (assoc :position (Integer/parseInt (:position form-data)))
    (contains? form-data :value)
    (assoc :value (when-not (str/blank? (:value form-data))
                    (json/parse-string (:value form-data) true)))
    (contains? form-data :ref-fn-id)
    (assoc :ref-fn-id (request/parse-uuid-or-clear (:ref-fn-id form-data)))
    (contains? form-data :literal)
    (assoc :literal (= "true" (:literal form-data)))))


;; === Rename helper ===

(defn ensure-rename-slot!
  "Phase 6b — keep UI rename atomically consistent with EDN parser
   output. When a binding write carries a non-blank `:rename-to=X`
   AND the binding's owner fn is composed (parent-fn-ids non-empty),
   the EDN parser would have ALSO emitted an own-slot row + fn-slot
   junction so descendants binding `X` find a slot identity to
   target. UI today writes only the binding row; this helper fills
   in the missing pair.

   Args: `fn-id` (binding's owner fn), `source-slot-id` (the slot
   the binding targets — becomes the new slot's :source-slot-id
   FK), `rename-to` (new name).

   Idempotent: walks the deterministic UUIDv5 scheme for slot-id
   and fn-slot-id, no-ops when the rows already exist (e.g. on
   repeat PUT). Returns nil; throws on unexpected storage failures
   so the caller can surface to the user."
  [storage fn-id source-slot-id rename-to]
  (when (and fn-id source-slot-id rename-to (not (str/blank? rename-to)))
    (let [fn-row (sp/read-entity storage :fn fn-id)
          parent-ids (:parent-ids fn-row)
          source-slot (sp/read-entity storage :slot source-slot-id)]
      (when (and (seq parent-ids) source-slot)
        (let [new-slot-id (records/slot-id fn-id rename-to)
              new-fn-slot-id (records/fn-slot-id fn-id new-slot-id)
              ;; Reuse source slot's type-fn-id so the renamed view
              ;; has the same type — UI doesn't expose type-override
              ;; in the rename popover. Type narrowing remains a
              ;; separate edit (the type chip).
              slot-row {:id new-slot-id
                        :name rename-to
                        :type-fn-id (:type-fn-id source-slot)
                        :required (or (:required source-slot) false)
                        :description nil
                        :source-slot-id source-slot-id}]
          (when-not (sp/read-entity storage :slot new-slot-id)
            (sp/create-entity storage :slot slot-row))
          (when-not (sp/read-entity storage :fn-slot new-fn-slot-id)
            (sp/create-entity storage :fn-slot
                              {:id new-fn-slot-id
                               :fn-id fn-id
                               :slot-id new-slot-id
                               :position 0})))))))


;; === Action Handlers ===

(defn parse-create-request
  "Stage 1 of create. Pull the entity-type from the URI, form-decode
   the body, and route the form-data through the per-type parser.
   Returns `{:entity-type :type-str :form-data :entity-data
   :parse-error}` — `:parse-error` (a string) is set when the per-type
   parser throws (bad UUID / malformed JSON) so the handler renders a
   400 rather than a 500. `:body` may arrive as a slurped string
   (internal-request path) or a raw httpkit InputStream (reitit
   passthrough); both are coerced."
  [request ctx]
  (let [{:keys [type-str entity-type]} (request/extract-entity-params request)
        raw-body (:body request)
        body-str (cond
                   (string? raw-body) raw-body
                   (instance? java.io.InputStream raw-body) (clojure.core/slurp raw-body)
                   :else nil)
        form-data (when body-str
                    (into {} (map (fn [[k v]] [(keyword k) v])
                                  (request/parse-query-string body-str))))
        base {:entity-type entity-type :type-str type-str :form-data form-data}]
    (if-not (and entity-type form-data)
      base
      (try
        (assoc base :entity-data
               (case type-str
                 "fn" (parse-fn-from-form form-data ctx)
                 "ns" (parse-ns-from-form form-data)
                 "slot" (parse-slot-from-form form-data)
                 "fn-slot" (parse-fn-slot-from-form form-data)
                 "binding" (parse-binding-from-form form-data)
                 "binding-list-item" (parse-binding-list-item-from-form form-data)
                 nil))
        ;; Catch EVERYTHING, not just ExceptionInfo — the parsers throw
        ;; IllegalArgumentException on a bad UUID, JsonParseException on
        ;; bad `:value` JSON; a malformed request must be a 400.
        (catch Exception e
          (assoc base :parse-error (Throwable/.getMessage e)))))))


(defn validate-create
  "Stage 2 of create. Run every write-time guard against a parsed
   create request; returns the first rejection `{:reason …}`, or nil
   when the create may proceed."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        {:keys [entity-type type-str form-data entity-data parse-error]} parsed]
    (cond
      (not (and entity-type form-data))
      {:reason (str "Invalid request — type=" (pr-str type-str)
                    " entity-type=" (pr-str entity-type)
                    " form-data=" (pr-str form-data))}

      parse-error {:reason parse-error}

      :else
      (or
        ;; Phase 6e — a direct `POST /api/entities/fn-slot` may not
        ;; attach an own-slot to a composed fn (parent-fn-ids non-empty)
        ;; unless the slot renames an inherited one (`:source-slot-id`
        ;; set). Internal flows write via `sp/create-entity` directly
        ;; and bypass this; only HTTP CRUD requests land here.
        (when (and entity-data (= type-str "fn-slot"))
          (let [fn-row (sp/read-entity storage :fn (:fn-id entity-data))
                slot-row (sp/read-entity storage :slot (:slot-id entity-data))]
            (when (and fn-row slot-row
                       (seq (:parent-ids fn-row))
                       (nil? (:source-slot-id slot-row)))
              {:reason
               (str "Composed fn " (pr-str (:name fn-row))
                    " can only own slots that rename an inherited slot "
                    "(set :source-slot-id). To add a new arg create a "
                    "new fn-def with this one as parent.")})))
        ;; Cycle / MI / :terminal / :list-closed write-time guards. The
        ;; editor runs `wouldCycle` + `miCollisionCheck` client-side;
        ;; the rest are server-only — non-editor API consumers need
        ;; server enforcement too.
        (when entity-data
          (validation/write-rej storage entity-type entity-data))
        ;; Save-time type-check for a binding's value / ref.
        (when (and entity-data (= type-str "binding"))
          (tc/type-check-binding-direct! storage entity-data nil))))))


(defn apply-create
  "Stage 3 of create — reached only after `validate-create` passes.
   Create the entity and return the partial Ring response. Handles
   unique-violation humanisation, the Phase-6c rename-slot side-effect,
   and the post-create whole-fn type-check — which rolls the new row
   back when the OWNING fn-def's aggregate check fails."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        {:keys [entity-type type-str form-data entity-data]} parsed
        humanise
        (fn [e]
          ;; Postgres unique-violation messages read like internal log
          ;; lines; render the user-facing form.
          (let [msg (or (Throwable/.getMessage e) "")
                nm (some-> entity-data :name)]
            (cond
              (and (re-find #"(?i)duplicate key" msg) nm)
              (str (name entity-type) " " (pr-str nm)
                   " already exists here — pick a different name")
              (re-find #"(?i)duplicate key" msg)
              (str (name entity-type) " already exists with these fields")
              :else (or (some-> (ex-data e) :reason) msg
                        (str "Failed to create " type-str)))))
        create-result (try {:created (sp/create-entity storage entity-type entity-data)}
                           (catch Exception e
                             (log/error e "create-entity failed for"
                                        entity-type entity-data)
                             {:error (humanise e)}))]
    ;; Phase 6c — forward a form `:rename-to` to the dedicated
    ;; renamed-view slot. A failure here is logged, not fatal — the
    ;; binding is still useful without the rename slot.
    (when (and (:created create-result)
               (= type-str "binding")
               (contains? form-data :rename-to))
      (try (ensure-rename-slot! storage
                                (:fn-id entity-data)
                                (:slot-id entity-data)
                                (when-not (str/blank? (:rename-to form-data))
                                  (str (:rename-to form-data))))
           (catch Exception e
             (log/error e "ensure-rename-slot! failed"))))
    ;; Post-create whole-fn type-check for binding mutations. A binding
    ;; can be individually-valid yet break the OWNING fn-def's aggregate
    ;; check; on failure delete the just-created row so DB state stays
    ;; consistent.
    (let [post-rej (when (and (:created create-result)
                              (#{"binding" "binding-list-item"} type-str))
                     (when-let [fn-id (cond
                                        (= type-str "binding")
                                        (:fn-id entity-data)
                                        (= type-str "binding-list-item")
                                        (some-> (:binding-id entity-data)
                                                (#(sp/read-entity storage :binding %))
                                                :fn-id))]
                       (when-let [rej (tc/type-check-fn-after-mutation! storage fn-id)]
                         (try (sp/delete-entity storage entity-type
                                                (:created create-result))
                              (catch Exception _))
                         rej)))]
      (cond
        post-rej {:status 400
                  :body (str "<p class=\"error\">" (:reason post-rej) "</p>")}
        (:created create-result)
        (do (invalidate! ctx storage entity-type
                         (assoc entity-data :id (:created create-result)))
            {:status 200 :headers {"HX-Trigger" "entityCreated"}
             :body "<p>Entity created successfully</p>"})
        :else {:status 400
               :body (str "<p class=\"error\">"
                          (or (:error create-result)
                              (str "Failed to create " type-str))
                          "</p>")}))))


(defn parse-update-request
  "Stage 1 of update — like `parse-create-request`, plus the `:id`
   URI segment and a pre-read of the existing row. The pre-read lets
   validation build the merged post-write picture and lets the
   response name the affected fn even when form-data omits the
   immutable FK fields (binding / fn-slot updates ship only the
   changed fields)."
  [request ctx]
  (let [storage (request/require-storage ctx)
        {:keys [type-str id-str entity-type]} (request/extract-entity-params request)
        id-uuid (try (java.util.UUID/fromString id-str) (catch Exception _ nil))
        raw-body (:body request)
        body-str (cond
                   (string? raw-body) raw-body
                   (instance? java.io.InputStream raw-body) (clojure.core/slurp raw-body)
                   :else nil)
        form-data (when body-str
                    (into {} (map (fn [[k v]] [(keyword k) v])
                                  (request/parse-query-string body-str))))
        pre-existing (when (and entity-type id-uuid)
                       (try (sp/read-entity storage entity-type id-uuid)
                            (catch Exception _ nil)))
        base {:entity-type entity-type :type-str type-str :id-str id-str
              :id-uuid id-uuid :form-data form-data :pre-existing pre-existing}]
    (if-not (and entity-type id-str form-data)
      base
      (try
        (assoc base :entity-data
               (case type-str
                 "fn" (parse-fn-from-form form-data ctx)
                 "ns" (parse-ns-from-form form-data)
                 "slot" (parse-slot-from-form form-data)
                 "fn-slot" (parse-fn-slot-from-form form-data)
                 "binding" (parse-binding-from-form form-data)
                 "binding-list-item" (parse-binding-list-item-from-form form-data)
                 nil))
        ;; A bad UUID / malformed JSON must be a 400, not a 500.
        (catch Exception e
          (assoc base :parse-error (Throwable/.getMessage e)))))))


(defn validate-update
  "Stage 2 of update. Same write-time guards as create, run against
   the merged post-write view (existing FK fields + the changed
   fields + the explicit id). Returns the first rejection
   `{:reason …}` or nil."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        {:keys [entity-type type-str id-str id-uuid form-data
                entity-data parse-error pre-existing]} parsed]
    (cond
      (not (and entity-type id-str form-data))
      {:reason "Invalid update request"}

      parse-error {:reason parse-error}

      :else
      (let [merged-data (merge pre-existing entity-data {:id id-uuid})]
        (or
          (when entity-data
            (validation/write-rej storage entity-type merged-data))
          (when (and entity-data (= type-str "binding") id-uuid)
            (tc/type-check-binding-direct! storage entity-data id-uuid)))))))


(defn apply-update
  "Stage 3 of update — reached only after `validate-update` passes.
   Update the entity and return the partial Ring response, including
   the Phase-6c rename-slot side-effect (UPDATE doesn't carry
   fn-id / slot-id — immutable post binding-create — so the existing
   binding row is read for them)."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        {:keys [entity-type type-str id-str id-uuid form-data
                entity-data pre-existing]} parsed
        updated (try (sp/update-entity storage entity-type id-uuid entity-data)
                     (catch Exception e
                       (log/error e "update-entity failed for"
                                  entity-type id-str entity-data)
                       nil))]
    (when (and updated (= type-str "binding") id-uuid
               (contains? form-data :rename-to))
      (try
        (when-let [existing (sp/read-entity storage :binding id-uuid)]
          (ensure-rename-slot! storage
                               (:fn-id existing)
                               (:slot-id existing)
                               (when-not (str/blank? (:rename-to form-data))
                                 (str (:rename-to form-data)))))
        (catch Exception e
          (log/error e "ensure-rename-slot! failed"))))
    (if updated
      (do (invalidate! ctx storage entity-type
                       (merge pre-existing entity-data {:id id-uuid}))
          {:status 200 :headers {"HX-Trigger" "entityUpdated"}
           :body "<p>Entity updated successfully</p>"})
      {:status 400 :body "<p class=\"error\">Failed to update entity</p>"})))


(defn ns-non-empty-reason
  "Returns a human-readable reason if `ns-id` still has nested
   namespaces or fns living under it; nil if empty (and therefore
   safe to delete)."
  [storage ns-id]
  (let [child-ns (count (sp/query-entities storage :ns {:parent-id ns-id}))
        child-fns (count (sp/query-entities storage :fn {:namespace-id ns-id}))]
    (when (or (pos? child-ns) (pos? child-fns))
      (str "Namespace contains "
           (when (pos? child-ns) (str child-ns " sub-namespace" (when (> child-ns 1) "s")))
           (when (and (pos? child-ns) (pos? child-fns)) " and ")
           (when (pos? child-fns) (str child-fns " graph" (when (> child-fns 1) "s")))
           " — remove the contents first."))))


(defn fn-in-use-reason
  "Returns a human-readable reason if `fn-id` is referenced by another
   fn (as a parent, via a binding's `ref-fn-id`, or via a list-item's
   `ref-fn-id`); nil if unreferenced. Slot/binding model: bindings
   replace arg-rows for ref tracking, with list-items handling
   sequence-element refs."
  [storage fn-id]
  (let [used-as-parent (count (filter (fn [f]
                                        (and (not= (:id f) fn-id)
                                             (some #(= % fn-id) (:parent-ids f))))
                                      (sp/query-entities storage :fn {})))
        ref-bindings (count (sp/query-entities storage :binding {:ref-fn-id fn-id}))
        ref-items (count (sp/query-entities storage :binding-list-item {:ref-fn-id fn-id}))
        refs (+ ref-bindings ref-items)]
    (when (or (pos? used-as-parent) (pos? refs))
      (str "Graph is "
           (when (pos? used-as-parent) (str "a parent of " used-as-parent " other graph"
                                            (when (> used-as-parent 1) "s")))
           (when (and (pos? used-as-parent) (pos? refs)) " and ")
           (when (pos? refs) (str "referenced by " refs " binding" (when (> refs 1) "s")))
           " — remove the dependents first."))))


(defn process-delete-entity
  [request ctx]
  (let [storage (request/require-storage ctx)
        {:keys [entity-type id-str]} (request/extract-entity-params request)
        id (when id-str (try (java.util.UUID/fromString id-str)
                             (catch Exception _ nil)))]
    (cond
      (or (nil? entity-type) (nil? id))
      {:status 400 :body "<p class=\"error\">Invalid request</p>"}

      ;; Namespace delete — must be empty.
      (= entity-type :ns)
      (if-let [reason (ns-non-empty-reason storage id)]
        {:status 409 :body (str "<p class=\"error\">" reason "</p>")}
        (do (sp/delete-entity storage entity-type id)
            ;; ns rename / delete doesn't reach into closures; full
            ;; clear is overkill but `affected-fn-ids` returns nil
            ;; for `:ns` so `invalidate!` falls through to that path
            ;; today. Acceptable until we audit per-ns descendants.
            (invalidate! ctx storage entity-type {:id id})
            {:status 200 :headers {"HX-Trigger" "entityDeleted"} :body ""}))

      ;; Fn delete — must be unreferenced.
      (= entity-type :fn)
      (if-let [reason (fn-in-use-reason storage id)]
        {:status 409 :body (str "<p class=\"error\">" reason "</p>")}
        (do (sp/delete-entity storage entity-type id)
            (invalidate! ctx storage entity-type {:id id})
            {:status 200 :headers {"HX-Trigger" "entityDeleted"} :body ""}))

      ;; Other entity types (slot/fn-slot/binding/binding-list-item) —
      ;; no extra constraint. Pre-read so we still know the parent
      ;; fn-id after the row is gone (binding-list-item especially —
      ;; we'd otherwise lose the binding-id needed to derive fn-id).
      :else
      (let [snapshot (try (sp/read-entity storage entity-type id)
                          (catch Exception _ nil))]
        (sp/delete-entity storage entity-type id)
        (invalidate! ctx storage entity-type (or snapshot {:id id}))
        {:status 200 :headers {"HX-Trigger" "entityDeleted"} :body ""}))))


;; === Sequence operations =====================================================
;;
;; Slot/binding model: a sequence slot's items live in
;; `binding_list_item` rows ordered by `:position`. The binding row
;; for `(fn, sequence-slot)` carries `:list-append true` when items
;; extend a parent's items rather than replace them. Append/remove
;; operate on item rows directly — no linked-list pointers, just
;; positional indices.

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


(defn ensure-sequence-binding
  "Return the binding row for the fn's sequence slot, creating an
   empty `:list-append` binding if one doesn't exist yet. Used by
   `process-sequence-append` so the first append doesn't have to
   special-case the absent-binding path."
  [ctx fn-id]
  (let [b (find-sequence-binding ctx fn-id)]
    (cond
      (nil? b) nil
      (:synthetic b)
      (sp/create-entity (request/require-storage ctx) :binding
                        {:fn-id (:fn-id b) :slot-id (:slot-id b)
                         :list-append true})
      :else b)))


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


(defn process-sequence-append
  "POST /api/sequence/append/:fn-id
   Appends one item to the sequence binding of fn :fn-id."
  [request ctx]
  (let [storage (request/require-storage ctx)
        fn-id-str (or (get-in request [:path-params :fn-id])
                      (:fn-id-str (request/parse-uri-segments (:uri request))))
        fn-id (try (java.util.UUID/fromString fn-id-str) (catch Exception _ nil))
        raw-body (:body request)
        body-str (cond
                   (string? raw-body) raw-body
                   (instance? java.io.InputStream raw-body) (clojure.core/slurp raw-body)
                   :else nil)
        body (when body-str
               (try (json/parse-string body-str true) (catch Exception _ nil)))]
    (cond
      (nil? fn-id) {:status 400 :body "<p class=\"error\">Invalid fn-id</p>"}
      (nil? body)  {:status 400 :body "<p class=\"error\">JSON body required</p>"}
      :else
      (if-let [seq-binding (ensure-sequence-binding ctx fn-id)]
        (let [binding-id (:id seq-binding)
              ;; A new item's `:position` must clear the BASE
              ;; `binding_list_item` table, not just the resolved
              ;; view. A soft-deleted item leaves its base row (the
              ;; cross-branch identity) still holding `(binding_id,
              ;; position)`, and that row's UNIQUE index rejects a
              ;; colliding insert. The resolved view hides those
              ;; orphans — query the base storage so they count.
              base-storage (or (:base-storage storage) storage)
              used-pos (map :position
                            (concat
                              (sp/query-entities base-storage :binding-list-item
                                                 {:binding-id binding-id})
                              (sp/query-entities storage :binding-list-item
                                                 {:binding-id binding-id})))
              new-pos (inc (apply max -1 used-pos))
              payload (resolve-sequence-payload storage body)
              new-item (merge {:id (random-uuid)
                               :binding-id binding-id
                               :position new-pos}
                              payload)
              ;; Same write-time guards as the regular binding-list-
              ;; item create path: cycle through `:ref-fn-id`, plus
              ;; `:list-closed` enforcement so a sealed list can't
              ;; be extended via `/api/sequence/append`.
              pre-rej (validation/write-rej storage :binding-list-item new-item)]
          (if pre-rej
            {:status 400 :body (str "<p class=\"error\">" (:reason pre-rej) "</p>")}
            (do (sp/create-entity storage :binding-list-item new-item)
                ;; The fn that owns the binding (and thus gets a fresh
                ;; sequence-element bound into its closure) is the seed.
                ;; Skip the binding-id round-trip — fn-id is right here.
                (exec-ctx/invalidate-graph-cache! ctx #{fn-id})
                {:status 200
                 :headers {"Content-Type" "application/json"}
                 :body (json/generate-string {:item-id (:id new-item)
                                              :position new-pos})})))
        {:status 404 :body "<p class=\"error\">Fn has no sequence slot</p>"}))))


(defn process-sequence-remove
  "DELETE /api/sequence/item/:item-id
   Removes one binding-list-item."
  [request ctx]
  (let [storage (request/require-storage ctx)
        item-id-str (or (get-in request [:path-params :item-id])
                        (:item-id-str (request/parse-uri-segments (:uri request))))
        item-id (try (java.util.UUID/fromString item-id-str) (catch Exception _ nil))]
    (cond
      (nil? item-id) {:status 400 :body "<p class=\"error\">Invalid item-id</p>"}
      :else
      (let [item (sp/read-entity storage :binding-list-item item-id)]
        (if (nil? item)
          {:status 404 :body "<p class=\"error\">Item not found</p>"}
          (do (sp/delete-entity storage :binding-list-item item-id)
              ;; Item carries `:binding-id`; `affected-fn-ids` follows
              ;; that to the binding's `:fn-id` for the seed.
              (invalidate! ctx storage :binding-list-item item)
              {:status 200 :body ""}))))))


(defn process-sequence-update
  "PUT /api/sequence/item/:item-id
   Replaces the value/ref of one existing binding-list-item."
  [request ctx]
  (let [storage (request/require-storage ctx)
        item-id-str (or (get-in request [:path-params :item-id])
                        (:item-id-str (request/parse-uri-segments (:uri request))))
        item-id (try (java.util.UUID/fromString item-id-str) (catch Exception _ nil))
        raw-body (:body request)
        body-str (cond
                   (string? raw-body) raw-body
                   (instance? java.io.InputStream raw-body) (clojure.core/slurp raw-body)
                   :else nil)
        body (when body-str
               (try (json/parse-string body-str true) (catch Exception _ nil)))]
    (cond
      (nil? item-id) {:status 400 :body "<p class=\"error\">Invalid item-id</p>"}
      (nil? body)    {:status 400 :body "<p class=\"error\">JSON body required</p>"}
      :else
      (let [item (sp/read-entity storage :binding-list-item item-id)]
        (if (nil? item)
          {:status 404 :body "<p class=\"error\">Item not found</p>"}
          (let [payload (resolve-sequence-payload storage body)
                changes (merge {:value nil :ref-fn-id nil :literal nil} payload)
                pre-rej (validation/write-rej storage :binding-list-item
                                              (merge item changes {:id item-id}))]
            (if pre-rej
              {:status 400 :body (str "<p class=\"error\">" (:reason pre-rej) "</p>")}
              (do (sp/update-entity storage :binding-list-item item-id changes)
                  (invalidate! ctx storage :binding-list-item item)
                  {:status 200 :body ""}))))))))


;; === Tighten fn-typed binding effects =====================================
;;
;; Phase 8 carved out a 4-arity `[:fn args ret #{eff-set}]` form so a
;; slot whose callable should stay pure (or only do certain effects)
;; can REJECT impure callbacks at sync time. There was no UI to set
;; the constraint, so it lived only in EDN-side declarations. This
;; endpoint exposes it: the editor sends `{effects: ["io" "db"]}`,
;; the server constructs the 4-arity constraint, dedupes via
;; deterministic `anonymous-fn-id` (same shape collapses to one row),
;; and writes the binding's `:type-override-fn-id`.
;;
;; Subtype safety: the new constraint must be a SUBTYPE of the
;; current effective fn-type. Tightening from a 3-arity (no eff
;; constraint = any effects allowed) to a 4-arity is always a
;; narrowing; tightening across two 4-arities requires the new
;; eff-set ⊆ old. `subtype?` enforces this and we surface the
;; rejection as a 400.

(defn commit-tighten!
  "Helper for `tighten-effects-impl!` — performs the actual write
   (anon fn-row create + binding update) once the safety checks have
   passed. Pulled out so the impl's let-and-cond chain stays
   readable."
  [storage binding-id b new-c _effects-vec]
  (let [hash-hex (records/digest-hex "SHA-1" (pr-str new-c))
        new-id (records/anonymous-fn-id hash-hex)
        pre-override (:type-override-fn-id b)]
    ;; Find or create. Storage upsert is the natural fit — same
    ;; id ⇒ same row, no orphan duplicates.
    (when-not (sp/read-entity storage :fn new-id)
      (sp/create-entity storage :fn
                        {:id new-id
                         :name nil
                         :namespace-id nil
                         :parent-ids []
                         :impl-hash nil
                         :base-fn-id nil
                         :element-fn-id nil
                         :return-type-fn-id nil
                         :anonymous-hash hash-hex
                         :constraint new-c}))
    (sp/update-entity storage :binding binding-id
                      {:type-override-fn-id new-id})
    ;; Aggregate type-check on the owning fn. The bound-callable
    ;; effect check above is the primary guard; this catches
    ;; whatever else `check-fn-def!` evaluates (return-type
    ;; subtype, deeper structural unification, etc.). Roll back
    ;; on rejection so the binding doesn't end up in a broken
    ;; state the user has to debug.
    (if-let [post-rej (tc/type-check-fn-after-mutation! storage (:fn-id b))]
      (do (sp/update-entity storage :binding binding-id
                            {:type-override-fn-id pre-override})
          {:status 400
           :reason (str "Tightening rejected by post-write "
                        "type-check: " (:reason post-rej))})
      {:status 200
       :result {:type-override-fn-id new-id
                :constraint new-c
                :fn-id (:fn-id b)}})))


(defn tighten-fn-type-impl!
  "Compute a narrower fn-type constraint by selectively replacing
   `args`, `ret`, or `effects` from the current effective type.
   `delta` is `{:args {…} :ret T :effects [\"io\" …]}` — any subset.
   Defaults preserve the current value: 3-arity gets a 4th element
   only when `:effects` is supplied, and `:args` / `:ret` keep
   whatever the current shape carries when omitted.

   Subtype-checks the new constraint against the current; rejects
   widenings. Then runs the bound-callable safety check (effects
   only — narrower args / ret don't introduce new escape paths the
   way effects do, and the post-write `check-fn-def!` catches deeper
   structural mismatches)."
  [storage binding-id delta]
  (let [b (sp/read-entity storage :binding binding-id)]
    (cond
      (nil? b)
      {:status 404 :reason "Binding not found"}

      :else
      (let [slot (sp/read-entity storage :slot (:slot-id b))
            cur-tfn-id (or (:type-override-fn-id b) (:type-fn-id slot))
            cur-tfn (when cur-tfn-id (sp/read-entity storage :fn cur-tfn-id))
            cur-c (:constraint cur-tfn)]
        (cond
          (or (not (vector? cur-c)) (not= :fn (first cur-c)))
          {:status 400
           :reason (str "Slot's effective type is not an fn-type ("
                        (pr-str cur-c) "); can't tighten.")}

          :else
          (let [cur-args (or (nth cur-c 1) {})
                cur-ret (nth cur-c 2)
                cur-eff (when (= 4 (count cur-c)) (nth cur-c 3))
                {:keys [args ret effects]} delta
                ;; Args delta is a per-name override map. Merge so
                ;; unmentioned arg names keep their current type.
                new-args (if (map? args)
                           (merge cur-args (types-api/json->type args))
                           cur-args)
                new-ret (if (some? ret)
                          (types-api/json->type ret)
                          cur-ret)
                new-eff (cond
                          (some? effects) (into #{} (map keyword) effects)
                          cur-eff         cur-eff
                          :else           nil)
                new-c (cond-> [:fn new-args new-ret] new-eff (conj new-eff))
                ok? (types/subtype? new-c cur-c)]
            (if-not ok?
              {:status 400
               :reason (str "Proposed type " (pr-str new-c)
                            " is not a narrowing of " (pr-str cur-c)
                            " — every component (args / ret / effects)"
                            " must be a subtype of the current value.")}
              ;; Bound-callable effect check — same as the
              ;; effect-only path. Args / ret narrowings don't
              ;; introduce new escape paths beyond what
              ;; `check-fn-def!` covers.
              (let [eff-set (or new-eff #{})
                    ref-fn-id (:ref-fn-id b)
                    ref-row (when ref-fn-id (sp/read-entity storage :fn ref-fn-id))
                    ref-info (when-let [n (:name ref-row)]
                               (registry/rich-type-of (keyword n)))
                    ref-effects (or (:effects ref-info) #{})
                    escapes (when (and (some? new-eff) (seq ref-effects))
                              (clojure.set/difference (set ref-effects) eff-set))]
                (if (seq escapes)
                  {:status 400
                   :reason (str "Bound fn `" (:name ref-row) "`"
                                " produces effects " (vec (sort escapes))
                                " that the requested constraint "
                                (vec (sort eff-set))
                                " forbids. Either widen the effect set"
                                " or rebind to a fn with effects ⊆ "
                                (vec (sort eff-set)) ".")}
                  (commit-tighten! storage binding-id b new-c nil))))))))))


(defn tighten-effects-impl!
  "Backwards-compatible thin wrapper — `tighten-fn-type-impl!` with
   only the `:effects` delta filled in. Tests load this symbol
   directly; production callers go through the form-driven defbase."
  [storage binding-id effects-vec]
  (tighten-fn-type-impl! storage binding-id {:effects effects-vec}))


(defn parse-tighten-request
  "Parse a `POST /api/bindings/:binding-id/tighten-fn-effects` request
   into the shape the validation predicates + `apply-tighten` consume:
   `{:binding-id <uuid|nil> :effects-val :args-val :ret-val :delta}`.
   No validation here — the guards live in the `:cond` graph of the
   `:process-tighten-binding-effects` fn-def. `effects` is expected to
   be a JSON array, `args` a map, `ret` any type-form; the tighten
   impl defaults each omitted component from the current constraint."
  [request]
  (let [binding-id-str (or (get-in request [:path-params :binding-id])
                           (:binding-id-str (request/parse-uri-segments (:uri request))))
        binding-id (try (java.util.UUID/fromString binding-id-str)
                        (catch Exception _ nil))
        body (request/read-json-body request)
        effects-val (:effects body)
        args-val (:args body)
        ret-val (:ret body)
        delta (cond-> {}
                (some? effects-val) (assoc :effects effects-val)
                (some? args-val)    (assoc :args args-val)
                (some? ret-val)     (assoc :ret ret-val))]
    {:binding-id binding-id
     :effects-val effects-val
     :args-val args-val
     :ret-val ret-val
     :delta delta}))


(defn apply-tighten
  "Success branch of tighten-fn-effects — reached only after the
   `:cond` validation clauses pass. Narrows the fn-typed binding's
   effective type, invalidates caches, and returns the partial Ring
   response (`{:status :headers :body}`)."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        {:keys [status reason result]}
        (tighten-fn-type-impl! storage (:binding-id parsed) (:delta parsed))]
    (if (= 200 status)
      (do (invalidate! ctx storage :binding {:fn-id (:fn-id result)})
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string result)})
      {:status status
       :body (str "<p class=\"error\">" reason "</p>")})))
