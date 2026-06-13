(ns graphden.packages.web.html.impls
  "Implementations for web/html base functions using Hiccup."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [hiccup2.core :as h]))


;; === Hiccup Helpers ===

;; The hiccup-walker that used to live here (element detection, attr
;; keywordization, script/style raw-content handling) is now a graph
;; fn-def chain in fns.edn rooted at `:hiccup-normalize`. The single
;; library boundary remaining is `:h-raw` (above), used inside
;; script/style bodies to bypass HTML entity escaping.


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


;; `:hiccup-normalize` is now a graph fn-def — see fns.edn.


(defbase hiccup-element
  [tag attrs children]
  (let [tag-kw (if (keyword? tag) tag (keyword tag))]
    (into (if attrs [tag-kw attrs] [tag-kw]) children)))


;; === Registry ===

(def impls
  {:render-hiccup render-hiccup
   :h-raw h-raw
   :hiccup hiccup-element})
