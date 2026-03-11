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
  (let [htmx-version (or version "2.0.4")
        htmx-script [:script {:src (str "https://unpkg.com/htmx.org@" htmx-version)}]
        head-elements (normalize-head head)]
    (conj head-elements htmx-script)))


(defn with-cytoscape
  [{:keys [head version]}]
  (let [cy-version (or version "3.30.4")
        cy-script [:script {:src (str "https://unpkg.com/cytoscape@" cy-version "/dist/cytoscape.min.js")}]
        head-elements (normalize-head head)]
    (conj head-elements cy-script)))


(defn cytoscape-container
  [{:keys [id style]}]
  (let [default-style {:width "100%"
                       :height "600px"
                       :border "1px solid #ccc"}
        merged-style (merge default-style (or style {}))]
    [:div {:id id :style merged-style}]))


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
   :button button})
