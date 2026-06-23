(ns graphden.packages.app.lookups.impls
  "Implementations for `app.lookups` base-fns. Each `defbase` here
   is a thin storage-protocol shim — the loop in `fn-ns-path` is
   the only one with a depth-limited iteration (recursion isn't
   expressible in the graph yet — docs/RECURSION.md is still a
   roadmap). See `fns.edn` for the full module purpose."
  (:require
    [clojure.string :as str]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]))


;; === Namespace path walker ===
;;
;; Walks up the `:ns` parent-id chain. Depth-limited (20 levels)
;; both for safety against cycles (storage constraints forbid them
;; but defence in depth is cheap) and so the loop terminates even
;; on corrupt data. 20 is far more than any realistic ns hierarchy
;; — production graphs typically nest 3-4 deep.

(def ^:private max-ns-depth 20)


(defbase fn-ns-path
  "Walk the `:ns` parent-id chain from `ns-id` to root, joining each
   ns name with `.` (so `(fn-ns-path <core.refinements id>)` →
   `\"core.refinements\"`). Returns the empty string for nil ns-id
   (the implicit root namespace has no path). Depth-capped at 20 to
   keep the loop finite under any storage misconfiguration —
   production hierarchies are 3-4 levels."
  [ns-id]
  (cr/record-effect! :db)
  (loop [acc nil cur ns-id depth 0]
    (if (or (nil? cur) (>= depth max-ns-depth))
      (str/join "." acc)
      (if-let [row (sp/read-entity (:storage ctx) :ns cur)]
        (recur (cons (:name row) acc) (:parent-id row) (inc depth))
        (str/join "." acc)))))


(def impls
  {:fn-ns-path fn-ns-path})
