(ns ^:integration graphden.layout.graph-real-test
  "Round 3 of `layout.graph` coverage — laying out the REAL package
   graph.

   `build-graph-elements`' deep mutually-recursive branches (HOF
   migration, substitution-context binding migration, deep ref
   trees, MI convergence) are designed around the production graph's
   topology; synthetic mini-graphs don't reach them. The fixture used
   to `ig/init` a `:dev` system up to `:exec/fn-entities` — a full
   per-NS package sync (~50 s) producing rows the golden template
   already holds (the golden IS `bootstrap-from-packages!` over the
   same bundle, and every laid-out fn — `web-server`, `router`,
   `text-error-router`, the `_app-cached` chain — lives in
   core/web/app). Now a ~100 ms golden clone."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.layout.core :as lc]
    [graphden.layout.graph :as lg]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *storage* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [f]
    ;; `examples` is in the set because `layout-migrate-on-fn-ref-test`
    ;; lays out the `ex-*` pedagogical fns (the :dev system this fixture
    ;; replaced loaded them implicitly). Without them the test's
    ;; `when-let` guards skip every assertion — kaocha's
    ;; zero-assertion failure is what catches that silent decay.
    (let [{:keys [storage]} (setup/bootstrap-crud-graph-from-golden!*
                              "graphden.layout.graph-real-test"
                              ["core" "web" "app" "examples"])]
      (binding [*storage* storage]
        (try (f) (finally (sp/close storage)))))))


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


;; ============================================================================
;; Expand = β-inline — :base-handler must migrate to its use-site
;; ============================================================================
;;
;; Mirrors `layout-router-default-handler-test` (the
;; slot-OWNERSHIP migration case) but for the free-arg
;; USE-SITE case the existing migration logic doesn't cover:
;;
;;   `_app-cached :parent response-cache-wrap :args
;;     {:base-handler :_app-encoded}`
;;   `response-cache-wrap :parent :if :args
;;     {:test :_cache-hit?
;;      :then :_cached-response
;;      :else :_fresh-with-maybe-store
;;      :base-handler {…declaration only…}}`
;;
;; `:base-handler`'s slot-owner is `:response-cache-wrap` — that fn
;; doesn't appear in any ancestor-ref's inheritance chain on expand,
;; so the slot-owner migration path stays silent. The CORRECT
;; behaviour (see memory `expansion_substitution_model.md`,
;; concrete failure pattern 2026-06-19):
;;
;;   - `:base-handler` is consumed inside `_fresh-with-maybe-store`'s
;;     body (the cache-miss path actually invokes the handler).
;;   - Expanding `response-cache-wrap` + `:if` should β-inline the
;;     body — `:base-handler :_app-encoded` materialises at its use-
;;     site → an edge from `_fresh-with-maybe-store`, NOT from the
;;     root `_app-cached` card.
;;
;; Current bug: the edge stays anchored at the root, visually
;; suggesting that `:if` itself takes a fourth arg `:base-handler`.
;; This test pins the contract; the surgical fix is in
;; `process-expanded-fn-impl`'s `migration-target-for` (needs a
;; free-arg / `deep-free-ext-names` fallback alongside the existing
;; slot-owner path).

(deftest ^:integration layout-app-cached-base-handler-migrates-to-use-site
  (let [root            (fn-id "_app-cached")
        app-encoded     (fn-id "_app-encoded")
        fresh-store     (fn-id "_fresh-with-maybe-store")]
    (when (and root app-encoded fresh-store)
      (let [result (layout root {(str "fn-" root) {:full-depth 2
                                                   :partial-fns #{}}})
            node-by-id (into {} (map (juxt #(:id (:data %)) identity))
                             (:nodes result))
            orig-of (fn [node-id]
                      (some-> (node-by-id node-id) :data :originalFnId))
            ;; All edges whose TARGET maps back to the _app-encoded
            ;; fn-id — i.e. every place the editor draws an arrow to
            ;; the _app-encoded card on this view.
            to-app-encoded (filter
                             #(= (str app-encoded)
                                 (orig-of (:target (:data %))))
                             (:edges result))
            sources  (set (map #(:source (:data %)) to-app-encoded))
            sources-orig (set (map orig-of sources))]
        (testing "_app-encoded receives the :base-handler edge"
          (is (seq to-app-encoded)
              "_app-encoded must be referenced from the expanded view"))
        (testing "edge does NOT source from the root _app-cached card"
          (is (not (contains? sources (str "fn-" root)))
              (str ":base-handler edge still anchored at the root "
                   "_app-cached card — should have migrated to its "
                   "use-site (_fresh-with-maybe-store). sources="
                   (pr-str sources-orig))))
        (testing "edge sources from _fresh-with-maybe-store (the use-site)"
          (is (contains? sources-orig (str fresh-store))
              (str ":base-handler edge must originate from "
                   "_fresh-with-maybe-store (the only consumer of "
                   ":base-handler inside the inlined response-cache-"
                   "wrap body). sources=" (pr-str sources-orig))))))))
