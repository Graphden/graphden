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
    [graphden.executor.compile.test-support :as support]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


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
          (let [entries (b/collect-bindings (:id cval) (support/lookups-for storage))]
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
          (let [entry (first (b/collect-bindings (:id cref) (support/lookups-for storage)))]
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
                                     :list-append true})
            _     (sp/create-entity storage :binding-list-item
                                    {:binding-id (:id bind) :position 0 :value 1})
            _     (sp/create-entity storage :binding-list-item
                                    {:binding-id (:id bind) :position 1 :value 2})]
        (testing "a :list-append binding classifies as :seq with its items"
          (let [entry (first (b/collect-bindings (:id cseq) (support/lookups-for storage)))]
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
          (let [entry (first (b/collect-bindings (:id cref) (support/lookups-for storage)))]
            (is (= :ref (:kind entry)))
            (is (true? (:is-fn entry)))))

        (testing "an unbound :fn-typed slot is :free and still :is-fn"
          (let [entry (first (b/collect-bindings (:id cfree) (support/lookups-for storage)))]
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
            entry (first (b/collect-bindings (:id cfn) (support/lookups-for storage)))]
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
          (let [entry (first (b/collect-bindings (:id cfn) (support/lookups-for storage)))]
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
          (is (empty? (b/collect-env-bindings (:id cfn) (support/lookups-for storage)))))

        (testing "a binding on a non-root slot surfaces as an env binding"
          (let [extra (setup/create-slot! storage "extra" :int)]
            (sp/create-entity storage :binding
                              {:fn-id (:id cfn) :slot-id (:id extra)
                               :value 99 :value-present true})
            (let [env (b/collect-env-bindings (:id cfn) (support/lookups-for storage))]
              (is (= 1 (count env)))
              (is (= :extra (:env-name (first env))))
              (is (= 99 (:value (first env))))))))
      (finally (sp/close storage)))))


;; ============================================================================
;; compile-perf memoisation — cached read == on-the-fly recompute
;; ============================================================================

(deftest fn-typed-fn-ids-and-env-bindings-cache-equivalence-test
  ;; `compute-fn-typed-fn-ids` (now in lookups.clj) and
  ;; `collect-env-bindings` are pure functions of the immutable
  ;; fn-map/graph. `build-lookups` memoises the first under
  ;; `:fn-typed-fn-ids` and wraps the second in an `:env-bindings-cache`
  ;; atom (mirroring `:bindings-cache`). Both keep an on-the-fly
  ;; recompute FALLBACK for hand-built lookups that lack the key. This
  ;; pins the contract that reading-from-cache is byte-identical to
  ;; recomputing — the equality proof for the O(n²) compile-perf fix.
  (let [storage (setup/create-test-storage)]
    (try
      (let [base   (setup/create-base-fn! storage "cache-base")
            sf     (setup/create-slot! storage "f" :fn)      ; fn-typed slot
            sn     (setup/create-slot! storage "n" :int)     ; NOT fn-typed
            _      (setup/attach-slot! storage (:id base) (:id sf) 0)
            _      (setup/attach-slot! storage (:id base) (:id sn) 1)
            target (setup/create-base-fn! storage "cache-target")
            cfn    (setup/create-composed-fn! storage "cache-fn" (:id base))
            _      (setup/bind-ref! storage (:id cfn) (:id sf) (:id target))
            ;; A binding on a NON-root slot → surfaces via
            ;; collect-env-bindings (exercises that path, not just
            ;; collect-bindings).
            extra  (setup/create-slot! storage "extra" :int)
            _      (sp/create-entity storage :binding
                                     {:fn-id (:id cfn) :slot-id (:id extra)
                                      :value 7 :value-present true})
            lookups (support/lookups-for storage)]

        (testing "build-lookups precomputed :fn-typed-fn-ids"
          (is (contains? lookups :fn-typed-fn-ids))
          (is (contains? lookups :env-bindings-cache)))

        (testing "compute-fn-typed-fn-ids: cached == recomputed, and is a mixed set"
          (let [cached (:fn-typed-fn-ids lookups)
                fresh  (l/compute-fn-typed-fn-ids lookups)]
            (is (= cached fresh))
            ;; The :fn primitive row qualifies — set is non-empty — but
            ;; not every fn-row does (base/target/cfn/:int don't), so
            ;; the set is strictly smaller than the fn-map: a real mixed
            ;; classification, not everything/nothing.
            (is (seq cached))
            (is (contains? cached (get setup/primitive-fn-ids :fn)))
            (is (< (count cached) (count (:fn-map lookups))))))

        (testing "collect-env-bindings: cache read == recompute fallback"
          (let [via-cache (b/collect-env-bindings (:id cfn) lookups)
                ;; Force the recompute fallback by dropping BOTH the
                ;; env cache and the precomputed fn-typed set.
                recomputed (b/collect-env-bindings
                             (:id cfn)
                             (dissoc lookups :env-bindings-cache :fn-typed-fn-ids))
                ;; Second call must hit the populated cache and match.
                second-hit (b/collect-env-bindings (:id cfn) lookups)]
            (is (= via-cache recomputed))
            (is (= via-cache second-hit))
            (is (= 1 (count via-cache)))
            (is (= :extra (:env-name (first via-cache)))))))
      (finally (sp/close storage)))))
