(ns graphden.packages.app.layout-test
  "Tests for graph layout algorithm.
   These tests verify grid-based layout calculation for graph visualization.

   These tests load the layout impls dynamically since they're in resources/packages/."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]))


;; =============================================================================
;; DYNAMIC LOADING OF LAYOUT IMPLS
;; =============================================================================

;; Load layout impls from resources/packages/
(def ^:private layout-ns
  (let [impls-file (io/resource "packages/app/layout/impls.clj")]
    (when impls-file
      (load-file (.getPath (io/file impls-file))))
    (find-ns 'graphden.packages.app.layout.impls)))

(def ^:private compute-layout-matrix
  (when layout-ns
    (ns-resolve layout-ns 'compute-layout-matrix)))


;; =============================================================================
;; HELPER FUNCTIONS
;; =============================================================================

(defn make-node
  "Create a node for testing."
  ([id] (make-node id "fn"))
  ([id type] {:id id :type type}))


(defn make-edge
  "Create an edge for testing."
  ([source target] (make-edge source target nil))
  ([source target arg-name] {:source source :target target :argName arg-name}))


(defn layout-elements
  "Compute layout for given nodes and edges."
  [nodes edges]
  (when compute-layout-matrix
    (compute-layout-matrix {:elements {:nodes nodes :edges edges}})))


(defn get-pos
  "Get position for a node from layout result."
  [result node-id]
  (get (:grid-pos result) node-id))


;; =============================================================================
;; BASIC LAYOUT TESTS
;; =============================================================================

(deftest simple-chain-test
  (testing "Linear chain: A -> B -> C"
    (let [nodes [(make-node "A") (make-node "B") (make-node "C")]
          edges [(make-edge "A" "B") (make-edge "B" "C")]
          result (layout-elements nodes edges)]
      (is (:valid (:validation result)))
      (is (= 3 (count (:grid-pos result))))
      ;; All on same row
      (is (= 0 (:row (get-pos result "A"))))
      (is (= 0 (:row (get-pos result "B"))))
      (is (= 0 (:row (get-pos result "C"))))
      ;; Columns increase
      (is (< (:col (get-pos result "A"))
             (:col (get-pos result "B"))
             (:col (get-pos result "C")))))))


(deftest branching-test
  (testing "Branching: A -> B, A -> C"
    (let [nodes [(make-node "A") (make-node "B") (make-node "C")]
          edges [(make-edge "A" "B") (make-edge "A" "C")]
          result (layout-elements nodes edges)]
      (is (:valid (:validation result)))
      (is (= 3 (count (:grid-pos result))))
      ;; A is root at col 0
      (is (= 0 (:col (get-pos result "A"))))
      ;; B and C are in next column
      (is (= 1 (:col (get-pos result "B"))))
      (is (= 1 (:col (get-pos result "C"))))
      ;; B and C on different rows
      (is (not= (:row (get-pos result "B"))
                (:row (get-pos result "C")))))))


;; =============================================================================
;; SHARED ARGUMENT TESTS
;; =============================================================================

(deftest shared-argument-basic-test
  (testing "Shared node: A -> B, A -> C, B -> D, C -> D"
    (let [nodes [(make-node "A") (make-node "B") (make-node "C") (make-node "D")]
          edges [(make-edge "A" "B") (make-edge "A" "C")
                 (make-edge "B" "D") (make-edge "C" "D")]
          result (layout-elements nodes edges)]
      (is (:valid (:validation result)))
      (is (= 4 (count (:grid-pos result))))
      ;; D should be placed (shared nodes must not be dropped)
      (is (some? (get-pos result "D")))
      ;; D should be to the right of B and C
      (is (> (:col (get-pos result "D"))
             (:col (get-pos result "B"))))
      (is (> (:col (get-pos result "D"))
             (:col (get-pos result "C"))))
      ;; D should be on row of last parent to minimize edge crossings
      (let [b-row (:row (get-pos result "B"))
            c-row (:row (get-pos result "C"))
            d-row (:row (get-pos result "D"))
            last-parent-row (max b-row c-row)]
        (is (= d-row last-parent-row)
            (str "Shared node D should be on row of last parent. "
                 "B row=" b-row ", C row=" c-row ", D row=" d-row))))))


(deftest shared-with-children-test
  (testing "Shared node with children: A -> B -> D, A -> C -> D, D -> E"
    (let [nodes [(make-node "A") (make-node "B") (make-node "C")
                 (make-node "D") (make-node "E")]
          edges [(make-edge "A" "B") (make-edge "A" "C")
                 (make-edge "B" "D") (make-edge "C" "D")
                 (make-edge "D" "E")]
          result (layout-elements nodes edges)]
      (is (:valid (:validation result)))
      (is (= 5 (count (:grid-pos result))))
      ;; E should be placed (child of shared node)
      (is (some? (get-pos result "E")))
      ;; E should be to the right of D
      (is (> (:col (get-pos result "E"))
             (:col (get-pos result "D")))))))


(deftest multiple-shared-arguments-test
  (testing "Multiple shared nodes like editor-routes pattern"
    (let [nodes [(make-node "list-11")
                 (make-node "item5-route") (make-node "item6-route")
                 (make-node "shared-handler")
                 (make-node "render-fn") (make-node "data-fn")]
          edges [(make-edge "list-11" "item5-route" "item5")
                 (make-edge "list-11" "item6-route" "item6")
                 (make-edge "item5-route" "shared-handler" "handler")
                 (make-edge "item6-route" "shared-handler" "handler")
                 (make-edge "shared-handler" "render-fn" "render-fn")
                 (make-edge "shared-handler" "data-fn" "data-fn")]
          result (layout-elements nodes edges)]
      (is (:valid (:validation result)))
      (is (= 6 (count (:grid-pos result))))
      ;; shared-handler should be placed
      (is (some? (get-pos result "shared-handler")))
      ;; Children of shared-handler should be placed
      (is (some? (get-pos result "render-fn")))
      (is (some? (get-pos result "data-fn")))
      ;; shared-handler should be on row of last parent
      (let [item5-row (:row (get-pos result "item5-route"))
            item6-row (:row (get-pos result "item6-route"))
            handler-row (:row (get-pos result "shared-handler"))]
        (is (= handler-row (max item5-row item6-row)))))))


;; =============================================================================
;; NO COLLISION TESTS
;; =============================================================================

(deftest no-node-collision-test
  (testing "No two nodes should occupy the same cell"
    (let [nodes [(make-node "A") (make-node "B") (make-node "C")
                 (make-node "D") (make-node "E") (make-node "F")]
          edges [(make-edge "A" "B") (make-edge "A" "C")
                 (make-edge "B" "D") (make-edge "C" "D")
                 (make-edge "D" "E") (make-edge "D" "F")]
          result (layout-elements nodes edges)
          positions (vals (:grid-pos result))
          pos-keys (map #(str (:row %) "," (:col %)) positions)]
      (is (:valid (:validation result)))
      ;; All position keys should be unique
      (is (= (count pos-keys) (count (set pos-keys)))
          "All nodes should have unique positions"))))


;; =============================================================================
;; EMPTY AND EDGE CASES
;; =============================================================================

(deftest empty-graph-test
  (testing "Empty graph returns empty layout"
    (let [result (layout-elements [] [])]
      (is (:valid (:validation result)))
      (is (empty? (:grid-pos result))))))


(deftest single-node-test
  (testing "Single node is placed at origin"
    (let [result (layout-elements [(make-node "A")] [])]
      (is (:valid (:validation result)))
      (is (= 1 (count (:grid-pos result))))
      (is (= {:row 0 :col 0} (get-pos result "A"))))))


(deftest disconnected-nodes-test
  (testing "Disconnected nodes - only root gets placed"
    ;; This is expected behavior: without edges,
    ;; only the first node (detected as root) is placed
    (let [result (layout-elements [(make-node "A") (make-node "B")] [])]
      (is (:valid (:validation result)))
      ;; Only root node is placed (B has no connection)
      (is (= 1 (count (:grid-pos result)))))))
