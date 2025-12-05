(ns graphden.graph.integration-test
  "Integration test that boots the full system via Integrant.
   Covers ig/init-key and ig/halt-key! methods."
  (:require
    [clojure.test :refer [deftest is testing]]
    ;; Required for ig/init-key methods to be registered
    [graphden.cache-eager.core]
    [graphden.graph.core]
    [graphden.graph.interface :as graph]
    [graphden.schema-malli.core]
    [graphden.storage-memory.core]
    [integrant.core :as ig]))


(def test-config
  {:graphden.schema-malli.core/provider
   {:schemas
    {:node [:map
            [:node-name :keyword]
            [:parent-name {:optional true} [:maybe :keyword]]
            [:args [:vector
                    [:map
                     [:arg-name :keyword]
                     [:arg-val :any]]]]]}}

   :graphden.storage-memory.core/storage
   {:initial-data {}}

   :graphden.cache-eager.core/cache
   {}

   :graphden.graph.core/graph
   {:schema-provider (ig/ref :graphden.schema-malli.core/provider)
    :storage (ig/ref :graphden.storage-memory.core/storage)
    :cache (ig/ref :graphden.cache-eager.core/cache)}})


(deftest integrant-system-lifecycle
  (testing "System boots via Integrant and all components work together"
    (let [system (ig/init test-config)
          g (:graphden.graph.core/graph system)]
      (try
        ;; Test basic operations through Integrant-wired system
        (graph/add-node g {:node-name :base
                           :args [{:arg-name :x :arg-val 1}]})
        (graph/add-node g {:node-name :child
                           :parent-name :base
                           :args [{:arg-name :y :arg-val 2}]})

        (is (some? (graph/get-node g :base)))
        (is (some? (graph/get-node g :child)))
        (is (= :base (graph/get-root-ancestor g :child)))
        (is (= #{:child} (graph/get-children g :base)))

        (finally
          ;; This covers ig/halt-key! for storage-memory
          (ig/halt! system))))))


(deftest integrant-with-initial-data
  (testing "Storage can be initialized with data"
    (let [config (assoc-in test-config
                           [:graphden.storage-memory.core/storage :initial-data]
                           {:node {:preloaded {:node-name :preloaded
                                               :args []}}})
          system (ig/init config)
          g (:graphden.graph.core/graph system)]
      (try
        (is (some? (graph/get-node g :preloaded)))
        (finally
          (ig/halt! system))))))
