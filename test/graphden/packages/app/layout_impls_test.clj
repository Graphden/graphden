(ns graphden.packages.app.layout-impls-test
  "Pure library-boundary tests for native layout build and placement.
   Graph request validation and annotation are tested by layout-semantic-test."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "app" "layout"))


(defn- call
  "Invoke a base-fn impl the way the executor does: args as delays.
   `delay` is a macro, so the map cannot be built with `update-vals`."
  [kw args]
  ((impls/impl-of kw)
   (into {} (map (fn [[k v]] [k (delay v)])) args)
   nil))


;; =============================================================================
;; :_layout-build-apply / :_layout-place-apply — stages A and B
;; =============================================================================

(deftest build-rejects-a-root-that-is-not-in-the-loaded-graph
  (testing "an unknown root-id throws :execution-error/not-found"
    ;; This is the stale-editor case (a fn deleted in another tab): the
    ;; graph's `:try` turns this exact type into `{:ok false :error}`.
    ;; An NPE or an empty result here would render a blank canvas with
    ;; no explanation instead.
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                  (call :_layout-build-apply
                        {:graph {:fns [] :args []}
                         :parsed {:root-id (random-uuid) :expansions {}}})))]
      (is (= :execution-error/not-found (:type (ex-data e)))))))


(deftest build-output-feeds-place-unchanged
  (testing "stage B grid-places exactly the node ids stage A emitted"
    ;; The two stages are separate base-fns glued by a fn-def, so the
    ;; shape contract between them ({:data {:id …}}-wrapped nodes) is
    ;; only enforced here. If build's node shape drifts, place silently
    ;; finds no root and returns an empty grid.
    (let [root (random-uuid)
          elements (call :_layout-build-apply
                         {:graph {:fns [{:id root :name "lit-root"}] :args []}
                          :parsed {:root-id root :expansions {}}})
          node-id (get-in (first (:nodes elements)) [:data :id])
          placed (call :_layout-place-apply {:elements elements})]
      (is (= (str "fn-" root) node-id))
      (is (contains? (:grid-pos placed) node-id)
          "the built node got a grid cell")
      (is (= (:nodes elements) (:nodes placed)) "nodes pass through untouched")
      (is (= (:edges elements) (:edges placed)) "edges pass through untouched")
      (is (true? (get-in placed [:validation :valid]))))))


(deftest place-of-an-empty-element-set-is-valid-not-an-error
  (testing "no nodes → empty grid, still :valid — an empty canvas is not a failure"
    (let [placed (call :_layout-place-apply {:elements {:nodes [] :edges []}})]
      (is (= {} (:grid-pos placed)))
      (is (true? (get-in placed [:validation :valid])))
      (is (empty? (get-in placed [:validation :issues]))))))
