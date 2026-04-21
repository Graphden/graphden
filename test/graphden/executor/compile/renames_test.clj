(ns graphden.executor.compile.renames-test
  "Direct unit tests for `compile.renames`. Covers `deep-free-ext-names`
   branches (:free, :ref, :seq, :value), `apply-renames` edge cases,
   and the private origin/f-arg lookups used by `build-ref-renames`."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.renames :as r]))


;; =============================================================================
;; apply-renames — edge cases
;; =============================================================================
;;
;; {R-ext → F-ext}. For each mapping, if F-ext is present in free-args,
;; re-key it under R-ext and drop F-ext. Otherwise the free-args map is
;; returned unchanged (the "else" branch).

(deftest apply-renames-noop-when-empty-renames
  (let [args {:path "/health" :request :req}]
    (is (= args (r/apply-renames args {})))))


(deftest apply-renames-renames-one-key
  (testing ":item1 → :path: key is rewritten, old key dropped"
    (is (= {:item1 "/health"}
           (r/apply-renames {:path "/health"} {:item1 :path})))))


(deftest apply-renames-skips-when-f-ext-not-in-args
  (testing "if F-ext is absent in free-args, the mapping is a no-op"
    ;; This exercises the else-branch of the reduce-kv conditional that
    ;; the end-to-end compile path never reaches for this target.
    (is (= {:unrelated :value}
           (r/apply-renames {:unrelated :value} {:item1 :path})))))


(deftest apply-renames-multiple-renames
  (testing "multiple mappings rewritten in one pass"
    (is (= {:item1 "x" :item2 "y" :kept 1}
           (r/apply-renames
             {:path "x" :handler "y" :kept 1}
             {:item1 :path :item2 :handler})))))


(deftest apply-renames-preserves-unmatched-keys
  (testing "free-args keys not covered by renames pass through"
    (is (= {:item1 "/hx" :keep-me 42}
           (r/apply-renames {:path "/hx" :keep-me 42} {:item1 :path})))))


;; =============================================================================
;; deep-free-ext-names — walks collect-bindings to gather free-arg names
;; =============================================================================
;;
;; Covers :free (direct name), :ref (recurses through non-HOF refs),
;; :seq (walks ref-id items), and :value (no-op) branches.

(defn- mk-lookups
  [fns args]
  (l/build-lookups fns args))


(deftest deep-free-ext-names-direct-free
  (let [base (random-uuid)
        p (random-uuid)
        fns [{:id base :parent-ids []}]
        args [{:id p :fn-id base :name "x" :source-id nil}]
        lookups (mk-lookups fns args)]
    (is (= [:x] (r/deep-free-ext-names base lookups)))))


(deftest deep-free-ext-names-walks-through-ref
  (testing "non-HOF ref — frees inside target surface up through the caller"
    (let [base-a (random-uuid)
          base-b (random-uuid)
          p-a (random-uuid)  ; base-a's :data primary
          p-b (random-uuid)  ; base-b's :y primary
          f-id (random-uuid) ; fn using base-a, refs base-b for :data
          f-arg-id (random-uuid)
          fns [{:id base-a :parent-ids []}
               {:id base-b :parent-ids []}
               {:id f-id :parent-ids [base-a]}]
          args [{:id p-a :fn-id base-a :name "data" :source-id nil}
                {:id p-b :fn-id base-b :name "y" :source-id nil}
                ;; f binds :data → ref to base-b
                {:id f-arg-id :fn-id f-id :source-id p-a
                 :ref-id base-b :is-fn false}]
          lookups (mk-lookups fns args)]
      (is (= [:y] (r/deep-free-ext-names f-id lookups))
          "free slot of the refed-to fn surfaces as caller's free slot"))))


(deftest deep-free-ext-names-does-not-recurse-through-is-fn-refs
  (testing "HOF :fn-type refs are treated as closed — their frees do NOT surface"
    (let [base-map (random-uuid)
          base-id (random-uuid)
          p-func (random-uuid)
          p-item (random-uuid)
          map-fn-id (random-uuid)
          target-fn-id (random-uuid)
          map-arg-func (random-uuid)
          fns [{:id base-map :parent-ids []}
               {:id base-id :parent-ids []}
               {:id map-fn-id :parent-ids [base-map]}
               {:id target-fn-id :parent-ids [base-id]}]
          args [{:id p-func :fn-id base-map :name "func" :source-id nil}
                {:id p-item :fn-id base-id :name "item" :source-id nil}
                ;; map-fn binds its :func slot to target — HOF
                {:id map-arg-func :fn-id map-fn-id :source-id p-func
                 :ref-id target-fn-id :is-fn true}]
          lookups (mk-lookups fns args)]
      (is (= [] (r/deep-free-ext-names map-fn-id lookups))
          ":item inside HOF target does NOT propagate up"))))


(deftest deep-free-ext-names-walks-seq-ref-items
  (testing ":seq binding items with :ref-id recurse into their targets"
    (let [base-coll (random-uuid)
          base-leaf (random-uuid)
          p-items (random-uuid)
          p-leaf (random-uuid)
          f-id (random-uuid)
          target-id (random-uuid)
          anchor-id (random-uuid)
          item-id (random-uuid)
          fns [{:id base-coll :parent-ids []}
               {:id base-leaf :parent-ids []}
               {:id f-id :parent-ids [base-coll]}
               {:id target-id :parent-ids [base-leaf]}]
          ;; base-coll has a :sequence primary "items"
          ;; base-leaf has a free primary "leaf"
          ;; f-id's anchor on :items holds a single ref item pointing at target-id
          args [{:id p-items :fn-id base-coll :name "items"
                 :source-id nil :type :sequence :of :any}
                {:id p-leaf :fn-id base-leaf :name "leaf" :source-id nil}
                ;; anchor on f-id
                {:id anchor-id :fn-id f-id :source-id p-items
                 :name nil :type :sequence :next-arg-id item-id
                 :value nil :ref-id nil}
                ;; ref-item inside the sequence
                {:id item-id :fn-id f-id :source-id nil :name nil
                 :ref-id target-id :value nil :next-arg-id nil}]
          lookups (mk-lookups fns args)]
      (is (= [:leaf] (r/deep-free-ext-names f-id lookups))))))


(deftest deep-free-ext-names-value-binding-contributes-nothing
  (testing ":value kind is ignored — bound slots don't expose free args"
    (let [base (random-uuid)
          p (random-uuid)
          f-id (random-uuid)
          own-arg (random-uuid)
          fns [{:id base :parent-ids []} {:id f-id :parent-ids [base]}]
          args [{:id p :fn-id base :name "x" :source-id nil}
                {:id own-arg :fn-id f-id :source-id p :value 42}]
          lookups (mk-lookups fns args)]
      (is (= [] (r/deep-free-ext-names f-id lookups))))))


;; =============================================================================
;; build-ref-renames — integration of deep-free-ext-names + r-origin/f-arg
;; =============================================================================

(deftest build-ref-renames-empty-when-frees-align-by-name
  (testing "R and F expose the same free name → no rename entry"
    (let [base (random-uuid)
          p (random-uuid)
          r-fn-id (random-uuid)
          f-fn-id (random-uuid)
          fns [{:id base :parent-ids []}
               {:id r-fn-id :parent-ids [base]}
               {:id f-fn-id :parent-ids [base]}]
          args [{:id p :fn-id base :name "x" :source-id nil}]
          lookups (mk-lookups fns args)]
      (is (= {} (r/build-ref-renames r-fn-id f-fn-id lookups))))))


(deftest build-ref-renames-produces-entry-for-differing-names
  (testing "F renames x → y; ref into F passes :y, which must translate to :x"
    ;; Scenario: base has :x primary. r-fn and f-fn both inherit base
    ;; but f-fn's own arg renames the primary to ext-name :y via :name
    ;; on the inheritance arg.
    (let [base (random-uuid)
          p-x (random-uuid)
          r-fn-id (random-uuid)
          f-fn-id (random-uuid)
          f-rename-arg (random-uuid)
          fns [{:id base :parent-ids []}
               {:id r-fn-id :parent-ids [base]}
               {:id f-fn-id :parent-ids [base]}]
          args [{:id p-x :fn-id base :name "x" :source-id nil}
                ;; on f-fn: propagation arg with its own :name "y"
                {:id f-rename-arg :fn-id f-fn-id
                 :source-id p-x :name "y"
                 :value nil :ref-id nil}]
          lookups (mk-lookups fns args)
          renames (r/build-ref-renames r-fn-id f-fn-id lookups)]
      (is (= {:x :y} renames)
          "R sees :x (base's name), F exposes it as :y → rename table"))))
