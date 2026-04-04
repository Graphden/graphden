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


;; =============================================================================
;; STRICT LAYOUT RULES TESTS
;; =============================================================================

(deftest rule-first-child-same-row-as-parent
  (testing "RULE: First child must be on SAME ROW as parent"
    (let [nodes [(make-node "P") (make-node "C1") (make-node "C2") (make-node "C3")]
          edges [(make-edge "P" "C1") (make-edge "P" "C2") (make-edge "P" "C3")]
          result (layout-elements nodes edges)
          p-row (:row (get-pos result "P"))
          c1-row (:row (get-pos result "C1"))]
      (is (:valid (:validation result)))
      (is (= p-row c1-row)
          (str "First child C1 must be on same row as parent P. "
               "P row=" p-row ", C1 row=" c1-row)))))


(deftest rule-other-children-different-rows
  (testing "RULE: All children of same parent must be on DIFFERENT rows"
    (let [nodes [(make-node "P") (make-node "C1") (make-node "C2") (make-node "C3")]
          edges [(make-edge "P" "C1") (make-edge "P" "C2") (make-edge "P" "C3")]
          result (layout-elements nodes edges)
          c1-row (:row (get-pos result "C1"))
          c2-row (:row (get-pos result "C2"))
          c3-row (:row (get-pos result "C3"))
          all-rows [c1-row c2-row c3-row]]
      (is (:valid (:validation result)))
      (is (= 3 (count (set all-rows)))
          (str "All children must be on different rows. Rows: " all-rows)))))


(deftest rule-shared-node-on-last-parent-row
  (testing "RULE: Shared node must be on SAME ROW as its LAST (lowest) parent"
    (let [nodes [(make-node "R")
                 (make-node "P1") (make-node "P2") (make-node "P3")
                 (make-node "S")]
          ;; R -> P1, P2, P3 and all three point to S
          edges [(make-edge "R" "P1") (make-edge "R" "P2") (make-edge "R" "P3")
                 (make-edge "P1" "S") (make-edge "P2" "S") (make-edge "P3" "S")]
          result (layout-elements nodes edges)
          p1-row (:row (get-pos result "P1"))
          p2-row (:row (get-pos result "P2"))
          p3-row (:row (get-pos result "P3"))
          s-row (:row (get-pos result "S"))
          last-parent-row (max p1-row p2-row p3-row)]
      (is (:valid (:validation result)))
      (is (= s-row last-parent-row)
          (str "Shared node S must be on row of last parent. "
               "P1=" p1-row ", P2=" p2-row ", P3=" p3-row
               ", S=" s-row ", expected=" last-parent-row)))))


(deftest rule-deep-chain-first-child-same-row
  (testing "RULE: In chain A->B->C->D, all should be on same row (first child rule)"
    (let [nodes [(make-node "A") (make-node "B") (make-node "C") (make-node "D")]
          edges [(make-edge "A" "B") (make-edge "B" "C") (make-edge "C" "D")]
          result (layout-elements nodes edges)
          a-row (:row (get-pos result "A"))
          b-row (:row (get-pos result "B"))
          c-row (:row (get-pos result "C"))
          d-row (:row (get-pos result "D"))]
      (is (:valid (:validation result)))
      (is (= a-row b-row c-row d-row)
          (str "Chain should be horizontal. Rows: A=" a-row " B=" b-row " C=" c-row " D=" d-row)))))


(deftest rule-complex-shared-with-subtrees
  (testing "RULE: Shared node with subtrees - first child same row, shared on last parent row"
    ;; Structure:
    ;;   R -> P1 -> S -> C1
    ;;   R -> P2 -> S -> C2
    ;; P1 should be row 0, P2 row 1
    ;; S should be on row 1 (same as P2 - last parent)
    ;; C1 should be on row 1 (same as S - first child)
    (let [nodes [(make-node "R") (make-node "P1") (make-node "P2")
                 (make-node "S") (make-node "C1") (make-node "C2")]
          edges [(make-edge "R" "P1") (make-edge "R" "P2")
                 (make-edge "P1" "S") (make-edge "P2" "S")
                 (make-edge "S" "C1") (make-edge "S" "C2")]
          result (layout-elements nodes edges)
          r-row (:row (get-pos result "R"))
          p1-row (:row (get-pos result "P1"))
          p2-row (:row (get-pos result "P2"))
          s-row (:row (get-pos result "S"))
          c1-row (:row (get-pos result "C1"))
          c2-row (:row (get-pos result "C2"))
          last-parent-row (max p1-row p2-row)]
      (is (:valid (:validation result)))
      ;; R and P1 same row (first child)
      (is (= r-row p1-row)
          (str "P1 should be same row as R. R=" r-row ", P1=" p1-row))
      ;; P1 and P2 different rows
      (is (not= p1-row p2-row)
          (str "P1 and P2 should be on different rows. P1=" p1-row ", P2=" p2-row))
      ;; S on last parent row
      (is (= s-row last-parent-row)
          (str "S should be on last parent row. S=" s-row ", last parent=" last-parent-row))
      ;; C1 same row as S (first child)
      (is (= s-row c1-row)
          (str "C1 should be same row as S. S=" s-row ", C1=" c1-row))
      ;; C1 and C2 different rows
      (is (not= c1-row c2-row)
          (str "C1 and C2 should be on different rows. C1=" c1-row ", C2=" c2-row)))))


(deftest rule-shared-with-many-siblings
  (testing "RULE: Shared node with many siblings between its parents"
    ;; This mimics the editor-routes structure:
    ;; R -> P1 -> S (shared), A1 (arg)
    ;; R -> X1 -> H1 (other route)
    ;; R -> X2 -> H2 (other route)
    ;; R -> P2 -> S (shared), A2 (arg)
    ;; R -> X3 -> H3 (other route)
    ;;
    ;; P1 and P2 both point to S, but they are NOT consecutive children of R.
    ;; S should still be on the row of P2 (last parent).
    (let [nodes [(make-node "R")
                 (make-node "P1") (make-node "X1") (make-node "X2") (make-node "P2") (make-node "X3")
                 (make-node "S")
                 (make-node "A1" "arg") (make-node "A2" "arg")
                 (make-node "H1") (make-node "H2") (make-node "H3")]
          edges [(make-edge "R" "P1") (make-edge "R" "X1") (make-edge "R" "X2")
                 (make-edge "R" "P2") (make-edge "R" "X3")
                 ;; P1 and P2 both connect to shared S
                 (make-edge "P1" "S") (make-edge "P1" "A1")
                 (make-edge "P2" "S") (make-edge "P2" "A2")
                 ;; Other routes
                 (make-edge "X1" "H1") (make-edge "X2" "H2") (make-edge "X3" "H3")]
          result (layout-elements nodes edges)
          _ (is (:valid (:validation result))
                (str "Layout should be valid: " (:validation result)))
          p1-row (:row (get-pos result "P1"))
          p2-row (:row (get-pos result "P2"))
          s-row (:row (get-pos result "S"))
          last-parent-row (max p1-row p2-row)]
      ;; The key test: S must be on last parent row
      (is (= s-row last-parent-row)
          (str "S should be on last parent row. S=" s-row
               ", P1=" p1-row ", P2=" p2-row ", last parent=" last-parent-row)))))


;; =============================================================================
;; SUBTREE CONTAINMENT TESTS
;; =============================================================================

(deftest rule-sibling-uses-compaction-test
  (testing "RULE: Sibling CAN share row with subtree if columns don't overlap"
    ;; Structure:
    ;;   R -> A -> B -> C   (horizontal branch)
    ;;            B -> D    (B's second child at col 3)
    ;;   R -> E -> F        (R's second child, branch is cols 1-2)
    ;;
    ;; D is at col 3, E -> F is at cols 1-2. No overlap!
    ;; With compaction, E can be at row 1 (same as D).
    ;;
    ;; Correct layout:
    ;;   row 0: R  A  B  C
    ;;   row 1:    E  F  D   <-- E and D share row, different columns
    (let [nodes [(make-node "R") (make-node "A") (make-node "B")
                 (make-node "C") (make-node "D")
                 (make-node "E") (make-node "F")]
          edges [(make-edge "R" "A") (make-edge "A" "B") (make-edge "B" "C")
                 (make-edge "B" "D")  ; B's second child at col 3
                 (make-edge "R" "E") (make-edge "E" "F")]  ; R's second child
          result (layout-elements nodes edges)
          _ (is (:valid (:validation result))
                (str "Layout should be valid: " (:validation result)))
          d-row (:row (get-pos result "D"))
          d-col (:col (get-pos result "D"))
          e-row (:row (get-pos result "E"))
          e-col (:col (get-pos result "E"))
          f-col (:col (get-pos result "F"))]
      ;; D should be at col 3 (child of B which is at col 2)
      (is (= 3 d-col) (str "D should be at col 3, got " d-col))
      ;; E should be at col 1, F at col 2
      (is (= 1 e-col) (str "E should be at col 1, got " e-col))
      (is (= 2 f-col) (str "F should be at col 2, got " f-col))
      ;; E's branch (cols 1-2) doesn't overlap with D (col 3)
      ;; So E CAN be at same row as D (compaction)
      (is (= e-row d-row)
          (str "E's branch (cols " e-col "-" f-col ") doesn't overlap with D (col " d-col "). "
               "E can be at same row as D. E row=" e-row ", D row=" d-row)))))


(deftest rule-deep-subtree-containment-test
  (testing "RULE: Deep subtree must complete before sibling starts"
    ;; Structure mimicking the bug with expanded delete-entity-route:
    ;;   R -> A -> B -> C -> D    (horizontal branch with depth 4)
    ;;                 C -> E     (C's second child)
    ;;        A -> F -> G         (A's second child)
    ;;   R -> H -> I              (R's second child)
    ;;
    ;; The subtree of A includes: B, C, D, E, F, G
    ;; H must start AFTER all of these, even if H's branch (cols 1-2)
    ;; doesn't overlap with E (col 4) or D (col 4)
    (let [nodes [(make-node "R") (make-node "A") (make-node "B") (make-node "C")
                 (make-node "D") (make-node "E")
                 (make-node "F") (make-node "G")
                 (make-node "H") (make-node "I")]
          edges [(make-edge "R" "A") (make-edge "A" "B") (make-edge "B" "C") (make-edge "C" "D")
                 (make-edge "C" "E")  ; C's second child
                 (make-edge "A" "F") (make-edge "F" "G")  ; A's second child subtree
                 (make-edge "R" "H") (make-edge "H" "I")]  ; R's second child
          result (layout-elements nodes edges)
          _ (is (:valid (:validation result))
                (str "Layout should be valid: " (:validation result)))
          ;; Get all rows from A's subtree
          a-subtree-rows (map #(:row (get-pos result %)) ["A" "B" "C" "D" "E" "F" "G"])
          max-subtree-row (apply max a-subtree-rows)
          h-row (:row (get-pos result "H"))]
      ;; H must start AFTER entire subtree of A
      (is (> h-row max-subtree-row)
          (str "Sibling H must be placed AFTER entire subtree of A. "
               "Max subtree row=" max-subtree-row ", H row=" h-row
               ". A's subtree rows: " (zipmap ["A" "B" "C" "D" "E" "F" "G"] a-subtree-rows))))))


(deftest rule-column-specific-compaction-inner-test
  (testing "RULE: Inner children use compaction (check only branch columns)"
    ;; Structure:
    ;;   R -> A -> B -> C -> D -> E    (horizontal branch with depth 5)
    ;;                       D -> F    (D's second child at col 5)
    ;;        A -> G                   (A's second child, branch is cols 2-2)
    ;;
    ;; G is a child of A (inner branch node), so compaction applies.
    ;; G's branch (col 2) should be able to fit at row 1 because
    ;; only col 5 is occupied there (by F).
    (let [nodes [(make-node "R") (make-node "A") (make-node "B") (make-node "C")
                 (make-node "D") (make-node "E") (make-node "F") (make-node "G")]
          edges [(make-edge "R" "A") (make-edge "A" "B") (make-edge "B" "C")
                 (make-edge "C" "D") (make-edge "D" "E")
                 (make-edge "D" "F")  ; D's second child at col 5
                 (make-edge "A" "G")]  ; A's second child at col 2
          result (layout-elements nodes edges)
          _ (is (:valid (:validation result))
                (str "Layout should be valid: " (:validation result)))
          f-row (:row (get-pos result "F"))
          f-col (:col (get-pos result "F"))
          g-row (:row (get-pos result "G"))
          g-col (:col (get-pos result "G"))]
      ;; F should be at col 5 (child of D which is at col 4)
      (is (= 5 f-col) (str "F should be at col 5, got " f-col))
      ;; G should be at col 2 (child of A which is at col 1)
      (is (= 2 g-col) (str "G should be at col 2, got " g-col))
      ;; G should be at the SAME row as F (compaction works because cols don't overlap)
      (is (= g-row f-row)
          (str "G (col " g-col ") should be at same row as F (col " f-col ") "
               "because their columns don't overlap. G row=" g-row ", F row=" f-row)))))


(deftest rule-column-specific-compaction-sibling-test
  (testing "RULE: Sibling of branch root uses compaction if columns don't overlap"
    ;; Structure:
    ;;   R -> A -> B -> C -> D -> E    (horizontal branch with depth 5)
    ;;                       D -> F    (D's second child at col 5)
    ;;   R -> G -> H                   (R's second child, branch is cols 1-2)
    ;;
    ;; G is a sibling of A (both children of R).
    ;; G's branch (cols 1-2) should be able to fit at row 1 because
    ;; only col 5 is occupied there (by F).
    ;;
    ;; Currently this is broken: G waits for global-max even though
    ;; its columns (1-2) don't overlap with F's column (5).
    (let [nodes [(make-node "R") (make-node "A") (make-node "B") (make-node "C")
                 (make-node "D") (make-node "E") (make-node "F")
                 (make-node "G") (make-node "H")]
          edges [(make-edge "R" "A") (make-edge "A" "B") (make-edge "B" "C")
                 (make-edge "C" "D") (make-edge "D" "E")
                 (make-edge "D" "F")  ; D's second child at col 5
                 (make-edge "R" "G") (make-edge "G" "H")]  ; R's second child
          result (layout-elements nodes edges)
          _ (is (:valid (:validation result))
                (str "Layout should be valid: " (:validation result)))
          f-row (:row (get-pos result "F"))
          f-col (:col (get-pos result "F"))
          g-row (:row (get-pos result "G"))
          g-col (:col (get-pos result "G"))
          h-col (:col (get-pos result "H"))]
      ;; F should be at col 5
      (is (= 5 f-col) (str "F should be at col 5, got " f-col))
      ;; G should be at col 1, H at col 2
      (is (= 1 g-col) (str "G should be at col 1, got " g-col))
      (is (= 2 h-col) (str "H should be at col 2, got " h-col))
      ;; G's branch (cols 1-2) doesn't overlap with F (col 5)
      ;; So G should be at same row as F (compaction)
      (is (= g-row f-row)
          (str "G's branch (cols " g-col "-" h-col ") doesn't overlap with F (col " f-col "). "
               "G should be at same row as F. G row=" g-row ", F row=" f-row)))))
