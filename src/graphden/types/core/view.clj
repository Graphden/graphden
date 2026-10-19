(ns graphden.types.core.view
  "View-label projection on already-resolved type data; no reasoning dependency."
  (:require
    [graphden.types.core.shapes :as shapes]))


(defn encoded-type
  "Remove only UiView's :ui-view marker. Preserve secrecy, other markers,
   execution/effect contracts and literal refinement operands."
  [t]
  (cond
    (shapes/marker-type? t)
    (let [inner (encoded-type (shapes/marker-inner t))]
      (if (and (= :ui-view (shapes/marker-tag t)) (not (shapes/hide-result-marker-type? t)))
        inner [(shapes/marker-tag t) inner]))
    (shapes/refine-type? t) [:refine (encoded-type (shapes/refine-base t)) (shapes/refine-constraint t)]
    ;; Encoding a value does not rewrite a callable's future inputs or
    ;; results. Those still carry the callback's original view contract.
    (shapes/fn-type? t) t
    (shapes/list-type? t) [:list (encoded-type (shapes/list-elem t))]
    (shapes/map-type? t) [:map (encoded-type (shapes/map-key t)) (encoded-type (shapes/map-val t))]
    (shapes/record-type? t) (update-vals t encoded-type)
    (shapes/tuple-type? t) (into [:tuple] (map encoded-type) (shapes/tuple-elems t))
    (shapes/union-type? t) (into [:union] (map encoded-type) (shapes/union-members t))
    :else t))


(defn contains-view?
  [t]
  (shapes/type-any? #(and (shapes/marker-type? %) (= :ui-view (shapes/marker-tag %))) t))
