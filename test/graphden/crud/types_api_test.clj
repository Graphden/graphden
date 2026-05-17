(ns graphden.crud.types-api-test
  "Tests for `graphden.crud.types-api` — the bodies behind `/api/types`,
   `/api/types/compatible`, `/api/types/candidates`, `/api/types/usages`,
   plus the shared graph-cache loaders and role / rich-type derivations.

   The pure helpers (`compute-fn-role`, `json->type`, `describe-mismatch`,
   `constraint-contains-type-ref?`, `types-compatible`) need no fixture;
   the rest go through the shared container."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.types-api :as ta]
    [graphden.executor.context :as ctx]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(defn- test-ctx
  [storage]
  (ctx/create-context {:storage storage :base-fns {}}))


;; ============================================================================
;; compute-fn-role — pure
;; ============================================================================

(deftest compute-fn-role-test
  (testing "parent-ids present → :composed"
    (is (= :composed (ta/compute-fn-role {:parent-ids [(random-uuid)]} false {}))))

  (testing "impl-hash → :base-fn"
    (is (= :base-fn (ta/compute-fn-role {:parent-ids [] :impl-hash "h"} false {}))))

  (testing "nil impl-hash but a registry entry with args → :base-fn"
    (is (= :base-fn
           (ta/compute-fn-role {:name "regfn" :parent-ids []} false
                               {:regfn {:args {:a :int}}}))))

  (testing "base-fn-id → :refinement, element-fn-id → :list"
    (is (= :refinement (ta/compute-fn-role {:base-fn-id (random-uuid)} false {})))
    (is (= :list (ta/compute-fn-role {:element-fn-id (random-uuid)} false {}))))

  (testing "constraint head → :union / :variant / :fn-type"
    (is (= :union    (ta/compute-fn-role {:constraint [:union :int :text]} false {})))
    (is (= :variant  (ta/compute-fn-role {:constraint [:variant :ok :int]} false {})))
    (is (= :fn-type  (ta/compute-fn-role {:constraint [:fn {} :int]} false {}))))

  (testing "plain row → :record when it has slots, :primitive otherwise"
    (is (= :record (ta/compute-fn-role {:name "rec"} true {})))
    (is (= :primitive (ta/compute-fn-role {:name "prim"} false {})))))


;; ============================================================================
;; json->type / json->type-form — pure
;; ============================================================================

(deftest json->type-test
  (testing "strings → keywords, scalars pass through"
    (is (= :int (ta/json->type "int")))
    (is (= 42 (ta/json->type 42)))
    (is (true? (ta/json->type true)))
    (is (nil? (ta/json->type nil))))

  (testing "fn-type / refinement / record JSON shapes round-trip"
    (is (= [:fn {:x :int} :int]
           (ta/json->type ["fn" {"x" "int"} "int"])))
    (is (= [:refine :int [:>= 0]]
           (ta/json->type ["refine" "int" [">=" 0]])))
    (is (= {:a :int :b :text}
           (ta/json->type {"a" "int" "b" "text"}))))

  (testing "json->type-form behaves the same"
    (is (= [:union :int :text]
           (ta/json->type-form ["union" "int" "text"])))))


;; ============================================================================
;; describe-mismatch — pure
;; ============================================================================

(deftest describe-mismatch-test
  (testing ":any on either side has a dedicated message"
    (is (re-find #"subtype of :any" (ta/describe-mismatch :any :int)))
    (is (re-find #":any is not a subtype" (ta/describe-mismatch :int :any))))

  (testing "refinement expected, plain candidate → constraint-missing message"
    (is (re-find #"lacks the refinement constraint"
                 (ta/describe-mismatch [:refine :int [:> 0]] :int))))

  (testing "two primitives → primitive-subtype message"
    (is (re-find #"not a primitive subtype"
                 (ta/describe-mismatch :int :text))))

  (testing "two fn-types → signature-mismatch message"
    (is (re-find #"function signature mismatch"
                 (ta/describe-mismatch [:fn {:x :int} :int]
                                       [:fn {} :int])))))


;; ============================================================================
;; constraint-contains-type-ref? — pure
;; ============================================================================

(deftest constraint-contains-type-ref-test
  (testing "a name nested in a union / fn-type constraint is found"
    (is (true? (ta/constraint-contains-type-ref? [:union :int :my-type] :my-type)))
    (is (true? (ta/constraint-contains-type-ref? [:fn {:x :my-type} :int] "my-type"))))

  (testing "an absent name → false; nil name → false"
    (is (false? (ta/constraint-contains-type-ref? [:union :int :text] :missing)))
    (is (false? (ta/constraint-contains-type-ref? [:union :int] nil)))))


;; ============================================================================
;; types-compatible
;; ============================================================================

(deftest types-compatible-test
  (testing "missing 'expected' / 'candidate' → {:ok false}"
    (is (false? (:ok (ta/types-compatible {:body {:candidate "int"}}))))
    (is (false? (:ok (ta/types-compatible {:body {:expected "int"}})))))

  (testing "compatible pair → ok true"
    (let [res (ta/types-compatible {:body {:expected "int" :candidate "int"}})]
      (is (true? (:ok res)))
      (is (= :int (:expected res)))))

  (testing "incompatible pair → ok false with a reason"
    (let [res (ta/types-compatible {:body {:expected "int" :candidate "text"}})]
      (is (false? (:ok res)))
      (is (string? (:reason res))))))


;; ============================================================================
;; Graph-cache loaders
;; ============================================================================

(deftest graph-cache-loader-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "load-graph-entities-uncached returns the five graph tables"
        (let [g (ta/load-graph-entities-uncached storage)]
          (is (every? #(contains? g %)
                      [:fns :slots :fn-slots :bindings :list-items]))))

      (testing "cached-or-load-graph fills the ctx cache on first call"
        (is (nil? (ctx/cached-graph c)))
        (let [g (ta/cached-or-load-graph c)]
          (is (contains? g :fns))
          (is (some? (ctx/cached-graph c)))))
      (finally (sp/close storage)))))


;; ============================================================================
;; rich-types-with-type-rows / all-rich-types
;; ============================================================================

(deftest rich-types-with-type-rows-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "a storage-only refinement type-row surfaces with its structural form"
        (let [int-id (get setup/primitive-fn-ids :int)
              _      (sp/create-entity storage :fn
                                       {:name "rttr-pos" :parent-ids []
                                        :base-fn-id int-id :constraint [:> 0]})
              snap   (ta/rich-types-with-type-rows c)
              entry  (get snap :rttr-pos)]
          (is (some? entry))
          (is (true? (:type-row? entry)))
          (is (= [:refine :int [:> 0]] (:return entry)))))

      (testing "all-rich-types is the same snapshot"
        (is (map? (ta/all-rich-types c))))
      (finally (sp/close storage)))))


;; ============================================================================
;; types-candidates
;; ============================================================================

(deftest types-candidates-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "missing 'expected' → {:ok false}"
        (is (false? (:ok (ta/types-candidates {:body {}} c)))))

      (testing "expected :any enumerates candidates; :count matches the vector"
        (let [res (ta/types-candidates {:body {:expected "any"}} c)]
          (is (true? (:ok res)))
          (is (vector? (:candidates res)))
          (is (= (:count res) (count (:candidates res))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; types-usages
;; ============================================================================

(deftest types-usages-test
  (let [storage (setup/create-test-storage)
        c (test-ctx storage)]
    (try
      (testing "missing / invalid type-fn-id → {:ok false}"
        (is (false? (:ok (ta/types-usages {:body {}} c))))
        (is (false? (:ok (ta/types-usages {:body {:type-fn-id "not-a-uuid"}} c)))))

      (testing "a slot typed against the target type-row is reported as a usage"
        (let [int-id  (get setup/primitive-fn-ids :int)
              type-row (sp/create-entity storage :fn
                                         {:name "tu-type" :parent-ids []
                                          :base-fn-id int-id :constraint [:> 0]})
              host    (setup/create-base-fn! storage "tu-host")
              slot    (setup/create-slot! storage "field" (:id type-row))
              _       (setup/attach-slot! storage (:id host) (:id slot) 0)
              res     (ta/types-usages {:body {:type-fn-id (str (:id type-row))}} c)]
          (is (true? (:ok res)))
          (is (pos? (:count res)))
          (is (some #(= :slot-of (:kind %)) (:usages res)))))
      (finally (sp/close storage)))))
