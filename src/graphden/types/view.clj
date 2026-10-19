(ns graphden.types.view
  "Static counterpart of the explicit view-to-data identity conversion."
  (:require
    [graphden.types.core :as types]
    [graphden.types.core.view :as view]))


(defn encoded-type
  "Encode view labels in data, retaining all other labels and callback contracts."
  [t]
  (types/resolve-alias (view/encoded-type (types/resolve-alias t))))


(defn broad-sink-hints
  "Derived hints where an explicit view reaches a deliberately broad sink."
  [expected bindings]
  (into []
        (keep (fn [[arg info]]
                (let [sink (types/resolve-alias (get expected arg))]
                  (when (and (#{:any :jsonb} sink)
                             (view/contains-view? (types/resolve-alias (:type info))))
                    {:arg-name arg :code :types/view-to-broad-sink
                     :message "This broad data input accepts a view representation; connect the field value to save data, or use encode-view to encode the view explicitly."}))))
        bindings))
