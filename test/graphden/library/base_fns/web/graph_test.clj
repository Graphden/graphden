(ns graphden.library.base-fns.web.graph-test
  "Tests for graph visualization functions.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.library.base-fns.web.graph :as graph]))


;; =============================================================================
;; Helper
;; =============================================================================

(defn- call-impl
  "Helper to call a defbase impl function with delays."
  [def-map arg-map]
  (let [impl (:impl def-map)
        delays (into {} (map (fn [[k v]] [k (delay v)]) arg-map))]
    (impl delays nil)))


;; =============================================================================
;; entities-to-cytoscape tests (2-entity schema)
;; =============================================================================

(deftest entities-to-cytoscape-test
  (testing "returns empty structure for empty input"
    (let [result (call-impl graph/entities-to-cytoscape {:entities {}})]
      (is (map? result))
      (is (= [] (:nodes result)))
      (is (= [] (:edges result)))))

  (testing "converts fn entities (base fn with parent-id=nil)"
    (let [entities {:fns [{:id #uuid "00000000-0000-0000-0000-000000000001"
                           :name "my-base-fn"
                           :parent-id nil
                           :return-type "int"}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})]
      (is (= 1 (count (:nodes result))))
      (let [node (first (:nodes result))]
        (is (= "00000000-0000-0000-0000-000000000001" (get-in node [:data :id])))
        (is (= "my-base-fn" (get-in node [:data :label])))
        (is (= "fn" (get-in node [:data :type]))))))

  (testing "converts fn entities (composed fn with parent-id set)"
    (let [base-fn-id #uuid "00000000-0000-0000-0000-000000000001"
          entities {:fns [{:id #uuid "00000000-0000-0000-0000-000000000002"
                           :name "my-composed-fn"
                           :parent-id base-fn-id}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})]
      (is (= 1 (count (:nodes result))))
      (let [node (first (:nodes result))]
        (is (= "fn" (get-in node [:data :type])))
        (is (= (str base-fn-id) (get-in node [:data :parent-id]))))))

  (testing "converts arg entities with literal value"
    (let [fn-id #uuid "00000000-0000-0000-0000-000000000001"
          entities {:args [{:id #uuid "00000000-0000-0000-0000-000000000003"
                            :fn-id fn-id
                            :name "x"
                            :type "int"
                            :value 42}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})]
      (is (= 1 (count (:nodes result))))
      (let [node (first (:nodes result))]
        (is (= "arg" (get-in node [:data :type])))
        (is (= "int" (get-in node [:data :arg-type])))
        (is (= "literal" (get-in node [:data :ref-type])))
        (is (false? (get-in node [:data :is-ref]))))))

  (testing "converts arg entities with ref-id reference"
    (let [fn-id #uuid "00000000-0000-0000-0000-000000000001"
          ref-fn-id #uuid "00000000-0000-0000-0000-000000000002"
          entities {:args [{:id #uuid "00000000-0000-0000-0000-000000000003"
                            :fn-id fn-id
                            :name "x"
                            :type "int"
                            :ref-id ref-fn-id}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})
          node (first (:nodes result))]
      (is (= "fn-ref" (get-in node [:data :ref-type])))
      (is (get-in node [:data :is-ref]))))

  (testing "converts arg entities with is-fn=true (HOF)"
    (let [fn-id #uuid "00000000-0000-0000-0000-000000000001"
          hof-fn-id #uuid "00000000-0000-0000-0000-000000000002"
          entities {:args [{:id #uuid "00000000-0000-0000-0000-000000000003"
                            :fn-id fn-id
                            :name "f"
                            :type "fn"
                            :is-fn true
                            :ref-id hof-fn-id}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})
          node (first (:nodes result))]
      (is (= "arg" (get-in node [:data :type])))
      (is (true? (get-in node [:data :is-fn])))))

  (testing "creates edges between fn and its args"
    (let [fn-id #uuid "00000000-0000-0000-0000-000000000001"
          arg-id #uuid "00000000-0000-0000-0000-000000000002"
          entities {:fns [{:id fn-id :name "test-fn" :parent-id nil}]
                    :args [{:id arg-id :fn-id fn-id :name "x" :type "int" :value 42}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})]
      (is (= 2 (count (:nodes result))))
      (is (>= (count (:edges result)) 1))
      ;; Check edge from fn to arg (fn "has-arg")
      (let [edge (first (:edges result))]
        (is (= (str fn-id) (get-in edge [:data :source])))
        (is (= (str arg-id) (get-in edge [:data :target])))
        (is (= "has-arg" (get-in edge [:data :type]))))))

  (testing "creates edges for composed fn to parent fn"
    (let [base-fn-id #uuid "00000000-0000-0000-0000-000000000001"
          composed-fn-id #uuid "00000000-0000-0000-0000-000000000002"
          entities {:fns [{:id base-fn-id :name "base-fn" :parent-id nil}
                          {:id composed-fn-id :name "composed-fn" :parent-id base-fn-id}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})]
      (is (= 2 (count (:nodes result))))
      (is (= 1 (count (:edges result))))
      (let [edge (first (:edges result))]
        (is (= (str composed-fn-id) (get-in edge [:data :source])))
        (is (= (str base-fn-id) (get-in edge [:data :target])))
        (is (= "inherits" (get-in edge [:data :type]))))))

  (testing "truncates long string values"
    (let [long-string (str/join (repeat 50 "x"))
          fn-id #uuid "00000000-0000-0000-0000-000000000001"
          entities {:args [{:id #uuid "00000000-0000-0000-0000-000000000003"
                            :fn-id fn-id
                            :name "x"
                            :type "text"
                            :value long-string}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})
          label (get-in (first (:nodes result)) [:data :label])]
      ;; Label format: "name: value..." where value is truncated to 20+3 chars
      ;; With name "x", total is "x: " (3) + truncated value (23) = 26 chars
      (is (<= (count label) 26)))))


;; =============================================================================
;; cytoscape-init-script tests
;; =============================================================================

(deftest cytoscape-init-script-test
  (testing "generates initialization script"
    (let [elements {:nodes [] :edges []}
          script (call-impl graph/cytoscape-init-script
                            {:container-id "cy" :elements elements})]
      (is (string? script))
      (is (str/includes? script "cytoscape"))
      (is (str/includes? script "getElementById('cy')"))))

  (testing "includes elements JSON"
    (let [elements {:nodes [{:data {:id "1"}}] :edges []}
          script (call-impl graph/cytoscape-init-script
                            {:container-id "graph" :elements elements})]
      (is (str/includes? script "elements:"))))

  (testing "includes default style"
    (let [script (call-impl graph/cytoscape-init-script
                            {:container-id "cy" :elements {:nodes [] :edges []}})]
      (is (str/includes? script "style:"))))

  (testing "uses custom style when provided"
    (let [custom-style [{:selector "node" :style {:color "red"}}]
          script (call-impl graph/cytoscape-init-script
                            {:container-id "cy"
                             :elements {:nodes [] :edges []}
                             :style custom-style})]
      (is (str/includes? script "red"))))

  (testing "includes layout"
    (let [script (call-impl graph/cytoscape-init-script
                            {:container-id "cy" :elements {:nodes [] :edges []}})]
      (is (str/includes? script "layout:"))))

  (testing "uses custom layout when provided"
    (let [custom-layout {:name "grid" :rows 2}
          script (call-impl graph/cytoscape-init-script
                            {:container-id "cy"
                             :elements {:nodes [] :edges []}
                             :layout custom-layout})]
      (is (str/includes? script "grid"))))

  (testing "includes click handler when provided"
    (let [script (call-impl graph/cytoscape-init-script
                            {:container-id "cy"
                             :elements {:nodes [] :edges []}
                             :on-click "handleNodeClick"})]
      (is (str/includes? script "handleNodeClick"))
      (is (str/includes? script "tap")))))


;; =============================================================================
;; cytoscape-update-script tests
;; =============================================================================

(deftest cytoscape-update-script-test
  (testing "generates update script"
    (let [elements {:nodes [{:data {:id "new"}}] :edges []}
          script (call-impl graph/cytoscape-update-script {:elements elements})]
      (is (string? script))
      (is (str/includes? script "graphdenCy"))
      (is (str/includes? script "elements().remove()"))))

  (testing "re-runs layout by default"
    (let [script (call-impl graph/cytoscape-update-script
                            {:elements {:nodes [] :edges []}})]
      (is (str/includes? script "layout"))))

  (testing "skips layout when disabled"
    (let [script (call-impl graph/cytoscape-update-script
                            {:elements {:nodes [] :edges []}
                             :layout false})]
      ;; Should not contain layout.run() when layout is false
      (is (not (str/includes? script ".run()")))))

  (testing "includes fit call"
    (let [script (call-impl graph/cytoscape-update-script
                            {:elements {:nodes [] :edges []}})]
      (is (str/includes? script "fit()")))))


;; =============================================================================
;; all-defs tests
;; =============================================================================

(deftest all-defs-test
  (testing "contains all expected functions"
    (is (map? graph/all-defs))
    (is (contains? graph/all-defs :entities-to-cytoscape))
    (is (contains? graph/all-defs :cytoscape-init-script))
    (is (contains? graph/all-defs :cytoscape-update-script)))

  (testing "entities-to-cytoscape has correct metadata"
    (let [def-map (:entities-to-cytoscape graph/all-defs)]
      (is (= :jsonb (get-in def-map [:args :entities])))
      (is (= :jsonb (:return-type def-map)))))

  (testing "cytoscape-init-script has correct metadata"
    (let [def-map (:cytoscape-init-script graph/all-defs)]
      (is (= :text (get-in def-map [:args :container-id])))
      (is (= :jsonb (get-in def-map [:args :elements])))
      (is (= :text (:return-type def-map)))))

  (testing "cytoscape-update-script has correct metadata"
    (let [def-map (:cytoscape-update-script graph/all-defs)]
      (is (= :jsonb (get-in def-map [:args :elements])))
      (is (= :text (:return-type def-map))))))
