(ns graphden.crud.type-check-test
  "Tests for `graphden.crud.type-check` — the save-time type guards
   and type-row chain walkers behind the web/crud endpoints.

   `rich-type-from-row` is pure (no fixture); the rest reconstruct
   EDN-shape fn-defs from DB rows, so they use the shared container."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.type-check :as tc]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once
  (setup/create-container-fixture)
  ;; `record-rich-types(-raw)!` writes by this ns leak into sibling
  ;; integration tests otherwise — see check-test for the same fix.
  exec/with-isolated-rich-types)


;; ============================================================================
;; rich-type-from-row — pure
;; ============================================================================

(deftest rich-type-from-row-test
  (let [int-row  {:id :int-id :name "int" :parent-ids []}
        text-row {:id :text-id :name "text" :parent-ids []}
        fns      {:int-id int-row :text-id text-row}]
    (testing "nil row → nil"
      (is (nil? (tc/rich-type-from-row nil fns))))

    (testing "primitive row → its keyword"
      (is (= :int (tc/rich-type-from-row int-row fns))))

    (testing "refinement walks base-fn-id"
      (is (= [:refine :int [:> 0]]
             (tc/rich-type-from-row {:base-fn-id :int-id :constraint [:> 0]}
                                    fns))))

    (testing "value-carrying constraints preserve their string values"
      ;; The storage codec already round-trips keyword operators and
      ;; leaves string values intact — `rich-type-from-row` must NOT
      ;; keywordize them. Regression: `[:not= ""]` once became
      ;; `[:not= :]`, silently making `:non-empty-text` accept `""`.
      (is (= [:refine :text [:not= ""]]
             (tc/rich-type-from-row {:base-fn-id :text-id :constraint [:not= ""]}
                                    fns)))
      (is (= [:refine :text [:= "x"]]
             (tc/rich-type-from-row {:base-fn-id :text-id :constraint [:= "x"]}
                                    fns)))
      (is (= [:refine :text [:in ["a" "b"]]]
             (tc/rich-type-from-row
               {:base-fn-id :text-id :constraint [:in ["a" "b"]]} fns)))
      (is (= [:refine :text [:matches "^[a-z]+$"]]
             (tc/rich-type-from-row
               {:base-fn-id :text-id :constraint [:matches "^[a-z]+$"]} fns))))

    (testing "list walks element-fn-id"
      (is (= [:list :int]
             (tc/rich-type-from-row {:element-fn-id :int-id} fns))))

    (testing "union surfaces its constraint verbatim"
      (is (= [:union :int :text]
             (tc/rich-type-from-row {:constraint [:union :int :text]} fns))))

    (testing "map / tuple constraints surface verbatim"
      (is (= [:map :keyword :int]
             (tc/rich-type-from-row {:constraint [:map :keyword :int]} fns)))
      (is (= [:tuple :text :int]
             (tc/rich-type-from-row {:constraint [:tuple :text :int]} fns))))

    (testing "variant constraint is desugared"
      (is (some? (tc/rich-type-from-row
                   {:constraint [:variant :ok :int :err :text]} fns))))

    (testing "base-fn (return-type-fn-id set, no type role) degrades to :jsonb"
      (is (= :jsonb
             (tc/rich-type-from-row {:name "add" :parent-ids []
                                     :return-type-fn-id :any-id}
                                    fns))))))


;; ============================================================================
;; resolve-type-fn-id / resolve-type-fn-id-or-throw
;; ============================================================================

(deftest resolve-type-fn-id-test
  (testing "blank input → nil"
    (let [storage (setup/create-test-storage)]
      (try
        (is (nil? (tc/resolve-type-fn-id storage "")))
        (is (nil? (tc/resolve-type-fn-id storage nil)))
        (finally (sp/close storage)))))

  (testing "a raw UUID string is returned as-is"
    (let [storage (setup/create-test-storage)
          u (random-uuid)]
      (try
        (is (= u (tc/resolve-type-fn-id storage (str u))))
        (finally (sp/close storage)))))

  (testing "a known type-row name resolves to its id"
    (let [storage (setup/create-test-storage)]
      (try
        (let [f (setup/create-base-fn! storage "rtfi-known")]
          (is (= (:id f) (tc/resolve-type-fn-id storage "rtfi-known"))))
        (finally (sp/close storage)))))

  (testing "an unknown name throws :crud/unknown-type-ref"
    ;; The enum-typed `fn.name` column makes an unmatched name an
    ;; invalid enum value; `query-fn-by-name` swallows the resulting
    ;; `:validation-error/type-mismatch` so the function's own
    ;; documented `:crud/unknown-type-ref` is the error that surfaces.
    (let [storage (setup/create-test-storage)]
      (try
        (let [ex (try (tc/resolve-type-fn-id storage "rtfi-nope")
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo ex))
          (is (= :crud/unknown-type-ref (:type (ex-data ex)))))
        (finally (sp/close storage))))))


(deftest resolve-type-fn-id-or-throw-test
  (testing "known name → id"
    (let [storage (setup/create-test-storage)]
      (try
        (let [f (setup/create-base-fn! storage "rtfiot-known")]
          (is (= (:id f) (tc/resolve-type-fn-id-or-throw storage "rtfiot-known"))))
        (finally (sp/close storage)))))

  (testing "blank input → throws :type-row/unknown-type"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ex (try (tc/resolve-type-fn-id-or-throw storage "")
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo ex))
          (is (= :type-row/unknown-type (:type (ex-data ex)))))
        (finally (sp/close storage))))))


;; ============================================================================
;; chain-fns-by-id / type-fn->rich-type
;; ============================================================================

(deftest type-fn->rich-type-test
  (testing "a primitive type-row resolves to its keyword"
    (let [storage (setup/create-test-storage)
          int-row (sp/read-entity storage :fn (get setup/primitive-fn-ids :int))]
      (try
        (is (= :int (tc/type-fn->rich-type storage int-row)))
        (finally (sp/close storage)))))

  (testing "a refinement row walks its base chain to the primitive"
    (let [storage (setup/create-test-storage)
          int-id  (get setup/primitive-fn-ids :int)]
      (try
        (let [pos (sp/create-entity storage :fn
                                    {:name "tfrt-pos" :parent-ids []
                                     :base-fn-id int-id :constraint [:> 0]})]
          (is (= [:refine :int [:> 0]]
                 (tc/type-fn->rich-type storage pos)))
          ;; chain-fns-by-id loaded the int row into the chain map.
          (is (contains? (tc/chain-fns-by-id storage pos) int-id)))
        (finally (sp/close storage))))))


;; ============================================================================
;; list-items-for-binding
;; ============================================================================

(deftest list-items-for-binding-test
  (testing "items come back ordered by position"
    (let [storage (setup/create-test-storage)]
      (try
        (let [f    (setup/create-base-fn! storage "lifb-fn")
              slot (setup/create-slot! storage "items" :int)
              b    (sp/create-entity storage :binding
                                     {:fn-id (:id f) :slot-id (:id slot)
                                      :override-kind :fixed})]
          (sp/create-entity storage :binding-list-item
                            {:binding-id (:id b) :position 1 :value 20})
          (sp/create-entity storage :binding-list-item
                            {:binding-id (:id b) :position 0 :value 10})
          (let [items (tc/list-items-for-binding storage (:id b))]
            (is (= [0 1] (mapv :position items)))
            (is (= [10 20] (mapv :value items)))))
        (finally (sp/close storage))))))


;; ============================================================================
;; reconstruct-fn-def
;; ============================================================================

(deftest reconstruct-fn-def-test
  (testing "a base-fn (no parents) → nil — nothing to type-check"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "rfd-base")]
          (is (nil? (tc/reconstruct-fn-def storage (:id base)))))
        (finally (sp/close storage)))))

  (testing "single-parent composed fn with a value binding"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base  (setup/create-base-fn! storage "rfd-add")
              slot  (setup/create-slot! storage "a" :int)
              _     (setup/attach-slot! storage (:id base) (:id slot) 0)
              child (setup/create-composed-fn! storage "rfd-add-5" (:id base))
              _     (setup/bind-value! storage (:id child) (:id slot) 5)
              fd    (tc/reconstruct-fn-def storage (:id child))]
          (is (= :rfd-add-5 (:name fd)))
          (is (= :rfd-add (:parent fd)))
          (is (= {:a {:value 5}} (:args fd))))
        (finally (sp/close storage)))))

  (testing "ref binding surfaces as the bound fn's name keyword"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base   (setup/create-base-fn! storage "rfd-host")
              slot   (setup/create-slot! storage "h" :int)
              _      (setup/attach-slot! storage (:id base) (:id slot) 0)
              target (setup/create-base-fn! storage "rfd-target")
              child  (setup/create-composed-fn! storage "rfd-uses-ref" (:id base))
              _      (setup/bind-ref! storage (:id child) (:id slot) (:id target))
              fd     (tc/reconstruct-fn-def storage (:id child))]
          (is (= {:h :rfd-target} (:args fd))))
        (finally (sp/close storage)))))

  (testing "list binding surfaces as a vector of item shapes"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base  (setup/create-base-fn! storage "rfd-listhost")
              slot  (setup/create-slot! storage "xs" :int)
              _     (setup/attach-slot! storage (:id base) (:id slot) 0)
              child (setup/create-composed-fn! storage "rfd-list" (:id base))
              b     (sp/create-entity storage :binding
                                      {:fn-id (:id child) :slot-id (:id slot)
                                       :override-kind :fixed})
              _     (sp/create-entity storage :binding-list-item
                                      {:binding-id (:id b) :position 0 :value 1})
              _     (sp/create-entity storage :binding-list-item
                                      {:binding-id (:id b) :position 1 :value 2})
              fd    (tc/reconstruct-fn-def storage (:id child))]
          (is (= {:xs [{:value 1 :literal? false}
                       {:value 2 :literal? false}]}
                 (:args fd))))
        (finally (sp/close storage)))))

  (testing "multi-inheritance composed fn → :parents vector"
    (let [storage (setup/create-test-storage)]
      (try
        (let [p1    (setup/create-base-fn! storage "rfd-p1")
              p2    (setup/create-base-fn! storage "rfd-p2")
              child (sp/create-entity storage :fn
                                      {:name "rfd-mi"
                                       :parent-ids [(:id p1) (:id p2)]})
              fd    (tc/reconstruct-fn-def storage (:id child))]
          (is (= [:rfd-p1 :rfd-p2] (:parents fd)))
          (is (nil? (:parent fd))))
        (finally (sp/close storage))))))


;; ============================================================================
;; type-check-fn-after-mutation!
;; ============================================================================

(deftest type-check-fn-after-mutation-test
  (testing "a base-fn short-circuits to nil (no parents → nothing to check)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "tcfam-base-only")]
          (is (nil? (tc/type-check-fn-after-mutation! storage (:id base)))))
        (finally (sp/close storage)))))

  (testing "a well-typed composed fn passes (returns nil)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base  (setup/create-base-fn! storage "tcfam-base")
              slot  (setup/create-slot! storage "a" :int)
              _     (setup/attach-slot! storage (:id base) (:id slot) 0)
              _     (registry/record-rich-types-raw!
                      :tcfam-base {:return :int :args {:a :int} :effects #{}})
              child (setup/create-composed-fn! storage "tcfam-child" (:id base))
              _     (setup/bind-value! storage (:id child) (:id slot) 5)]
          (is (nil? (tc/type-check-fn-after-mutation! storage (:id child)))))
        (finally (sp/close storage))))))


;; ============================================================================
;; type-check-binding-direct!
;; ============================================================================

(deftest type-check-binding-direct-test
  (testing ":any-typed slot — check is skipped (escape hatch)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [slot (setup/create-slot! storage "anything" :any)]
          (is (nil? (tc/type-check-binding-direct!
                      storage {:slot-id (:id slot) :value "whatever"} nil))))
        (finally (sp/close storage)))))

  (testing "value binding matching the slot type → nil"
    (let [storage (setup/create-test-storage)]
      (try
        (let [slot (setup/create-slot! storage "n" :int)]
          (is (nil? (tc/type-check-binding-direct!
                      storage {:slot-id (:id slot) :value 7} nil))))
        (finally (sp/close storage)))))

  (testing "value binding of the wrong type → rejection"
    (let [storage (setup/create-test-storage)]
      (try
        (let [slot (setup/create-slot! storage "n" :int)
              rej  (tc/type-check-binding-direct!
                     storage {:slot-id (:id slot) :value "hello"} nil)]
          (is (some? rej))
          (is (re-find #"Type mismatch on value" (:reason rej))))
        (finally (sp/close storage)))))

  (testing "ref binding whose return type clashes with the slot → rejection"
    (let [storage (setup/create-test-storage)]
      (try
        (let [slot   (setup/create-slot! storage "n" :int)
              target (setup/create-base-fn! storage "tcbd-text-fn")
              _      (registry/record-rich-types-raw!
                       :tcbd-text-fn {:return :text :args {} :effects #{}})
              rej    (tc/type-check-binding-direct!
                       storage {:slot-id (:id slot) :ref-fn-id (:id target)} nil)]
          (is (some? rej))
          (is (re-find #"Type mismatch on ref binding" (:reason rej))))
        (finally (sp/close storage)))))

  (testing "ref binding whose return type matches the slot → nil"
    (let [storage (setup/create-test-storage)]
      (try
        (let [slot   (setup/create-slot! storage "n" :int)
              target (setup/create-base-fn! storage "tcbd-int-fn")
              _      (registry/record-rich-types-raw!
                       :tcbd-int-fn {:return :int :args {} :effects #{}})]
          (is (nil? (tc/type-check-binding-direct!
                      storage {:slot-id (:id slot) :ref-fn-id (:id target)} nil))))
        (finally (sp/close storage)))))

  (testing "the slot-id is recovered from binding-id when entity-data omits it"
    (let [storage (setup/create-test-storage)]
      (try
        (let [base (setup/create-base-fn! storage "tcbd-bid-base")
              slot (setup/create-slot! storage "n" :int)
              _    (setup/attach-slot! storage (:id base) (:id slot) 0)
              comp-fn (setup/create-composed-fn! storage "tcbd-bid-comp"
                                                 (:id base))
              bind (setup/bind-value! storage (:id comp-fn) (:id slot) 3)]
          ;; entity-data carries no :slot-id — the check reads the
          ;; binding row to recover it
          (is (nil? (tc/type-check-binding-direct!
                      storage {:value 7} (:id bind)))))
        (finally (sp/close storage)))))

  (testing "a value satisfying a refinement-typed slot → nil"
    (let [storage (setup/create-test-storage)]
      (try
        (let [int-id (get setup/primitive-fn-ids :int)
              refine (sp/create-entity storage :fn
                                       {:name "tcbd-pos" :parent-ids []
                                        :base-fn-id int-id :constraint [:> 0]})
              slot   (setup/create-slot! storage "n" (:id refine))]
          ;; 5 > 0 — satisfies; base-subtype + refinement both hold
          (is (nil? (tc/type-check-binding-direct!
                      storage {:slot-id (:id slot) :value 5} nil))))
        (finally (sp/close storage)))))

  (testing "a non-empty-text slot rejects \"\" but accepts a non-empty string"
    ;; End-to-end guard for the constraint-keywordization bug: the
    ;; `[:not= ""]` constraint must reach the refinement check intact,
    ;; or `""` slips through.
    (let [storage (setup/create-test-storage)]
      (try
        (let [text-id (get setup/primitive-fn-ids :text)
              refine  (sp/create-entity storage :fn
                                        {:name "tcbd-nonempty" :parent-ids []
                                         :base-fn-id text-id :constraint [:not= ""]})
              slot    (setup/create-slot! storage "s" (:id refine))]
          (is (some? (tc/type-check-binding-direct!
                       storage {:slot-id (:id slot) :value ""} nil)))
          (is (nil? (tc/type-check-binding-direct!
                      storage {:slot-id (:id slot) :value "hello"} nil))))
        (finally (sp/close storage)))))

  (testing "a ref whose base-typed return feeds a refinement slot → nil"
    (let [storage (setup/create-test-storage)]
      (try
        (let [int-id (get setup/primitive-fn-ids :int)
              refine (sp/create-entity storage :fn
                                       {:name "tcbd-pos2" :parent-ids []
                                        :base-fn-id int-id :constraint [:> 0]})
              slot   (setup/create-slot! storage "n" (:id refine))
              target (setup/create-base-fn! storage "tcbd-ret-int")
              _      (registry/record-rich-types-raw!
                       :tcbd-ret-int {:return :int :args {} :effects #{}})]
          (is (nil? (tc/type-check-binding-direct!
                      storage {:slot-id (:id slot) :ref-fn-id (:id target)} nil))))
        (finally (sp/close storage)))))

  (testing "HOF-forwarding — scalar-returning ref into [:fn {} :any] slot"
    ;; `:future`-shaped slot expects a 0-arg callable returning :any.
    ;; A ref to a fn that returns `:int` should be ACCEPTED — the
    ;; runtime hof-wrap forwards the ref as the callable, and
    ;; `[:fn {} :int] ⊆ [:fn {} :any]` by covariant return.
    ;; Prior to the HOF-forwarding clause, the spot-check naively
    ;; compared `:int` vs `[:fn {} :any]` and rejected.
    (let [storage (setup/create-test-storage)]
      (try
        (let [slot   (setup/create-slot! storage "body" [:fn {} :any])
              target (setup/create-base-fn! storage "tcbd-hof-int-leaf")
              _      (registry/record-rich-types-raw!
                       :tcbd-hof-int-leaf
                       {:return :int :args {} :effects #{}})]
          (is (nil? (tc/type-check-binding-direct!
                      storage {:slot-id (:id slot) :ref-fn-id (:id target)} nil))))
        (finally (sp/close storage)))))

  (testing "HOF-forwarding — text-returning ref into [:fn {} :any] slot"
    (let [storage (setup/create-test-storage)]
      (try
        (let [slot   (setup/create-slot! storage "body" [:fn {} :any])
              target (setup/create-base-fn! storage "tcbd-hof-text-leaf")
              _      (registry/record-rich-types-raw!
                       :tcbd-hof-text-leaf
                       {:return :text :args {} :effects #{}})]
          (is (nil? (tc/type-check-binding-direct!
                      storage {:slot-id (:id slot) :ref-fn-id (:id target)} nil))))
        (finally (sp/close storage)))))

  (testing "HOF-forwarding — return covariance into [:fn {} :int] slot"
    ;; Tighter slot — return type `:int`. `:int` return passes,
    ;; `:text` return rejects.
    (let [storage (setup/create-test-storage)]
      (try
        (let [slot      (setup/create-slot! storage "tick" [:fn {} :int])
              ok-target (setup/create-base-fn! storage "tcbd-hof-int-tight")
              _         (registry/record-rich-types-raw!
                          :tcbd-hof-int-tight
                          {:return :int :args {} :effects #{}})
              bad-target (setup/create-base-fn! storage "tcbd-hof-text-tight")
              _          (registry/record-rich-types-raw!
                           :tcbd-hof-text-tight
                           {:return :text :args {} :effects #{}})]
          (is (nil? (tc/type-check-binding-direct!
                      storage {:slot-id (:id slot)
                               :ref-fn-id (:id ok-target)} nil)))
          (let [rej (tc/type-check-binding-direct!
                      storage {:slot-id (:id slot)
                               :ref-fn-id (:id bad-target)} nil)]
            (is (some? rej))
            (is (re-find #"Type mismatch on ref binding" (:reason rej)))))
        (finally (sp/close storage))))))
