(ns graphden.graph.fallback-test
  "Tests for fallback compute functions using a pass-through cache.
   These functions are not called with eager cache but will be used
   with TTL or lazy cache implementations."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache.interface :as cache]
    [graphden.graph.core :as graph-core]
    [graphden.graph.interface :as graph]
    [graphden.schema-malli.core :as schema-malli]
    [graphden.storage-memory.core :as storage-memory]))


;; A pass-through cache that always calls compute-fn (simulates cache miss)
(defrecord PassThroughCache
  [data-atom]

  cache/CacheStrategy

  (on-node-added
    [this {:keys [node-name parent-name]}]
    ;; Only track children for deletion validation
    (when parent-name
      (swap! data-atom update-in [:children parent-name] (fnil conj #{}) node-name))
    this)


  (on-node-deleted
    [this node-name]
    (swap! data-atom update :children dissoc node-name)
    this)


  (on-node-renamed [_ _ _] nil)


  (on-arg-changed [_ _ _ _] nil)


  (on-parent-changed [_ _ _] nil)


  (get-cached
    [_ cache-key]
    (get-in @data-atom cache-key))


  (compute-if-absent
    [_ cache-key compute-fn]
    ;; Always compute - this tests the fallback functions
    (let [value (compute-fn)]
      (swap! data-atom assoc-in cache-key value)
      value)))


(defn- create-pass-through-cache
  []
  (->PassThroughCache (atom {:children {}})))


(def test-schema-provider
  (schema-malli/create-provider
    {:schemas
     {:node [:map
             [:node-name :keyword]
             [:parent-name {:optional true} [:maybe :keyword]]
             [:args [:vector
                     [:map
                      [:arg-name :keyword]
                      [:arg-val :any]]]]]}}))


(defn- create-test-graph-with-pass-through-cache
  []
  (let [storage (storage-memory/create-storage)
        cache (create-pass-through-cache)]
    (graph-core/create-graph storage test-schema-provider cache)))


;; === Tests for compute-root-ancestor fallback ===

(deftest compute-root-ancestor-for-base-node
  (let [g (create-test-graph-with-pass-through-cache)]
    (testing "Base node has no root ancestor (returns itself via compute)"
      (graph/add-node g {:node-name :base :args []})
      ;; This triggers compute-root-ancestor because pass-through cache
      ;; always calls compute-fn
      (is (= :base (graph/get-root-ancestor g :base))))))


(deftest compute-root-ancestor-for-child
  (let [g (create-test-graph-with-pass-through-cache)]
    (testing "Child's root ancestor is computed by walking up"
      (graph/add-node g {:node-name :root :args []})
      (graph/add-node g {:node-name :child :parent-name :root :args []})
      (is (= :root (graph/get-root-ancestor g :child))))))


(deftest compute-root-ancestor-deep-hierarchy
  (let [g (create-test-graph-with-pass-through-cache)]
    (testing "Deep hierarchy computes root correctly"
      (graph/add-node g {:node-name :level0 :args []})
      (graph/add-node g {:node-name :level1 :parent-name :level0 :args []})
      (graph/add-node g {:node-name :level2 :parent-name :level1 :args []})
      (graph/add-node g {:node-name :level3 :parent-name :level2 :args []})
      (is (= :level0 (graph/get-root-ancestor g :level3))))))


;; === Tests for compute-full-args fallback ===

(deftest compute-full-args-for-base-node
  (let [g (create-test-graph-with-pass-through-cache)]
    (testing "Base node full args are just its own args"
      (graph/add-node g {:node-name :base
                         :args [{:arg-name :a :arg-val 1}
                                {:arg-name :b :arg-val 2}]})
      (let [full-args (graph/get-full-args g :base)]
        (is (= 2 (count full-args)))
        (is (= 1 (get-in full-args [:a :arg-val])))))))


(deftest compute-full-args-merged-from-ancestors
  (let [g (create-test-graph-with-pass-through-cache)]
    (testing "Child args merge with parent args"
      (graph/add-node g {:node-name :parent
                         :args [{:arg-name :a :arg-val 1}
                                {:arg-name :b :arg-val 2}]})
      (graph/add-node g {:node-name :child
                         :parent-name :parent
                         :args [{:arg-name :b :arg-val 20}   ; override
                                {:arg-name :c :arg-val 3}]}) ; new
      (let [full-args (graph/get-full-args g :child)]
        (is (= 3 (count full-args)))
        (is (= 1 (get-in full-args [:a :arg-val])))   ; inherited
        (is (= 20 (get-in full-args [:b :arg-val])))  ; overridden
        (is (= 3 (get-in full-args [:c :arg-val]))))))) ; own

(deftest compute-full-args-three-levels
  (let [g (create-test-graph-with-pass-through-cache)]
    (testing "Three-level inheritance computes correctly"
      (graph/add-node g {:node-name :grandparent
                         :args [{:arg-name :a :arg-val 1}]})
      (graph/add-node g {:node-name :parent
                         :parent-name :grandparent
                         :args [{:arg-name :b :arg-val 2}]})
      (graph/add-node g {:node-name :child
                         :parent-name :parent
                         :args [{:arg-name :c :arg-val 3}]})
      (let [full-args (graph/get-full-args g :child)]
        (is (= 3 (count full-args)))
        (is (= 1 (get-in full-args [:a :arg-val])))
        (is (= 2 (get-in full-args [:b :arg-val])))
        (is (= 3 (get-in full-args [:c :arg-val])))))))
