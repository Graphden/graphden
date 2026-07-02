(ns graphden.packages.web.html.impls
  "Implementations for web/html base functions using Hiccup."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [hiccup2.core :as h]))


;; === Hiccup Helpers ===


;; === Implementations ===

(defbase render-hiccup
  [hiccup]
  (str (h/html hiccup)))


(defbase h-raw
  "Wrap `:string` in a hiccup `RawString` so `:render-hiccup` emits it
   verbatim — no HTML entity escaping. Used inside `<script>` / `<style>`
   bodies where escaping would mangle the content. Single library call."
  [string]
  (h/raw string))


(defbase hiccup-element
  [tag attrs children]
  (let [tag-kw (if (keyword? tag) tag (keyword tag))]
    (into (if attrs [tag-kw attrs] [tag-kw]) children)))


;; === Registry ===

(def impls
  {:render-hiccup render-hiccup
   :h-raw h-raw
   :hiccup hiccup-element})
