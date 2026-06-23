(ns graphden.versioning.storage.resolution-test
  "Pure-helper unit tests for `versioning.storage.resolution`. The
   resolve-version / resolve-entity public surface is exercised by
   the integration suite (`versioning.storage.core-test`); this NS
   pins the pure transforms that the resolution algorithm builds on
   so they're independently verified at unit speed."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.versioning.storage.resolution :as res]))


(deftest versioned-entity?-test
  (testing "fn, fn-slot, binding, binding-list-item → true"
    (is (true? (res/versioned-entity? :fn)))
    (is (true? (res/versioned-entity? :fn-slot)))
    (is (true? (res/versioned-entity? :binding)))
    (is (true? (res/versioned-entity? :binding-list-item))))

  (testing "branch, service, execution → false (not versioned)"
    (is (false? (res/versioned-entity? :branch)))
    (is (false? (res/versioned-entity? :service)))
    (is (false? (res/versioned-entity? :execution))))

  (testing "unknown / nil → false"
    (is (false? (res/versioned-entity? :something-made-up)))
    (is (false? (res/versioned-entity? nil)))))


(deftest latest-by-created-at-test
  (let [latest-by-created-at @#'res/latest-by-created-at
        t1 #inst "2025-01-01T00:00:00.000-00:00"
        t2 #inst "2025-06-01T00:00:00.000-00:00"
        t3 #inst "2025-12-31T23:59:59.000-00:00"]

    (testing "nil for empty seq"
      (is (nil? (latest-by-created-at [])))
      (is (nil? (latest-by-created-at nil))))

    (testing "single record returned as-is"
      (let [r {:id 1 :created-at t1 :payload "only"}]
        (is (= r (latest-by-created-at [r])))))

    (testing "picks the record with the latest :created-at"
      (let [r1 {:id 1 :created-at t1}
            r2 {:id 2 :created-at t2}
            r3 {:id 3 :created-at t3}]
        (is (= r3 (latest-by-created-at [r1 r2 r3])))
        (is (= r3 (latest-by-created-at [r3 r1 r2])) "order-independent")
        (is (= r3 (latest-by-created-at [r2 r3 r1])))))

    (testing "stable on ties — first-encountered with equal stamp wins"
      (let [a {:id :a :created-at t1}
            b {:id :b :created-at t1}]
        ;; Implementation note: reduce chooses `r` only when `pos?`,
        ;; so equal stamps keep the accumulator (= first arg).
        (is (= a (latest-by-created-at [a b])))))))


(deftest extract-version-data-test
  (let [extract-version-data @#'res/extract-version-data
        version-row {:id #uuid "00000000-0000-0000-0000-000000000001"
                     :branch-id #uuid "00000000-0000-0000-0000-000000000002"
                     :created-at #inst "2025-01-01"
                     :fn-id #uuid "00000000-0000-0000-0000-000000000003"
                     :name "my-fn"
                     :description "doc"
                     :branch-local? false}]

    (testing "strips :id, :branch-id, :created-at, and the version-id-field"
      (let [stripped (extract-version-data version-row :fn-id)]
        (is (not (contains? stripped :id)))
        (is (not (contains? stripped :branch-id)))
        (is (not (contains? stripped :created-at)))
        (is (not (contains? stripped :fn-id))
            ":fn-id is the version-id-field for :fn — must be stripped")))

    (testing "preserves all other data fields"
      (let [stripped (extract-version-data version-row :fn-id)]
        (is (= "my-fn"        (:name stripped)))
        (is (= "doc"          (:description stripped)))
        (is (= false          (:branch-local? stripped)))))

    (testing "different version-id-field stays / strips correctly"
      ;; e.g. :binding rows use :binding-id as the version-id-field;
      ;; calling with the wrong field would leave it in the result.
      (let [binding-row {:id 1 :branch-id 2 :created-at 3
                         :binding-id #uuid "00000000-0000-0000-0000-000000000004"
                         :fn-id #uuid "00000000-0000-0000-0000-000000000005"
                         :value 42}
            stripped (extract-version-data binding-row :binding-id)]
        (is (not (contains? stripped :binding-id)))
        (is (contains? stripped :fn-id)
            ":fn-id is NOT the version-id-field for :binding, must remain")
        (is (= 42 (:value stripped)))))))


(deftest owning-fn-id-test
  (let [owning-fn-id @#'res/owning-fn-id
        fn-id     #uuid "00000000-0000-0000-0000-000000000010"
        other-fn  #uuid "00000000-0000-0000-0000-000000000011"]

    (testing ":fn → entity-id itself"
      (is (= fn-id (owning-fn-id :fn fn-id nil)))
      (is (= fn-id (owning-fn-id :fn fn-id {:irrelevant true}))
          "version-row content doesn't matter for :fn"))

    (testing ":fn-slot → version-row's :fn-id"
      (is (= other-fn
             (owning-fn-id :fn-slot fn-id {:fn-id other-fn}))
          "owner is the fn-id carried on the version row, NOT entity-id"))

    (testing ":binding → version-row's :fn-id"
      (is (= other-fn
             (owning-fn-id :binding fn-id {:fn-id other-fn}))))

    (testing ":binding-list-item / unknown → nil (no owner-resolution)"
      (is (nil? (owning-fn-id :binding-list-item fn-id {:fn-id other-fn}))
          "items of a filtered binding never reach a reader anyway")
      (is (nil? (owning-fn-id :branch fn-id {:fn-id other-fn}))
          ":branch isn't versioned — case dispatch falls through"))))
