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


;; === Implementations ===

(defbase render-hiccup
  [hiccup]
  (str (h/html hiccup)))


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


(defbase hiccup-element
  [tag attrs children]
  (let [tag-kw (if (keyword? tag) tag (keyword tag))]
    (into (if attrs [tag-kw attrs] [tag-kw]) children)))


;; === Registry ===

(def impls
  {:render-hiccup render-hiccup
   :html-page html-page
   :with-cdn-script with-cdn-script
   :cytoscape-container cytoscape-container
   :hiccup hiccup-element})
