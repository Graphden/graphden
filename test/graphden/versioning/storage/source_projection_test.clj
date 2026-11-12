(ns ^:integration ^:serial graphden.versioning.storage.source-projection-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.browser-source :as source]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.grouped-read :as grouped]
    [graphden.storage.postgres.source-projection :as projected-pg]
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
  (let [calls (atom []) queries (atom [])]
    {:answer (binding [util/*jdbc-override*
                       {:execute! (fn [connection params opts]
                                    (swap! calls conj (first params))
                                    (swap! queries conj params)
                                    (jdbc/execute! connection params opts))
                        :execute-one! (fn [connection params opts]
                                        (swap! calls conj (first params))
                                        (swap! queries conj params)
                                        (jdbc/execute-one! connection params opts))}]
               (f))
     :calls @calls :queries @queries}))


(deftest readable-stale-owner-is-projected-without-loading-its-values
  (let [storage (setup/create-versioned-test-storage)
        {:keys [owner slot]} (fixture-rows storage)
        extra (sp/create-entity storage :fn {:name "unreachable-readable-owner"})
        _ (sp/create-entity storage :fn-slot {:fn-id (:id extra) :slot-id (:id slot) :position 0})
        extra-binding (sp/create-entity storage :binding {:fn-id (:id extra) :slot-id (:id slot)
                                                          :value "unreachable private literal"})]
    (snapshot storage
              (fn [scoped]
                (let [ordinary (source/collect-client-closure scoped [(:id owner)] (constantly nil))
                      hint (update ordinary :fns
                                   #(conj (mapv (fn [row]
                                                  (if (= (:id owner) (:id row))
                                                    (update row :parent-ids conj (:id extra)) row)) %) extra))
                      {:keys [answer queries]} (observed #(source/collect-client-closure scoped [(:id owner)]
                                                                                         (constantly nil) hint))
                      owned-payload? (fn [[sql & parameters]]
                                       (and (re-find #"(?i)SELECT .*\*" sql)
                                            (re-find #"\"binding(?:_version|_list_item(?:_version)?)?\"" sql)
                                            (some #{(:id extra-binding)} parameters)))]
                  (is (seq queries))
                  (is (not-any? #(= (:id extra) (:id %)) (:fns answer)))
                  (is (not-any? #(= (:id extra-binding) (:id %)) (:bindings answer)))
                  (is (not-any? owned-payload? queries)))))))


(deftest projected-client-preserves-complete-authorized-body
  (let [storage (setup/create-versioned-test-storage)
        {:keys [owner]} (fixture-rows storage)
        projected-var (ns-resolve 'graphden.executor.browser-source 'collect-projected-client)
        original @projected-var
        successes (atom 0)]
    (snapshot storage
              (fn [scoped]
                (let [ordinary (source/collect-client-closure scoped [(:id owner)] (constantly nil))
                      projected (with-redefs-fn
                                  {projected-var (fn [& args]
                                                   (let [result (apply original args)]
                                                     (when result (swap! successes inc))
                                                     result))}
                                  #(source/collect-client-closure scoped [(:id owner)] (constantly nil) ordinary))
                      rows #(into {} (map (fn [key] [key (set (get % key))]))
                                  [:fns :slots :fn-slots :bindings :list-items])]
                  (is (= 1 @successes))
                  (is (= (rows ordinary) (rows projected)))
                  (is (= "secret binding payload" (:value (first (:bindings projected)))))
                  (is (= "secret item payload" (:value (first (:list-items projected))))))))))


(deftest deadline-refuses-projection-before-any-owned-body
  (let [storage (setup/create-versioned-test-storage)
        {:keys [owner other]} (fixture-rows storage)]
    (snapshot storage
              (fn [scoped]
                (let [cancelled? (atom false)
                      witness (projection/authorize-scope!
                                scoped (vals (sp/read-entities scoped :fn [(:id owner) (:id other)]))
                                (constantly nil) (fn [& _])
                                #(when @cancelled? (throw (InterruptedException. "cancelled projection"))))]
                  (reset! cancelled? true)
                  (let [{:keys [answer calls]}
                        (observed #(try (sp/read-source-projection scoped {:op :source-edges :scope witness})
                                        (catch InterruptedException _ :cancelled)))]
                    (is (= :cancelled answer))
                    (is (empty? calls))))))))


(defn- measured-chain
  [length]
  (let [storage (setup/create-versioned-test-storage)
        ids (vec (repeatedly length random-uuid))
        _ (sp/create-entities storage :fn
                              (mapv (fn [index id] {:id id :name (str "budget-node-" index) :parent-ids []})
                                    (range) ids))
        slot (sp/create-entity storage :slot {:name "next" :type-fn-id (peek ids)})
        _ (sp/create-entities storage :fn-slot
                              (mapv #(hash-map :fn-id % :slot-id (:id slot) :position 0) ids))
        _ (sp/create-entities storage :binding
                              (mapv (fn [index id]
                                      (cond-> {:fn-id id :slot-id (:id slot)}
                                        (< index (dec length)) (assoc :ref-fn-id (get ids (inc index)))
                                        (= index (dec length)) (assoc :value "leaf"))) (range) ids))
        hint (source/collect-client-closure storage [(first ids)] (constantly nil))
        projected-var (ns-resolve 'graphden.executor.browser-source 'collect-projected-client)
        original @projected-var
        successes (atom 0)]
    (snapshot storage
              (fn [scoped]
                (let [{:keys [answer calls]}
                      (observed #(with-redefs-fn
                                   {projected-var (fn [& args]
                                                    (let [result (apply original args)]
                                                      (when result (swap! successes inc))
                                                      result))}
                                   (fn [] (source/collect-client-closure scoped [(first ids)] (constantly nil) hint))))]
                  (is (= 1 @successes))
                  (is (= (set ids) (set (map :id (:fns answer)))))
                  {:owners length :queries (count calls)})))))


(deftest eligible-client-acquisition-sql-does-not-follow-reference-depth
  (let [twenty (measured-chain 20)
        forty (measured-chain 40)]
    (is (pos? (:queries twenty)))
    (is (= (:queries twenty) (:queries forty))
        (str "doubling reference depth adds in-memory work, not SQL rounds: " [twenty forty]))))


(deftest physical-projection-scales-in-parameter-chunks-with-one-total-row-budget
  (let [storage (setup/create-versioned-test-storage)
        {:keys [owner]} (fixture-rows storage)
        base (vs/unwrap storage)
        ids (vec (repeatedly 20 random-uuid))]
    (doseq [id ids]
      (let [slot (sp/create-entity storage :slot {:name (str "chunk-" id) :type-fn-id (:id owner)})]
        (sp/create-entity base :binding {:id id :org-id nil :fn-id (:id owner) :slot-id (:id slot)})))
    (snapshot storage
              (fn [scoped]
                (let [backend (vs/unwrap scoped)
                      owner-scope (scope scoped [(:id owner)] (constantly nil))
                      sealed (projection/resolution-scope owner-scope scoped backend {:binding (set ids)} #{(:branch-id scoped)})
                      request {:scope sealed :op :read-ids :entity :binding :fields [:id :org-id]
                               :ids ids :max-rows 20}
                      {:keys [answer queries]} (binding [util/*max-statement-params* 8]
                                                 (observed #(sp/read-source-projection backend request)))
                      body-queries (filter #(str/includes? (first %) "FROM \"binding\"") queries)]
                  (is (= (set ids) (set (map :id answer))))
                  (is (= 3 (count body-queries)) "20 IDs use ceil(20/(8-1)) queries, including LIMIT parameter")
                  (is (every? #(<= (dec (count %)) 8) body-queries))
                  (is (= :storage-error/source-projection-unsupported
                         (binding [util/*max-statement-params* 8]
                           (refusal #(sp/read-source-projection backend (assoc request :max-rows 19)))))))))))


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
        original projected-pg/read-projection
        injected (atom 0)]
    ;; The normal SQL unique index excludes ties. Simulate a legacy/corrupt
    ;; backend receipt to prove the defensive path refuses before edge reads.
    (with-redefs [projected-pg/read-projection
                  (fn [backend connection fields-for request]
                    (let [rows (original backend connection fields-for request)]
                      (if (and (= :query (:op request)) (= :binding-version (:entity request)) (seq rows))
                        (do (swap! injected inc) (conj rows (assoc (first rows) :id (random-uuid))))
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
        {:keys [owner other item junction] binding-row :binding} (fixture-rows storage)
        feature (vs/switch-branch storage (:id (vs/create-branch! storage "projection-source")))
        target (vs/switch-branch storage (:id (vs/create-branch! storage "projection-target")))]
    (sp/update-entity feature :binding (:id binding-row) {:ref-fn-id (:id owner)})
    (sp/update-entity feature :binding-list-item (:id item) {:ref-fn-id (:id owner)})
    (binding [vs/*tombstone-delete?* true] (sp/delete-entity feature :fn-slot (:id junction)))
    (snapshot feature
              (fn [scoped]
                (let [ordinary (sp/query-bounded-entities scoped :fn-slot {:fn-id [(:id owner) (:id other)]} 100)
                      projected (edges scoped [(:id owner) (:id other)] (constantly nil))]
                  (is (empty? ordinary))
                  (is (empty? (:fn-slots projected))))))
    (vs/merge-branch! target (:branch-id feature))
    ;; A later unmerged source edit must not cross the stored merge cutoff.
    (sp/update-entity feature :binding (:id binding-row) {:ref-fn-id (:id other)})
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
