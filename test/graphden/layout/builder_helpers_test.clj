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


;; =============================================================================
;; Expansion specs — what the editor's "expand ancestors" control means
;; =============================================================================
;; The spec arrives from the client as either a LEVEL (an integer depth) or a
;; map carrying `:full-depth` plus hand-picked `:partial-fns`. Every downstream
;; decision — which ancestors merge into the focus card, whether the layout can
;; skip the whole expansion pass — reads it through these three functions, so
;; the shapes they accept are a wire contract with the editor.

(def ^:private gp-id (random-uuid))


(def ^:private chain-fn-map
  "child → parent → grandparent, the fn-map shape `get-inheritance-levels`
   walks."
  {child-id {:id child-id :parent-ids [parent-id]}
   parent-id {:id parent-id :parent-ids [gp-id]}
   gp-id {:id gp-id :parent-ids []}})


(deftest get-effective-spec-defaults-to-collapsed
  (testing "a node with no entry is collapsed, not expanded"
    (is (zero? (bh/get-effective-spec {} "node-1")))
    (is (zero? (bh/get-effective-spec {"other" 2} "node-1"))))
  (testing "an entry is returned as-is, whatever its shape"
    (is (= 2 (bh/get-effective-spec {"node-1" 2} "node-1")))
    (is (= {:full-depth 1} (bh/get-effective-spec {"node-1" {:full-depth 1}} "node-1")))))


(deftest spec-trivial?-recognises-every-do-nothing-spec
  (testing "level 0 and an empty map expand nothing"
    (is (true? (bh/spec-trivial? 0)))
    (is (true? (bh/spec-trivial? {:full-depth 0 :partial-fns []})))
    (is (true? (bh/spec-trivial? {}))))
  (testing "a depth or a hand-picked fn is not trivial"
    (is (false? (bh/spec-trivial? 1)))
    (is (false? (bh/spec-trivial? {:full-depth 1})))
    (is (false? (bh/spec-trivial? {:full-depth 0 :partial-fns [(random-uuid)]}))))
  (testing "an unknown spec shape is treated as trivial, never as an expansion"
    (is (true? (bh/spec-trivial? nil)))
    (is (true? (bh/spec-trivial? "2")))))


(deftest spec->expand-set-cascades-by-level-and-adds-picks
  (testing "level 0 is the focus fn alone"
    (is (= #{child-id} (bh/spec->expand-set chain-fn-map child-id 0))))
  (testing "each level pulls in one more ancestor generation"
    (is (= #{child-id parent-id} (bh/spec->expand-set chain-fn-map child-id 1)))
    (is (= #{child-id parent-id gp-id} (bh/spec->expand-set chain-fn-map child-id 2))))
  (testing "a depth past the root stops at the root"
    (is (= #{child-id parent-id gp-id} (bh/spec->expand-set chain-fn-map child-id 9))))
  (testing "the map shape means the same thing as the integer"
    (is (= (bh/spec->expand-set chain-fn-map child-id 1)
           (bh/spec->expand-set chain-fn-map child-id {:full-depth 1}))))
  (testing "hand-picked fns join the cascade, and a STRING id is parsed"
    ;; The editor sends ids as JSON strings; a set of strings would never
    ;; match the uuid-keyed fn-map, so the parse is the contract.
    (is (= #{child-id gp-id}
           (bh/spec->expand-set chain-fn-map child-id
                                {:full-depth 0 :partial-fns [(str gp-id)]})))
    (is (= #{child-id gp-id}
           (bh/spec->expand-set chain-fn-map child-id
                                {:full-depth 0 :partial-fns [gp-id]}))))
  (testing "an unknown spec shape expands nothing beyond the focus"
    (is (= #{child-id} (bh/spec->expand-set chain-fn-map child-id nil)))
    (is (= #{child-id} (bh/spec->expand-set chain-fn-map child-id "1")))))


;; =============================================================================
;; arg-row->node-id-fields — the ids the editor addresses rows by
;; =============================================================================
;; Editor JS reads these straight off the node and PUTs to
;; /api/entities/binding/:id with them, so a dropped or mistyped key is a
;; silently unaddressable row, not a visual glitch.

(deftest arg-row->node-id-fields-emits-only-the-ids-present
  (testing "an empty arg row yields no id fields at all"
    (is (= {} (bh/arg-row->node-id-fields {}))))
  (testing "each id is stringified under its camelCase editor key"
    (let [slot (random-uuid) binding (random-uuid) item (random-uuid) fn- (random-uuid)]
      (is (= {:slotId (str slot) :bindingId (str binding)
              :itemId (str item) :fnId (str fn-)}
             (bh/arg-row->node-id-fields {:slot-id slot :binding-id binding
                                          :item-id item :fn-id fn-})))))
  (testing "`:arg-type` names the type"
    (is (= {:argType "text"} (bh/arg-row->node-id-fields {:arg-type :text}))))
  (testing "`:type` is the fallback, and never overrides an explicit :arg-type"
    (is (= {:argType "int"} (bh/arg-row->node-id-fields {:type :int})))
    (is (= {:argType "text"} (bh/arg-row->node-id-fields {:arg-type :text :type :int})))))
