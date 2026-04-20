(ns graphden.packages.web.html.impls
  "Implementations for web/html base functions using Hiccup."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [hiccup2.core :as h]))


;; === Hiccup Helpers ===

(defn- element-name?
  [x]
  (or (keyword? x)
      (and (string? x)
           (seq x)
           (Character/isLetter (first x)))))


(defn- hiccup-element?
  [x]
  (and (or (vector? x) (seq? x) (list? x))
       (seq x)
       (element-name? (first x))))


(defn- normalize-attrs
  [attrs]
  (when (map? attrs)
    (into {}
          (map (fn [[k v]]
                 [(if (string? k) (keyword k) k) v])
               attrs))))


(declare normalize-hiccup)


(defn- normalize-hiccup-element
  [el]
  (when (hiccup-element? el)
    (let [as-vec (if (vector? el) el (vec el))
          tag (first as-vec)
          normalized-tag (if (string? tag) (keyword tag) tag)
          [attrs children-start] (if (and (> (count as-vec) 1)
                                          (map? (second as-vec)))
                                   [(normalize-attrs (second as-vec)) 2]
                                   [nil 1])
          children (subvec as-vec children-start)
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
  [value]
  (cond
    (hiccup-element? value)
    (normalize-hiccup-element value)

    (vector? value)
    (mapv normalize-hiccup value)

    :else
    value))


(defn- flatten-head
  [head]
  (cond
    (nil? head) []
    (hiccup-element? head)
    [(normalize-hiccup-element head)]
    (and (or (vector? head) (seq? head) (list? head))
         (seq head))
    (vec (mapcat (fn [item]
                   (cond
                     (hiccup-element? item) [(normalize-hiccup-element item)]
                     (and (or (vector? item) (seq? item) (list? item))
                          (seq item))
                     (flatten-head item)
                     :else []))
                 head))
    :else []))


(defn- apply-transform-impl
  [value transform]
  (case (when transform (keyword transform))
    :keyword-to-str (when value (if (keyword? value) (name value) (str value)))
    :pr-str (when value (pr-str value))
    :bool-to-yesno (if value "Yes" "No")
    value))


;; === Implementations ===

(defbase render-hiccup
  [hiccup]
  (str (h/html hiccup)))


(defbase html-response
  [body status]
  {:status (or status 200)
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (if (string? body) body (str (h/html body)))})


(defbase html-page
  [title head body scripts]
  (let [head-elements (flatten-head head)
        scripts-elements (flatten-head scripts)
        normalized-body (normalize-hiccup body)]
    (into [:html {:lang "en"}
           (into [:head
                  [:meta {:charset "utf-8"}]
                  [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                  [:title title]]
                 head-elements)]
          [(into [:body normalized-body] scripts-elements)])))


(defbase with-cdn-script
  [head url]
  (let [script-el [:script {:src url}]
        head-elements (flatten-head head)]
    (conj head-elements script-el)))


(defbase cytoscape-container
  [id style]
  [:div {:id id :style style}])


(defbase form-input
  [field-name label-text input-type field-value extra-attrs]
  [:div {:class "form-group"}
   [:label {:for field-name} label-text]
   [:input (merge {:type input-type :name field-name :id field-name}
                  (when field-value {:value field-value})
                  extra-attrs)]])


(defbase form-select
  [field-name label-text options selected-value extra-attrs]
  [:div {:class "form-group"}
   [:label {:for field-name} label-text]
   (into [:select (merge {:name field-name :id field-name} extra-attrs)]
         (for [opt options
               :let [[v l] (if (map? opt) [(:value opt) (:label opt)] opt)]]
           [:option (cond-> {:value v} (= v selected-value) (assoc :selected true)) l]))])


(defbase button
  [btn-text btn-type extra-attrs]
  [:button (merge {:type (or btn-type "button")} extra-attrs) btn-text])


(defbase field-row
  "Renders a label-value field row."
  [label value]
  [:div {:class "field-row"}
   [:span {:class "field-label"} label]
   [:span {:class "field-value"} (if (nil? value) "-" (str value))]])


(defbase badge
  [badge-text badge-type]
  [:span {:class (if badge-type (str "badge badge-" badge-type) "badge")} badge-text])


(defbase entity-field-rows
  "Renders multiple field rows from entity using field-specs.
   field-specs: [[label key] ...] or [[label key transform] ...]"
  [entity field-specs]
  (into [:div]
        (for [spec field-specs]
          (let [[label key-path transform] (if (= 3 (count spec))
                                             spec
                                             [(first spec) (second spec) nil])
                raw-value (if (vector? key-path)
                            (get-in entity key-path)
                            (get entity key-path))
                value (apply-transform-impl raw-value transform)]
            [:div {:class "field-row"}
             [:span {:class "field-label"} label]
             [:span {:class "field-value"} (if (nil? value) "-" (str value))]]))))


(defbase wrap-element
  "Wraps text content in an HTML element."
  [tag content]
  [(keyword tag) content])


(defbase hiccup-element
  [tag attrs children]
  (let [tag-kw (if (keyword? tag) tag (keyword tag))]
    (into (if attrs [tag-kw attrs] [tag-kw]) children)))


(defbase button-row
  "Creates a horizontal flex container for buttons."
  [buttons style]
  (into [:div {:style style}] buttons))


(defbase apply-transform-fn
  [value transform]
  (apply-transform-impl value transform))


;; === Registry ===

(def impls
  {:render-hiccup render-hiccup
   :html-response html-response
   :html-page html-page
   :with-cdn-script with-cdn-script
   :cytoscape-container cytoscape-container
   :form-input form-input
   :form-select form-select
   :button button
   :field-row field-row
   :badge badge
   :entity-field-rows entity-field-rows
   :wrap-element wrap-element
   :hiccup hiccup-element
   :button-row button-row
   :apply-transform apply-transform-fn})
