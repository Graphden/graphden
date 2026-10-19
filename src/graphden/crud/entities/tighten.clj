(ns graphden.crud.entities.tighten
  "Binding-local callable constraints, including nested map/list positions.
   Complete type proposals and legacy args/return/effects deltas use the same
   narrowing guard, canonical identity materialization and serialized writer.
   Shared type rows and slot identities remain immutable."
  (:require
    [clojure.set]
    [graphden.crud.package-guard :as pkg-guard]
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as tc]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records.types :as record-types]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.core :as types]
    [graphden.types.expansion :as expansion]
    [graphden.versioning.branch-local :as branch-local]
    [graphden.versioning.storage.core :as versioned]
    [graphden.versioning.storage.resolution :as resolution]))


(defn- materialize-constraint!
  "Materialize the new contract and nested callable rows using the parser's
   canonical identities. Existing shared rows retain their identities."
  [storage new-c]
  (let [rows (vec (distinct (record-types/inline-fn-type-rows-from-form new-c)))
        root (or (first (filter #(= new-c (:constraint %)) rows))
                 (throw (ex-info "Unsupported callable constraint row" {:type :types/unsupported-constraint-row})))
        root-id (or (some->> (sp/query-entities storage :fn {:anonymous-hash (:anonymous-hash root)})
                             (filter #(= (tenancy/current-org) (:org-id %))) first :id)
                    (:id root))
        rows (mapv #(cond-> % (= (:id root) (:id %)) (assoc :id root-id)) rows)
        existing (into #{} (map :id) (sp/query-entities storage :fn {:id (mapv :id rows)}))
        missing (filterv #(not (contains? existing (:id %))) rows)]
    (doseq [row missing] (sp/create-entity storage :fn (dissoc row :kind)))
    {:id root-id :created (mapv :id missing)}))


(defn commit-tighten!
  "Write a binding-local contract with canonical nested identities. A secret
   rejection restores the binding and removes only this call's new type rows."
  [ctx storage binding-id b new-c _effects-vec]
  (let [{new-id :id created :created} (materialize-constraint! storage new-c)
        pre-override (:type-override-fn-id b)]
    (sp/update-entity storage :binding binding-id
                      {:type-override-fn-id new-id})
    ;; Aggregate type-check on the owning fn. The bound-callable
    ;; effect check above is the primary guard; this catches
    ;; whatever else `check-fn-def!` evaluates (return-type
    ;; subtype, deeper structural unification, etc.).
    ;; Error-tolerance Phase 2: the new override is KEPT even when
    ;; the aggregate check fails — the guard records the failure in
    ;; the per-branch diagnostics store (and a later fixing write
    ;; clears it), and the response surfaces it additively as
    ;; `:type-warnings` on the success payload. The subtype-safety
    ;; pre-checks in `tighten-fn-type-impl!` (widening rejection,
    ;; bound-callable effect escape) stay hard rejections — they
    ;; guard the tighten operation's own contract, not fn validity.
    ;; SECURITY CARVE-OUT: a SECRET-involving aggregate failure keeps
    ;; the pre-Phase-2 shape — revert the override (and the anon
    ;; fn-row we just materialised), no store record, hard 400.
    (let [post-rej (tc/type-check-fn-and-dependents! ctx storage (:fn-id b)
                                                     {:reject-secret? true})]
      (if (:secret? post-rej)
        (do (sp/update-entity storage :binding binding-id
                              {:type-override-fn-id pre-override})
            (doseq [id created]
              (sp/delete-entity storage :fn id))
            {:status 400
             :reason (str "Tightening rejected by post-write type-check "
                          "(secret-flow violation): " (:reason post-rej))})
        {:status 200
         :result (cond-> {:type-override-fn-id new-id
                          :constraint new-c
                          :fn-id (:fn-id b)}
                   post-rej (assoc :type-warnings [(:diagnostic post-rej)]))}))))


(defn- callable-delta
  [current {:keys [args ret effects]}]
  (when-not (types/fn-type? current)
    (throw (ex-info "Slot's effective type is not an fn-type; use a complete collection contract" {})))
  (types/make-fn-type
    (if (map? args) (merge (types/fn-args current) (types-api/json->type args)) (types/fn-args current))
    (if (some? ret) (types-api/json->type ret) (types/fn-ret current))
    (if (some? effects) (into #{} (map keyword) effects) (types/fn-effects current))
    (types/fn-execution current)))


(defn- proposed-constraint
  [current delta]
  (when (and (contains? delta :type) (some #(contains? delta %) [:args :ret :effects]))
    (throw (ex-info "Use either a complete type or individual callable changes" {})))
  (let [proposed (if (contains? delta :type)
                   (types-api/json->type (:type delta))
                   (callable-delta current delta))]
    (expansion/assert-finite! current)
    (expansion/assert-finite! proposed)
    ;; Preserve authored alias references in the stored contract.
    ;; well-formed?/subtype? resolve them only for validation.
    (when-not (and (types/well-formed? proposed)
                   (or (types/fn-type? proposed) (types/list-type? proposed) (types/map-type? proposed))
                   (some #(= proposed (:constraint %))
                         (record-types/inline-fn-type-rows-from-form proposed)))
      (throw (ex-info "Choose a callable or a map/list contract containing callables" {})))
    (when-not (types/subtype? proposed current)
      (throw (ex-info "Proposed contract is not a narrowing of the current type (args / return / effects / execution)" {})))
    proposed))


(defn- bound-effects-rejection
  [storage binding proposed]
  (when (and (types/fn-type? proposed) (set? (types/fn-effects proposed)))
    (let [ref (some->> (:ref-fn-id binding) (sp/read-entity storage :fn))
          actual (some-> (:id ref) registry/rich-type-of-id :effects)
          escaped (clojure.set/difference (set actual) (types/fn-effects proposed))]
      (when (seq escaped)
        {:status 400 :reason (str "Bound fn produces effects " (vec (sort escaped))
                                  " that the requested effect constraint forbids. Rebind to a compatible callable.")}))))


(defn- tighten-fn-type!
  "Narrow one binding's effective contract. Legacy args/ret/effects deltas
   remain supported; a complete :type handles nested map/list callable pins.
   All checks and writes run inside the caller's serialized write unit."
  [ctx storage binding-id delta]
  (if-let [b (sp/read-entity storage :binding binding-id)]
    (let [slot (sp/read-entity storage :slot (:slot-id b))
          type-id (or (:type-override-fn-id b) (:type-fn-id slot))
          row (when type-id (sp/read-entity storage :fn type-id))
          current (record-types/stored-type-ref
                    type-id {:fn-by-id (tc/chain-fns-by-id storage row)})
          computed (try {:constraint (proposed-constraint current delta)}
                        (catch clojure.lang.ExceptionInfo e {:status 400 :reason (ex-message e)}))]
      (if-not (:constraint computed)
        computed
        (or (bound-effects-rejection storage b (:constraint computed))
            (commit-tighten! ctx storage binding-id b (:constraint computed) nil))))
    {:status 404 :reason "Binding not found"}))


(defn tighten-fn-type-impl!
  "Serialize the effective-type read, narrowing checks and anonymous type /
   binding writes. Rejection rolls back only this transaction's new rows."
  ([storage binding-id delta]
   (tighten-fn-type-impl! nil storage binding-id delta))
  ([ctx storage binding-id delta]
   (let [pooled? (some? (tx/datasource storage))]
     (when pooled? (tx/assert-owns-commit! storage))
     (let [result (try
                    (writer/call-with-write
                      storage {:entity :binding :ids [binding-id]}
                      (fn [bound]
                        (resolution/call-with-fresh-memos
                          (fn []
                            (let [binding-row (sp/read-entity bound :binding binding-id)
                                  result (if-let [reason (pkg-guard/write-rejection bound :binding binding-row)]
                                           {:status :rejected :reason reason}
                                           (tighten-fn-type! (when ctx (assoc ctx :storage bound))
                                                             bound binding-id delta))]
                              (if (and pooled? (not= 200 (:status result)))
                                (throw (ex-info "Tightening rejected" {::rejection result}))
                                result))))))
                    (catch clojure.lang.ExceptionInfo e
                      (if-let [result (::rejection (ex-data e))] result (throw e))))]
       (resolution/forget-read-memos!)
       (branch-local/invalidate! (versioned/unwrap storage))
       result))))


(defn tighten-effects-impl!
  "Thin wrapper — `tighten-fn-type-impl!` with
   only the `:effects` delta filled in. Tests load this symbol
   directly; production callers go through the form-driven defbase."
  [storage binding-id effects-vec]
  (tighten-fn-type-impl! storage binding-id {:effects effects-vec}))


(defn apply-tighten-core
  "§3.3 atomic core of tighten-fn-effects: narrows the fn-typed
   binding's effective type. Returns `{:status :reason :result}` from
   `tighten-fn-type-impl!` unchanged — the outer graph dispatches on
   `:status` and runs invalidate + response."
  [parsed ctx]
  (tighten-fn-type-impl! ctx (request/require-storage ctx)
                         (:binding-id parsed) (:delta parsed)))
