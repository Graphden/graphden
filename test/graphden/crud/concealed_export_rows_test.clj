(ns ^:serial graphden.crud.concealed-export-rows-test
  "`crud.entities/concealed-export-rows` — the raw rows a BYO executor
   loads, as the viewer may see them (docs/SECURITY_MODEL.md layer 9).

   `^:serial` — the view-impl seam is a process-global atom."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.entities :as entities]))


(defn- uid
  [n]
  (java.util.UUID/fromString (format "00000000-0000-0000-0000-%012d" n)))


(def ^:private base (uid 1))
(def ^:private helper (uid 2))
(def ^:private shared (uid 3))
(def ^:private mine (uid 4))
(def ^:private src-slot (uid 20))
(def ^:private renamed-slot (uid 21))


(def ^:private rows
  {:fns [{:id base :name "echo" :parent-ids [] :return-type-fn-id base}
         ;; an anonymous helper only the shared fn is built from
         {:id helper :name nil :parent-ids [base]}
         {:id shared :name "shared" :parent-ids [base]}
         {:id mine :name "mine" :parent-ids [shared]}]
   :slots [{:id src-slot :name "v" :type-fn-id base}
           {:id renamed-slot :name "w" :type-fn-id base :source-slot-id src-slot}]
   :fn-slots [{:fn-id base :slot-id src-slot :position 0}
              {:fn-id shared :slot-id renamed-slot :position 0}]
   :bindings [{:id (uid 30) :fn-id shared :slot-id src-slot :ref-fn-id helper}
              {:id (uid 31) :fn-id mine :slot-id src-slot :value 1 :value-present true}]
   :list-items [{:id (uid 40) :binding-id (uid 30) :position 0 :value 1}]})


(deftest nothing-hidden-ships-the-rows-as-they-are
  (is (identical? rows (entities/concealed-export-rows rows)) "no filter installed"))


(deftest a-hidden-fn-ships-as-its-signature
  (reset! entities/view-impl-filter
          (fn [g]
            (entities/strip-impl-of g (into #{} (comp (filter (comp #{"shared"} :name)) (map :id))
                                            (:fns g)))))
  (try
    (let [out (entities/concealed-export-rows rows)
          by-id (into {} (map (juxt :id identity)) (:fns out))]
      (testing "the hidden fn: no parents, marked concealed"
        (is (= {:id shared :name "shared" :parent-ids [] :concealed? true} (get by-id shared))))
      (testing "none of its bindings, list items or internal renames"
        (is (= [(uid 31)] (mapv :id (:bindings out))))
        (is (= [] (:list-items out)))
        (is (not-any? #(= shared (:fn-id %)) (:fn-slots out))))
      (testing "the anonymous helper only it was built from is left out"
        (is (not (contains? by-id helper))))
      (testing "the viewer's own fn built on it ships whole"
        (is (= [shared] (:parent-ids (get by-id mine))))
        (is (nil? (:concealed? (get by-id mine))))))
    (finally (reset! entities/view-impl-filter nil))))
