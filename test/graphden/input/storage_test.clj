(ns ^:integration graphden.input.storage-test
  "Real PostgreSQL checks of offline v2 storage, using the normal decorators."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.input.access :as access]
    [graphden.input.migration :as migration]
    [graphden.input.offline :as offline]
    [graphden.input.references :as input-refs]
    [graphden.input.rows :as rows]
    [graphden.input.storage :as input-edit]
    [graphden.schema.graph.schema :as graph]
    [graphden.schema.malli.core :as malli]
    [graphden.schema.protocol.protocol :as schema]
    [graphden.schema.traits.schema :as traits]
    [graphden.schema.versioned.schema :as versioned]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.versioning.identity-repair :as identity-repair]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.input :as input-storage]
    [graphden.versioning.storage.resolution :as resolution]))


(def ^:dynamic *container* nil)
(use-fixtures :once (th/create-container-fixture #'*container*))


(use-fixtures :each (th/create-clean-db-fixture #'*container*)
  (fn [f] (binding [access/*offline?* true] (f))))


(defn- storage-schema
  []
  (-> (malli/create-builder) graph/extend-builder versioned/extend-builder
      traits/extend-builder schema/build))


(defn- base-storage
  []
  (-> (pg/create-storage (th/get-container-config *container*))
      (sp/initialize-with-cleanup! (storage-schema))))


(defmacro ^:private with-storage
  [[sym] & body]
  `(let [~sym (base-storage)]
     (try ~@body (finally (sp/close ~sym)))))


(defn- seed!
  [storage]
  (let [text (sp/create-entity storage :fn {:name "text"})
        record (sp/create-entity storage :fn {:name "user"})
        root (sp/create-entity storage :fn {:name "input"})
        name-field (sp/create-entity storage :slot {:name "name" :type-fn-id (:id text)})
        email-field (sp/create-entity storage :slot {:name "email" :type-fn-id (:id text)})
        user-field (sp/create-entity storage :slot {:name "user" :type-fn-id (:id record)})
        owner (sp/create-entity storage :fn {:name "send" :input-type-fn-id (:id root)
                                             :return-type-fn-id (:id text)})]
    (sp/create-entities storage :fn-slot
                        [{:fn-id (:id record) :slot-id (:id name-field) :position 0}
                         {:fn-id (:id record) :slot-id (:id email-field) :position 1}
                         {:fn-id (:id root) :slot-id (:id user-field) :position 0}])
    {:owner (:id owner) :input (:id root) :user (:id user-field)
     :name (:id name-field) :email (:id email-field)}))


(defn- literal
  [{:keys [owner]} path value]
  {:id (random-uuid) :fn-id owner :scope-path [] :target-path path
   :expression-kind "value" :value-present true :value value})


(defn- attempt
  [f]
  (try (f) :ok (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(deftest persisted-roundtrip-and-offline-admission-test
  (with-storage [base]
    (let [v (vs/wrap-with-versioning base)
          {:keys [owner input user name] :as ids} (seed! v)
          row (literal ids [user name] nil)
          created (sp/create-entity v :input-binding row)
          loaded (rows/normalize (sp/read-entity v :input-binding (:id created)))]
      (is (= input (:input-type-fn-id (sp/read-entity v :fn owner))))
      (is (= (select-keys row [:id :fn-id :scope-path :target-path :expression-kind :value :value-present])
             (select-keys loaded [:id :fn-id :scope-path :target-path :expression-kind :value :value-present])))
      (is (= 1 (count (sp/query-entities base :input-binding-version {}))))
      (binding [access/*offline?* false]
        (is (= :input/offline-only
               (attempt #(sp/create-entity v :input-binding (literal ids [user] {})))))))))


(deftest single-bulk-and-inherited-overlaps-test
  (with-storage [base]
    (let [v (vs/wrap-with-versioning base)
          {:keys [owner input user name email] :as ids} (seed! v)
          first-row (literal ids [user name] "Alice")
          second-row (literal ids [user email] "a@example.test")]
      (sp/create-entities v :input-binding [first-row second-row])
      (is (= 2 (count (sp/query-entities v :input-binding {}))))
      (is (= :input/invalid-binding
             (attempt #(sp/create-entity v :input-binding (literal ids [user] {})))))
      (is (= :input/invalid-binding
             (attempt #(sp/update-entity v :input-binding (:id second-row) {:target-path [user]}))))
      (is (= 2 (count (sp/query-entities v :input-binding {}))))
      (let [child (sp/create-entity v :fn {:name "child" :parent-ids [owner]
                                           :input-type-fn-id input})]
        (is (= :input/invalid-binding
               (attempt #(sp/create-entity v :input-binding
                                           (assoc (literal ids [user name] "Alice") :fn-id (:id child)))))))
      (is (= :input/invalid-row
             (attempt #(sp/create-entity v :input-binding (literal ids [(random-uuid)] "bad"))))))))


(deftest branch-merge-prefix-conflicts-are-atomic-test
  (with-storage [base]
    (let [v (vs/wrap-with-versioning base)
          {:keys [user name] :as ids} (seed! v)
          branch (vs/create-branch! v "nested")
          feature (vs/switch-branch v (:id branch))]
      (sp/create-entity feature :input-binding (literal ids [user name] "Alice"))
      (sp/create-entity v :input-binding (literal ids [user] {}))
      (is (= :input/invalid-binding (attempt #(vs/merge-branch! v (:id branch)))))
      (is (= [] (sp/query-entities base :branch-merge {})))
      (is (= 1 (count (sp/query-entities v :input-binding {})))))))


(deftest sibling-merge-tombstone-and-branch-reclamation-test
  (with-storage [base]
    (let [v (vs/wrap-with-versioning base)
          {:keys [user name email] :as ids} (seed! v)
          branch (vs/create-branch! v "nested")
          feature (vs/switch-branch v (:id branch))
          own (sp/create-entity v :input-binding (literal ids [user name] "Alice"))
          other (sp/create-entity feature :input-binding (literal ids [user email] "a@example.test"))]
      (vs/merge-branch! v (:id branch))
      (is (= #{(:id own) (:id other)} (set (map :id (sp/query-entities v :input-binding {})))))
      (binding [vs/*tombstone-delete?* true]
        (sp/delete-entity v :input-binding (:id other)))
      (is (nil? (sp/read-entity v :input-binding (:id other))))
      (is (= (:id other) (:id (sp/read-entity feature :input-binding (:id other)))))
      (is (= 1 (count (:bindings (input-storage/read-view base (vs/current-branch-id v)))))))))


(deftest concurrent-prefix-binds-have-one-winner-test
  (with-storage [base]
    (let [v (vs/wrap-with-versioning base)
          {:keys [user name] :as ids} (seed! v)
          start (promise)
          writes (mapv (fn [path]
                         (future @start (attempt #(sp/create-entity v :input-binding (literal ids path "x")))))
                       [[user] [user name]])]
      (deliver start true)
      (is (= #{:ok :input/invalid-binding} (set (map #(deref % 15000 :timeout) writes))))
      (is (= 1 (count (sp/query-entities v :input-binding {})))))))


(deftest offline-artifact-preserves-history-and-restores-test
  (with-storage [base]
    (let [v (vs/wrap-with-versioning base)
          t (sp/create-entity v :fn {:name "text"})
          f (sp/create-entity v :fn {:name "legacy" :return-type-fn-id (:id t)})
          slot (sp/create-entity v :slot {:name "name" :type-fn-id (:id t)})
          _ (sp/create-entity v :fn-slot {:fn-id (:id f) :slot-id (:id slot) :position 0})
          old (sp/create-entity v :binding {:fn-id (:id f) :slot-id (:id slot)
                                            :value-present true :value "before"})
          branch (vs/create-branch! v "history")
          feature (vs/switch-branch v (:id branch))
          _ (sp/update-entity feature :binding (:id old) {:value "after"})
          source (offline/snapshot base)
          artifact (-> source offline/artifact offline/encode offline/decode)]
      (is (= [] (get-in artifact [:plan :exceptions])))
      (is (= (:id old) (migration/binding-origin (:plan artifact) [(:id f) []] [(:id slot)])))
      (is (= {:status :applied} (offline/apply! base artifact)))
      (is (= {:status :already-applied} (offline/apply! base artifact)))
      (is (= "before" (:value (sp/read-entity v :input-binding (:id old)))))
      (is (= "after" (:value (sp/read-entity feature :input-binding (:id old)))))
      (let [framework [:id :branch-id :created-at :deleted-at :change-id :change-revision :author-id :author-label]
            original (set (map #(select-keys % framework) (:binding-version source)))
            migrated (set (map #(select-keys % framework) (sp/query-entities base :input-binding-version {})))]
        (is (= original migrated)))
      (is (= {:status :restored} (offline/restore! base artifact)))
      (is (= (migration/fingerprint source) (migration/fingerprint (offline/snapshot base)))))))


(deftest edit-and-remove-require-the-current-version-test
  (with-storage [base]
    (let [v (vs/wrap-with-versioning base)
          {:keys [user name] :as ids} (seed! v)
          row (sp/create-entity v :input-binding (literal ids [user name] "before"))
          version #(-> (resolution/resolve-version base :input-binding (:id row) (vs/current-branch-id v)) :id)
          old-version (version)]
      (input-edit/replace-binding! v (:id row) old-version {:value "after"})
      (is (= :input/stale-version
             (attempt #(input-edit/replace-binding! v (:id row) old-version {:value "lost"}))))
      (is (= "after" (:value (sp/read-entity v :input-binding (:id row)))))
      (is (= :input/stale-version (attempt #(input-edit/remove-binding! v (:id row) old-version))))
      (input-edit/remove-binding! v (:id row) (version))
      (is (nil? (sp/read-entity v :input-binding (:id row)))))))


(deftest historical-membership-tombstone-test
  (with-storage [base]
    (let [v (vs/wrap-with-versioning base)
          t (sp/create-entity v :fn {:name "text"})
          f (sp/create-entity v :fn {:name "legacy" :return-type-fn-id (:id t)})
          slot (sp/create-entity v :slot {:name "name" :type-fn-id (:id t)})
          member (sp/create-entity v :fn-slot {:fn-id (:id f) :slot-id (:id slot) :position 0})
          branch (vs/create-branch! v "deleted-member")
          feature (vs/switch-branch v (:id branch))]
      (binding [vs/*tombstone-delete?* true]
        (sp/delete-entity feature :fn-slot (:id member)))
      (let [artifact (offline/artifact (offline/snapshot base))
            descriptor (migration/derived-id :input-type (:id f))]
        (offline/apply! base artifact)
        (is (= [(:id slot)] (mapv :slot-id (sp/query-entities v :fn-slot {:fn-id descriptor}))))
        (is (= [] (sp/query-entities feature :fn-slot {:fn-id descriptor})))
        (offline/restore! base artifact))
      (is (= [] (sp/query-entities feature :fn-slot {:fn-id (:id f)}))))))


(deftest branch-only-binding-reclamation-test
  (with-storage [base]
    (let [v (vs/wrap-with-versioning base)
          {:keys [user name] :as ids} (seed! v)
          branch (vs/create-branch! v "temporary")
          feature (vs/switch-branch v (:id branch))
          row (sp/create-entity feature :input-binding (literal ids [user name] "temporary"))]
      (is (nil? (sp/read-entity v :input-binding (:id row))))
      (vs/delete-branch! v (:id branch))
      (is (nil? (sp/read-entity base :input-binding (:id row))))
      (is (= [] (sp/query-entities base :input-binding-version {:input-binding-id (:id row)}))))))


(deftest scoped-reference-origins-remain-shared-and-protected-test
  (with-storage [base]
    (let [v (vs/wrap-with-versioning base)
          {:keys [owner input user name email] :as ids} (seed! v)
          target (sp/create-entity v :fn {:name "shared" :input-type-fn-id input})
          edges (mapv (fn [field]
                        (sp/create-entity v :input-binding
                                          {:fn-id owner :scope-path [] :target-path [user field]
                                           :expression-kind "ref" :ref-fn-id (:id target)}))
                      [name email])
          first-use (assoc (literal ids [user name] "one") :scope-path [(:id (first edges))])
          second-use (assoc (literal ids [user name] "two") :scope-path [(:id (second edges))])]
      (sp/create-entities v :input-binding [first-use second-use])
      (is (= 4 (count (sp/query-entities v :input-binding {}))))
      (is (= [(:id target) (:id target)] (mapv :ref-fn-id edges)))
      (is (= #{user name} (input-refs/retained-ids base :slot [user name])))
      (is (= #{(:id (first edges))} (input-refs/retained-ids base :input-binding [(:id (first edges))])))
      (is (seq (identity-repair/inbound-refs-many base #{owner (:id target)})))
      (is (= :input/invalid-row
             (attempt #(sp/create-entity v :input-binding
                                         (assoc (literal ids [user email] "bad") :scope-path [(random-uuid)]))))))))


(deftest artifact-refuses-stale-source-and-stale-restore-test
  (with-storage [base]
    (let [v (vs/wrap-with-versioning base)
          artifact (offline/artifact (offline/snapshot base))]
      (sp/create-entity v :fn {:name "changed"})
      (is (= :input/stale-snapshot (attempt #(offline/apply! base artifact))))
      (is (= [] (sp/query-entities base :input-binding {})))
      (let [fresh (offline/artifact (offline/snapshot base))]
        (offline/apply! base fresh)
        (sp/create-entity v :fn {:name "changed-again"})
        (is (= :input/stale-snapshot (attempt #(offline/restore! base fresh))))))))


(deftest additive-schema-upgrade-is-idempotent-and-preserves-v1-test
  (let [full (storage-schema)
        old (-> full
                (update :entities-map dissoc :input-binding :input-binding-version)
                (update :entity-uuids-map dissoc :input-binding :input-binding-version)
                (update :constraints-map dissoc :input-binding :input-binding-version)
                (update-in [:entities-map :fn] dissoc :input-type-fn-id)
                (update-in [:entities-map :fn-version] dissoc :input-type-fn-id))
        base (pg/create-storage (th/get-container-config *container*))]
    (try
      (sp/initialize-with-cleanup! base old)
      (let [v (vs/wrap-with-versioning base)
            original (sp/create-entity v :fn {:name "retained"})]
        (sp/initialize base full)
        (sp/initialize base full)
        (is (= "retained" (:name (sp/read-entity v :fn (:id original)))))
        (is (nil? (:input-type-fn-id (sp/read-entity v :fn (:id original)))))
        (is (= [] (sp/query-entities base :input-binding {}))))
      (finally (sp/close base)))))
