(ns graphden.packages.core.refinements.impls
  "Runtime narrowing for built-in refinements (Phase 4 step 3).

   Each `:ensure-*` impl validates a single constraint and either
   returns its input (now narrowed to the refined type from the type
   system's view) or throws `:refinement/violated`. The throw shape
   mirrors the type-checker's sync-time rejection so callers can
   catch one error mode regardless of when the violation surfaces."
  (:require
    [graphden.executor.defbase :refer [defbase]]))


(defn- violated!
  [refine-name constraint v]
  (throw (ex-info (str "Refinement " refine-name
                       " violated: " (pr-str v)
                       " doesn't satisfy " (pr-str constraint))
                  {:type :refinement/violated
                   :refine-name refine-name
                   :constraint constraint
                   :value v})))


(defbase ensure-positive-int [value]
  (if (and (integer? value) (pos? value))
    value
    (violated! :positive-int [:> 0] value)))


(defbase ensure-non-negative-int [value]
  (if (and (integer? value) (>= value 0))
    value
    (violated! :non-negative-int [:>= 0] value)))


(defbase ensure-negative-int [value]
  (if (and (integer? value) (neg? value))
    value
    (violated! :negative-int [:< 0] value)))


(defbase ensure-non-empty-text [value]
  (if (and (string? value) (not= value ""))
    value
    (violated! :non-empty-text [:not= ""] value)))


(defbase ensure-positive-numeric [value]
  (if (and (number? value) (pos? value))
    value
    (violated! :positive-numeric [:> 0] value)))


(def impls
  {:ensure-positive-int      ensure-positive-int
   :ensure-non-negative-int  ensure-non-negative-int
   :ensure-negative-int      ensure-negative-int
   :ensure-non-empty-text    ensure-non-empty-text
   :ensure-positive-numeric  ensure-positive-numeric})
