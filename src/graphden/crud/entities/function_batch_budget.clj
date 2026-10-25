(ns graphden.crud.entities.function-batch-budget
  "One exact function cohort must fit both atomic creation and atomic cleanup.
   This is independent of quotas and per-component/browser source limits.")


(def max-functions 4096)


(defn accepted-count?
  [size]
  (and (integer? size) (<= 1 size max-functions)))
