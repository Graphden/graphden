(ns graphden.postgres-storage.migration-test
  "Unit tests for PostgreSQL migration functions.
   Tests internal functions that don't require a database connection."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.postgres-storage.migration :as migration]))


;; === Migration context tests ===

(deftest create-migration-context-test
  (testing "creates context with all required atoms"
    (let [ctx (#'migration/create-migration-context)]
      (is (map? ctx))
      (is (instance? clojure.lang.Atom (:created-enums ctx)))
      (is (instance? clojure.lang.Atom (:renamed-enums ctx)))
      (is (instance? clojure.lang.Atom (:created-enum-values ctx)))
      (is (instance? clojure.lang.Atom (:created-entities ctx)))
      (is (instance? clojure.lang.Atom (:renamed-entities ctx)))
      (is (instance? clojure.lang.Atom (:created-fields ctx)))
      (is (instance? clojure.lang.Atom (:renamed-fields ctx)))
      (is (instance? clojure.lang.Atom (:columns-cache ctx)))))

  (testing "all atoms start empty"
    (let [ctx (#'migration/create-migration-context)]
      (is (= [] @(:created-enums ctx)))
      (is (= {} @(:renamed-enums ctx)))
      (is (= [] @(:created-enum-values ctx)))
      (is (= [] @(:created-entities ctx)))
      (is (= {} @(:renamed-entities ctx)))
      (is (= [] @(:created-fields ctx)))
      (is (= [] @(:renamed-fields ctx)))
      (is (= {} @(:columns-cache ctx))))))


(deftest context->changes-test
  (testing "extracts empty changes from fresh context"
    (let [ctx (#'migration/create-migration-context)
          changes (#'migration/context->changes ctx)]
      (is (= {:entities {:created [] :renamed {}}
              :fields {:created [] :renamed []}
              :enums {:created [] :renamed {}}
              :enum-values {:created []}}
             changes))))

  (testing "extracts populated changes from modified context"
    (let [ctx (#'migration/create-migration-context)]
      ;; Populate context
      (swap! (:created-entities ctx) conj :user :order)
      (swap! (:renamed-entities ctx) assoc :old-name :new-name)
      (swap! (:created-fields ctx) conj {:entity :user :field :email})
      (swap! (:renamed-fields ctx) conj {:entity :user :old-field :name :new-field :full-name})
      (swap! (:created-enums ctx) conj :status)
      (swap! (:renamed-enums ctx) assoc :old-status :new-status)
      (swap! (:created-enum-values ctx) conj {:enum :status :value :active})

      (let [changes (#'migration/context->changes ctx)]
        (is (= [:user :order] (:created (:entities changes))))
        (is (= {:old-name :new-name} (:renamed (:entities changes))))
        (is (= [{:entity :user :field :email}] (:created (:fields changes))))
        (is (= [{:entity :user :old-field :name :new-field :full-name}] (:renamed (:fields changes))))
        (is (= [:status] (:created (:enums changes))))
        (is (= {:old-status :new-status} (:renamed (:enums changes))))
        (is (= [{:enum :status :value :active}] (:created (:enum-values changes))))))))


;; === log-migration-summary tests ===

(deftest log-migration-summary-test
  (testing "handles empty changes"
    ;; Should not throw
    (is (nil? (#'migration/log-migration-summary
               {:entities {:created [] :renamed {}}
                :fields {:created [] :renamed []}
                :enums {:created [] :renamed {}}
                :enum-values {:created []}}
               false))))

  (testing "handles first init with changes"
    (is (nil? (#'migration/log-migration-summary
               {:entities {:created [:user :order] :renamed {}}
                :fields {:created [{:entity :user :field :name}] :renamed []}
                :enums {:created [:status] :renamed {}}
                :enum-values {:created [{:enum :status :value :active}]}}
               true))))

  (testing "handles migration with changes"
    (is (nil? (#'migration/log-migration-summary
               {:entities {:created [:product] :renamed {:old-user :user}}
                :fields {:created [] :renamed [{:entity :user :old-field :n :new-field :name}]}
                :enums {:created [] :renamed {:old-status :status}}
                :enum-values {:created []}}
               false))))

  (testing "handles nil nested values gracefully"
    ;; Tests the (get-in ... []) default
    (is (nil? (#'migration/log-migration-summary {} false)))))


;; === get-cached-columns tests ===

(deftest get-cached-columns-test
  (testing "returns cached value on second call"
    (let [ctx (#'migration/create-migration-context)
          ;; Pre-populate cache
          _ (swap! (:columns-cache ctx) assoc "users" {:name {:type :text}})]
      ;; Should return cached value without needing ds
      (is (= {:name {:type :text}}
             (#'migration/get-cached-columns nil "users" ctx)))))

  (testing "cache is per-table"
    (let [ctx (#'migration/create-migration-context)]
      (swap! (:columns-cache ctx) assoc "users" {:name {:type :text}})
      (swap! (:columns-cache ctx) assoc "orders" {:total {:type :numeric}})
      (is (= {:name {:type :text}} (#'migration/get-cached-columns nil "users" ctx)))
      (is (= {:total {:type :numeric}} (#'migration/get-cached-columns nil "orders" ctx))))))
