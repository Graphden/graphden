(ns graphden.layout.data-test
  "The derived arg rows a card is drawn from (`derive-fn-slot-views`)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.layout.data :as data]))


(deftest a-binding-on-the-rename-root-reaches-the-view-row
  ;; `text-ok-response` exposes `body`, a rename view over `assoc`'s :value.
  ;; Binding `body` in the editor writes the row on :value (the write path
  ;; canonicalises to the family's root); the card's arg row is the view's.
  ;; The view row must carry that binding, or its `+` outlives the bind.
  (let [assoc-id (random-uuid) ring-id (random-uuid) hello-id (random-uuid)
        value-slot (random-uuid) body-slot (random-uuid) item-slot (random-uuid)
        text-id (random-uuid)
        rows (data/derive-fn-slot-views
               {:fns [{:id text-id :name "text" :parent-ids []}
                      {:id assoc-id :name "assoc" :parent-ids []}
                      {:id ring-id :name "ring-response" :parent-ids [assoc-id]}
                      {:id hello-id :name "tutorial-hello" :parent-ids [ring-id]}]
                :slots [{:id value-slot :name "value" :type-fn-id text-id}
                        {:id body-slot :name "body" :type-fn-id text-id :source-slot-id value-slot}
                        {:id item-slot :name "items" :type-fn-id text-id}]
                :fn-slots [{:fn-id assoc-id :slot-id value-slot :position 0}
                           {:fn-id ring-id :slot-id body-slot :position 0}
                           {:fn-id assoc-id :slot-id item-slot :position 1}]
                :bindings [{:id "b-hello" :fn-id hello-id :slot-id value-slot
                            :value "hello" :value-present true}
                           {:id "b-list" :fn-id hello-id :slot-id item-slot :list-append true}]
                :list-items [{:id "i1" :binding-id "b-list" :position 0 :value 1}]})
        hello-rows (filter #(= hello-id (:fn-id %)) rows)
        body-row (first (filter #(= "body" (:name %)) hello-rows))]
    (testing "the view row carries the binding written on the root"
      (is (some? body-row))
      (is (= "b-hello" (:binding-id body-row)))
      (is (= "hello" (:value body-row)))
      (is (true? (:value-present body-row))))
    (testing "and no row for the root slot itself is emitted twice"
      (is (= 1 (count (filter #(contains? #{"body" "value"} (:name %)) hello-rows)))))
    (testing "a list binding's items chain to the emitted anchor row"
      (let [items-row (first (filter #(= "items" (:name %)) hello-rows))
            item (first (filter #(= "i1" (:id %)) rows))]
        (is (= (:id items-row) (:source-id item)))
        (is (= (:id item) (:next-arg-id items-row)))))))
