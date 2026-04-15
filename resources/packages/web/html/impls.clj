(ns graphden.packages.web.html.impls
  "Implementations for web/html base functions using Hiccup."
  (:require
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
    (into [] (mapcat (fn [item]
                       (cond
                         (hiccup-element? item) [(normalize-hiccup-element item)]
                         (and (or (vector? item) (seq? item) (list? item))
                              (seq item))
                         (flatten-head item)
                         :else []))
                     head))
    :else []))


(defn normalize-head
  [head]
  (flatten-head head))


;; === Implementations ===

(defn render-hiccup
  [{:keys [hiccup]}]
  (str (h/html hiccup)))


(defn html-response
  [{:keys [body status]}]
  (let [html-str (if (string? body)
                   body
                   (str (h/html body)))
        actual-status (or status 200)]
    {:status actual-status
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body html-str}))


(defn html-page
  [{:keys [title head body scripts]}]
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


(defn with-htmx
  [{:keys [head version]}]
  (let [htmx-script [:script {:src (str "https://unpkg.com/htmx.org@" version)}]
        head-elements (normalize-head head)]
    (conj head-elements htmx-script)))


(defn with-cytoscape
  [{:keys [head version]}]
  (let [cy-script [:script {:src (str "https://unpkg.com/cytoscape@" version "/dist/cytoscape.min.js")}]
        head-elements (normalize-head head)]
    (conj head-elements cy-script)))


(defn cytoscape-container
  [{:keys [id style]}]
  [:div {:id id :style style}])


(defn form-input
  [{:keys [field-name label-text input-type field-value extra-attrs]}]
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


(defn form-select
  [{:keys [field-name label-text options selected-value extra-attrs]}]
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


(defn button
  [{:keys [btn-text btn-type extra-attrs]}]
  (let [base-attrs {:type (or btn-type "button")}
        final-attrs (merge base-attrs (or extra-attrs {}))]
    [:button final-attrs btn-text]))


(defn field-row
  "Renders a label-value field row."
  [{:keys [label value]}]
  [:div {:class "field-row"}
   [:span {:class "field-label"} label]
   [:span {:class "field-value"} (if (nil? value) "-" (str value))]])


(defn badge
  "Renders a badge with optional type for styling."
  [{:keys [badge-text badge-type]}]
  (let [type-class (if badge-type
                     (str "badge badge-" badge-type)
                     "badge")]
    [:span {:class type-class} badge-text]))


(defn- apply-transform
  "Applies transformation to value based on transform spec.
   Supports: :keyword-to-str, :pr-str, :bool-to-yesno, or nil (no transform)."
  [value transform]
  (case transform
    :keyword-to-str (when value (if (keyword? value) (name value) (str value)))
    :pr-str (when value (pr-str value))
    :bool-to-yesno (if value "Yes" "No")
    ;; default: no transform
    value))


(defn entity-field-rows
  "Renders multiple field rows from entity using field-specs.
   field-specs: [[label key] ...] or [[label key transform] ...]
   - key is a keyword or vector path to get from entity
   - transform is optional: :keyword-to-str, :pr-str, :bool-to-yesno
   Returns a div containing all field-rows."
  [{:keys [entity field-specs]}]
  (into [:div]
        (for [spec field-specs]
          (let [[label key-path transform] (if (= 3 (count spec))
                                             spec
                                             [(first spec) (second spec) nil])
                raw-value (if (vector? key-path)
                            (get-in entity key-path)
                            (get entity key-path))
                value (apply-transform raw-value transform)]
            [:div {:class "field-row"}
             [:span {:class "field-label"} label]
             [:span {:class "field-value"} (if (nil? value) "-" (str value))]]))))


(defn wrap-style
  "Wraps content in a style element."
  [{:keys [content]}]
  [:style content])


(defn wrap-script
  "Wraps content in a script element."
  [{:keys [content]}]
  [:script content])


(defn hiccup-element
  "Creates a hiccup element from tag, attrs, and children."
  [{:keys [tag attrs children]}]
  (let [tag-kw (if (keyword? tag) tag (keyword tag))
        base (if attrs
               [tag-kw attrs]
               [tag-kw])]
    (if children
      (into base children)
      base)))


(defn button-row
  "Creates a horizontal flex container for buttons."
  [{:keys [buttons style]}]
  (into [:div {:style style}] buttons))


;; === Registry ===

(def impls
  {:render-hiccup render-hiccup
   :html-response html-response
   :html-page html-page
   :with-htmx with-htmx
   :with-cytoscape with-cytoscape
   :cytoscape-container cytoscape-container
   :form-input form-input
   :form-select form-select
   :button button
   :field-row field-row
   :badge badge
   :entity-field-rows entity-field-rows
   :wrap-style wrap-style
   :wrap-script wrap-script
   :hiccup hiccup-element
   :button-row button-row})
