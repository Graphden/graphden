(ns ^:integration graphden.crud.secret-shape-test
  "Tests for the shared secret-shape predicates. `secret-fn?` is pure
   data; `find-admin-only-vault-base-fn-ids` needs storage AND the
   in-memory rich-types-registry (which carries the `:admin-only-vault`
   tag set from `web/vault/fns.edn`), so we run against the shared PG
   container fixture and stub the registry entry directly."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.secret-shape :as shape]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(deftest secret-fn?-pure-test
  (testing "exactly [secret-leaf-id] in parent-ids → true"
    (let [sl (random-uuid)]
      (is (true? (shape/secret-fn? {:parent-ids [sl]} sl)))))

  (testing "empty parent-ids → false"
    (let [sl (random-uuid)]
      (is (not (shape/secret-fn? {:parent-ids []} sl)))
      (is (not (shape/secret-fn? {:parent-ids nil} sl)))))

  (testing "MI child (secret-leaf + another parent) → false"
    (let [sl (random-uuid)
          other (random-uuid)]
      (is (not (shape/secret-fn? {:parent-ids [sl other]} sl)))
      (is (not (shape/secret-fn? {:parent-ids [other sl]} sl)))))

  (testing "different sole parent → false"
    (let [sl (random-uuid)
          other (random-uuid)]
      (is (not (shape/secret-fn? {:parent-ids [other]} sl)))))

  (testing "id nil → false (vault package not loaded)"
    (is (not (shape/secret-fn? {:parent-ids [(random-uuid)]} nil)))))


(deftest find-admin-only-vault-base-fn-ids-test
  (testing "no tagged entries → empty set"
    (exec/with-clean-registry
      #(let [storage (setup/create-test-storage)]
         (try
           (is (= #{} (shape/find-admin-only-vault-base-fn-ids storage)))
           (finally (sp/close storage))))))

  (testing "tag → set of fn-ids; non-tagged entries excluded"
    (exec/with-clean-registry
      #(let [storage (setup/create-test-storage)]
         (try
           (registry/record-rich-types! :vault-put
                                        {:return :null :args {}
                                         :tags #{:admin-only-vault}})
           (registry/record-rich-types! :vault-metadata-get
                                        {:return :jsonb :args {}})
           (let [vp (setup/create-base-fn! storage "vault-put" :null)
                 _vmg (setup/create-base-fn! storage "vault-metadata-get" :jsonb)
                 result (shape/find-admin-only-vault-base-fn-ids storage)]
             (is (= #{(:id vp)} result)))
           (finally (sp/close storage)))))))
