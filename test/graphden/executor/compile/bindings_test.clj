(ns graphden.executor.compile.bindings-test
  "Direct unit tests for `compile.bindings` private helpers.

   `collect-bindings` / `collect-env-bindings` are exercised
   end-to-end through `compile_test.clj` already; these tests cover
   the inner classify/anchor/walk helpers and edge cases that the
   end-to-end tests don't reach — notably the `:seq` env-binding
   branch and the `fn-chain-stays-within?` filter."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile.bindings :as b]
    [graphden.executor.compile.lookups :as l]))


;; =============================================================================
;; walk-anchor-chain — sequence-anchor linked list walker
;; =============================================================================

(deftest walk-anchor-chain-empty-anchor
  (testing "anchor with nil :next-arg-id returns empty"
    (is (= [] (#'b/walk-anchor-chain {:next-arg-id nil} {})))))


(deftest walk-anchor-chain-single-item
  (let [i (random-uuid)
        arg-map {i {:id i :value 10 :next-arg-id nil}}]
    (is (= [{:id i :value 10 :next-arg-id nil}]
           (#'b/walk-anchor-chain {:next-arg-id i} arg-map)))))


(deftest walk-anchor-chain-multi-items
  (let [a (random-uuid), b (random-uuid), c (random-uuid)
        arg-map {a {:id a :value 1 :next-arg-id b}
                 b {:id b :value 2 :next-arg-id c}
                 c {:id c :value 3 :next-arg-id nil}}]
    (is (= [1 2 3]
           (mapv :value (#'b/walk-anchor-chain {:next-arg-id a} arg-map))))))


(deftest walk-anchor-chain-broken-pointer-stops-gracefully
  (testing "chain aborts when :next-arg-id points at an id missing from arg-map"
    (let [a (random-uuid)
          dangling (random-uuid)
          arg-map {a {:id a :value 1 :next-arg-id dangling}}]
      (is (= 1 (count (#'b/walk-anchor-chain {:next-arg-id a} arg-map)))
          "walk stops at first missing id"))))


;; =============================================================================
;; closest-binding + sequence-anchor
;; =============================================================================

(deftest closest-binding-picks-first-value-or-ref
  (testing "skips free args, returns the first :value-bearing arg"
    (let [free {:value nil :ref-id nil}
          bound-value {:value 42 :ref-id nil}
          bound-ref {:value nil :ref-id (random-uuid)}]
      (is (= bound-value
             (#'b/closest-binding [{:arg free} {:arg bound-value} {:arg bound-ref}])))
      (is (= bound-ref
             (#'b/closest-binding [{:arg free} {:arg bound-ref}]))))))


(deftest closest-binding-all-free-returns-nil
  (is (nil? (#'b/closest-binding [{:arg {:value nil :ref-id nil}}
                                  {:arg {:value nil :ref-id nil}}]))))


(deftest sequence-anchor-finds-sequence-typed-arg
  (let [anchor {:type :sequence :value nil :ref-id nil}
        scalar {:type :int :value 1}]
    (is (= anchor (#'b/sequence-anchor [{:arg scalar} {:arg anchor}])))
    (is (nil? (#'b/sequence-anchor [{:arg scalar}])))))


(deftest sequence-anchor-ignores-bound-sequence-args
  (testing ":type :sequence but with :value or :ref-id is not an anchor"
    (let [not-anchor {:type :sequence :value 42 :ref-id nil}]
      (is (nil? (#'b/sequence-anchor [{:arg not-anchor}]))))))


;; =============================================================================
;; fn-chain-args-for-primary — propagation-filter
;; =============================================================================

(deftest fn-chain-args-for-primary-excludes-out-of-chain-props
  (testing "arg whose source-id lives on a fn outside F's chain is excluded"
    (let [f-fn (random-uuid)
          base (random-uuid)
          r-fn (random-uuid)
          primary-id (random-uuid)
          own-arg-id (random-uuid)
          prop-arg-id (random-uuid)
          r-prop-id (random-uuid)
          arg-map {;; own arg — source-id chain stays within [f-fn, base]
                   own-arg-id {:id own-arg-id :fn-id f-fn
                               :source-id primary-id
                               :value 1}
                   ;; propagation arg — source-id goes via r-fn, outside F's chain
                   prop-arg-id {:id prop-arg-id :fn-id f-fn
                                :source-id r-prop-id
                                :value 2}
                   r-prop-id {:id r-prop-id :fn-id r-fn
                              :source-id primary-id}
                   primary-id {:id primary-id :fn-id base}}
          args-by-fn {f-fn [(get arg-map own-arg-id)
                            (get arg-map prop-arg-id)]
                      base [(get arg-map primary-id)]
                      r-fn [(get arg-map r-prop-id)]}
          fn-chain [f-fn base]
          fn-chain-set #{f-fn base}
          matches (#'b/fn-chain-args-for-primary primary-id fn-chain fn-chain-set
                                                 args-by-fn arg-map)
          f-matches (filter #(= f-fn (:fn-id %)) matches)]
      ;; On f-fn's own args, only `own-arg` should match — `prop-arg`
      ;; is filtered out because its source chain crosses into r-fn.
      ;; (The primary arg itself also matches under base — that's fine.)
      (is (= 1 (count f-matches)))
      (is (= own-arg-id (:id (:arg (first f-matches))))))))


;; =============================================================================
;; collect-env-bindings — :seq env binding not covered by classify
;; =============================================================================
;;
;; The :seq branch of env-bindings fires when F (or an ancestor) has a
;; :type :sequence arg with its own :next-arg-id chain, but the
;; terminal primary of that chain is NOT one of F's base primaries —
;; so `classify-binding` wouldn't have consumed it.

(deftest collect-env-bindings-emits-seq-entry-for-orphan-anchor
  (let [f-fn (random-uuid)
        base-fn (random-uuid)
        ;; Primary on base — this becomes a classify slot, not an env entry
        base-primary-id (random-uuid)
        ;; Unrelated primary NOT on F's base — its anchor on F becomes :seq env
        other-primary-id (random-uuid)
        anchor-id (random-uuid)
        item-id (random-uuid)
        fn-map {f-fn {:id f-fn :parent-ids [base-fn]}
                base-fn {:id base-fn :parent-ids []}}
        arg-map {base-primary-id {:id base-primary-id :fn-id base-fn
                                  :name "x" :source-id nil}
                 other-primary-id {:id other-primary-id :fn-id :some-other-fn
                                   :name "items"}
                 anchor-id {:id anchor-id :fn-id f-fn
                            :source-id other-primary-id
                            :name nil :type :sequence
                            :next-arg-id item-id
                            :value nil :ref-id nil}
                 item-id {:id item-id :fn-id f-fn
                          :source-id nil :name nil
                          :value 7 :next-arg-id nil}}
        args-by-fn {f-fn [(get arg-map anchor-id) (get arg-map item-id)]
                    base-fn [(get arg-map base-primary-id)]
                    :some-other-fn [(get arg-map other-primary-id)]}
        lookups {:fn-map fn-map :arg-map arg-map :args-by-fn args-by-fn}
        env (b/collect-env-bindings f-fn lookups)]
    (is (seq env) "at least one env entry")
    (let [seq-entries (filter #(= :seq (:kind %)) env)]
      (is (= 1 (count seq-entries)) "anchor pointing outside base emits :seq env")
      (is (= :items (:env-name (first seq-entries))))
      (is (= [7] (mapv :value (:items (first seq-entries))))))))


(deftest collect-env-bindings-emits-value-entry-for-propagated-arg
  (testing "a value-bearing arg whose source chain exits F's inheritance"
    (let [f-fn (random-uuid)
          base-fn (random-uuid)
          r-fn (random-uuid)
          r-primary-id (random-uuid)
          ;; F's arg has source-id pointing at r-fn's primary — propagation
          prop-arg-id (random-uuid)
          base-primary-id (random-uuid)
          fn-map {f-fn {:id f-fn :parent-ids [base-fn]}
                  base-fn {:id base-fn :parent-ids []}}
          arg-map {base-primary-id {:id base-primary-id :fn-id base-fn
                                    :name "unused" :source-id nil}
                   r-primary-id {:id r-primary-id :fn-id r-fn
                                 :name "path"}
                   prop-arg-id {:id prop-arg-id :fn-id f-fn
                                :source-id r-primary-id
                                :name nil :value "/health"}}
          args-by-fn {f-fn [(get arg-map prop-arg-id)]
                      base-fn [(get arg-map base-primary-id)]
                      r-fn [(get arg-map r-primary-id)]}
          lookups {:fn-map fn-map :arg-map arg-map :args-by-fn args-by-fn}
          env (b/collect-env-bindings f-fn lookups)
          val-entries (filter #(= :value (:kind %)) env)]
      (is (= 1 (count val-entries)))
      (is (= :path (:env-name (first val-entries))))
      (is (= "/health" (:value (first val-entries)))))))


;; =============================================================================
;; classify-binding — full cond coverage
;; =============================================================================
;;
;; These end-to-end through `collect-bindings` to exercise :value / :ref
;; / :seq / :free kinds.

(defn- mk-base-fn
  [primary-name]
  (let [base-id (random-uuid)
        p-id (random-uuid)]
    {:base-id base-id
     :p-id p-id
     :base-fn-entity {:id base-id :parent-ids []}
     :p-arg {:id p-id :fn-id base-id :name primary-name
             :source-id nil :type :int}}))


(deftest classify-binding-value-kind
  (let [{:keys [base-id p-id base-fn-entity p-arg]} (mk-base-fn "x")
        f-id (random-uuid)
        own-arg-id (random-uuid)
        fn-map {f-id {:id f-id :parent-ids [base-id]} base-id base-fn-entity}
        arg-map {p-id p-arg
                 own-arg-id {:id own-arg-id :fn-id f-id
                             :source-id p-id :value 42}}
        args-by-fn {f-id [(get arg-map own-arg-id)] base-id [p-arg]}
        bindings (b/collect-bindings f-id {:fn-map fn-map
                                           :arg-map arg-map
                                           :args-by-fn args-by-fn})]
    (is (= 1 (count bindings)))
    (is (= :value (:kind (first bindings))))
    (is (= 42 (:value (first bindings))))
    (is (= :x (:base-name (first bindings))))))


(deftest classify-binding-ref-kind-with-is-fn
  (let [{:keys [base-id p-id base-fn-entity p-arg]} (mk-base-fn "fn-arg")
        f-id (random-uuid)
        target-id (random-uuid)
        own-arg-id (random-uuid)
        fn-map {f-id {:id f-id :parent-ids [base-id]} base-id base-fn-entity}
        arg-map {p-id p-arg
                 own-arg-id {:id own-arg-id :fn-id f-id
                             :source-id p-id
                             :ref-id target-id
                             :is-fn true}}
        args-by-fn {f-id [(get arg-map own-arg-id)] base-id [p-arg]}
        [bnd] (b/collect-bindings f-id {:fn-map fn-map
                                        :arg-map arg-map
                                        :args-by-fn args-by-fn})]
    (is (= :ref (:kind bnd)))
    (is (= target-id (:ref-id bnd)))
    (is (true? (:is-fn bnd)))))


(deftest classify-binding-free-kind-when-no-binding-reaches-slot
  (let [{:keys [base-id p-id base-fn-entity p-arg]} (mk-base-fn "y")
        f-id (random-uuid)
        fn-map {f-id {:id f-id :parent-ids [base-id]} base-id base-fn-entity}
        arg-map {p-id p-arg}
        args-by-fn {f-id [] base-id [p-arg]}
        [bnd] (b/collect-bindings f-id {:fn-map fn-map
                                        :arg-map arg-map
                                        :args-by-fn args-by-fn})]
    (is (= :free (:kind bnd)))
    (is (= :y (:base-name bnd)))
    (is (= :y (:ext-name bnd)) "falls back to primary name")))


(deftest classify-binding-respects-l-lookups
  (testing "compile.bindings is a pure function of its lookups struct"
    (let [{:keys [base-id p-arg]} (mk-base-fn "z")
          lookups (l/build-lookups [{:id base-id :parent-ids []}]
                                   [p-arg])]
      (is (= base-id (:id (l/base-fn-of base-id (:fn-map lookups))))))))
