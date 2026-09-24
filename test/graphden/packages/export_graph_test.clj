(ns ^{:cost :heavy} graphden.packages.export-graph-test
  "The exporter against a LIVE graph — the storage adapter
   (`export/export-graph`) and the `:export-graph` / `:export-namespace`
   bundle fn-defs over a golden clone. The pure round-trip layer is
   `export-test`."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.export :as export]
    [graphden.packages.records.parse :as parse]
    [graphden.types.core :as types]))


;; *ctx* drives the GRAPH bundle fn-defs (:export-namespace /
;; :export-graph are compositions now, not Clojure fns). *graph-export* is
;; the whole-graph `export/export-graph` — the expensive walk — built once
;; for the namespace and shared by the tests that read it.
(def ^:dynamic *ctx* nil)
(def ^:dynamic *graph-export* nil)


(use-fixtures :once
  (fn [t]
    ;; registry is its own OPTIONAL package now — bootstrap the golden WITH
    ;; "registry" so `:export-graph` / `:export-namespace` + their handlers
    ;; are present.
    (let [graph (setup/bootstrap-crud-graph-from-golden!
                  "export-graph-test" ["core" "web" "app" "registry" "mcp"])]
      (binding [types/*type-aliases-override* (atom {})
                *ctx* (:ctx graph)
                *graph-export* (delay (export/export-graph (:storage graph)))]
        (t)))))


(defn- export-namespace-bundle*
  [root]
  (exec/execute-by-name *ctx* "export-namespace" {:root root}))


(defn- export-graph-bundle*
  []
  (exec/execute-by-name *ctx* "export-graph" {:include-secret-paths nil}))


(defn- norm
  "Records as an order-insensitive, key-order-insensitive set."
  [records]
  (set (map #(into (sorted-map) %) records)))


;; =============================================================================
;; Storage adapter — end-to-end export from a live graph
;; =============================================================================

(deftest graph-export-end-to-end
  (let [fns @*graph-export*
        by-name (into {} (map (juxt :name identity)) fns)]
    (testing "exports the whole synced graph"
      (is (> (count fns) 2000) "core+web+app should yield thousands of fn-defs"))
    (testing "namespace-id UUIDs are reversed to dotted paths"
      (is (every? #(or (nil? (:namespace %)) (string? (:namespace %))) fns))
      (is (contains? (set (map :namespace fns)) "app.page")
          "dotted ns paths reconstructed from the :ns parent tree"))
    (testing "a known fn-def reconstructs structurally"
      (let [hph (get by-name :html-page-handler)]
        (is (= :html-ok-response (:parent hph)))
        (is (= :html-page-rendered (get-in hph [:args :body])))))
    (testing "the live-graph export reaches the same stable fixpoint"
      (let [recs-a (parse/parse-module fns)
            recs-b (parse/parse-module (export/records->fn-defs recs-a))]
        (is (= (norm recs-a) (norm recs-b))
            "re-parsing the storage export must be a fixpoint")))))


(deftest export-graph-bundle-shape
  (let [bundle (export-graph-bundle*)]
    (testing "the migration bundle shape (incl. the always-present secret keys)"
      (is (= #{:fns :namespaces :secrets :secret-paths-included?}
             (set (keys bundle))))
      (is (= [] (:secrets bundle)) "golden graph has no secret bindings")
      (is (false? (:secret-paths-included? bundle))))
    (testing ":fns is the whole-graph export (thousands of fn-defs, known one present)"
      (is (> (count (:fns bundle)) 2000))
      (is (some #(= :html-page-handler (:name %)) (:fns bundle))))
    (testing ":namespaces are the sorted, distinct, string namespaces the fns span"
      (let [nss (:namespaces bundle)]
        (is (= nss (vec (sort nss))) "sorted")
        (is (= (count nss) (count (distinct nss))) "distinct")
        (is (every? string? nss))
        (is (contains? (set nss) "app.page"))))
    (testing "every fn's namespace is covered by :namespaces"
      (is (every? (set (:namespaces bundle))
                  (keep :namespace (:fns bundle)))))))


;; =============================================================================
;; Scoped publish bundle — namespace subtree export
;; =============================================================================

(deftest export-namespace-bundle
  (testing "a leaf namespace exports only its own fns + external deps"
    (let [bundle (export-namespace-bundle* "app.contact-demo")
          own-names (set (map :name (:fns bundle)))]
      (is (seq (:fns bundle)))
      (is (every? #(= "app.contact-demo" (:namespace %)) (:fns bundle))
          "every fn in the bundle lives under the root")
      (is (= ["app.contact-demo"] (:namespaces bundle)))
      (testing "dependencies are external (never the subtree's own fns)"
        (is (not-any? own-names (:dependencies bundle)))
        (is (some #{:html-page-handler} (:dependencies bundle))
            "contact-demo builds on :html-page-handler from app.page"))))
  (testing "lower layers (core, storage, web) have no upward deps"
    ;; The dependency detector surfaced real package-layering inversions
    ;; into app.common, now fixed: `:assoc-empty` → core.collections, and
    ;; the HTTP response matrix → the web.response module. core/storage
    ;; must not reach web/app; web must not reach app.
    (let [name->ns (into {} (keep (fn [d] (when (:name d) [(:name d) (:namespace d)]))
                                  @*graph-export*))
          upward? (fn [bundle tops]
                    (some (fn [dep]
                            (when-let [ns (name->ns dep)]
                              (some #(str/starts-with? ns %) tops)))
                          (:dependencies bundle)))]
      (doseq [[root tops] {"core" ["web" "app"] "storage" ["web" "app"] "web" ["app"]}]
        (let [bundle (export-namespace-bundle* root)]
          (is (seq (:fns bundle)))
          (is (not (upward? bundle tops))
              (str root " must not depend upward on " (pr-str tops))))))))
