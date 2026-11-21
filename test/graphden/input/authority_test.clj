(ns graphden.input.authority-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.authority :as authority]
    [graphden.tenancy.context :as tenancy]))


(deftest a-named-operation-is-confined-to-its-live-request
  (let [actor {:org "acme" :user-id "writer" :authenticated? true}
        store {:identity (random-uuid)}
        branch (random-uuid)
        scope {:operation :binding :phase :check :owner (random-uuid) :id nil}]
    (binding [tenancy/*current-org* "acme" tenancy/*current-principal* actor]
      (let [late (authority/call store branch scope
                                 (fn []
                                   (is (= (assoc scope :branch branch) (authority/current store)))
                                   (is (nil? (authority/current (into {} store))))
                                   (binding [tenancy/*current-org* "other"]
                                     (is (nil? (authority/current store))))
                                   (binding [tenancy/*current-principal* (assoc actor :user-id "reader")]
                                     (is (nil? (authority/current store))))
                                   (is (nil? @(future (authority/current store))))
                                   (bound-fn [] (authority/current store))))]
        (is (nil? (late)))
        (is (nil? (authority/current store)))))))


(deftest installed-tenancy-requires-the-entire-domain-operation-set
  (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
    (is (nil? (authority/operations {})))
    (tenancy/install-org-cap-fn! (constantly false))
    (doseq [store [{} {::authority/operations {:source-bindings identity :version-ids identity}}]]
      (is (= :authz/forbidden (try (authority/operations store)
                                   (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
    (let [ops (zipmap [:source-bindings :version-ids :validate-candidates!] (repeat identity))]
      (is (= ops (authority/operations {::authority/operations ops}))))))
