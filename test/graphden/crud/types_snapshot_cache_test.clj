(ns ^:serial graphden.crud.types-snapshot-cache-test
  "Count snapshot work through an installed, freshly read ACL seam. No DB.
   Serial because the wire-projection counter wraps a process-wide Var."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]))


(defn- scenario
  [run]
  (let [public-id (random-uuid)
        private-id (random-uuid)
        foreign-id (random-uuid)
        graph {:fns [{:id public-id :name "shared"}
                     {:id private-id :name "private" :org-id "acme"}
                     {:id foreign-id :name "foreign" :org-id "other"}]
               :slots [] :fn-slots [] :bindings [] :list-items []}
        ctx {:graph-cache (atom graph)}
        grants (atom {"alice" #{private-id} "bob" #{}})
        counts (atom {:filters 0 :policy-reads 0 :projected-entries 0})
        ;; The addon owns grant semantics. This fixture reads its policy store
        ;; on each seam call, making fresh authority observable without SQL.
        policy-store (reify sp/StorageCRUD
                       (query-entities
                         [_ _ where]
                         (swap! counts update :policy-reads inc)
                         (get @grants (:subject-id where))))
        filter-graph (fn [g]
                       (swap! counts update :filters inc)
                       (let [allowed (sp/query-entities policy-store :grant
                                                        {:subject-id (tenancy/current-user-id)})]
                         (update g :fns
                                 #(filterv (fn [f]
                                             (or (nil? (:org-id f))
                                                 (contains? allowed (:id f)))) %))))
        project types-api/project-rich-type-entry]
    (binding [registry/*rich-types-override* (atom {})
              tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))
              tenancy/*current-org* "acme"
              tenancy/*current-principal* {:authenticated? true :user-id "alice"}]
      (doseq [[id n] [[public-id :shared] [private-id :private] [foreign-id :foreign]]]
        (registry/record-rich-types-raw! id n {:return :int :args {}}))
      (tenancy/install-graph-read-filter! filter-graph)
      (with-redefs-fn
        {#'types-api/project-rich-type-entry
         (fn [entry]
           (swap! counts update :projected-entries inc)
           (project entry))}
        #(run {:ctx ctx :grants grants :counts counts
               :public-id public-id :private-id private-id})))))


(deftest repeated-tenant-reads-reuse-only-pure-augmentation
  (scenario
    (fn [{:keys [ctx counts]}]
      (let [first-result (types-api/all-rich-types ctx)
            second-result (types-api/all-rich-types ctx)]
        (is (= #{:shared :private} (set (keys first-result))))
        (is (= first-result second-result))
        (is (= {:filters 2 :policy-reads 2 :projected-entries 2} @counts)
            "two requests still read policy twice, but derive types once")))))


(deftest fresh-authority-vetoes-a-warm-snapshot
  (scenario
    (fn [{:keys [ctx grants counts private-id]}]
      (is (contains? (types-api/all-rich-types ctx) :private))
      (testing "another principal in the same org cannot inherit Alice's visibility"
        (binding [tenancy/*current-principal* {:authenticated? true :user-id "bob"}]
          (is (= #{:shared} (set (keys (types-api/all-rich-types ctx)))))))
      (is (contains? (types-api/all-rich-types ctx) :private))
      (testing "revoking a grant changes neither source identity nor org"
        (swap! grants update "alice" disj private-id)
        (is (= #{:shared} (set (keys (types-api/all-rich-types ctx))))))
      (testing "a stable denied view can reuse pure work, never its authorization"
        (is (= #{:shared} (set (keys (types-api/all-rich-types ctx))))))
      (is (= {:filters 5 :policy-reads 5 :projected-entries 6} @counts)))))


(deftest registry-and-graph-replacements-invalidate-the-snapshot
  (scenario
    (fn [{:keys [ctx counts public-id private-id]}]
      (let [original (types-api/all-rich-types ctx)]
        (testing "same identity with a changed registry signature is fresh"
          (registry/record-rich-types-raw! public-id :shared {:return :text :args {}})
          (is (= :text (get-in (types-api/all-rich-types ctx) [:shared :return]))))
        (testing "a graph mutation contributes a new structural type"
          (swap! (:graph-cache ctx) update :fns conj
                 {:id (random-uuid) :name "choice"
                  :constraint [:union :int :text]})
          (is (= [:union :int :text]
                 (get-in (types-api/all-rich-types ctx) [:choice :return]))))
        (testing "other orgs still exclude Acme's private signature"
          (binding [tenancy/*current-org* "other"
                    tenancy/*current-principal* {:authenticated? true :user-id "bob"}]
            (is (not (contains? (types-api/all-rich-types ctx) :private)))))
        (is (= :int (get-in original [:shared :return]))
            "a later read never mutates an earlier returned snapshot")
        (is (some #(= private-id (:id %)) (:fns @(:graph-cache ctx))))
        (is (= {:filters 4 :policy-reads 4 :projected-entries 7} @counts))))))
