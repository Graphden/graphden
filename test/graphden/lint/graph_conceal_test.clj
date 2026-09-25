(ns ^:serial graphden.lint.graph-conceal-test
  "`lint-branch` lints the graph AS THE VIEWER MAY SEE IT (the view-impl
   seam, docs/SECURITY_MODEL.md layer 9): a finding about a fn whose
   composition is concealed — a duplicate of it, its parent chain —
   would state that composition, so the concealed fn lints as a leaf.

   `^:serial` — the seam is a process-global atom."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.entities :as entities]
    [graphden.lint.graph :as lg]
    [graphden.storage.protocol.core :as sp]))


(defn- uid
  [n]
  (java.util.UUID/fromString (format "00000000-0000-0000-0000-%012d" n)))


(def ^:private ns-rows [{:id (uid 1) :name "app" :parent-id nil}])
(def ^:private assoc-id (uid 10))
(def ^:private text-id (uid 12))
(def ^:private slot-map (uid 20))
(def ^:private slot-key (uid 21))
(def ^:private slot-value (uid 22))


(defn- twin
  "A named child of `assoc` binding every slot the same way."
  [n nm]
  (let [fid (uid n)]
    {:fn {:id fid :name nm :namespace-id (uid 1) :parent-ids [assoc-id]}
     :bindings [{:id (uid (+ 100 n)) :fn-id fid :slot-id slot-map :value {:class "x"} :value-present true}
                {:id (uid (+ 200 n)) :fn-id fid :slot-id slot-key :value :title :value-present true}
                {:id (uid (+ 300 n)) :fn-id fid :slot-id slot-value :ref-fn-id text-id}]}))


(defn- graph
  []
  (let [a (twin 31 "mine") b (twin 32 "theirs")]
    {:fns [{:id assoc-id :name "assoc" :parent-ids [] :return-type-fn-id text-id}
           {:id text-id :name "text" :parent-ids []}
           (:fn a) (:fn b)]
     :slots [{:id slot-map :name "map" :type-fn-id text-id}
             {:id slot-key :name "key" :type-fn-id text-id}
             {:id slot-value :name "value" :type-fn-id text-id}]
     :fn-slots [{:fn-id assoc-id :slot-id slot-map :position 0}
                {:fn-id assoc-id :slot-id slot-key :position 1}
                {:fn-id assoc-id :slot-id slot-value :position 2}]
     :bindings (into (:bindings a) (:bindings b))
     :list-items []}))


(defn- stub-ctx
  [g]
  {:graph-cache (atom g)
   :storage #_{:clj-kondo/ignore [:missing-protocol-method]}
   (reify sp/StorageCRUD
     (query-entities [_ _ _] ns-rows)

     (query-entities [_ _ _ _] ns-rows))})


(defn- dup-ids
  [ctx]
  (into [] (comp (filter (comp #{:duplicate-definition} :rule)) (map :fn-ids))
        (lg/lint-branch ctx #{})))


(deftest a-concealed-fn-is-no-duplicate-of-anything
  (let [ctx (stub-ctx (graph))]
    (testing "control — the two fns are built the same way"
      (is (= [[(uid 31) (uid 32)]] (dup-ids ctx))))
    (reset! entities/view-impl-filter
            (fn [g]
              (entities/strip-impl-of g (into #{} (comp (filter (comp #{"theirs"} :name)) (map :id))
                                              (:fns g)))))
    (try
      (is (= [] (dup-ids ctx)) "the finding would state how `theirs` is built")
      (finally (reset! entities/view-impl-filter nil)))
    (testing "the memo is keyed per concealed set — the unfiltered view is not served the filtered answer"
      (is (= [[(uid 31) (uid 32)]] (dup-ids ctx))))))
