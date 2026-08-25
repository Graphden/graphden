(ns graphden.versioning.storage.resolution-test
  "Pure-helper unit tests for `versioning.storage.resolution`. The
   resolve-version / resolve-entity public surface is exercised by
   the integration suite (`versioning.storage.core-test`); this NS
   pins the pure transforms that the resolution algorithm builds on
   so they're independently verified at unit speed."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
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
        (is (false? (:branch-local? stripped)))))

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
        other-fn  #uuid "00000000-0000-0000-0000-000000000011"
        bind-id   #uuid "00000000-0000-0000-0000-000000000012"
        ;; Stub storage — only `:binding-list-item` reaches into it, to
        ;; chain through the binding IDENTITY row to its owning fn-id.
        ;; Intentional partial impl: `owning-fn-id` only calls read-entity.
        stub #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify sp/StorageCRUD
          (read-entity
            [_ entity-name id]
            (when (and (= entity-name :binding) (= id bind-id))
              {:id bind-id :fn-id other-fn})))]

    (testing ":fn → entity-id itself (storage unused)"
      (is (= fn-id (owning-fn-id nil :fn fn-id nil)))
      (is (= fn-id (owning-fn-id nil :fn fn-id {:irrelevant true}))
          "version-row content doesn't matter for :fn"))

    (testing ":fn-slot → version-row's :fn-id"
      (is (= other-fn
             (owning-fn-id nil :fn-slot fn-id {:fn-id other-fn}))
          "owner is the fn-id carried on the version row, NOT entity-id"))

    (testing ":binding → version-row's :fn-id"
      (is (= other-fn
             (owning-fn-id nil :binding fn-id {:fn-id other-fn}))))

    (testing ":binding-list-item → chains through the binding's :fn-id"
      (is (= other-fn
             (owning-fn-id stub :binding-list-item fn-id {:binding-id bind-id}))
          "reads the binding identity row to recover its owning fn"))

    (testing "unknown entity → nil"
      (is (nil? (owning-fn-id nil :branch fn-id {:fn-id other-fn}))
          ":branch isn't versioned — case dispatch falls through"))))


(deftest extract-fn-refs-includes-resolver-fn-id
  ;; A `:resolved-value` binding references its resolver fn ONLY through
  ;; :resolver-fn-id (its :ref-fn-id is nil). If the closure walk skips it, the
  ;; resolver's subtree is dropped from the resolved execution graph → the
  ;; protocol's closure contract is broken (fn-not-found on first force).
  (let [extract @#'res/extract-fn-refs-from-bindings
        rid #uuid "00000000-0000-0000-0000-0000000000aa"
        ref #uuid "00000000-0000-0000-0000-0000000000bb"
        tov #uuid "00000000-0000-0000-0000-0000000000cc"]
    (testing ":resolver-fn-id is chased alongside :ref-fn-id and :type-override-fn-id"
      (is (contains? (extract [{:resolver-fn-id rid :value-present true}]) rid))
      (is (= #{ref tov rid}
             (extract [{:ref-fn-id ref} {:type-override-fn-id tov} {:resolver-fn-id rid}]))))
    (testing "nil resolver-fn-id contributes nothing"
      (is (empty? (extract [{:resolver-fn-id nil}]))))))
