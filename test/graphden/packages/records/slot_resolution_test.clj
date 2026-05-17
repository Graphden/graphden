(ns graphden.packages.records.slot-resolution-test
  "Tests for `graphden.packages.records.slot-resolution` — parse-time
   slot-owner resolution through the inheritance + rename chain.
   Every fn is pure (walks the input fn-defs, never storage), so no
   fixture is needed."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.records.slot-resolution :as sr]))


;; ============================================================================
;; type-row-arg-names / arg-spec-type
;; ============================================================================

(deftest type-row-arg-names-test
  (testing "each type-row shape exposes the right slot names"
    (is (= #{:a :b} (sr/type-row-arg-names {:type {:a :int :b :text}})))
    (is (= #{:value} (sr/type-row-arg-names {:refine {:base :int}})))
    (is (= #{:items} (sr/type-row-arg-names {:list :int})))
    (is (= #{:x :y} (sr/type-row-arg-names {:args {:x :int :y :int}}))))

  (testing "a composed fn (has :parent) declares no slots — its :args are bindings"
    (is (= #{} (sr/type-row-arg-names {:args {:x :int} :parent :base})))
    (is (= #{} (sr/type-row-arg-names {})))))


(deftest arg-spec-type-test
  (testing "bare keyword / spec-map / unknown shapes"
    (is (= :int (sr/arg-spec-type :int)))
    (is (= :text (sr/arg-spec-type {:type :text :required false})))
    (is (= :any (sr/arg-spec-type {:no-type :here})))
    (is (= :any (sr/arg-spec-type "weird")))))


;; ============================================================================
;; slot-type-of
;; ============================================================================

(deftest slot-type-of-test
  (let [defs {:base-fn {:args {:x :int}}
              :a-list  {:list :int}
              :a-ref   {:refine {:base :numeric}}
              :a-rec   {:type {:field :text}}}]
    (testing "resolves the declared type from each owner shape"
      (is (= :int (sr/slot-type-of :base-fn :x defs)))
      (is (= :sequence (sr/slot-type-of :a-list :items defs)))
      (is (= :numeric (sr/slot-type-of :a-ref :value defs)))
      (is (= :text (sr/slot-type-of :a-rec :field defs))))

    (testing "an owner not in the defs index → nil"
      (is (nil? (sr/slot-type-of :missing :x defs))))))


;; ============================================================================
;; rename-target
;; ============================================================================

(deftest rename-target-test
  (testing "a real {orig {:as exposed}} rename returns the original arg-name"
    (is (= :orig (sr/rename-target {:args {:orig {:as :renamed}}} :renamed))))

  (testing "a no-op rename ({:x {:as :x}}) is not a rename"
    (is (nil? (sr/rename-target {:args {:x {:as :x}}} :x))))

  (testing "a positional list-item rename is recognised"
    (is (= :items (sr/rename-target {:args {:items [{:as :path} :other]}} :path))))

  (testing "no matching rename → nil"
    (is (nil? (sr/rename-target {:args {:a :int}} :nope)))))


;; ============================================================================
;; chain-of
;; ============================================================================

(deftest chain-of-test
  (testing "single-parent inheritance chain, BFS, self first"
    (is (= [:child :base]
           (sr/chain-of :child {:child {:parent :base} :base {}}))))

  (testing "multi-inheritance includes every parent"
    (is (= [:c :a :b]
           (sr/chain-of :c {:c {:parents [:a :b]} :a {} :b {}}))))

  (testing "an out-of-index parent is a chain leaf — included, but not expanded"
    (is (= [:child :external] (sr/chain-of :child {:child {:parent :external}})))))


;; ============================================================================
;; ref-targets-of / rename-passthrough-ref
;; ============================================================================

(deftest ref-targets-of-test
  (let [defs {:r1 {} :r2 {} :r3 {}}]
    (testing "bare keyword refs and {:ref X} refs are collected"
      (is (= [:r1] (sr/ref-targets-of {:args {:x :r1}} defs)))
      (is (= [:r2] (sr/ref-targets-of {:args {:x {:ref :r2}}} defs))))

    (testing "refs inside a sequence binding's items are collected"
      (is (= #{:r1 :r3}
             (set (sr/ref-targets-of {:args {:items [:r1 {:ref :r3}]}} defs)))))

    (testing "a keyword not naming an in-module fn is skipped"
      (is (= [] (sr/ref-targets-of {:args {:x :not-a-fn}} defs))))))


(deftest rename-passthrough-ref-test
  (testing "{src {:as exposed :ref RefFn}} → the ref's name"
    (is (= :ref-fn
           (sr/rename-passthrough-ref
             {:args {:src {:as :exposed :ref :ref-fn}}} :exposed))))

  (testing "a plain rename with no :ref → nil"
    (is (nil? (sr/rename-passthrough-ref {:args {:src {:as :exposed}}} :exposed)))))


;; ============================================================================
;; resolve-slot-owner
;; ============================================================================

(deftest resolve-slot-owner-test
  (testing "an inherited base-fn slot resolves to [base-fn arg-name]"
    (let [defs {:base {:args {:x :int}}
                :child {:parent :base}}]
      (is (= [:base :x] (sr/resolve-slot-owner :child :x defs)))))

  (testing "an unresolvable arg falls back to [primary-parent arg-name]"
    (let [defs {:base {:args {:x :int}}
                :child {:parent :base}}]
      (is (= [:base :unknown] (sr/resolve-slot-owner :child :unknown defs)))))

  (testing "a {:as} rename slot is owned by the renaming ancestor"
    (let [defs {:base  {:args {:x :int}}
                :mid   {:parent :base :args {:x {:as :y}}}
                :child {:parent :mid}}]
      (is (= [:mid :y] (sr/resolve-slot-owner :child :y defs))))))


;; ============================================================================
;; build-defs-by-name / ancestor-type-pin / collect-exposed-names
;; ============================================================================

(deftest build-defs-by-name-test
  (testing "named fn-defs are indexed; unnamed entries dropped"
    (let [idx (sr/build-defs-by-name [{:name :a :parent :p}
                                      {:name :b}
                                      {:no-name true}])]
      (is (= #{:a :b} (set (keys idx))))
      (is (= :p (:parent (:a idx)))))))


(deftest ancestor-type-pin-test
  (testing "a {:as :n :type T} pin on an ancestor binding surfaces T"
    (let [defs {:base  {:args {:value :any}}
                :mid   {:parent :base :args {:value {:as :value :type :fn}}}
                :child {:parent :mid}}]
      (is (= :fn (sr/ancestor-type-pin :child :value defs)))))

  (testing "no pin in the chain → nil"
    (is (nil? (sr/ancestor-type-pin :child :value {:child {:parent :base} :base {}})))))


(deftest collect-exposed-names-test
  (testing "a scalar {src {:as exposed}} yields [exposed type src]"
    (is (= #{[:exposed nil :src]}
           (sr/collect-exposed-names {:src {:as :exposed}} :f {}))))

  (testing "a positional list rename yields [exposed type nil]"
    (is (= #{[:path nil nil]}
           (sr/collect-exposed-names {:items [{:as :path}]} :f {}))))

  (testing "a no-op rename is not exposed"
    (is (= #{} (sr/collect-exposed-names {:x {:as :x}} :f {})))))
