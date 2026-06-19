(ns graphden.executor.compile.bindings-test
  "Tests for `graphden.executor.compile.bindings` — static slot
   classification (`:value | :ref | :seq | :free`) and env-binding
   collection.

   Each test builds a real graph in storage, snapshots the five
   slot/binding tables, and runs `l/build-lookups` over them — the
   same shape the compiler feeds `collect-bindings`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile.bindings :as b]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(defn- lookups-for
  "Snapshot the five graph tables and build the compiler's lookups."
  [storage]
  (l/build-lookups
    {:fns        (sp/query-entities storage :fn {})
     :slots      (sp/query-entities storage :slot {})
     :fn-slots   (sp/query-entities storage :fn-slot {})
     :bindings   (sp/query-entities storage :binding {})
     :list-items (sp/query-entities storage :binding-list-item {})}))


;; ============================================================================
;; collect-bindings — value / free / ref
;; ============================================================================

(deftest collect-bindings-value-and-free-test
  (let [storage (setup/create-test-storage)]
    (try
      (let [base (setup/create-base-fn! storage "cb-base")
            sa   (setup/create-slot! storage "a" :int)
            sb   (setup/create-slot! storage "b" :int)
            _    (setup/attach-slot! storage (:id base) (:id sa) 0)
            _    (setup/attach-slot! storage (:id base) (:id sb) 1)
            cval (setup/create-composed-fn! storage "cb-val" (:id base))
            _    (setup/bind-value! storage (:id cval) (:id sa) 10)]
        (testing "a value-bound slot is :value; an unbound slot is :free, in position order"
          (let [entries (b/collect-bindings (:id cval) (lookups-for storage))]
            (is (= [:value :free] (mapv :kind entries)))
            (is (= 10 (:value (first entries))))
            (is (= :a (:base-name (first entries))))
            (is (= :b (:base-name (second entries))))
            ;; slots default to required → free entry carries :required true
            (is (true? (:required (second entries)))))))
      (finally (sp/close storage)))))


(deftest collect-bindings-ref-test
  (let [storage (setup/create-test-storage)]
    (try
      (let [base   (setup/create-base-fn! storage "cbr-base")
            slot   (setup/create-slot! storage "x" :int)
            _      (setup/attach-slot! storage (:id base) (:id slot) 0)
            target (setup/create-base-fn! storage "cbr-target")
            cref   (setup/create-composed-fn! storage "cbr-fn" (:id base))
            _      (setup/bind-ref! storage (:id cref) (:id slot) (:id target))]
        (testing "a ref-bound slot is :ref carrying the target fn-id"
          (let [entry (first (b/collect-bindings (:id cref) (lookups-for storage)))]
            (is (= :ref (:kind entry)))
            (is (= (:id target) (:ref-id entry))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; collect-bindings — sequence slots
;; ============================================================================

(deftest collect-bindings-seq-test
  (let [storage (setup/create-test-storage)]
    (try
      (let [base  (setup/create-base-fn! storage "cbs-base")
            slot  (setup/create-slot! storage "items" :sequence)
            _     (setup/attach-slot! storage (:id base) (:id slot) 0)
            cseq  (setup/create-composed-fn! storage "cbs-fn" (:id base))
            bind  (sp/create-entity storage :binding
                                    {:fn-id (:id cseq) :slot-id (:id slot)
                                     :list-append true :override-kind :fixed})
            _     (sp/create-entity storage :binding-list-item
                                    {:binding-id (:id bind) :position 0 :value 1})
            _     (sp/create-entity storage :binding-list-item
                                    {:binding-id (:id bind) :position 1 :value 2})]
        (testing "a :list-append binding classifies as :seq with its items"
          (let [entry (first (b/collect-bindings (:id cseq) (lookups-for storage)))]
            (is (= :seq (:kind entry)))
            (is (= 2 (count (:items entry)))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; collect-bindings — fn-typed slots (HOF marker) + optional slots
;; ============================================================================

(deftest collect-bindings-fn-typed-test
  (let [storage (setup/create-test-storage)]
    (try
      (let [base   (setup/create-base-fn! storage "cbf-base")
            slot   (setup/create-slot! storage "f" :fn)
            _      (setup/attach-slot! storage (:id base) (:id slot) 0)
            target (setup/create-base-fn! storage "cbf-target")
            cref   (setup/create-composed-fn! storage "cbf-ref" (:id base))
            _      (setup/bind-ref! storage (:id cref) (:id slot) (:id target))
            cfree  (setup/create-composed-fn! storage "cbf-free" (:id base))]
        (testing "a ref into an :fn-typed slot is flagged :is-fn"
          (let [entry (first (b/collect-bindings (:id cref) (lookups-for storage)))]
            (is (= :ref (:kind entry)))
            (is (true? (:is-fn entry)))))

        (testing "an unbound :fn-typed slot is :free and still :is-fn"
          (let [entry (first (b/collect-bindings (:id cfree) (lookups-for storage)))]
            (is (= :free (:kind entry)))
            (is (true? (:is-fn entry))))))
      (finally (sp/close storage)))))


(deftest collect-bindings-literal-nil-classifies-as-value-not-free-test
  ;; Regression test for the executor HOF-leak fix in commit
  ;; 0f63affb. Before that, `value-binding?` checked `(some? (:value
  ;; b))` and returned false for `{:value-present true, :value nil}`
  ;; — the storage shape of `:default nil` (or any `{:slot nil}`
  ;; literal in an EDN `:args` map). The slot then fell through to
  ;; `:free` and the executor pulled its runtime value from the
  ;; caller's fa, surfacing the Ring request map at `:_shape-secret-
  ;; path :default` and crashing `to-json-string` on `/api/secrets`.
  ;;
  ;; The contract: `:value-present true` is authoritative — any
  ;; binding with the flag set is `:value`, regardless of nil-ness
  ;; of `:value` itself. This unit-level pin is cheaper than the
  ;; full /api/secrets integration round-trip and catches the
  ;; regression directly at the classifier.
  (let [storage (setup/create-test-storage)]
    (try
      (let [base (setup/create-base-fn! storage "cbn-base")
            slot (setup/create-slot! storage "default" :any)
            _    (setup/attach-slot! storage (:id base) (:id slot) 0)
            cfn  (setup/create-composed-fn! storage "cbn-fn" (:id base))
            ;; setup/bind-value! sets :value-present true even when
            ;; value is nil — mirrors the EDN-parser shape for
            ;; `:default nil` literal bindings.
            _    (setup/bind-value! storage (:id cfn) (:id slot) nil)
            entry (first (b/collect-bindings (:id cfn) (lookups-for storage)))]
        (testing "value-binding with literal nil is classified as :value, not :free"
          (is (= :value (:kind entry))
              (str "literal-nil binding fell through to :free — the "
                   "executor would pull this slot's runtime value "
                   "from the caller's fa, surfacing whatever happens "
                   "to share the slot name (see commit 0f63affb)"))
          (is (nil? (:value entry))
              "classifier preserves the literal nil value")
          (is (not (contains? entry :required))
              ":free-only key absent — confirms NOT classified as :free")))
      (finally (sp/close storage)))))


(deftest collect-bindings-optional-slot-test
  (let [storage (setup/create-test-storage)]
    (try
      (let [base (setup/create-base-fn! storage "cbo-base")
            ;; Slot explicitly marked optional.
            slot (sp/create-entity storage :slot
                                   {:name "opt"
                                    :type-fn-id (get setup/primitive-fn-ids :int)
                                    :required false})
            _    (setup/attach-slot! storage (:id base) (:id slot) 0)
            cfn  (setup/create-composed-fn! storage "cbo-fn" (:id base))]
        (testing "an unbound optional slot → :free with :required false"
          (let [entry (first (b/collect-bindings (:id cfn) (lookups-for storage)))]
            (is (= :free (:kind entry)))
            (is (false? (:required entry))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; collect-env-bindings
;; ============================================================================

(deftest collect-env-bindings-test
  (let [storage (setup/create-test-storage)]
    (try
      (let [base (setup/create-base-fn! storage "ceb-base")
            sa   (setup/create-slot! storage "a" :int)
            _    (setup/attach-slot! storage (:id base) (:id sa) 0)
            cfn  (setup/create-composed-fn! storage "ceb-fn" (:id base))]
        (testing "a fn binding only its own root slots has no env bindings"
          (setup/bind-value! storage (:id cfn) (:id sa) 1)
          (is (empty? (b/collect-env-bindings (:id cfn) (lookups-for storage)))))

        (testing "a binding on a non-root slot surfaces as an env binding"
          (let [extra (setup/create-slot! storage "extra" :int)]
            (sp/create-entity storage :binding
                              {:fn-id (:id cfn) :slot-id (:id extra)
                               :value 99 :value-present true
                               :override-kind :fixed})
            (let [env (b/collect-env-bindings (:id cfn) (lookups-for storage))]
              (is (= 1 (count env)))
              (is (= :extra (:env-name (first env))))
              (is (= 99 (:value (first env))))))))
      (finally (sp/close storage)))))
