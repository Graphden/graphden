(ns ^:integration graphden.crud.secret-shape-test
  "Tests for the shared secret-shape predicates. `secret-fn?` is pure
   data; `find-vault-get-fn-id` needs storage so we run against the
   shared PG container fixture."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.secret-shape :as shape]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(deftest secret-fn?-pure-test
  (testing "exactly [vault-get-id] in parent-ids → true (legacy shape)"
    (let [vg (random-uuid)
          sl (random-uuid)]
      (is (true? (shape/secret-fn? {:parent-ids [vg]} vg sl)))))

  (testing "exactly [secret-leaf-id] in parent-ids → true (F-4 shape)"
    (let [vg (random-uuid)
          sl (random-uuid)]
      (is (true? (shape/secret-fn? {:parent-ids [sl]} vg sl)))))

  (testing "empty parent-ids → false"
    (let [vg (random-uuid)
          sl (random-uuid)]
      (is (not (shape/secret-fn? {:parent-ids []} vg sl)))
      (is (not (shape/secret-fn? {:parent-ids nil} vg sl)))))

  (testing "MI child (vault-get or secret-leaf + another parent) → false"
    (let [vg (random-uuid)
          sl (random-uuid)
          other (random-uuid)]
      (is (not (shape/secret-fn? {:parent-ids [vg other]} vg sl)))
      (is (not (shape/secret-fn? {:parent-ids [sl other]} vg sl)))
      (is (not (shape/secret-fn? {:parent-ids [other vg]} vg sl)))))

  (testing "different sole parent → false"
    (let [vg (random-uuid)
          sl (random-uuid)
          other (random-uuid)]
      (is (not (shape/secret-fn? {:parent-ids [other]} vg sl)))))

  (testing "both ids nil → false (vault package not loaded)"
    (is (not (shape/secret-fn? {:parent-ids [(random-uuid)]} nil nil)))))


(deftest find-vault-get-fn-id-test
  (let [storage (setup/create-test-storage)]
    (try
      (testing "no vault-get row → nil (package not loaded)"
        (is (nil? (shape/find-vault-get-fn-id storage))))

      (testing "after seeding vault-get → returns its id"
        (let [vg (setup/create-base-fn! storage "vault-get" :text)]
          (is (= (:id vg) (shape/find-vault-get-fn-id storage)))))

      (testing "name match is exact — `vault-getter` doesn't shadow"
        (let [storage2 (setup/create-test-storage)
              _ (setup/create-base-fn! storage2 "vault-getter" :text)]
          (try
            (is (nil? (shape/find-vault-get-fn-id storage2)))
            (finally (sp/close storage2)))))
      (finally (sp/close storage)))))
