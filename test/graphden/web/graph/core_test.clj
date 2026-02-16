(ns graphden.web.graph.core-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.web.graph.core :as graph]))


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
;; entities-to-cytoscape tests
;; =============================================================================

(deftest entities-to-cytoscape-test
  (testing "returns empty structure for empty input"
    (let [result (call-impl graph/entities-to-cytoscape {:entities {}})]
      (is (map? result))
      (is (= [] (:nodes result)))
      (is (= [] (:edges result)))))

  (testing "converts fn-schema entities"
    (let [entities {:fn-schemas [{:id #uuid "00000000-0000-0000-0000-000000000001"
                                  :name :my-fn
                                  :returned-type :int
                                  :base-fn-name nil}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})]
      (is (= 1 (count (:nodes result))))
      (let [node (first (:nodes result))]
        (is (= "00000000-0000-0000-0000-000000000001" (get-in node [:data :id])))
        (is (= "my-fn" (get-in node [:data :label])))
        (is (= "fn-schema" (get-in node [:data :type]))))))

  (testing "converts fn entities"
    (let [fn-schema-id #uuid "00000000-0000-0000-0000-000000000001"
          entities {:fns [{:id #uuid "00000000-0000-0000-0000-000000000002"
                           :name :my-fn-instance
                           :fn-schema-id fn-schema-id}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})]
      (is (= 1 (count (:nodes result))))
      (let [node (first (:nodes result))]
        (is (= "fn" (get-in node [:data :type])))
        (is (= (str fn-schema-id) (get-in node [:data :fn-schema-id]))))))

  (testing "converts arg-schema entities"
    (let [entities {:arg-schemas [{:id #uuid "00000000-0000-0000-0000-000000000003"
                                   :name :my-arg
                                   :type :int
                                   :required true
                                   :fn-schema-id #uuid "00000000-0000-0000-0000-000000000001"}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})]
      (is (= 1 (count (:nodes result))))
      (let [node (first (:nodes result))]
        (is (= "arg-schema" (get-in node [:data :type])))
        (is (= "int" (get-in node [:data :arg-type])))
        (is (true? (get-in node [:data :required]))))))

  (testing "converts arg-value entities with literal value"
    (let [entities {:arg-values [{:id #uuid "00000000-0000-0000-0000-000000000004"
                                  :value "hello"
                                  :owner-fn-id #uuid "00000000-0000-0000-0000-000000000002"
                                  :arg-schema-id #uuid "00000000-0000-0000-0000-000000000003"}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})]
      (is (= 1 (count (:nodes result))))
      (let [node (first (:nodes result))]
        (is (= "arg-value" (get-in node [:data :type])))
        (is (= "literal" (get-in node [:data :ref-type])))
        (is (false? (get-in node [:data :is-ref]))))))

  (testing "converts arg-value entities with fn reference"
    (let [entities {:arg-values [{:id #uuid "00000000-0000-0000-0000-000000000004"
                                  :value {:fn-id #uuid "00000000-0000-0000-0000-000000000005"}
                                  :owner-fn-id #uuid "00000000-0000-0000-0000-000000000002"
                                  :arg-schema-id #uuid "00000000-0000-0000-0000-000000000003"}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})
          node (first (:nodes result))]
      (is (= "fn-ref" (get-in node [:data :ref-type])))
      (is (get-in node [:data :is-ref]))))

  (testing "converts arg-value entities with call-site reference"
    (let [entities {:arg-values [{:id #uuid "00000000-0000-0000-0000-000000000004"
                                  :value {:call-site-id #uuid "00000000-0000-0000-0000-000000000006"}
                                  :owner-fn-id #uuid "00000000-0000-0000-0000-000000000002"
                                  :arg-schema-id #uuid "00000000-0000-0000-0000-000000000003"}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})
          node (first (:nodes result))]
      (is (= "call-site-ref" (get-in node [:data :ref-type])))
      (is (get-in node [:data :is-ref]))))

  (testing "converts call-site entities"
    (let [entities {:call-sites [{:id #uuid "00000000-0000-0000-0000-000000000006"
                                  :name :my-call-site
                                  :fn-id #uuid "00000000-0000-0000-0000-000000000002"}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})]
      (is (= 1 (count (:nodes result))))
      (let [node (first (:nodes result))]
        (is (= "call-site" (get-in node [:data :type])))
        (is (= "my-call-site" (get-in node [:data :label]))))))

  (testing "creates edges between entities"
    (let [fn-schema-id #uuid "00000000-0000-0000-0000-000000000001"
          fn-id #uuid "00000000-0000-0000-0000-000000000002"
          entities {:fn-schemas [{:id fn-schema-id :name :schema}]
                    :fns [{:id fn-id :name :fn :fn-schema-id fn-schema-id}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})]
      (is (= 2 (count (:nodes result))))
      (is (= 1 (count (:edges result))))
      (let [edge (first (:edges result))]
        (is (= (str fn-id) (get-in edge [:data :source])))
        (is (= (str fn-schema-id) (get-in edge [:data :target])))
        (is (= "has-schema" (get-in edge [:data :type]))))))

  (testing "truncates long string values"
    (let [long-string (str/join (repeat 50 "x"))
          entities {:arg-values [{:id #uuid "00000000-0000-0000-0000-000000000004"
                                  :value long-string
                                  :owner-fn-id #uuid "00000000-0000-0000-0000-000000000002"
                                  :arg-schema-id #uuid "00000000-0000-0000-0000-000000000003"}]}
          result (call-impl graph/entities-to-cytoscape {:entities entities})
          label (get-in (first (:nodes result)) [:data :label])]
      (is (<= (count label) 23)))))


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
