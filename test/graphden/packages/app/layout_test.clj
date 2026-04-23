(ns graphden.packages.app.layout-test
  "Tests for graph layout algorithm.
   These tests verify grid-based layout calculation for graph visualization.

   These tests load the layout impls dynamically since they're in resources/packages/."
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]))


;; =============================================================================
;; DYNAMIC LOADING OF LAYOUT IMPLS
;; =============================================================================

;; Load layout impls from resources/packages/
(def ^:private layout-ns
  (let [impls-file (io/resource "packages/app/layout/impls.clj")]
    (when impls-file
      (load-file (java.io.File/.getPath (io/file impls-file))))
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
  ([id node-type] {:id id :type node-type}))


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
      (is (zero? (:row (get-pos result "A"))))
      (is (zero? (:row (get-pos result "B"))))
      (is (zero? (:row (get-pos result "C"))))
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
      (is (zero? (:col (get-pos result "A"))))
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


;; =============================================================================
;; EDGE CROSSING TESTS
;; =============================================================================

(deftest rule-no-edge-crossing-inner-child-test
  (testing "RULE: No edge crossings - inner node's second child vs sibling's branch"
    ;; Structure that causes edge crossing:
    ;;   R -> A -> B -> C    (horizontal branch)
    ;;             B -> D    (B's second child, should be placed first)
    ;;   R -> E -> F         (R's second child)
    ;;
    ;; Processing order (right-to-left along branch, then siblings):
    ;; 1. Place horizontal branch: R(0,0) A(0,1) B(0,2) C(0,3)
    ;; 2. Process C: no more children
    ;; 3. Process B: place D at (1, 3)  <-- B's second child
    ;; 4. Process A: no more children
    ;; 5. Process R: place E's branch
    ;;
    ;; If E is placed at row 1, cols 1-2, then:
    ;; - Edge B(0,2) -> D(1,3) goes right-down
    ;; - Edge R(0,0) -> E(1,1) goes right-down
    ;; - Edge E(1,1) -> F(1,2) is horizontal
    ;;
    ;; The crossing happens because E's branch (row 1, cols 1-2) is placed
    ;; BEFORE checking if it would cross the edge from B to D.
    ;;
    ;; Edge B->D occupies: vertical segment at col 3 from row 0 to row 1
    ;; Edge R->E occupies: vertical segment at col 1 from row 0 to row 1
    ;; These don't cross.
    ;;
    ;; But if D is at row 2 (because row 1 col 3 is blocked), and E is at row 1:
    ;; Edge B->D: col 3, rows 0-2
    ;; Edge E->F: row 1, col 2
    ;; Still no crossing.
    ;;
    ;; The REAL problem is when inner node (like A) has a second child X
    ;; that needs to be placed, but the sibling (E) is placed BEFORE X.
    ;;
    ;; Let's construct the exact problematic case:
    ;;   R -> A -> B       (horizontal branch at row 0)
    ;;        A -> X -> Y  (A's second child, branch uses cols 2-3)
    ;;   R -> E -> F       (R's second child, branch uses cols 1-2)
    ;;
    ;; If E is placed at row 1 before X:
    ;; - E at (1,1), F at (1,2)
    ;; Then X tries to fit:
    ;; - X needs cols 2-3
    ;; - Row 1: col 2 occupied by F!
    ;; - X goes to row 2
    ;;
    ;; Now edges:
    ;; - Edge A(0,1) -> X(2,2): vertical at col 2 from row 0 to row 2
    ;; - Edge E(1,1) -> F(1,2): horizontal at row 1 from col 1 to col 2
    ;;
    ;; Does the edge A->X cross through (1,2)? YES!
    ;; A is at (0,1), X is at (2,2).
    ;; Edge goes: down from (0,1) to (1,1), then right to (1,2), then down to (2,2)?
    ;; No - edges go to child's column first, then down.
    ;; Edge A->X: right from (0,1) to (0,2), then down from (0,2) to (2,2)
    ;; That's a vertical line at col 2 from row 0 to row 2.
    ;; Does it pass through (1,2)? YES - F is at (1,2)!
    ;;
    ;; This is node collision, not edge crossing!
    ;; The edge A->X passes through cell (1,2) which has node F.
    ;;
    ;; Hmm, let me reconsider. The edges are visual lines between nodes.
    ;; With column-aware compaction, we're only checking node positions.
    ;; We're not reserving cells for edge paths.
    ;;
    ;; The actual problem from the user:
    ;; - delete-entity-route -> pair-1: vertical edge at col 2, rows 0-2
    ;; - entity-form-create-route -> "/partials/...": horizontal edge at row 1, cols 1-2
    ;;
    ;; These edges CROSS at (1, 2) if drawn as orthogonal lines!
    ;;
    ;; Actually wait - entity-form-create-route is at (1,1) and its child
    ;; "/partials/..." is at (1,2). That's a horizontal edge on the same row.
    ;; delete-entity-route is at (0,1) and pair-1 is at (2,2).
    ;; The edge from delete-entity-route to pair-1:
    ;; - Starts at (0,1), ends at (2,2)
    ;; - Visual path: right from (0,1) to (0,2), then down from (0,2) to (2,2)
    ;; - This passes through cells: (0,1)->(0,2) horizontal, then (0,2)->(1,2)->(2,2) vertical
    ;;
    ;; Cell (1,2) is on the path of edge delete-entity-route -> pair-1
    ;; But node "/partials/..." is AT (1,2)!
    ;;
    ;; This is an edge-through-node collision, not edge-edge crossing.
    ;; The edge path reservation should prevent placing F at (1,2) if
    ;; that cell is already reserved for an edge.
    ;;
    ;; Simplified test case:
    ;;   R -> A -> B        (row 0: R(0,0) A(0,1) B(0,2))
    ;;        A -> X        (A's second child at col 2)
    ;;   R -> E -> F        (R's second child, branch cols 1-2)
    ;;
    ;; If X is placed at row 2 (below the horizontal branch):
    ;; - Edge A->X goes through col 2, rows 0-2, passing through (1,2)
    ;; If E's branch is placed at row 1:
    ;; - E at (1,1), F at (1,2)
    ;; - F occupies (1,2) which is on edge A->X path!
    ;;
    ;; The fix: when placing a branch at row N, check that no edge from
    ;; higher rows passes through the branch's cells.
    ;;
    ;; Or simpler: siblings of branch nodes must wait until all children
    ;; of that branch node are placed. (subtree containment rule)
    (let [nodes [(make-node "R") (make-node "A") (make-node "B")
                 (make-node "X")
                 (make-node "E") (make-node "F")]
          edges [(make-edge "R" "A") (make-edge "A" "B")
                 (make-edge "A" "X")  ; A's second child
                 (make-edge "R" "E") (make-edge "E" "F")]  ; R's second child
          result (layout-elements nodes edges)
          _ (is (:valid (:validation result))
                (str "Layout should be valid: " (:validation result)))
          a-pos (get-pos result "A")
          x-pos (get-pos result "X")
          e-pos (get-pos result "E")
          f-pos (get-pos result "F")]
      ;; X is A's second child, should be at col 2 (A's col + 1)
      (is (= 2 (:col x-pos)) (str "X should be at col 2, got " (:col x-pos)))
      ;; E's branch starts at col 1, F at col 2
      (is (= 1 (:col e-pos)) (str "E should be at col 1, got " (:col e-pos)))
      (is (= 2 (:col f-pos)) (str "F should be at col 2, got " (:col f-pos)))

      ;; The key test: if X is below row 0, then E's branch cannot be
      ;; between A and X (would cross the edge A->X)
      ;;
      ;; If A is at row 0 and X is at row N > 0, then:
      ;; - Edge A->X passes through col 2, rows 0 to N
      ;; - F cannot be at any row between 0 and N at col 2
      ;;
      ;; So either:
      ;; 1. X is at row 1, F is at row 2 or later
      ;; 2. F is at row 1, X is at row 1 (same row = no edge crossing)
      ;; 3. F is at row >= X's row
      ;;
      ;; The invariant: F's row must NOT be strictly between A's row and X's row
      (let [a-row (:row a-pos)
            x-row (:row x-pos)
            f-row (:row f-pos)]
        (is (not (< a-row f-row x-row))
            (str "F cannot be between A and X (would cross edge A->X). "
                 "A row=" a-row ", F row=" f-row ", X row=" x-row
                 ". F is at col 2, same as X, so edge A->X passes through (F's row, 2)."))))))


(deftest rule-no-edge-crossing-sibling-branch-test
  (testing "RULE: Sibling's branch must not cross edges from horizontal branch nodes"
    ;; This is the exact structure from the bug report:
    ;;   R -> A -> B -> C    (horizontal branch at row 0)
    ;;        A -> X -> Y    (A's second child, X at col 2)
    ;;   R -> E -> F         (R's second child)
    ;;
    ;; Processing order (right-to-left, depth-first):
    ;; 1. Place branch: R(0,0) A(0,1) B(0,2) C(0,3)
    ;; 2. C: no children
    ;; 3. B: no second child
    ;; 4. A: place X->Y subtree - X at col 2, Y at col 3
    ;;    X should be at row 1 (first available)
    ;; 5. R: place E->F branch - E at col 1, F at col 2
    ;;
    ;; The BUG: if E's branch is placed at row 1:
    ;; - F would be at (1,2)
    ;; - But edge A->X goes from (0,1) to (1,2), passing through... wait
    ;; - Actually edge A->X goes: horizontal (0,1)->(0,2), then vertical (0,2)->(1,2)
    ;; - So cell (1,2) is the END of the edge, which is X itself
    ;;
    ;; If X is pushed to row 2 (because row 1 col 3 is occupied by something):
    ;; - Edge A->X goes through col 2, rows 0-2
    ;; - F at (1,2) would be ON the edge path!
    ;;
    ;; Let's force X to row 2 by making B have a child at col 3, row 1:
    ;;   R -> A -> B -> C    (horizontal branch at row 0)
    ;;             B -> D    (B's second child at (1,3))
    ;;        A -> X -> Y    (A's second child at col 2, row 2 because D is blocking row 1 col 3)
    ;;   R -> E -> F         (R's second child)
    ;;
    ;; Wait, X's branch is cols 2-3. D is at col 3, row 1.
    ;; So X tries row 1: col 2 free, col 3 occupied by D -> X goes to row 2.
    ;; X at (2,2), Y at (2,3).
    ;; Edge A->X: from (0,1) horizontal to (0,2), vertical to (2,2)
    ;; Edge path at col 2: rows 0,1,2 (passes through (1,2))
    ;;
    ;; Now E's branch at row 1:
    ;; - E at (1,1), F at (1,2)
    ;; - F is at (1,2) which is ON the edge A->X!
    ;;
    ;; This is the bug we need to test.
    (let [nodes [(make-node "R") (make-node "A") (make-node "B") (make-node "C")
                 (make-node "D")  ; B's second child that blocks row 1 col 3
                 (make-node "X") (make-node "Y")  ; A's second child subtree
                 (make-node "E") (make-node "F")]  ; R's second child
          edges [(make-edge "R" "A") (make-edge "A" "B") (make-edge "B" "C")
                 (make-edge "B" "D")  ; B's second child, will be at (1,3)
                 (make-edge "A" "X") (make-edge "X" "Y")  ; A's second child subtree
                 (make-edge "R" "E") (make-edge "E" "F")]  ; R's second child
          result (layout-elements nodes edges)
          _ (is (:valid (:validation result))
                (str "Layout should be valid: " (:validation result)))
          a-pos (get-pos result "A")
          d-pos (get-pos result "D")
          x-pos (get-pos result "X")
          f-pos (get-pos result "F")]

      ;; First verify our setup: D should be at (1,3), blocking X
      (is (= {:row 1 :col 3} d-pos)
          (str "D should be at (1,3) to block X. Got: " d-pos))

      ;; X should be pushed to row 2 because D blocks row 1
      (is (= 2 (:row x-pos))
          (str "X should be at row 2 (blocked by D at row 1). Got: " (:row x-pos)))

      (let [a-row (:row a-pos)
            x-row (:row x-pos)
            f-row (:row f-pos)
            f-col (:col f-pos)
            x-col (:col x-pos)]
        ;; The key test: edge A->X passes through col 2 from row 0 to row 2
        ;; F should NOT be at (1,2) because that's on the edge path
        (when (= f-col x-col)  ; F is at same column as X (col 2)
          (is (not (< a-row f-row x-row))
              (str "F at col " f-col " row " f-row " is between A (row " a-row ") and X (row " x-row "). "
                   "This means F is ON the edge path from A to X - EDGE CROSSING!")))))))


;; =============================================================================
;; DIVERGENCE ROOTS AND PATH SORTING TESTS
;; =============================================================================

(deftest divergence-roots-stay-adjacent-test
  (testing "RULE: Divergence roots must stay adjacent, neutral siblings not between them"
    ;; Structure:
    ;;   A -> B (neutral)
    ;;   A -> C -> F (shared)
    ;;   A -> D -> F (shared)
    ;;   A -> E (neutral)
    ;;
    ;; C and D are divergence roots (both lead to shared F).
    ;; They must be adjacent in sorted order - B and E cannot be between them.
    ;;
    ;; Valid orderings: [B, C, D, E], [C, D, B, E], [B, E, C, D], etc.
    ;; Invalid ordering: [B, C, E, D] or [C, B, D, E] (neutral between divergence roots)
    (let [nodes [(make-node "A") (make-node "B") (make-node "C")
                 (make-node "D") (make-node "E") (make-node "F")]
          edges [(make-edge "A" "B") (make-edge "A" "C") (make-edge "A" "D") (make-edge "A" "E")
                 (make-edge "C" "F") (make-edge "D" "F")]
          result (layout-elements nodes edges)
          _ (is (:valid (:validation result))
                (str "Layout should be valid: " (:validation result)))
          b-row (:row (get-pos result "B"))
          c-row (:row (get-pos result "C"))
          d-row (:row (get-pos result "D"))
          e-row (:row (get-pos result "E"))
          ;; Rows for A's children: lower row = earlier in sorted order
          child-rows [[b-row "B"] [c-row "C"] [d-row "D"] [e-row "E"]]
          sorted-children (map second (sort-by first child-rows))
          sorted-vec (vec sorted-children)
          c-idx (java.util.List/.indexOf sorted-vec "C")
          d-idx (java.util.List/.indexOf sorted-vec "D")]
      ;; C and D must be adjacent (indices differ by exactly 1)
      (is (= 1 (Math/abs (- c-idx d-idx)))
          (str "Divergence roots C and D must be adjacent. "
               "Sorted order: " (vec sorted-children)
               ", C idx=" c-idx ", D idx=" d-idx)))))


(deftest lower-path-forms-horizontal-branch-test
  (testing "RULE: Lower-path parent keeps shared node (horizontal branch)"
    ;; Structure:
    ;;   A -> B -> S (shared, B is upper path)
    ;;   A -> C -> E -> S (shared, E is lower path — bottom parent)
    ;;
    ;; B (depth 1) and E (depth 2) are parents of S.
    ;; Lower-path parent (E) keeps S.  B (upper path) loses S from children.
    ;; S is placed on the lower path's horizontal branch.
    ;;
    ;; Expected layout:
    ;;   row 0: A  B
    ;;   row 1:    C  E  S
    ;;
    ;; A, B at row 0 (horizontal branch, B has no children after S removed)
    ;; C, E, S at row 1 (lower path horizontal branch)
    (let [nodes [(make-node "A") (make-node "B") (make-node "C")
                 (make-node "E") (make-node "S")]
          edges [(make-edge "A" "B") (make-edge "A" "C")
                 (make-edge "B" "S") (make-edge "C" "E") (make-edge "E" "S")]
          result (layout-elements nodes edges)
          _ (is (:valid (:validation result))
                (str "Layout should be valid: " (:validation result)))
          c-row (:row (get-pos result "C"))
          e-row (:row (get-pos result "E"))
          s-row (:row (get-pos result "S"))]
      ;; C, E, S should all be on the same row (lower path horizontal branch)
      (is (= c-row e-row s-row)
          (str "Lower path C -> E -> S should be horizontal branch. "
               "C row=" c-row ", E row=" e-row ", S row=" s-row)))))


(deftest upper-path-children-go-last-test
  (testing "RULE: Upper path nodes have path-to-shared children sorted LAST"
    ;; Structure:
    ;;   A -> B -> X (neutral child of B)
    ;;   A -> B -> S (shared, B is upper path parent)
    ;;   A -> C -> S (shared, C is lower path parent)
    ;;
    ;; B is on upper path to S. Its children are X (neutral) and S (shared).
    ;; For upper path: path-to-shared (S) goes LAST.
    ;; So X should be first child of B (horizontal branch), S below.
    ;;
    ;; Expected:
    ;;   row 0: A  B  X
    ;;   row 1:    C  S
    ;;
    ;; B is row 0 (first child of A), X is row 0 (first child of B)
    ;; C is row 1 (second child of A), S is row 1 (first/only child of C after S removed from B)
    (let [nodes [(make-node "A") (make-node "B") (make-node "C")
                 (make-node "X") (make-node "S")]
          edges [(make-edge "A" "B") (make-edge "A" "C")
                 (make-edge "B" "X") (make-edge "B" "S")
                 (make-edge "C" "S")]
          result (layout-elements nodes edges)
          _ (is (:valid (:validation result))
                (str "Layout should be valid: " (:validation result)))
          b-row (:row (get-pos result "B"))
          x-row (:row (get-pos result "X"))
          c-row (:row (get-pos result "C"))
          s-row (:row (get-pos result "S"))]
      ;; B and X on same row (X is first child of B on upper path)
      (is (= b-row x-row)
          (str "B and X should be on same row (upper path, neutral child first). "
               "B row=" b-row ", X row=" x-row))
      ;; C and S on same row (lower path horizontal branch)
      (is (= c-row s-row)
          (str "C and S should be on same row (lower path horizontal). "
               "C row=" c-row ", S row=" s-row))
      ;; S should be below B (not same row)
      (is (> s-row b-row)
          (str "S should be below B (shared on lower path). "
               "B row=" b-row ", S row=" s-row)))))


(deftest lower-path-children-go-first-test
  (testing "RULE: Lower-path parent keeps shared node"
    ;; Structure:
    ;;   A -> B -> S (shared, B is upper path)
    ;;   A -> C -> E -> S (shared, E is lower path — bottom parent)
    ;;   A -> C -> X (neutral child of C)
    ;;
    ;; Lower-path parent (E) keeps S. B (upper) loses S.
    ;;
    ;; Expected layout:
    ;;   row 0: A  B
    ;;   row 1:    C  E  S
    ;;   row 2:       X
    ;;
    ;; E and S on same row (lower path horizontal branch)
    ;; X below (other child of C)
    (let [nodes [(make-node "A") (make-node "B") (make-node "C")
                 (make-node "E") (make-node "X") (make-node "S")]
          edges [(make-edge "A" "B") (make-edge "A" "C")
                 (make-edge "B" "S")
                 (make-edge "C" "E") (make-edge "C" "X")
                 (make-edge "E" "S")]
          result (layout-elements nodes edges)
          _ (is (:valid (:validation result))
                (str "Layout should be valid: " (:validation result)))
          a-row (:row (get-pos result "A"))
          b-row (:row (get-pos result "B"))
          c-row (:row (get-pos result "C"))
          e-row (:row (get-pos result "E"))
          x-row (:row (get-pos result "X"))
          s-row (:row (get-pos result "S"))]
      ;; E and S on same row (lower path horizontal branch)
      (is (= e-row s-row)
          (str "E and S should be on same row (lower path keeps S). "
               "A row=" a-row ", B row=" b-row ", S row=" s-row))
      ;; C and E on same row (E is first child of C)
      (is (= c-row e-row)
          (str "C and E should be on same row. "
               "C row=" c-row ", E row=" e-row))
      ;; X should be below C (second child of C)
      (is (> x-row c-row)
          (str "X should be below C. "
               "C row=" c-row ", X row=" x-row)))))


(deftest divergence-roots-preserve-position-test
  (testing "RULE: Divergence roots stay at their original position, not pushed to top/bottom"
    ;; Structure:
    ;;   A -> N1 (neutral, original idx 0)
    ;;   A -> C -> S (shared, original idx 1)
    ;;   A -> D -> S (shared, original idx 2)
    ;;   A -> N2 (neutral, original idx 3)
    ;;
    ;; C and D are divergence roots at indices 1 and 2.
    ;; They should stay adjacent at approximately their original position.
    ;; N1 should stay at idx 0 (above C,D), N2 at idx 3 (below C,D).
    ;;
    ;; Expected sorted order: [N1, C, D, N2] or [N1, D, C, N2]
    ;; NOT: [C, D, N1, N2] (pushed to top) or [N1, N2, C, D] (pushed to bottom)
    (let [nodes [(make-node "A") (make-node "N1") (make-node "C")
                 (make-node "D") (make-node "N2") (make-node "S")]
          edges [(make-edge "A" "N1") (make-edge "A" "C") (make-edge "A" "D") (make-edge "A" "N2")
                 (make-edge "C" "S") (make-edge "D" "S")]
          result (layout-elements nodes edges)
          _ (is (:valid (:validation result))
                (str "Layout should be valid: " (:validation result)))
          n1-row (:row (get-pos result "N1"))
          c-row (:row (get-pos result "C"))
          d-row (:row (get-pos result "D"))
          n2-row (:row (get-pos result "N2"))
          ;; Get sorted order
          child-rows [[n1-row "N1"] [c-row "C"] [d-row "D"] [n2-row "N2"]]
          sorted-children (vec (map second (sort-by first child-rows)))]
      ;; N1 should be first (index 0)
      (is (= "N1" (first sorted-children))
          (str "N1 should be first. Sorted order: " sorted-children))
      ;; N2 should be last (index 3)
      (is (= "N2" (last sorted-children))
          (str "N2 should be last. Sorted order: " sorted-children))
      ;; C and D should be in the middle, adjacent
      (let [c-idx (java.util.List/.indexOf sorted-children "C")
            d-idx (java.util.List/.indexOf sorted-children "D")]
        (is (and (pos? c-idx) (< c-idx 3))
            (str "C should be in middle. Sorted order: " sorted-children))
        (is (and (pos? d-idx) (< d-idx 3))
            (str "D should be in middle. Sorted order: " sorted-children))
        (is (= 1 (Math/abs (- c-idx d-idx)))
            (str "C and D must be adjacent. Sorted order: " sorted-children))))))
