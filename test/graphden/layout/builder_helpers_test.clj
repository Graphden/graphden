(ns graphden.layout.builder-helpers-test
  "Directed unit tests for `edge-description-fields` — the server-side
   description-precedence walk behind the `:descSource` edge fact. The
   integration test (`layout-strip-facts-test/edge-desc-source-fact`)
   accepts either outcome per edge, so the per-fn binding-override arm
   — the whole reason the walk moved server-side — needs these
   synthetic-lookups cases to be provably exercised."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.layout.builder-helpers :as bh]))


(def arg-id (random-uuid))
(def slot-id (random-uuid))
(def child-id (random-uuid))
(def parent-id (random-uuid))
(def child-binding-id (random-uuid))
(def parent-binding-id (random-uuid))


(defn- lookups
  [binding-by-fn-slot]
  {:arg-map {arg-id {:slot-id slot-id :fn-id child-id}}
   :fn-map {child-id {:parent-ids [parent-id]}
            parent-id {:parent-ids []}}
   :binding-by-fn-slot binding-by-fn-slot
   :slot-map {slot-id {:id slot-id :description "canonical slot text"}}})


(deftest own-binding-override-wins
  (let [r (bh/edge-description-fields
            (lookups {[child-id slot-id] {:id child-binding-id :description "own override"}})
            arg-id)]
    (is (= {:descSource {:entityType "binding"
                         :entityId (str child-binding-id)}}
           r))))


(deftest ancestor-binding-override-found-through-parent-ids
  (let [r (bh/edge-description-fields
            (lookups {[parent-id slot-id] {:id parent-binding-id :description "inherited override"}})
            arg-id)]
    (is (= {:descSource {:entityType "binding"
                         :entityId (str parent-binding-id)}}
           r))))


(deftest closest-binding-wins-over-ancestor
  (let [r (bh/edge-description-fields
            (lookups {[child-id slot-id] {:id child-binding-id :description "own"}
                      [parent-id slot-id] {:id parent-binding-id :description "ancestor"}})
            arg-id)]
    (is (= (str child-binding-id) (get-in r [:descSource :entityId])))))


(deftest empty-description-does-not-shadow-ancestor
  ;; A binding row PRESENT but with an empty description is not an
  ;; override — the walk must continue to the ancestor's real one.
  (let [r (bh/edge-description-fields
            (lookups {[child-id slot-id] {:id child-binding-id :description ""}
                      [parent-id slot-id] {:id parent-binding-id :description "ancestor"}})
            arg-id)]
    (is (= (str parent-binding-id) (get-in r [:descSource :entityId])))))


(deftest slot-fallback-when-no-binding-describes
  (let [r (bh/edge-description-fields (lookups {}) arg-id)]
    (is (= {:descSource {:entityType "slot"
                         :entityId (str slot-id)}}
           r))))


(deftest unresolvable-arg-returns-empty-map
  (testing "unknown arg-id"
    (is (= {} (bh/edge-description-fields (lookups {}) (random-uuid)))))
  (testing "arg row without a slot-id"
    (is (= {} (bh/edge-description-fields
                (assoc-in (lookups {}) [:arg-map arg-id :slot-id] nil)
                arg-id)))))
