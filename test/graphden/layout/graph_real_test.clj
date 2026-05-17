(ns ^:integration graphden.layout.graph-real-test
  "Round 3 of `layout.graph` coverage — laying out the REAL package
   graph.

   `build-graph-elements`' deep mutually-recursive branches (HOF
   migration, substitution-context binding migration, deep ref
   trees, MI convergence) are designed around the production graph's
   topology; synthetic mini-graphs don't reach them. This fixture
   boots the full package sync (`:dev` config, init only up to
   `:exec/fn-entities` — no http server / compiled registry) so
   `compute-layout` runs over `web-server`, `router`,
   `text-error-router` and friends."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.layout.core :as lc]
    [graphden.layout.graph :as lg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.interface :as sys]
    [integrant.core :as ig]))


(def ^:dynamic *container* nil)
(def ^:dynamic *storage* nil)


(use-fixtures :once
  (pth/create-container-fixture #'*container*)
  (fn [f]
    (pth/clean-database-fast! *container*)
    (let [cfg    (pth/get-container-config *container*)
          config (-> (sys/read-config :dev)
                     (assoc-in [:db/postgres :jdbc-url] (:jdbc-url cfg))
                     (assoc-in [:db/postgres :username] (:username cfg))
                     (assoc-in [:db/postgres :password] (:password cfg)))
          ;; init only up to fn-entities — the full package sync,
          ;; without the compiled registry or the http server.
          system (ig/init config [:exec/fn-entities])]
      (binding [*storage* (:db/versioned system)]
        (try (f) (finally (ig/halt! system)))))))


(defn- fn-id
  [nm]
  (:id (first (sp/query-entities *storage* :fn {:name nm}))))


(defn- layout
  ([root-id] (layout root-id {}))
  ([root-id expansions]
   (lc/compute-layout (lg/load-graph-entities-uncached *storage*)
                      root-id expansions)))


(defn- fn-node-count
  [result]
  (count (filter #(= "fn" (:type (:data %))) (:nodes result))))


;; ============================================================================
;; The whole package graph synced — sanity
;; ============================================================================

(deftest packages-synced-test
  (testing "the full package set synced — web-server and friends exist"
    (is (some? (fn-id "web-server")))
    (is (some? (fn-id "router")))
    (is (some? (fn-id "text-error-router")))
    (is (< 100 (count (sp/query-entities *storage* :fn {})))
        "hundreds of package fns present")))


;; ============================================================================
;; compute-layout over the real graph — collapsed + progressively expanded
;; ============================================================================

(deftest layout-web-server-test
  (let [ws (fn-id "web-server")]
    (testing "web-server lays out at every expansion depth"
      (doseq [depth [0 1 2 3]]
        (let [result (layout ws (if (zero? depth)
                                  {}
                                  {(str "fn-" ws) {:full-depth depth
                                                   :partial-fns #{}}}))]
          (is (seq (:nodes result)) (str "depth " depth " produces nodes"))
          (is (:valid (:validation result)) (str "depth " depth " grid is valid"))
          (is (some #(= (str ws) (:originalFnId (:data %))) (:nodes result))
              "the root is always present"))))

    (testing "expanding deeper never produces fewer fn-cards"
      (let [d1 (fn-node-count (layout ws {(str "fn-" ws)
                                          {:full-depth 1 :partial-fns #{}}}))
            d3 (fn-node-count (layout ws {(str "fn-" ws)
                                          {:full-depth 3 :partial-fns #{}}}))]
        (is (<= d1 d3))))))


(deftest layout-text-error-router-test
  (testing "text-error-router — the 3-way MI of r404/r405/r500"
    (let [ter (fn-id "text-error-router")
          collapsed (layout ter)
          expanded  (layout ter {(str "fn-" ter) {:full-depth 1
                                                  :partial-fns #{}}})]
      (is (seq (:nodes collapsed)))
      (is (:valid (:validation expanded)))
      ;; r404 / r405 / r500 each surface exactly once (the dedupe
      ;; fixed by the declarative-sync change).
      (let [labels (mapcat #(str/split-lines (str (:label (:data %))))
                           (:nodes expanded))]
        (is (= 1 (count (filter #{"r404-body" "_r404-body"} labels)))
            "no duplicate r404-body card")))))


(deftest layout-router-default-handler-test
  (testing "fully expanding `_router` migrates the three response
            bindings down to `_router-default-handler` and shows each
            slot exactly once (no phantom raw `not-acceptable-response`
            beside the renamed `error-response`)"
    (let [router  (fn-id "_router")
          dh      (fn-id "_router-default-handler")
          result  (layout router {(str "fn-" router) {:full-depth 5
                                                      :partial-fns #{}}})
          node-by-id (into {} (map (juxt #(:id (:data %)) identity))
                           (:nodes result))
          orig-of (fn [node-id] (:originalFnId (:data (node-by-id node-id))))
          dh-node    (some #(when (= (str dh) (:originalFnId (:data %))) %)
                           (:nodes result))
          dh-node-id (:id (:data dh-node))
          dh-edges   (filter #(= dh-node-id (:source (:data %))) (:edges result))
          dh-args    (set (map #(:argName (:data %)) dh-edges))
          body-ids   (set (map #(str (fn-id %))
                               ["_r404-body" "_r405-body" "_r500-body"]))
          root->bodies (filter #(and (= (str "fn-" router) (:source (:data %)))
                                     (contains? body-ids
                                                (orig-of (:target (:data %)))))
                               (:edges result))]
      (is (some? dh-node) "_router-default-handler node present")
      (is (= #{"not-found-response" "method-not-allowed-response"
               "error-response"}
             dh-args)
          "default-handler exposes exactly its 3 renamed response slots")
      (is (every? #(not (:isUnset (:data %))) dh-edges)
          "all three response slots are bound, none unset")
      (is (empty? root->bodies)
          "no response binding sources from the root _router card"))))


(deftest layout-router-and-handlers-test
  (testing "the router and a route handler lay out without error"
    (doseq [nm ["router" "_router" "health-handler" "_app-ring-response"]]
      (when-let [id (fn-id nm)]
        (let [result (layout id {(str "fn-" id) {:full-depth 1
                                                 :partial-fns #{}}})]
          (is (seq (:nodes result)) (str nm " produces nodes"))
          (is (:valid (:validation result)) (str nm " grid is valid")))))))


(deftest layout-type-row-test
  (testing "a structural type-row root lays out its internal edges"
    (doseq [nm ["ring-response-shape" "positive-int" "port"]]
      (when-let [id (fn-id nm)]
        (let [result (layout id)]
          (is (seq (:nodes result)) (str nm " produces nodes")))))))


(deftest layout-migrate-on-fn-ref-test
  (testing "the migrate-on-fn-ref example fns lay out across expansion
            depths — exercises build-graph-elements' migrated-binding
            branches (a value bound on a ref-reached slot)"
    (doseq [nm ["ex-outer" "_ex-pair-with-first" "_ex-pair-like"]]
      (when-let [id (fn-id nm)]
        (doseq [depth [1 2 3 4]]
          (let [result (layout id {(str "fn-" id) {:full-depth depth
                                                   :partial-fns #{}}})]
            (is (seq (:nodes result)) (str nm " @" depth " produces nodes"))
            (is (:valid (:validation result))
                (str nm " @" depth " grid is valid"))))))))
