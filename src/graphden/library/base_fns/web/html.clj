(ns graphden.library.base-fns.web.html
  "HTML templating base functions using Hiccup.

   Provides base functions for rendering HTML:
   - render-hiccup: Converts hiccup data structures to HTML strings
   - html-response: Creates Ring response with HTML content
   - html-handler: Creates Ring handler that returns HTML
   - htmx-response: Creates HTMX partial response (no full page wrapper)

   These are low-level primitives. Higher-level page templates
   and components are built as fn-defs using these base-fns.

   ## HTMX Integration

   HTMX attributes in hiccup use keyword syntax:
   [:button {:hx-post \"/api/action\" :hx-swap \"outerHTML\"} \"Click\"]

   For dynamic HTMX targets, use string interpolation in fn-defs."
  (:require
    [graphden.executor.registry.macros :refer [defbase]]
    [hiccup2.core :as h]))


;; =============================================================================
;; Core Rendering Functions
;; =============================================================================

(defbase render-hiccup
  "Renders hiccup data structure to HTML string.

   Arguments:
   - hiccup: Hiccup data structure (vector or seq of vectors)

   Returns:
   HTML string.

   Example:
   (render-hiccup [:div {:class \"container\"} [:p \"Hello\"]])
   ;; => \"<div class=\\\"container\\\"><p>Hello</p></div>\""
  {:args {:hiccup :jsonb}
   :return-type :text}
  (str (h/html hiccup)))


(defbase html-response
  "Creates a Ring response map with HTML content.

   Arguments:
   - body: HTML string or hiccup data structure
   - status: HTTP status code (optional, default 200)

   Returns:
   Ring response map with Content-Type: text/html"
  {:args {:body :any
          :status {:type :int :required false}}
   :return-type :jsonb}
  (let [html-str (if (string? body)
                   body
                   (str (h/html body)))
        actual-status (or status 200)]
    {:status actual-status
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body html-str}))


;; =============================================================================
;; Hiccup Helpers
;; =============================================================================

(defn- element-name?
  "Returns true if x could be a hiccup element name (keyword or string starting with letter)."
  [x]
  (or (keyword? x)
      (and (string? x)
           (seq x)
           (Character/isLetter (first x)))))


(defn- hiccup-element?
  "Returns true if x is a single hiccup element (vector or list starting with keyword/string tag)."
  [x]
  (and (or (vector? x) (seq? x) (list? x))
       (seq x)
       (element-name? (first x))))


(defn- normalize-attrs
  "Normalizes attribute map: converts string keys to keywords."
  [attrs]
  (when (map? attrs)
    (into {}
          (map (fn [[k v]]
                 [(if (string? k) (keyword k) k) v])
               attrs))))


(declare normalize-hiccup)


(defn- normalize-hiccup-element
  "Normalizes a single hiccup element recursively.
   Converts string tags to keywords, string attr keys to keywords,
   and recursively normalizes children.
   Special handling for :script and :style tags - wraps string content
   in hiccup2.core/raw to prevent HTML escaping."
  [el]
  (when (hiccup-element? el)
    (let [as-vec (if (vector? el) el (vec el))
          tag (first as-vec)
          normalized-tag (if (string? tag) (keyword tag) tag)
          ;; Check if second element is attrs map
          [attrs children-start] (if (and (> (count as-vec) 1)
                                          (map? (second as-vec)))
                                   [(normalize-attrs (second as-vec)) 2]
                                   [nil 1])
          children (subvec as-vec children-start)
          ;; For script/style tags, wrap string children in h/raw to prevent escaping
          raw-tag? (#{:script :style "script" "style"} tag)
          normalized-children (if raw-tag?
                                (mapv (fn [child]
                                        (if (string? child)
                                          (h/raw child)
                                          (normalize-hiccup child)))
                                      children)
                                (mapv normalize-hiccup children))]
      (if attrs
        (into [normalized-tag attrs] normalized-children)
        (into [normalized-tag] normalized-children)))))


(defn- normalize-hiccup
  "Recursively normalizes hiccup data that may have come from JSONB.
   Converts string tags to keywords, string attr keys to keywords.
   Works on single elements, vectors of elements, or raw values."
  [value]
  (cond
    ;; Single hiccup element - normalize it
    (hiccup-element? value)
    (normalize-hiccup-element value)

    ;; Vector that's not a hiccup element - might be list of elements
    (vector? value)
    (mapv normalize-hiccup value)

    ;; Keep other values as-is
    :else
    value))


(defn- flatten-head
  "Flattens nested head content into a flat vector of hiccup elements.
   Handles cases like [[elem1 elem2] elem3] -> [elem1 elem2 elem3].
   Also handles deeply nested structures from fn-def composition.
   Uses normalize-hiccup-element for proper script/style handling."
  [head]
  (cond
    (nil? head) []
    ;; Single hiccup element - wrap in vector
    (hiccup-element? head)
    [(normalize-hiccup-element head)]
    ;; Collection (vector, list, seq)
    (and (or (vector? head) (seq? head) (list? head))
         (seq head))
    ;; Recursively flatten all elements, then concat results
    (vec (mapcat (fn [item]
                   (cond
                     (hiccup-element? item) [(normalize-hiccup-element item)]
                     (and (or (vector? item) (seq? item) (list? item))
                          (seq item))
                     (flatten-head item)
                     :else []))
                 head))
    :else []))


(defn normalize-head
  "Normalizes head content to always be a flat vector of hiccup elements.
   Public function for use by http-kit graph-editor-server."
  [head]
  (flatten-head head))


;; =============================================================================
;; Page Layout Helpers
;; =============================================================================

(defbase html-page
  "Creates a full HTML5 page with common structure.

   Arguments:
   - title: Page title
   - head: Additional head content (hiccup, optional - can be single element or list)
   - body: Body content (hiccup)
   - scripts: Script tags to include at end of body (hiccup, optional)

   Returns:
   Complete HTML5 page as hiccup structure."
  {:args {:title :text
          :head {:type :jsonb :required false}
          :body :jsonb
          :scripts {:type :jsonb :required false}}
   :return-type :jsonb}
  (let [head-elements (normalize-head head)
        scripts-elements (normalize-head scripts)
        normalized-body (normalize-hiccup body)]
    (into [:html {:lang "en"}
           (into [:head
                  [:meta {:charset "utf-8"}]
                  [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                  [:title title]]
                 head-elements)]
          [(into [:body normalized-body] scripts-elements)])))


(defbase with-htmx
  "Adds HTMX script tag to head content.

   Arguments:
   - head: Existing head content (hiccup, optional)
   - version: HTMX version (optional, default \"2.0.4\")

   Returns:
   Head content with HTMX script included (always a vector of elements)."
  {:args {:head {:type :jsonb :required false}
          :version {:type :text :required false}}
   :return-type :jsonb}
  (let [htmx-version (or version "2.0.4")
        htmx-script [:script {:src (str "https://unpkg.com/htmx.org@" htmx-version)}]
        head-elements (normalize-head head)]
    (conj head-elements htmx-script)))


;; =============================================================================
;; Cytoscape.js Integration
;; =============================================================================

(defbase with-cytoscape
  "Adds Cytoscape.js script tags to head content.

   Arguments:
   - head: Existing head content (hiccup, optional)
   - version: Cytoscape version (optional, default \"3.30.4\")

   Returns:
   Head content with Cytoscape script included (always a vector of elements)."
  {:args {:head {:type :jsonb :required false}
          :version {:type :text :required false}}
   :return-type :jsonb}
  (let [cy-version (or version "3.30.4")
        cy-script [:script {:src (str "https://unpkg.com/cytoscape@" cy-version "/dist/cytoscape.min.js")}]
        head-elements (normalize-head head)]
    (conj head-elements cy-script)))


(defbase cytoscape-container
  "Creates a div container for Cytoscape graph.

   Arguments:
   - id: Element ID for the container
   - style: CSS style map (optional, default fills viewport)

   Returns:
   Hiccup for the container div."
  {:args {:id :text
          :style {:type :jsonb :required false}}
   :return-type :jsonb}
  (let [default-style {:width "100%"
                       :height "600px"
                       :border "1px solid #ccc"}
        merged-style (merge default-style (or style {}))]
    [:div {:id id :style merged-style}]))


;; =============================================================================
;; UI Component Helpers
;; =============================================================================

(defbase form-input
  "Creates a form input with label.

   Arguments:
   - field-name: Input name attribute
   - label-text: Label text
   - input-type: Input type (optional, default \"text\")
   - field-value: Current value (optional)
   - extra-attrs: Additional attributes (optional)

   Returns:
   Hiccup for labeled input."
  {:args {:field-name :text
          :label-text :text
          :input-type {:type :text :required false}
          :field-value {:type :any :required false}
          :extra-attrs {:type :jsonb :required false}}
   :return-type :jsonb}
  (let [the-type (or input-type "text")
        base-attrs {:type the-type
                    :name field-name
                    :id field-name}
        with-value (if field-value
                     (assoc base-attrs :value field-value)
                     base-attrs)
        final-attrs (merge with-value (or extra-attrs {}))]
    [:div {:class "form-group"}
     [:label {:for field-name} label-text]
     [:input final-attrs]]))


(defbase form-select
  "Creates a select dropdown with label.

   Arguments:
   - field-name: Select name attribute
   - label-text: Label text
   - options: Vector of [value label] pairs or maps {:value v :label l}
   - selected-value: Currently selected value (optional)
   - extra-attrs: Additional attributes (optional)

   Returns:
   Hiccup for labeled select."
  {:args {:field-name :text
          :label-text :text
          :options :jsonb
          :selected-value {:type :any :required false}
          :extra-attrs {:type :jsonb :required false}}
   :return-type :jsonb}
  (let [base-attrs {:name field-name :id field-name}
        final-attrs (merge base-attrs (or extra-attrs {}))
        option-elements (for [opt options]
                          (let [[v l] (if (map? opt)
                                        [(:value opt) (:label opt)]
                                        opt)
                                opt-attrs (cond-> {:value v}
                                            (= v selected-value) (assoc :selected true))]
                            [:option opt-attrs l]))]
    [:div {:class "form-group"}
     [:label {:for field-name} label-text]
     (into [:select final-attrs] option-elements)]))


(defbase button
  "Creates a button element.

   Arguments:
   - btn-text: Button text
   - btn-type: Button type (optional, default \"button\")
   - extra-attrs: Additional attributes (optional, useful for hx-* attrs)

   Returns:
   Hiccup for button."
  {:args {:btn-text :text
          :btn-type {:type :text :required false}
          :extra-attrs {:type :jsonb :required false}}
   :return-type :jsonb}
  (let [base-attrs {:type (or btn-type "button")}
        final-attrs (merge base-attrs (or extra-attrs {}))]
    [:button final-attrs btn-text]))


;; =============================================================================
;; Exports
;; =============================================================================

(def all-defs
  "All HTML base function definitions.
   Note: html-handler, htmx-handler, htmx-fragment removed - use fn-compositions:
   - html-response > make-handler for handlers
   - html-response for responses"
  {:render-hiccup render-hiccup
   :html-response html-response
   :html-page html-page
   :with-htmx with-htmx
   :with-cytoscape with-cytoscape
   :cytoscape-container cytoscape-container
   :form-input form-input
   :form-select form-select
   :button button})
