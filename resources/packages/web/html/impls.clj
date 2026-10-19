(ns graphden.packages.web.html.impls
  "Implementations for web/html base functions using Hiccup."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [graphden.types.view :as view]
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


(defbase as-ui-view
  "Label checked Hiccup as UiView without changing runtime representation."
  [hiccup]
  hiccup)


(defbase encode-view
  "Explicit representation conversion; values keep their runtime identity."
  [view]
  view)


(defn encode-view-return-rule
  [bindings-info default-ret]
  (if-let [t (get-in bindings-info [:view :type])]
    (view/encoded-type t)
    default-ret))


;; === Registry ===

(def impls
  ;; `:render-hiccup` and `:hiccup` serialize / assemble a hiccup tree
  ;; whose scalar leaves + `:any`-typed attrs can carry a `:secret`
  ;; value (via the `[:list :any]` arm of `:hiccup-node`). Like the
  ;; other value-deriving serializers (`:to-json-string`, `:parse-json`)
  ;; they must propagate the taint so the executor redacts a rendered
  ;; secret at `/api/execute`. `:h-raw` needs no rule — its `:string`
  ;; input can't accept a `[:secret :text]` (taint can't be stripped).
  {:as-ui-view {:impl as-ui-view :taint-propagate? true}
   :encode-view {:impl encode-view :return-type-rule encode-view-return-rule :taint-propagate? true}
   :render-hiccup {:impl render-hiccup :taint-propagate? true}
   :h-raw h-raw
   :hiccup {:impl hiccup-element :taint-propagate? true}})
