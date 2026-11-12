(ns ^:integration graphden.versioning.storage.source-projection-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.grouped-read :as grouped]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.source-projection :as projection]
    [graphden.storage.tx :as tx]
    [graphden.versioning.storage.core :as vs]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/create-container-fixture))


(defn- refusal
  [f]
  (try (f) (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))


(defn- snapshot
  [storage f]
  (jdbc/with-transaction [connection (tx/datasource storage) {:read-only true :isolation :repeatable-read}]
                         (grouped/call-with-snapshot-cache connection #(f (tx/with-connection storage connection)))))


(defn- scope
  [storage owners guard]
  (projection/authorize-scope! storage (vals (sp/read-entities storage :fn owners)) guard (fn [& _])))


(defn- edges
  [storage owners guard]
  (sp/read-source-projection storage {:op :source-edges :scope (scope storage owners guard)}))


(defn- fixture-rows
  [storage]
  (let [owner (sp/create-entity storage :fn {:name "projected-owner"})
        other (sp/create-entity storage :fn {:name "projected-other"})
        slot (sp/create-entity storage :slot {:name "projected-slot" :type-fn-id (:id owner)})
        junction (sp/create-entity storage :fn-slot {:fn-id (:id owner) :slot-id (:id slot) :position 0})
        binding (sp/create-entity storage :binding {:fn-id (:id owner) :slot-id (:id slot)
                                                    :ref-fn-id (:id other) :value "secret binding payload"})
        item (sp/create-entity storage :binding-list-item {:binding-id (:id binding) :position 0
                                                           :ref-fn-id (:id other) :value "secret item payload"})]
    {:owner owner :other other :slot slot :junction junction :binding binding :item item}))


(defn- observed
  [f]
  (let [calls (atom [])]
    {:answer (binding [util/*jdbc-override*
                       {:execute! (fn [connection params opts]
                                    (swap! calls conj (first params)) (jdbc/execute! connection params opts))
                        :execute-one! (fn [connection params opts]
                                        (swap! calls conj (first params)) (jdbc/execute-one! connection params opts))}]
               (f))
     :calls @calls}))


(deftest projected-edges-preserve-ordinary-rows-without-value-transfer
  (let [storage (setup/create-versioned-test-storage)
        {:keys [owner other binding item junction]} (fixture-rows storage)]
    (snapshot storage
              (fn [scoped]
                (let [witness (scope scoped [(:id owner) (:id other)] (constantly nil))
                      {:keys [answer calls]} (observed #(sp/read-source-projection scoped {:op :source-edges :scope witness}))]
                  (is (= [(:id junction)] (mapv :id (:fn-slots answer))))
                  (is (= [(:id binding)] (mapv :id (:bindings answer))))
                  (is (= [(:id item)] (mapv :id (:list-items answer))))
                  (is (= (:ref-fn-id binding) (:ref-fn-id (first (:bindings answer)))))
                  (is (not-any? #(contains? % :value) (concat (:bindings answer) (:list-items answer))))
                  (is (not-any? #(re-find #"(?i)\"(?:value|literal|description)\"" %) calls))
                  (is (not-any? #(re-find #"(?i)SELECT \* FROM \"(?:binding|fn_slot)(?:_version|_list_item(?:_version)?)?\"" %) calls)))))))


(deftest foreign-historical-owner-and-interface-bodies-refuse-before-reference-read
  (let [storage (setup/create-versioned-test-storage)
        {:keys [owner other binding]} (fixture-rows storage)
        child (vs/switch-branch storage (:id (vs/create-branch! storage "projected-move")))]
    (sp/update-entity child :binding (:id binding) {:fn-id (:id other)})
    (doseq [[view owners guard] [[child [(:id owner)] (constantly nil)]
                                 [storage [(:id owner) (:id other)] (constantly :bare-type)]
                                 [storage [(:id owner) (:id other)] (constantly {:type-contract {}})]]]
      (snapshot view
                (fn [scoped]
                  (let [{:keys [answer calls]} (observed #(refusal (fn [] (edges scoped owners guard))))]
                    (is (= :storage-error/source-projection-unsupported answer))
                    (is (not-any? #(str/includes? % "\"ref_fn_id\"") calls))))))))


(deftest ^:serial ambiguous-version-timestamps-refuse-without-projecting-references
  (let [storage (setup/create-versioned-test-storage)
        {:keys [owner other]} (fixture-rows storage)
        original sp/read-source-projection]
    ;; The normal SQL unique index excludes ties. Simulate a legacy/corrupt
    ;; backend receipt to prove the defensive path refuses before edge reads.
    (with-redefs [sp/read-source-projection
                  (fn [backend request]
                    (let [rows (original backend request)]
                      (if (and (= :query (:op request)) (= :binding-version (:entity request)) (seq rows))
                        (conj rows (assoc (first rows) :id (random-uuid)))
                        rows)))]
      (snapshot storage
                (fn [scoped]
                  (let [{:keys [answer calls]} (observed #(refusal (fn [] (edges scoped [(:id owner) (:id other)] (constantly nil)))))]
                    (is (= :storage-error/source-projection-unsupported answer))
                    (is (not-any? #(str/includes? % "\"ref_fn_id\"") calls))))))))


(deftest sealed-scope-refuses-expired-and-forged-requests
  (let [storage (setup/create-versioned-test-storage)
        {:keys [owner other]} (fixture-rows storage)
        retained (atom nil)]
    (snapshot storage
              (fn [scoped]
                (let [witness (scope scoped [(:id owner) (:id other)] (constantly nil))]
                  (reset! retained [scoped witness])
                  (is (= :storage-error/source-projection-unsupported
                         (refusal #(sp/read-source-projection scoped {:op :source-edges :scope {:owners #{(:id owner)}}})))))))
    (let [[scoped witness] @retained]
      (is (= :storage-error/source-projection-unsupported
             (refusal #(sp/read-source-projection scoped {:op :source-edges :scope witness})))))))


(deftest projection-keeps-merge-cutoff-tombstones-and-item-ownership
  (let [storage (setup/create-versioned-test-storage)
        {:keys [owner other binding item junction]} (fixture-rows storage)
        feature (vs/switch-branch storage (:id (vs/create-branch! storage "projection-source")))
        target (vs/switch-branch storage (:id (vs/create-branch! storage "projection-target")))]
    (sp/update-entity feature :binding (:id binding) {:ref-fn-id (:id owner)})
    (sp/update-entity feature :binding-list-item (:id item) {:ref-fn-id (:id owner)})
    (binding [vs/*tombstone-delete?* true] (sp/delete-entity feature :fn-slot (:id junction)))
    (snapshot feature
              (fn [scoped]
                (is (empty? (:fn-slots (edges scoped [(:id owner) (:id other)] (constantly nil)))))))
    (vs/merge-branch! target (:branch-id feature))
    ;; A later unmerged source edit must not cross the stored merge cutoff.
    (sp/update-entity feature :binding (:id binding) {:ref-fn-id (:id other)})
    (snapshot target
              (fn [scoped]
                (let [answer (edges scoped [(:id owner) (:id other)] (constantly nil))]
                  (doseq [[key entity fields] [[:fn-slots :fn-slot [:id :fn-id :slot-id :position]]
                                               [:bindings :binding [:id :fn-id :slot-id :ref-fn-id :type-override-fn-id :resolver-fn-id]]]]
                    (is (= (set (map #(select-keys % fields) (sp/query-bounded-entities scoped entity {:fn-id [(:id owner) (:id other)]} 100)))
                           (set (map #(select-keys % fields) (get answer key))))))
                  (is (= (:id owner) (:ref-fn-id (first (:bindings answer)))))
                  (is (= (:id owner) (:ref-fn-id (first (:list-items answer))))))))))


(deftest physical-projection-denies-foreign-branch-cross-entity-and-literal-fields
  (let [storage (setup/create-versioned-test-storage)
        {:keys [owner other binding]} (fixture-rows storage)]
    (snapshot storage
              (fn [scoped]
                (let [base (vs/unwrap scoped)
                      witness (scope scoped [(:id owner) (:id other)] (constantly nil))
                      branch (:branch-id scoped)
                      sealed (projection/resolution-scope witness scoped base {:binding #{(:id binding)}} #{branch})
                      request {:scope sealed :op :read-ids :entity :binding :fields [:id :org-id] :ids [(:id binding)] :max-rows 10}]
                  (doseq [bad [(assoc request :entity :unknown)
                               (assoc request :fields [:id :value])
                               (assoc request :ids [(random-uuid)])
                               {:scope sealed :op :query :entity :binding-version
                                :fields [:id :binding-id :fn-id :branch-id :created-at :deleted-at]
                                :where {:binding-id [(:id binding)] :branch-id [(random-uuid)]} :max-rows 10}]]
                    (is (= :storage-error/source-projection-unsupported (refusal #(sp/read-source-projection base bad)))))
                  (let [version (random-uuid)
                        frozen (projection/versions-scope sealed base {version {:entity :fn-slot :id (:id binding)}})]
                    (is (= :storage-error/source-projection-unsupported
                           (refusal #(sp/read-source-projection base {:scope frozen :op :read-ids :entity :binding-version
                                                                      :fields [:id :ref-fn-id] :ids [version] :max-rows 1}))))))))))
