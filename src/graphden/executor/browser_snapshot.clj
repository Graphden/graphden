(ns graphden.executor.browser-snapshot
  "Read and check one immutable graph for the bounded self-hosted backend.
   Cached policy must match the checked source and pass a fresh snapshot check."
  (:require
    [clojure.set :as set]
    [graphden.crud.type-check :as type-check]
    [graphden.executor.browser-contracts :as contracts]
    [graphden.executor.browser-plan :as plan]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.compile.deps :as deps]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.source-contract :as source-contract]
    [graphden.executor.structural-contracts :as structural-contracts]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.remote.core :as remote]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.check :as check]
    [graphden.types.check.provenance :as provenance]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]
    [graphden.util.ns-path :as ns-path]
    [graphden.versioning.branch-local :as branch-local]
    [graphden.versioning.graph-rows :as graph-rows]
    [graphden.versioning.storage.resolution :as resolution]
    [next.jdbc :as jdbc]
    [next.jdbc.transaction :as jdbc-tx])
  (:import
    (java.sql
      Connection)))


(defn- reject!
  [reason context]
  (throw (ex-info (str "Browser snapshot rejected: " (name reason))
                  (merge {:type :browser-plan/unsupported :reason reason}
                         (select-keys context [:fn-id])))))


(defn- closed-policy-type?
  "Reject unresolved aliases and unknown constructors under one frozen view."
  [t]
  (not (types/type-any?
         (fn [part]
           (not (or (shapes/primitive? part) (shapes/type-var? part)
                    (shapes/record-type? part) (shapes/fn-type? part)
                    (shapes/list-type? part) (shapes/map-type? part)
                    (shapes/tuple-type? part) (shapes/refine-type? part)
                    (shapes/union-type? part) (shapes/marker-type? part))))
         t)))


(defn- closed-policy-signature?
  [{:keys [return args]}]
  (and (closed-policy-type? return)
       ;; Rich arguments are types. A record field named :type is ordinary
       ;; data and must not hide its siblings from the policy walk.
       (every? closed-policy-type? (vals args))))


(defn- signature-class
  [id signature flags]
  (binding [shapes/*marker-registry-override*
            (atom (assoc flags :secret {:monotone? true :hide-result? true}))]
    (if (closed-policy-signature? signature)
      (registry/trace-capture-class id nil)
      :unknown)))


(defn- capture-policy-for
  ([rich signatures]
   (capture-policy-for rich signatures (shapes/markers-snapshot)))
  ([rich signatures current-flags]
   (binding [types/*type-aliases-override* (atom {})
             types/*alias-view* nil
             shapes/*marker-registry-override* (atom {:secret {:monotone? true :hide-result? true}})
             registry/*rich-types-override* (atom rich)
             registry/*per-org-rich-override* (atom {})]
     (try
       {:rich rich
        :classes (into {}
                       (map (fn [[id signature]]
                              (let [frozen (provenance/checked-marker-flags id signature)
                                    original (if (and (nil? frozen)
                                                      (provenance/checker-stamp-present? signature))
                                               :unknown
                                               (signature-class id signature (or frozen {})))]
                                [id (if (= :unknown original)
                                      :unknown
                                      (let [current (signature-class id signature current-flags)]
                                        (if (or (= :unknown current) (= :plain original))
                                          current original)))])))
                       signatures)}
       (catch Exception _ (reject! :policy-capture-failed {}))))))


(defn capture-policy
  "Freeze visibility before source reads. Custom markers require a checker
   witness; frozen flags authorize while the current view can only veto."
  [rich]
  (capture-policy-for rich (:by-id rich)))


(defn capture-context-policy
  "Freeze the same original rich policy and ephemeral common-recheck frontier.
   Recovery may request a retry, never supply a source certificate or grant."
  [ctx]
  (assoc (capture-policy @(or (:rich-types-atom ctx) (registry/active-rich-types-atom)))
         :type-recheck (some-> (:type-recheck-state ctx) deref
                               (select-keys [:status :pending-ids]))))


(defn read-snapshot
  "Read every graph table and namespace in one read-only REPEATABLE READ
   transaction, through the original decorators. Request/global resolver caches
   cannot participate. Refuse non-pooled and nested transaction contexts."
  [storage]
  (let [ds (tx/datasource storage)]
    (when (or (nil? ds) (instance? Connection ds))
      (reject! :snapshot-storage-unsupported {}))
    (try
      (binding [jdbc-tx/*nested-tx* :prohibit
                resolution/*branch-chain-cache* (atom {})
                resolution/*merges-memo* (atom {})
                resolution/*graph-load-memo* (atom {})
                branch-local/*storage-caches-override* (atom {})]
        (jdbc/with-transaction [connection ds {:isolation :repeatable-read :read-only true}]
                               (let [storage (tx/with-connection storage connection)]
                                 {:graph (graph-rows/read-all storage)
                                  :namespaces (vec (sp/query-entities storage :ns {}))})))
      (catch Exception _ (reject! :snapshot-read-failed {})))))


(defn- stored-constraint-matches?
  "Keywords remain type aliases; only the exact symbol or its JSONB string
   spelling can be reconstructed as a declared generic variable."
  [expected actual]
  (cond
    (symbol? expected) (or (= expected actual)
                           (and (string? actual) (= (str expected) actual)))
    (map? expected) (and (map? actual) (= (set (keys expected)) (set (keys actual)))
                         (every? (fn [[k v]] (stored-constraint-matches? v (get actual k))) expected))
    (vector? expected) (and (vector? actual) (= (count expected) (count actual))
                            (every? true? (map stored-constraint-matches? expected actual)))
    :else (= expected actual)))


(defn- type-row-semantics-match?
  "Descriptions and storage bookkeeping carry no callable semantics. Every
   other non-nil field, including future effect/execution annotations, must
   agree with the canonical emitted row before replacing its constraint."
  [expected actual]
  (let [cosmetic #{:description :author-id :author-label :created-at :updated-at :deleted-at :kind
                   :constraint :org-id :concealed?}
        fields (set/difference (into (set (keys expected)) (keys actual)) cosmetic)]
    (and (nil? (:deleted-at actual))
         ;; Entity-keyed storage rows omit :kind; an explicit discriminator
         ;; must still identify the same emitted fn row.
         (or (not (contains? actual :kind)) (= (:kind expected) (:kind actual)))
         (every? #(= (get expected %) (get actual %)) fields))))


(defn- reconstruction-fns
  "Restore type-variable symbols only from an exact trusted type contract.
   JSONB keeps their spelling as strings. The original graph remains the source
   witness; this projection is used only to reconstruct checker declarations."
  [{:keys [fns slots fn-slots bindings]}]
  (let [slots-by-id (into {} (map (juxt :id identity)) slots)
        junctions (group-by :fn-id fn-slots)
        bindings (group-by :fn-id bindings)]
    (mapv (fn [row]
            (if-let [contract (when (and (owned/owned-fn-id? (:id row))
                                         (contains? #{nil tenancy/public-org} (:org-id row)))
                                (let [contract (contracts/contract (:id row))]
                                  (when (and (not (:concealed? row))
                                             (= :fn (first (get-in contract [:fn :constraint]))))
                                    contract)))]
              (let [own-junctions (vec (get junctions (:id row)))
                    own-slots (mapv #(get slots-by-id (:slot-id %)) own-junctions)]
                (when-not (and (type-row-semantics-match? (:fn contract) row)
                               (contracts/matches? contract row own-slots own-junctions
                                                   (get bindings (:id row)))
                               (stored-constraint-matches? (get-in contract [:fn :constraint]) (:constraint row)))
                  (reject! :type-contract-source-mismatch {:fn-id (:id row)}))
                (assoc row :constraint (get-in contract [:fn :constraint])))
              row)) fns)))


(defn- snapshot-storage
  [{:keys [graph namespaces]}]
  ;; Source fingerprints are computed from the original immutable rows. Only
  ;; the checker's read adapter receives the canonical typed reconstruction.
  (remote/from-bundle {:fn (reconstruction-fns graph) :slot (:slots graph)
                       :fn-slot (:fn-slots graph) :binding (:bindings graph)
                       :binding-list-item (:list-items graph) :ns namespaces}))


(defn- seed-primitives!
  "Only package-protected primitives may retain their loaded implementation
   metadata. User/composed entries are deliberately absent, including entries
   already checked by the live context."
  [primitive-signatures]
  (doseq [[id signature] primitive-signatures]
    (registry/record-rich-types-raw! id (:name signature) signature)))


(defn- check-closure!
  [snapshot entries original-rich recovery]
  (let [graph (:graph snapshot)
        {:keys [forward-deps reverse-deps]} (deps/build-deps-state graph)
        reachable (set/difference (deps/forward-closure forward-deps (vals entries))
                                  (:identity-target-ids graph #{}))
        storage (snapshot-storage snapshot)
        source-fingerprints (source-contract/fingerprints graph)
        {:keys [ordered cyclic]} (deps/dependency-order reverse-deps reachable)]
    (when (> (count reachable) 2048) (reject! :type-closure-limit {}))
    (when (seq cyclic) (reject! :type-cycle {:fn-id (first cyclic)}))
    (doseq [id ordered]
      (when-let [definition (type-check/reconstruct-fn-def storage id)]
        (when-not (let [signature (get-in original-rich [:by-id id])]
                    (or (provenance/matches? signature (check/checker-view definition))
                        (provenance/matches-storage-input? signature (get source-fingerprints id))))
          (let [{:keys [status pending-ids]} recovery]
            (if (and (contains? #{:pending :running} status)
                     (not (owned/owned-fn-id? id))
                     (or (nil? pending-ids) (contains? pending-ids id)))
              (reject! :policy-refresh-required {:fn-id id})
              (reject! :type-source-mismatch {:fn-id id}))))
        (try
          (check/check-fn-def! definition)
          (when-not (registry/rich-type-of-id id)
            (reject! :type-unresolved {:fn-id id}))
          (catch Exception _
            ;; Checker diagnostics may contain literal data. Export errors do
            ;; not carry their message, cause, or original ex-data.
            (reject! :type-check-failed {:fn-id id})))))))


(defn- checked-source
  "Derive a fresh policy and plan from the SAME immutable rows. No writes to
   the executor's rich types, aliases, markers, diagnostics, or storage."
  [snapshot entries {:keys [rich classes type-recheck]} structural? consume]
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            registry/*per-org-rich-override* (atom {})
            types/*type-aliases-override* (atom {})
            types/*alias-view* nil
            shapes/*marker-registry-override* (atom {:secret {:monotone? true :hide-result? true}})
            runtime/*per-org-aliases-override* (atom {})]
    (try
      (runtime/register-type-aliases-from-db! (:graph snapshot) ::snapshot
                                              (ns-path/path-map (:namespaces snapshot)))
      ;; Even a stored declaration cannot weaken the seeded confidentiality tag.
      (shapes/register-marker! :secret {:monotone? true :hide-result? true})
      (when structural?
        (let [present (into #{} (map :id) (get-in snapshot [:graph :fns]))]
          (doseq [id (set/intersection present (structural-contracts/operation-ids))]
            (when-not (structural-contracts/matches? id (:graph snapshot))
              (reject! :structural-operation-source-mismatch {:fn-id id})))))
      (seed-primitives! (:server-signatures snapshot))
      (seed-primitives! (select-keys (:by-id rich)
                                     (cond-> (conj (plan/supported-primitive-ids)
                                                   (ids/fn-id "app.ui-components" :ui-components))
                                       structural? (into (structural-contracts/operation-ids)))))
      (check-closure! snapshot entries rich type-recheck)
      ;; A snapshot checks only source identities it actually exports. The
      ;; original policy was frozen in full before the storage read; this
      ;; second classification is a fresh veto, not another authority grant.
      (let [current-flags (shapes/markers-snapshot)
            snapshot-class (memoize
                             (fn [id]
                               (get-in (capture-policy-for rich
                                                           (select-keys (:by-id rich) [id])
                                                           current-flags)
                                       [:classes id])))]
        (consume
          {:allow-fn?
           (fn [id]
             (and (= :plain (registry/trace-capture-class id nil))
                  ;; Storage intentionally erases inline marker
                  ;; annotations. A fresh check is only a veto:
                  ;; it must never relax the original policy.
                  (= :plain (get classes id))
                  (= :plain (snapshot-class id))))}))
      (catch Exception error
        (if (= :browser-plan/unsupported (:type (ex-data error)))
          (throw error)
          (reject! :snapshot-check-failed {}))))))


(defn validate-source!
  "Validate a manifest source under the same isolated provenance/type policy
   as component plans. No manifest graph is evaluated or returned to clients."
  [snapshot root policy]
  (checked-source snapshot {:manifest root} policy true
                  (fn [{:keys [allow-fn?]}]
                    (let [forward (:forward-deps (deps/build-deps-state (:graph snapshot)))
                          reachable (set/difference (deps/forward-closure forward [root])
                                                    (get-in snapshot [:graph :identity-target-ids] #{}))
                          rows (into {} (map (juxt :id identity)) (get-in snapshot [:graph :fns]))]
                      (doseq [id reachable]
                        (when (and (or (seq (:parent-ids (get rows id)))
                                       (:return-type-fn-id (get rows id)))
                                   (not (allow-fn? id)))
                          (reject! :visibility-denied {:fn-id id})))))))


(defn export-snapshot
  "Derive a fresh policy and plan from the same immutable authorized rows."
  [snapshot base-fns entries policy]
  (checked-source snapshot entries policy false
                  #(plan/export-plan (:graph snapshot) base-fns entries
                                     (merge % (:server-plan-options snapshot)))))
