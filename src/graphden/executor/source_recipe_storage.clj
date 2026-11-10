(ns graphden.executor.source-recipe-storage
  "Known storage shapes for opt-in source replay. No unwrapping bypasses the
   datasource used to borrow, and unknown decorators retain ordinary reads."
  (:require
    [graphden.storage.postgres.core]
    [graphden.storage.tx :as tx]
    [graphden.versioning.branch-local :as branch-local]
    [graphden.versioning.storage.core]
    [graphden.versioning.storage.resolution :as resolution])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)
    (graphden.storage.postgres.core
      PostgresStorage)
    (graphden.versioning.storage.core
      VersionedStorage)))


(def source-tables
  "Exact physical relations understood by this source adapter, not a generic
   entity-name conversion. New producers require explicit admission."
  #{:fn :fn_version :fn_parent_ids :slot :fn_slot :fn_slot_version
    :binding :binding_version :binding_list_item :binding_list_item_version
    :ns :branch :branch_merge :ui_pref})


(defn describe
  "Inspect the original LIVE stack before borrowing. Classifiers are the
   current explicitly registered no-I/O adapter. Wrapper eligibility must be
   checked before considering its Hikari delegate; no generic map walk or
   DataSource unwrap is accepted. Returns nil for unsupported combinations."
  [storage registration]
  (when (and registration (= VersionedStorage (class storage)) (uuid? (:branch-id storage)))
    (let [base (:base-storage storage)
          wrapped? (or (:unwrap-storage registration) (:unwrap-datasource registration))
          wrapper (when-let [classify (:unwrap-storage registration)] (classify base))
          backend (if wrapped?
                    (when (and (= :org-scoped (:kind wrapper))
                               (identical? (:delegate wrapper) (:base base)))
                      (:delegate wrapper))
                    base)]
      (when (= PostgresStorage (class backend))
        (let [datasource (:pool backend)
              ds-wrapper (when-let [classify (:unwrap-datasource registration)] (classify datasource))
              pool (if wrapped?
                     (when (and (= :org-aware (:kind ds-wrapper)) (fn? (:policy-contract-for ds-wrapper)))
                       (:delegate ds-wrapper))
                     datasource)]
          (when (= HikariDataSource (class pool))
            {:storage storage :backend backend :wrapped? (boolean wrapped?)
             :pool pool :datasource datasource :branch-id (:branch-id storage)
             :policy-contract-for (:policy-contract-for ds-wrapper)}))))))


(defn fresh-storage
  "Copy only the admitted stack, retaining every decorator and replacing its
   slot-row-cache. tx/with-connection alone preserves that cache and therefore
   cannot establish a complete JDBC trace. Never clear the shared cache atom."
  [{:keys [storage backend wrapped?]} connection]
  (let [backend (assoc backend :slot-row-cache (atom {}))
        base (if wrapped? (assoc (:base-storage storage) :base backend) backend)]
    (tx/with-connection (assoc storage :base-storage base) connection)))


(defn call-with-fresh-memos
  "Source record, replay and ordinary fallback each own all resolver memos.
   A warm anchor or previous pass cannot hide SQL from the next source trace."
  [f]
  (binding [resolution/*branch-chain-cache* (atom {})
            resolution/*merges-memo* (atom {})
            resolution/*graph-load-memo* (atom {})
            branch-local/*storage-caches-override* (atom {})]
    (f)))
