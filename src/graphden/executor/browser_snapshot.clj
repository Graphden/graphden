(ns graphden.executor.browser-snapshot
  "Read and check one immutable graph for the bounded self-hosted backend.
   Cached policy must match the checked source and pass a fresh snapshot check."
  (:require
    [graphden.crud.type-check :as type-check]
    [graphden.executor.browser-plan :as plan]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.compile.deps :as deps]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.remote.core :as remote]
    [graphden.storage.tx :as tx]
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
  "Only resolved built-in shapes have visibility independent of later alias
   or marker registration. Custom tags and unresolved aliases fail closed."
  [t]
  (not (types/type-any?
         (fn [part]
           (not (or (shapes/primitive? part) (shapes/type-var? part)
                    (shapes/record-type? part) (shapes/fn-type? part)
                    (shapes/list-type? part) (shapes/map-type? part)
                    (shapes/tuple-type? part) (shapes/refine-type? part)
                    (shapes/union-type? part) (shapes/secret-type? part))))
         t)))


(defn- closed-policy-signature?
  [{:keys [return args]}]
  (and (closed-policy-type? return)
       (every? (fn [arg] (closed-policy-type? (or (:type arg) arg)))
               (vals args))))


(defn capture-policy
  "Freeze visibility from rich entries BEFORE the database read. Only fully
   resolved built-in shapes qualify: aliases/custom markers cannot be safely
   reinterpreted through separately mutable registries, even before that read."
  [rich]
  (binding [types/*type-aliases-override* (atom {})
            types/*alias-view* nil
            shapes/*marker-registry-override* (atom {:secret {:monotone? true :hide-result? true}})
            registry/*rich-types-override* (atom rich)
            registry/*per-org-rich-override* (atom {})]
    (try
      {:rich rich
       :classes (into {}
                      (map (fn [[id signature]]
                             [id (if (closed-policy-signature? signature)
                                   (registry/trace-capture-class id nil)
                                   :unknown)]))
                      (:by-id rich))}
      (catch Exception _ (reject! :policy-capture-failed {})))))


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


(defn- snapshot-storage
  [{:keys [graph namespaces]}]
  ;; The existing adapter accepts entity-keyed bundles and performs no I/O.
  (remote/from-bundle {:fn (:fns graph) :slot (:slots graph)
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
  [snapshot entries original-rich]
  (let [graph (:graph snapshot)
        {:keys [forward-deps reverse-deps]} (deps/build-deps-state graph)
        reachable (deps/forward-closure forward-deps (vals entries))
        storage (snapshot-storage snapshot)
        {:keys [ordered cyclic]} (deps/dependency-order reverse-deps reachable)]
    (when (> (count reachable) 2048) (reject! :type-closure-limit {}))
    (when (seq cyclic) (reject! :type-cycle {:fn-id (first cyclic)}))
    (doseq [id ordered]
      (when-let [definition (type-check/reconstruct-fn-def storage id)]
        (when-not (provenance/matches? (get-in original-rich [:by-id id])
                                       (check/checker-view definition))
          (reject! :type-source-mismatch {:fn-id id}))
        (try
          (check/check-fn-def! definition)
          (when-not (registry/rich-type-of-id id)
            (reject! :type-unresolved {:fn-id id}))
          (catch Exception _
            ;; Checker diagnostics may contain literal data. Export errors do
            ;; not carry their message, cause, or original ex-data.
            (reject! :type-check-failed {:fn-id id})))))))


(defn export-snapshot
  "Derive a fresh policy and plan from the SAME immutable rows. No writes to
   the executor's rich types, aliases, markers, diagnostics, or storage."
  [snapshot base-fns entries {:keys [rich classes]}]
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            registry/*per-org-rich-override* (atom {})
            types/*type-aliases-override* (atom {})
            types/*alias-view* nil
            shapes/*marker-registry-override* (atom {:secret {:monotone? true :hide-result? true}})
            runtime/*per-org-aliases-override* (atom {})]
    (try
      (runtime/register-type-aliases-from-db! (:graph snapshot) ::snapshot
                                              (ns-path/path-map (:namespaces snapshot)))
      (seed-primitives! (select-keys (:by-id rich) (plan/supported-primitive-ids)))
      (check-closure! snapshot entries rich)
      (plan/export-plan (:graph snapshot) base-fns entries
                        {:allow-fn?
                         (fn [id]
                           (and (= :plain (registry/trace-capture-class id nil))
                                ;; Storage intentionally erases inline marker
                                ;; annotations. A fresh check is only a veto:
                                ;; it must never relax the original policy.
                                (= :plain (get classes id))))})
      (catch Exception error
        (if (= :browser-plan/unsupported (:type (ex-data error)))
          (throw error)
          (reject! :snapshot-check-failed {}))))))
