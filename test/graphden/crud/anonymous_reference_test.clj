(ns graphden.crud.anonymous-reference-test
  "Stored anonymous references retain identity through checker reconstruction."
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.crud.type-check :as reconstruct]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.remote.core :as remote]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.check :as check]))


(deftest stored-bindings-and-list-items-reconstruct-anonymous-identity-references
  (let [target (random-uuid)
        scalar-binding {:id (random-uuid) :ref-fn-id target}
        list-binding {:id (random-uuid) :list-append true}
        storage (remote/from-bundle
                  {:binding-list-item [{:id (random-uuid) :binding-id (:id list-binding)
                                        :position 0 :ref-fn-id target :value nil}]})
        rows {target {:id target :name nil}}
        shape #(reconstruct/binding-shape-for-edn storage rows {} {} %)]
    (is (= {:ref target} (shape scalar-binding)))
    (is (= [{:ref target}] (shape list-binding)))
    (is (= {:ref target :type :fn-ref}
           (shape (assoc scalar-binding :type-override-fn-id ids/fn-ref-type-id))))
    (is (= :named-target
           (reconstruct/binding-shape-for-edn storage {target {:id target :name "named-target"}}
                                              {} {} scalar-binding)))
    (binding [registry/*rich-types-override* (atom {})
              registry/*per-org-rich-override* (atom {})]
      (tenancy/with-org tenancy/public-org
                        (registry/record-rich-types! :scalar-parent {:args {:value :int} :return-type :int})
                        (registry/record-rich-types! :list-parent {:args {:items [:list :int]} :return-type [:list :int]})
                        (registry/record-rich-types-raw! target nil {:return :int :args {} :namespace "personal.components"})
                        (doseq [[parent args expected] [[:scalar-parent {:value (shape scalar-binding)} :int]
                                                        [:list-parent {:items (shape list-binding)} [:list :int]]]]
                          (let [consumer (random-uuid)]
                            (check/check-fn-def! {:fn-id consumer :name :consumer :parent parent :args args})
                            (is (= expected (:return (registry/rich-type-of-id consumer))))))
                        (registry/record-rich-types-raw! target nil {:return [:secret :int] :args {}})
                        (is (thrown? clojure.lang.ExceptionInfo
                              (check/check-fn-def! {:fn-id (random-uuid) :name :plain-sink
                                                    :parent :scalar-parent :return-type :int
                                                    :args {:value (shape scalar-binding)}})))))))


(deftest uuid-checker-lookup-preserves-exact-tenant-identity-precedence
  (let [id (random-uuid)]
    (binding [registry/*rich-types-override* (atom {})
              registry/*per-org-rich-override* (atom {})]
      (tenancy/with-org tenancy/public-org
                        (registry/record-rich-types-raw! id nil {:return :text :args {:value :text}}))
      (tenancy/with-org "anonymous-reference-tenant"
                        (registry/record-rich-types-raw! id nil {:return :int :args {:value :int}})
                        (is (= (registry/rich-type-of-id id) (registry/rich-type-of id)))
                        (is (= :int (registry/rich-type-of id :value))))
      (tenancy/with-org tenancy/public-org
                        (is (= (registry/rich-type-of-id id) (registry/rich-type-of id)))
                        (is (= :text (registry/rich-type-of id :value)))))))
