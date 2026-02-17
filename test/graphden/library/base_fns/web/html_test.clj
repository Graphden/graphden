(ns graphden.library.base-fns.web.html-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.library.base-fns.web.html :as html]))


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
;; render-hiccup tests
;; =============================================================================

(deftest render-hiccup-test
  (testing "renders simple element"
    (is (= "<div></div>"
           (call-impl html/render-hiccup {:hiccup [:div]}))))

  (testing "renders element with text content"
    (is (= "<p>Hello</p>"
           (call-impl html/render-hiccup {:hiccup [:p "Hello"]}))))

  (testing "renders element with attributes"
    (let [result (call-impl html/render-hiccup {:hiccup [:div {:class "container"} "Content"]})]
      (is (str/includes? result "class=\"container\""))
      (is (str/includes? result "Content"))))

  (testing "renders nested elements"
    (let [result (call-impl html/render-hiccup {:hiccup [:div [:p "Nested"]]})]
      (is (str/includes? result "<div>"))
      (is (str/includes? result "<p>Nested</p>"))
      (is (str/includes? result "</div>")))))


;; =============================================================================
;; html-response tests
;; =============================================================================

(deftest html-response-test
  (testing "creates response with HTML string"
    (let [response (call-impl html/html-response {:body "<p>Hello</p>"})]
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "Content-Type"])))
      (is (= "<p>Hello</p>" (:body response)))))

  (testing "creates response with hiccup"
    (let [response (call-impl html/html-response {:body [:p "Hello"]})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "<p>Hello</p>"))))

  (testing "respects custom status code"
    (let [response (call-impl html/html-response {:body "Error" :status 404})]
      (is (= 404 (:status response))))))


;; NOTE: html-handler, htmx-fragment, htmx-handler tests removed.
;; These functions were refactored to use primitives:
;; - html-response for response construction (supports optional :status arg)
;; - make-handler (from system.clj) for handler creation
;; Use fn-compositions instead of these removed base-fns.


;; =============================================================================
;; html-page tests
;; =============================================================================

(deftest html-page-test
  (testing "creates basic HTML5 page"
    (let [page (call-impl html/html-page {:title "Test" :body [:p "Content"]})]
      (is (vector? page))
      (is (= :html (first page)))))

  (testing "includes title"
    (let [page (call-impl html/html-page {:title "My Title" :body [:p "Content"]})
          page-str (str page)]
      (is (str/includes? page-str "My Title"))))

  (testing "includes head content"
    (let [page (call-impl html/html-page {:title "T"
                                          :body [:p "B"]
                                          :head [:link {:rel "stylesheet" :href "/style.css"}]})
          page-str (str page)]
      (is (str/includes? page-str ":link"))))

  (testing "includes scripts"
    (let [page (call-impl html/html-page {:title "T"
                                          :body [:p "B"]
                                          :scripts [:script "console.log('hi')"]})]
      (is (vector? page)))))


;; =============================================================================
;; with-htmx tests
;; =============================================================================

(deftest with-htmx-test
  (testing "adds HTMX script to empty head"
    (let [head (call-impl html/with-htmx {})]
      (is (vector? head))
      (is (some #(and (vector? %) (= :script (first %))) head))))

  (testing "adds HTMX script to existing head"
    (let [head (call-impl html/with-htmx {:head [:meta {:charset "utf-8"}]})]
      (is (vector? head))
      (is (>= (count head) 2))))

  (testing "uses custom version"
    (let [head (call-impl html/with-htmx {:version "1.9.0"})
          script (first (filter #(and (vector? %) (= :script (first %))) head))
          src (get-in script [1 :src])]
      (is (str/includes? src "1.9.0")))))


;; =============================================================================
;; with-cytoscape tests
;; =============================================================================

(deftest with-cytoscape-test
  (testing "adds Cytoscape script"
    (let [head (call-impl html/with-cytoscape {})]
      (is (vector? head))
      (is (some #(and (vector? %)
                      (= :script (first %))
                      (str/includes? (get-in % [1 :src] "") "cytoscape"))
                head))))

  (testing "uses custom version"
    (let [head (call-impl html/with-cytoscape {:version "3.25.0"})
          script (first (filter #(and (vector? %)
                                      (= :script (first %))
                                      (str/includes? (get-in % [1 :src] "") "cytoscape"))
                                head))
          src (get-in script [1 :src])]
      (is (str/includes? src "3.25.0")))))


;; =============================================================================
;; cytoscape-container tests
;; =============================================================================

(deftest cytoscape-container-test
  (testing "creates container div with id"
    (let [container (call-impl html/cytoscape-container {:id "cy"})]
      (is (= :div (first container)))
      (is (= "cy" (get-in container [1 :id])))))

  (testing "uses default style"
    (let [container (call-impl html/cytoscape-container {:id "graph"})
          style (get-in container [1 :style])]
      (is (contains? style :width))
      (is (contains? style :height))))

  (testing "merges custom style"
    (let [container (call-impl html/cytoscape-container {:id "g" :style {:background "#000"}})
          style (get-in container [1 :style])]
      (is (= "#000" (:background style)))
      (is (contains? style :width)))))


;; =============================================================================
;; form-input tests
;; =============================================================================

(deftest form-input-test
  (testing "creates labeled input"
    (let [input (call-impl html/form-input {:field-name "email" :label-text "Email"})]
      (is (= :div (first input)))
      (is (some #(and (vector? %) (= :label (first %))) input))
      (is (some #(and (vector? %) (= :input (first %))) input))))

  (testing "uses correct input type"
    (let [input (call-impl html/form-input {:field-name "pass" :label-text "Password" :input-type "password"})
          input-el (first (filter #(and (vector? %) (= :input (first %))) input))]
      (is (= "password" (get-in input-el [1 :type])))))

  (testing "includes value when provided"
    (let [input (call-impl html/form-input {:field-name "name" :label-text "Name" :field-value "John"})
          input-el (first (filter #(and (vector? %) (= :input (first %))) input))]
      (is (= "John" (get-in input-el [1 :value])))))

  (testing "merges extra attributes"
    (let [input (call-impl html/form-input {:field-name "x" :label-text "X" :extra-attrs {:required true}})
          input-el (first (filter #(and (vector? %) (= :input (first %))) input))]
      (is (true? (get-in input-el [1 :required]))))))


;; =============================================================================
;; form-select tests
;; =============================================================================

(deftest form-select-test
  (testing "creates labeled select"
    (let [sel (call-impl html/form-select {:field-name "color"
                                           :label-text "Color"
                                           :options [["red" "Red"] ["blue" "Blue"]]})]
      (is (= :div (first sel)))
      (is (some #(and (vector? %) (= :select (first %))) sel))))

  (testing "handles map options"
    (let [sel (call-impl html/form-select {:field-name "size"
                                           :label-text "Size"
                                           :options [{:value "s" :label "Small"}
                                                     {:value "l" :label "Large"}]})]
      (is (some #(and (vector? %) (= :select (first %))) sel))))

  (testing "marks selected option"
    (let [sel (call-impl html/form-select {:field-name "x"
                                           :label-text "X"
                                           :options [["a" "A"] ["b" "B"]]
                                           :selected-value "b"})
          select-el (first (filter #(and (vector? %) (= :select (first %))) sel))
          options (filter #(and (vector? %) (= :option (first %))) select-el)]
      (is (seq options)))))


;; =============================================================================
;; button tests
;; =============================================================================

(deftest button-test
  (testing "creates button with text"
    (let [btn (call-impl html/button {:btn-text "Click me"})]
      (is (= :button (first btn)))
      (is (= "Click me" (last btn)))))

  (testing "uses default type button"
    (let [btn (call-impl html/button {:btn-text "Test"})]
      (is (= "button" (get-in btn [1 :type])))))

  (testing "uses custom type"
    (let [btn (call-impl html/button {:btn-text "Submit" :btn-type "submit"})]
      (is (= "submit" (get-in btn [1 :type])))))

  (testing "merges extra attributes"
    (let [btn (call-impl html/button {:btn-text "HTMX"
                                      :extra-attrs {:hx-post "/api" :hx-swap "outerHTML"}})]
      (is (= "/api" (get-in btn [1 :hx-post])))
      (is (= "outerHTML" (get-in btn [1 :hx-swap]))))))


;; =============================================================================
;; normalize-head tests
;; =============================================================================

(deftest normalize-head-test
  (testing "handles nil"
    (is (= [] (html/normalize-head nil))))

  (testing "handles single element"
    (let [result (html/normalize-head [:link {:href "/style.css"}])]
      (is (vector? result))
      (is (= 1 (count result)))))

  (testing "handles multiple elements"
    (let [result (html/normalize-head [[:meta {:charset "utf-8"}]
                                       [:link {:href "/style.css"}]])]
      (is (vector? result))
      (is (= 2 (count result)))))

  (testing "handles nested structures"
    (let [result (html/normalize-head [[[:meta {:x 1}] [:meta {:y 2}]]])]
      (is (vector? result)))))


;; =============================================================================
;; all-defs tests
;; =============================================================================

(deftest all-defs-test
  (testing "contains all expected functions"
    (is (map? html/all-defs))
    (is (contains? html/all-defs :render-hiccup))
    (is (contains? html/all-defs :html-response))
    ;; html-handler, htmx-fragment, htmx-handler removed - use fn-compositions
    (is (contains? html/all-defs :html-page))
    (is (contains? html/all-defs :with-htmx))
    (is (contains? html/all-defs :with-cytoscape))
    (is (contains? html/all-defs :cytoscape-container))
    (is (contains? html/all-defs :form-input))
    (is (contains? html/all-defs :form-select))
    (is (contains? html/all-defs :button))))
