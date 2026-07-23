(ns graphden.crud.validation-test
  "DB-backed tests for the server-side write-time guards in
   `graphden.crud.validation` — cycle detection, MI-collision,
   value-override + `:list-closed` enforcement, constraint-shape
   validation, and the `write-rej` aggregator.

   Follows the container pattern of `crud-tighten-test`: a shared
   PostgreSQL container, a fresh storage per test."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.validation :as v]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (setup/create-container-fixture))


;; ============================================================================
;; Storage scenario helpers
;; ============================================================================

(defn- make-binding!
  "Create a raw binding row with arbitrary extra fields
   (`:list-append`, `:list-closed`, …) — `bind-value!` only covers
   the plain value case."
  [storage fields]
  (sp/create-entity storage :binding fields))


(defn- cyclic-ref-graph!
  "Build the canonical 3-fn ref cycle used by `constraints-test`:
   composed a / b / c all inheriting one base-fn, with bindings
   b.x→c and c.x→a. Validating an a→b edge then closes a→b→c→a.
   Returns `{:a :b :c :slot-x}` ids."
  [storage]
  (let [base   (setup/create-base-fn! storage "cyc-base")
        slot-x (setup/create-slot! storage "x" (:id base))
        _      (setup/attach-slot! storage (:id base) (:id slot-x) 0)
        a      (setup/create-composed-fn! storage "cyc-a" (:id base))
        b      (setup/create-composed-fn! storage "cyc-b" (:id base))
        c      (setup/create-composed-fn! storage "cyc-c" (:id base))]
    (setup/bind-ref! storage (:id b) (:id slot-x) (:id c))
    (setup/bind-ref! storage (:id c) (:id slot-x) (:id a))
    {:a (:id a) :b (:id b) :c (:id c) :slot-x (:id slot-x)}))


;; ============================================================================
;; constraint-type-ref-names — pure
;; ============================================================================

(deftest constraint-type-ref-names-test
  (testing "union branches surface as bare-name strings, op head dropped"
    (is (= #{"my-int" "my-text"}
           (v/constraint-type-ref-names [:union :my-int :my-text]))))

  (testing "refine — base name kept, atomic op + literal dropped"
    (is (= #{"int"} (v/constraint-type-ref-names [:refine :int [:> 0]]))))

  (testing "fn-type — names buried in the args-map and ret are found"
    (is (= #{"my-arg-type" "my-ret"}
           (v/constraint-type-ref-names [:fn {:req :my-arg-type} :my-ret]))))

  (testing "compound of pure ops + numbers → empty set"
    (is (= #{} (v/constraint-type-ref-names [:and [:> 0] [:< 10]]))))

  (testing "nil / non-collection → empty set"
    (is (= #{} (v/constraint-type-ref-names nil)))))


;; ============================================================================
;; cycle-check-pair
;; ============================================================================

(deftest cycle-check-pair-test
  (testing "nil owner or nil ref → nil (nothing to check)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [f (setup/create-base-fn! storage "cp-a")]
          (is (nil? (v/cycle-check-pair storage nil (:id f))))
          (is (nil? (v/cycle-check-pair storage (:id f) nil))))
        (finally (sp/close storage)))))

  (testing "two unrelated fns → no cycle"
    (let [storage (setup/create-test-storage)]
      (try
        (let [a (setup/create-base-fn! storage "cp-x")
              b (setup/create-base-fn! storage "cp-y")]
          (is (nil? (v/cycle-check-pair storage (:id a) (:id b)))))
        (finally (sp/close storage)))))

  (testing "self-reference is allowed (recursion, depth-bounded at runtime)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [a (setup/create-base-fn! storage "cp-self")]
          (is (nil? (v/cycle-check-pair storage (:id a) (:id a)))))
        (finally (sp/close storage)))))

  (testing "a→b that closes a→b→c→a is rejected"
    (let [storage (setup/create-test-storage)]
      (try
        (let [{:keys [a b]} (cyclic-ref-graph! storage)
              rej (v/cycle-check-pair storage a b)]
          (is (some? rej))
          (is (re-find #"[Dd]ependency cycle" (:reason rej))))
        (finally (sp/close storage))))))


;; ============================================================================
;; mi-collision-check / mi-collision-rej
;; ============================================================================

(deftest mi-collision-check-test
  (testing "fewer than two parents cannot collide"
    (let [storage (setup/create-test-storage)]
      (try
        (let [p (setup/create-base-fn! storage "mi-solo")]
          (is (nil? (v/mi-collision-check storage [(:id p)])))
          (is (nil? (v/mi-collision-check storage []))))
        (finally (sp/close storage)))))

  (testing "two parents exposing the SAME slot identity → no collision"
    (let [storage (setup/create-test-storage)]
      (try
        (let [p1   (setup/create-base-fn! storage "mi-shared-1")
              p2   (setup/create-base-fn! storage "mi-shared-2")
              slot (setup/create-slot! storage "shared" :int)]
          (setup/attach-slot! storage (:id p1) (:id slot) 0)
          (setup/attach-slot! storage (:id p2) (:id slot) 0)
          (is (nil? (v/mi-collision-check storage [(:id p1) (:id p2)]))))
        (finally (sp/close storage)))))

  (testing "two parents with distinct slots under the same name → collision"
    (let [storage (setup/create-test-storage)]
      (try
        (let [p1 (setup/create-base-fn! storage "mi-clash-1")
              p2 (setup/create-base-fn! storage "mi-clash-2")
              s1 (setup/create-slot! storage "dup" :int)
              s2 (setup/create-slot! storage "dup" :text)]
          (setup/attach-slot! storage (:id p1) (:id s1) 0)
          (setup/attach-slot! storage (:id p2) (:id s2) 0)
          (let [rej (v/mi-collision-check storage [(:id p1) (:id p2)])]
            (is (some? rej))
            (is (re-find #"collision" (:reason rej)))))
        (finally (sp/close storage))))))


(deftest mi-collision-rej-test
  (testing "non-:fn writes pass through; :fn write with a colliding parent set is rejected"
    (let [storage (setup/create-test-storage)]
      (try
        (let [p1 (setup/create-base-fn! storage "mir-1")
              p2 (setup/create-base-fn! storage "mir-2")
              s1 (setup/create-slot! storage "n" :int)
              s2 (setup/create-slot! storage "n" :text)]
          (setup/attach-slot! storage (:id p1) (:id s1) 0)
          (setup/attach-slot! storage (:id p2) (:id s2) 0)
          (is (nil? (v/mi-collision-rej storage :slot {:parent-ids [(:id p1) (:id p2)]})))
          (is (nil? (v/mi-collision-rej storage :fn {:parent-ids [(:id p1)]})))
          (is (some? (v/mi-collision-rej storage :fn
                                         {:parent-ids [(:id p1) (:id p2)]}))))
        (finally (sp/close storage))))))


;; ============================================================================
;; ancestor-binding-flag? / list-closed-rej
;; ============================================================================

(deftest ancestor-binding-flag-test
  (testing ":list-closed set on a parent's binding is visible from the child"
    (let [storage (setup/create-test-storage)]
      (try
        (let [parent (setup/create-base-fn! storage "abf-parent")
              slot   (setup/create-slot! storage "items" :any)
              _      (setup/attach-slot! storage (:id parent) (:id slot) 0)
              _      (make-binding! storage {:fn-id (:id parent) :slot-id (:id slot)
                                             :list-closed true})
              child  (setup/create-composed-fn! storage "abf-child" (:id parent))]
          (is (true? (v/ancestor-binding-flag? storage (:id child) (:id slot) :list-closed))))
        (finally (sp/close storage)))))

  (testing "no ancestor → false"
    (let [storage (setup/create-test-storage)]
      (try
        (let [f    (setup/create-base-fn! storage "abf-orphan")
              slot (setup/create-slot! storage "s" :int)]
          (is (false? (v/ancestor-binding-flag? storage (:id f) (:id slot) :list-closed))))
        (finally (sp/close storage))))))


(deftest value-override-rej-test
  (testing "binding write rejected when an ancestor already supplied a value"
    (let [storage (setup/create-test-storage)]
      (try
        (let [parent (setup/create-base-fn! storage "vor-value-parent")
              slot   (setup/create-slot! storage "s" :int)
              _      (setup/attach-slot! storage (:id parent) (:id slot) 0)
              _      (make-binding! storage {:fn-id (:id parent) :slot-id (:id slot)
                                             :value 42})
              child  (setup/create-composed-fn! storage "vor-value-child" (:id parent))
              rej    (v/value-override-rej storage :binding
                                           {:fn-id (:id child) :slot-id (:id slot)})]
          (is (some? rej))
          (is (re-find #"already supplied a value" (:reason rej))))
        (finally (sp/close storage)))))

  (testing "binding write rejected when an ancestor already supplied a ref-fn-id"
    (let [storage (setup/create-test-storage)]
      (try
        (let [parent (setup/create-base-fn! storage "vor-ref-parent")
              target (setup/create-base-fn! storage "vor-ref-target")
              slot   (setup/create-slot! storage "s" :any)
              _      (setup/attach-slot! storage (:id parent) (:id slot) 0)
              _      (make-binding! storage {:fn-id (:id parent) :slot-id (:id slot)
                                             :ref-fn-id (:id target)})
              child  (setup/create-composed-fn! storage "vor-ref-child" (:id parent))
              rej    (v/value-override-rej storage :binding
                                           {:fn-id (:id child) :slot-id (:id slot)})]
          (is (some? rej)))
        (finally (sp/close storage)))))

  (testing "ancestor with rename / type-narrowing only (no value, no ref) → child write allowed"
    ;; Mirrors `:ex-keyword-key-assoc :args {:key {:type :keyword}}` →
    ;; `:ex-keyword-key-status :args {:key :status}` — parent narrows
    ;; type, child fills value, no override happens.
    (let [storage (setup/create-test-storage)]
      (try
        (let [parent (setup/create-base-fn! storage "vor-narrow-parent")
              type-fn (setup/create-base-fn! storage "vor-narrow-type")
              slot   (setup/create-slot! storage "s" :any)
              _      (setup/attach-slot! storage (:id parent) (:id slot) 0)
              ;; Parent binding has only :type-override-fn-id — no value.
              _      (make-binding! storage {:fn-id (:id parent) :slot-id (:id slot)
                                             :type-override-fn-id (:id type-fn)})
              child  (setup/create-composed-fn! storage "vor-narrow-child" (:id parent))]
          (is (nil? (v/value-override-rej storage :binding
                                          {:fn-id (:id child) :slot-id (:id slot)}))))
        (finally (sp/close storage)))))

  (testing "no ancestor binding at all → child write allowed"
    (let [storage (setup/create-test-storage)]
      (try
        (let [parent (setup/create-base-fn! storage "vor-empty-parent")
              slot   (setup/create-slot! storage "s" :int)
              _      (setup/attach-slot! storage (:id parent) (:id slot) 0)
              child  (setup/create-composed-fn! storage "vor-empty-child" (:id parent))]
          (is (nil? (v/value-override-rej storage :binding
                                          {:fn-id (:id child) :slot-id (:id slot)}))))
        (finally (sp/close storage)))))

  (testing "non-:binding writes → nil (validator only fires for :binding entity-type)"
    (let [storage (setup/create-test-storage)]
      (try
        (is (nil? (v/value-override-rej storage :slot
                                        {:fn-id (random-uuid) :slot-id (random-uuid)})))
        (is (nil? (v/value-override-rej storage :fn
                                        {:fn-id (random-uuid) :slot-id (random-uuid)})))
        (finally (sp/close storage))))))


(deftest list-closed-rej-test
  (testing "`:list-append` binding on a list sealed by an ancestor is rejected"
    (let [storage (setup/create-test-storage)]
      (try
        (let [parent (setup/create-base-fn! storage "lc-parent")
              slot   (setup/create-slot! storage "items" :int)
              _      (setup/attach-slot! storage (:id parent) (:id slot) 0)
              _      (make-binding! storage {:fn-id (:id parent) :slot-id (:id slot)
                                             :list-closed true})
              child  (setup/create-composed-fn! storage "lc-child" (:id parent))]
          ;; A descendant trying to extend the sealed list.
          (let [rej (v/list-closed-rej storage :binding
                                       {:fn-id (:id child) :slot-id (:id slot)
                                        :list-append true})]
            (is (some? rej))
            (is (re-find #"list-closed" (:reason rej))))
          ;; Without `:list-append true` there is nothing to seal against.
          (is (nil? (v/list-closed-rej storage :binding
                                       {:fn-id (:id child) :slot-id (:id slot)}))))
        (finally (sp/close storage))))))


(deftest terminal-rej-test
  (testing "a binding on a slot an ancestor sealed `:terminal true` is rejected (§4.3)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [parent (setup/create-base-fn! storage "term-parent")
              slot   (setup/create-slot! storage "arg" :int)
              _      (setup/attach-slot! storage (:id parent) (:id slot) 0)
              _      (make-binding! storage {:fn-id (:id parent) :slot-id (:id slot)
                                             :terminal true})
              child  (setup/create-composed-fn! storage "term-child" (:id parent))]
          ;; A descendant trying to override the sealed slot — rejected even
          ;; though no VALUE was set on the ancestor (the explicit seal goes
          ;; beyond value-override-rej's automatic value-seal).
          (let [rej (v/terminal-rej storage :binding
                                    {:fn-id (:id child) :slot-id (:id slot)
                                     :value 5 :value-present true})]
            (is (some? rej))
            (is (re-find #"terminal" (:reason rej))))
          ;; Non-:binding entity types are never terminal-checked.
          (is (nil? (v/terminal-rej storage :slot
                                    {:fn-id (:id child) :slot-id (:id slot)})))
          ;; A sibling slot with no terminal ancestor is freely bindable.
          (let [free (setup/create-slot! storage "other" :int)]
            (setup/attach-slot! storage (:id parent) (:id free) 1)
            (is (nil? (v/terminal-rej storage :binding
                                      {:fn-id (:id child) :slot-id (:id free)
                                       :value 1 :value-present true})))))
        (finally (sp/close storage))))))


;; ============================================================================
;; resolve-base-name
;; ============================================================================

(deftest resolve-base-name-test
  (testing "a primitive id resolves to its own keyword"
    (let [storage (setup/create-test-storage)]
      (try
        (is (= :int (v/resolve-base-name storage (get setup/primitive-fn-ids :int))))
        (finally (sp/close storage)))))

  (testing "refinement-of-refinement descends to the primitive base"
    (let [storage (setup/create-test-storage)
          int-id  (get setup/primitive-fn-ids :int)]
      (try
        (let [pos  (sp/create-entity storage :fn
                                     {:name "rbn-pos" :parent-ids []
                                      :base-fn-id int-id :constraint [:> 0]})
              big  (sp/create-entity storage :fn
                                     {:name "rbn-big" :parent-ids []
                                      :base-fn-id (:id pos) :constraint [:> 100]})]
          (is (= :int (v/resolve-base-name storage (:id big)))))
        (finally (sp/close storage)))))

  (testing "a non-refinement fn → nil (chain dead-ends)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [f (setup/create-base-fn! storage "rbn-plain")]
          (is (nil? (v/resolve-base-name storage (:id f)))))
        (finally (sp/close storage))))))


;; ============================================================================
;; constraint-shape-rej
;; ============================================================================

(deftest constraint-shape-rej-test
  (let [storage (setup/create-test-storage)
        int-id  (get setup/primitive-fn-ids :int)
        text-id (get setup/primitive-fn-ids :text)
        rej?    (fn [data] (v/constraint-shape-rej storage :fn data))]
    (try
      (testing "non-:fn entity / nil constraint → nil"
        (is (nil? (v/constraint-shape-rej storage :slot {:constraint [:union :int]})))
        (is (nil? (rej? {:constraint nil}))))

      (testing "constraint must be a vector"
        (is (re-find #"must be a vector" (:reason (rej? {:constraint "nope"})))))

      (testing "union shape rules"
        (is (re-find #"≥ 2 branches" (:reason (rej? {:constraint [:union :int]}))))
        (is (re-find #"duplicate"    (:reason (rej? {:constraint [:union :int :int]}))))
        (is (nil? (rej? {:constraint [:union :int :text]}))))

      (testing "variant shape rules"
        (is (re-find #"≥ 1" (:reason (rej? {:constraint [:variant]}))))
        (is (re-find #"pairs" (:reason (rej? {:constraint [:variant :ok]}))))
        (is (re-find #"duplicate tags"
                     (:reason (rej? {:constraint [:variant :ok :int :ok :text]}))))
        (is (nil? (rej? {:constraint [:variant :ok :int :err :text]}))))

      (testing ":and / :or need at least one operand"
        (is (re-find #"≥ 1 operand" (:reason (rej? {:constraint [:and]}))))
        (is (nil? (rej? {:constraint [:and [:> 0]]}))))

      (testing "fn-type constraint shape"
        (is (re-find #"args-map"
                     (:reason (rej? {:constraint [:fn :int]}))))
        (is (re-find #"both args-map and return-type"
                     (:reason (rej? {:constraint [:fn {}]}))))
        (is (nil? (rej? {:constraint [:fn {} :int]}))))

      (testing "refinement op must be legal on the resolved base type"
        (is (re-find #"not legal on base type"
                     (:reason (rej? {:constraint [:>= 0] :base-fn-id text-id}))))
        (is (nil? (rej? {:constraint [:> 0] :base-fn-id int-id}))))
      (finally (sp/close storage)))))


;; ============================================================================
;; resolve-constraint-refs-to-ids
;; ============================================================================

(deftest resolve-constraint-refs-to-ids-test
  (testing "empty name set → empty id set"
    (let [storage (setup/create-test-storage)]
      (try
        (is (= #{} (v/resolve-constraint-refs-to-ids storage #{})))
        (finally (sp/close storage)))))

  (testing "known names resolve to ids; unknown names are dropped"
    (let [storage (setup/create-test-storage)]
      (try
        (let [a (setup/create-base-fn! storage "rcr-type-a")
              b (setup/create-base-fn! storage "rcr-type-b")
              ids (v/resolve-constraint-refs-to-ids
                    storage #{"rcr-type-a" "rcr-type-b" "rcr-nonexistent"})]
          (is (= #{(:id a) (:id b)} ids)))
        (finally (sp/close storage))))))


;; ============================================================================
;; cycle-check-rej / write-rej
;; ============================================================================

(deftest cycle-check-rej-test
  (testing ":binding ref-fn-id that closes a cycle is rejected"
    (let [storage (setup/create-test-storage)]
      (try
        (let [{:keys [a b]} (cyclic-ref-graph! storage)]
          ;; a→b would close a→b→c→a.
          (is (some? (v/cycle-check-rej storage :binding
                                        {:fn-id a :ref-fn-id b})))
          ;; b→b self-ref, and an unrelated edge, are fine.
          (is (nil? (v/cycle-check-rej storage :binding
                                       {:fn-id b :ref-fn-id b}))))
        (finally (sp/close storage))))))


(deftest write-rej-test
  (testing "aggregates cycle rejection with the dependency-cycle type tag"
    (let [storage (setup/create-test-storage)]
      (try
        (let [{:keys [a b]} (cyclic-ref-graph! storage)
              rej (v/write-rej storage :binding {:fn-id a :ref-fn-id b})]
          (is (= :constraint-violation/dependency-cycle (:type rej)))
          (is (string? (:reason rej))))
        (finally (sp/close storage)))))

  (testing "aggregates constraint-shape rejection with its type tag"
    (let [storage (setup/create-test-storage)]
      (try
        (let [rej (v/write-rej storage :fn {:constraint [:union :int]})]
          (is (= :constraint-violation/constraint-shape (:type rej))))
        (finally (sp/close storage)))))

  (testing "a clean write → nil"
    (let [storage (setup/create-test-storage)]
      (try
        (let [a (setup/create-base-fn! storage "wr-clean-a")
              b (setup/create-base-fn! storage "wr-clean-b")]
          (is (nil? (v/write-rej storage :binding
                                 {:fn-id (:id a) :ref-fn-id (:id b)}))))
        (finally (sp/close storage))))))


;; =============================================================================
;; reparent-cross-branch-rej — parent-set edits are root-branch-only
;; =============================================================================

(deftest reparent-cross-branch-gate-test
  (let [storage (setup/create-versioned-test-storage)]
    (try
      (let [p1 (setup/create-base-fn! storage "rcb-parent-1")
            p2 (setup/create-base-fn! storage "rcb-parent-2")
            f (sp/create-entity storage :fn {:name "rcb-child"
                                             :parent-ids [(:id p1)]})
            reparent {:id (:id f) :parent-ids [(:id p2)]}]
        (testing "root branch, no diverging versions → allowed"
          (is (nil? (v/write-rej storage :fn reparent))))
        (testing "parent-preserving update → allowed anywhere"
          (is (nil? (v/write-rej storage :fn {:id (:id f)
                                              :parent-ids [(:id p1)]
                                              :description "relabel"}))))
        (testing "off-root branch → rejected"
          (let [b (vs/create-branch! storage "rcb-feature")
                on-b (vs/switch-branch storage (:id b))]
            (is (re-find #"root branch"
                         (:reason (v/write-rej on-b :fn reparent))))
            (testing "…and a version row written on that branch blocks
                      the ROOT-side re-parent too"
              (sp/update-entity on-b :fn (:id f) {:description "branch edit"})
              (let [rej (v/write-rej storage :fn reparent)]
                (is (some? rej))
                (is (re-find #"rcb-feature" (:reason rej))
                    "the diverging branch is named in the reason"))))))
      (finally (sp/close storage)))))


;; =============================================================================
;; resolver-rej — generic resolver bindings can't launder hidden markers
;; =============================================================================

(deftest resolver-rej-test
  (let [storage (setup/create-versioned-test-storage)]
    (try
      (let [plain (setup/create-base-fn! storage "rr-plain" :text)
            plain-slot (setup/create-slot! storage "s" :text)
            _ (setup/attach-slot! storage (:id plain) (:id plain-slot) 0)
            owner (setup/create-base-fn! storage "rr-owner" :any)
            owner-slot (setup/create-slot! storage "x" :text)
            _ (setup/attach-slot! storage (:id owner) (:id owner-slot) 0)]
        (testing "nonexistent resolver → rejected"
          (is (re-find #"does not resolve"
                       (:reason (v/write-rej storage :binding
                                             {:fn-id (:id owner)
                                              :resolver-fn-id (random-uuid)
                                              :value "v" :value-present true})))))
        (testing "plain resolver into plain slot → allowed"
          (is (nil? (v/write-rej storage :binding
                                 {:fn-id (:id owner)
                                  :resolver-fn-id (:id plain)
                                  :value "v" :value-present true})))))
      (finally (sp/close storage)))))
