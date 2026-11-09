(ns ^:integration graphden.input.storage-test
  "Real PostgreSQL checks of offline v2 storage, using the normal decorators."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.input.access :as access]
    [graphden.input.migration :as migration]
    [graphden.input.offline :as offline]
    [graphden.input.rows :as rows]
    [graphden.schema.graph.schema :as graph]
    [graphden.schema.malli.core :as malli]
    [graphden.schema.protocol.protocol :as schema]
    [graphden.schema.traits.schema :as traits]
    [graphden.schema.versioned.schema :as versioned]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.input :as input-storage]))


(def ^:dynamic *container* nil)
(use-fixtures :once (th/create-container-fixture #'*container*))


(use-fixtures :each (th/create-clean-db-fixture #'*container*)
  (fn [f] (binding [access/*offline?* true] (f))))


(defn- base-storage
  []
  (let [s (-> (malli/create-builder) graph/extend-builder versioned/extend-builder
              traits/extend-builder schema/build)]
    (-> (pg/create-storage (th/get-container-config *container*))
        (sp/initialize-with-cleanup! s))))


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
