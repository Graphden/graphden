(ns graphden.datomic-storage.no-changes-test
  "Tests for datomic-storage re-initialization with no changes."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.datomic-storage.test-setup :as setup]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th]))


(deftest no-changes-test
  (testing "re-initializing with same schema reports no changes"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (let [changes (sp/initialize storage schema)]
          (is (= [] (:created (:entities changes))))
          (is (= {} (:renamed (:entities changes))))
          (is (= [] (:created (:fields changes))))
          (is (= [] (:renamed (:fields changes)))))
        (finally
          (sp/close storage))))))
