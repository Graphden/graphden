(ns graphden.executor.compile.lookups-test
  "Tests for `graphden.executor.compile.lookups` helpers — currently
   focused on `effective-reader-slot-id`, the rename-aware slot-id
   resolver Phase 4 of the slot-id-keyed runtime refactor uses for
   `arg-builder :free` reads."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.test-support :as support]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(deftest no-rename-falls-back-to-chain-leaf-test
  (testing "fn without renames → reader uses the chain-leaf slot-id"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/build-fn! storage
                                    {:name "lrs-base"
                                     :slots [{:name "x" :type :int}]})
              fn1  (setup/build-fn! storage
                                    {:name "lrs-fn1" :parent base})
              chain-leaf (-> base :slots (get "x") :id)
              lookups (support/lookups-for storage)]
          (is (= chain-leaf
                 (l/effective-reader-slot-id (-> fn1 :fn :id)
                                             chain-leaf
                                             lookups))
              "no own renames → returns the chain-leaf itself"))
        (finally (sp/close storage))))))


(deftest own-rename-wins-over-chain-leaf-test
  (testing "fn with own rename of the slot → reader uses the rename slot's id"
    ;; The #104-style scenario in synthetic form: two composed fns of
    ;; the same base-fn, each with its own rename, must resolve to
    ;; DIFFERENT reader slot-ids (the rename slots are distinct).
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/build-fn! storage
                                    {:name "lrs2-base"
                                     :slots [{:name "value" :type :int}]})
              chain-leaf (-> base :slots (get "value") :id)
              ;; Composed fn A — owns a rename slot from "value" to "src".
              fn-a (setup/create-composed-fn! storage "lrs2-a" (-> base :fn :id))
              s-src (sp/create-entity storage :slot
                                      {:name "src"
                                       :type-fn-id (-> base :slots (get "value")
                                                       :type-fn-id)
                                       :source-slot-id chain-leaf})
              _ (setup/attach-slot! storage (:id fn-a) (:id s-src) 0)
              ;; Composed fn B — owns a SEPARATE rename slot named "alt".
              fn-b (setup/create-composed-fn! storage "lrs2-b" (-> base :fn :id))
              s-alt (sp/create-entity storage :slot
                                      {:name "alt"
                                       :type-fn-id (-> base :slots (get "value")
                                                       :type-fn-id)
                                       :source-slot-id chain-leaf})
              _ (setup/attach-slot! storage (:id fn-b) (:id s-alt) 0)
              lookups (support/lookups-for storage)]
          (is (= (:id s-src)
                 (l/effective-reader-slot-id (:id fn-a) chain-leaf lookups))
              "fn-a's reader uses its own :src rename slot id")
          (is (= (:id s-alt)
                 (l/effective-reader-slot-id (:id fn-b) chain-leaf lookups))
              "fn-b's reader uses its own :alt rename slot id")
          (is (not= (l/effective-reader-slot-id (:id fn-a) chain-leaf lookups)
                    (l/effective-reader-slot-id (:id fn-b) chain-leaf lookups))
              "distinct rename slots → distinct fa keys → no collision"))
        (finally (sp/close storage))))))


(deftest ancestor-rename-wins-from-descendant-test
  (testing "descendant of a renaming fn inherits the rename slot-id"
    ;; When fn C is composed on fn-a (which renamed :value → :src),
    ;; C's reader walks the chain and finds fn-a's rename slot.
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/build-fn! storage
                                    {:name "lrs3-base"
                                     :slots [{:name "value" :type :int}]})
              chain-leaf (-> base :slots (get "value") :id)
              fn-a (setup/create-composed-fn! storage "lrs3-a" (-> base :fn :id))
              s-src (sp/create-entity storage :slot
                                      {:name "src"
                                       :type-fn-id (-> base :slots (get "value")
                                                       :type-fn-id)
                                       :source-slot-id chain-leaf})
              _ (setup/attach-slot! storage (:id fn-a) (:id s-src) 0)
              fn-c (setup/create-composed-fn! storage "lrs3-c" (:id fn-a))]
          (is (= (:id s-src)
                 (l/effective-reader-slot-id
                   (:id fn-c) chain-leaf (support/lookups-for storage)))
              "C inherits A's rename slot id — propagates through chain"))
        (finally (sp/close storage))))))


(deftest multi-hop-rename-resolves-to-closest-test
  (testing "rename of a rename — closest-in-chain rename wins"
    ;; A renames :value → :src. B (composed on A) renames :src → :double-src.
    ;; B's reader uses B's own :double-src slot id (closest).
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/build-fn! storage
                                    {:name "lrs4-base"
                                     :slots [{:name "value" :type :int}]})
              chain-leaf (-> base :slots (get "value") :id)
              fn-a (setup/create-composed-fn! storage "lrs4-a" (-> base :fn :id))
              s-src (sp/create-entity storage :slot
                                      {:name "src"
                                       :type-fn-id (-> base :slots (get "value")
                                                       :type-fn-id)
                                       :source-slot-id chain-leaf})
              _ (setup/attach-slot! storage (:id fn-a) (:id s-src) 0)
              fn-b (setup/create-composed-fn! storage "lrs4-b" (:id fn-a))
              s-dbl (sp/create-entity storage :slot
                                      {:name "double-src"
                                       :type-fn-id (-> base :slots (get "value")
                                                       :type-fn-id)
                                       :source-slot-id (:id s-src)})
              _ (setup/attach-slot! storage (:id fn-b) (:id s-dbl) 0)]
          (is (= (:id s-dbl)
                 (l/effective-reader-slot-id
                   (:id fn-b) chain-leaf (support/lookups-for storage)))
              "closest rename (B's :double-src) wins, transitive chain reaches :value"))
        (finally (sp/close storage))))))
