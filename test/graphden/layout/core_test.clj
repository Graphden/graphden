(ns graphden.layout.core-test
  "Tests for `graphden.layout.core` paths the grid-layout fixtures in
   `packages.app.layout-test` don't reach: the HTTP request parsers
   (`parse-layout-request` → `parse-expansions` → `parse-spec`) and the
   `compute-layout` root-not-found guard."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.layout.core :as lc]))


(def ^:private empty-graph
  {:fns [] :slots [] :fn-slots [] :bindings [] :list-items []})


;; ============================================================================
;; parse-layout-request → parse-expansions → parse-spec
;; ============================================================================

(deftest parse-layout-request-test
  (testing "root-id is coerced to a UUID; expansions parsed per spec kind"
    (let [root (random-uuid)
          pf   (random-uuid)
          req  {:body {:root-id (str root)
                       :expansions {;; integer spec — kept as-is
                                    :fn-a 2
                                    ;; map spec — full-depth + partial-fns,
                                    ;; mixing a uuid string and a uuid object
                                    :fn-b {:full-depth 3
                                           :partial-fns [(str pf) pf]}
                                    ;; neither int nor map → :else 0
                                    :fn-c "garbage"}}}
          {:keys [root-id expansions]} (lc/parse-layout-request req)]
      (is (= root root-id))
      (is (= 2 (get expansions "fn-a")))
      (is (= {:full-depth 3 :partial-fns #{pf}} (get expansions "fn-b")))
      (is (= 0 (get expansions "fn-c")))))

  (testing "a map spec with no :full-depth defaults the depth to 0"
    (let [req {:body {:root-id (str (random-uuid))
                      :expansions {:fn-x {:partial-fns []}}}}
          spec (get (:expansions (lc/parse-layout-request req)) "fn-x")]
      (is (= {:full-depth 0 :partial-fns #{}} spec))))

  (testing "an absent :expansions key yields an empty map"
    (let [req {:body {:root-id (str (random-uuid))}}]
      (is (= {} (:expansions (lc/parse-layout-request req))))))

  (testing "a body with no root-id throws :execution-error/invalid-args"
    (let [ex (try (lc/parse-layout-request {:body {:expansions {}}})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :execution-error/invalid-args (:type (ex-data ex)))))))


;; ============================================================================
;; compute-layout — root-not-found guard
;; ============================================================================

(deftest compute-layout-root-not-found-test
  (testing "a root-id absent from the graph throws :execution-error/not-found"
    (let [ex (try (lc/compute-layout empty-graph (random-uuid) {})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :execution-error/not-found (:type (ex-data ex)))))))


;; ============================================================================
;; compute-layout-matrix — empty + no-root branches
;; ============================================================================

(deftest compute-layout-matrix-edge-cases-test
  (testing "no nodes → empty grid, valid"
    (let [res (lc/compute-layout-matrix {:elements {:nodes [] :edges []}})]
      (is (= {} (:grid-pos res)))
      (is (true? (:valid (:validation res))))))

  (testing "nodes present but none is a root → no_root issue"
    ;; two nodes, each an edge target of the other — find-root-node
    ;; finds nothing with zero in-edges.
    (let [a "n-a" b "n-b"
          res (lc/compute-layout-matrix
                {:elements {:nodes [{:id a} {:id b}]
                            :edges [{:source a :target b}
                                    {:source b :target a}]}})]
      (is (= {} (:grid-pos res)))
      (is (false? (:valid (:validation res))))
      (is (= "no_root" (:type (first (:issues (:validation res)))))))))
