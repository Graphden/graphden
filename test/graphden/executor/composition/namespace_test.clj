(ns graphden.executor.composition.namespace-test
  "Namespace identity regressions through the production composition boundary."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.composition.core :as composition]
    [graphden.packages.records :as records]))


(defn- compose
  [defs]
  (composition/fn-defs->records defs {} {}))


(defn- error-data
  [defs]
  (try (compose defs)
       (catch clojure.lang.ExceptionInfo e (ex-data e))))


(defn- fn-rows
  [rows]
  (into {} (comp (filter #(= :fn (:kind %))) (map (juxt :id identity))) rows))


(defn- bindings
  [rows]
  (into {} (comp (filter #(= :binding (:kind %)))
                 (map (juxt (juxt :fn-id :slot-id) identity))) rows))


(deftest same-names-compose-with-local-and-qualified-references
  (let [defs [{:name :use :namespace "a" :parent :helper :args {:left 11}}
              {:name :use :namespace "b" :parent :helper :args {:right "b"}}
              {:name :foreign :namespace "a" :parent :b/helper :args {:right "cross"}}
              {:name :helper :namespace "b" :parent :record-b}
              {:name :helper :namespace "a" :parent :record-a}
              {:name :record-b :namespace "b" :type {:right :text}}
              {:name :record-a :namespace "a" :type {:left :int}}]
        rows (compose defs)
        fns (fn-rows rows)
        bs (bindings rows)
        aid (records/fn-id "a" :use)
        bid (records/fn-id "b" :use)
        left (records/slot-id (records/fn-id "a" :record-a) :left)
        right (records/slot-id (records/fn-id "b" :record-b) :right)]
    (testing "local parents and explicit foreign parents preserve distinct identities"
      (is (= [(records/fn-id "a" :helper)] (:parent-ids (fns aid))))
      (is (= [(records/fn-id "b" :helper)] (:parent-ids (fns bid))))
      (is (= [(records/fn-id "b" :helper)]
             (:parent-ids (fns (records/fn-id "a" :foreign))))))
    (testing "own-name binding lookup cannot read the other namespace's shape"
      (is (= 11 (:value (bs [aid left]))))
      (is (= "b" (:value (bs [bid right]))))
      (is (= "cross" (:value (bs [(records/fn-id "a" :foreign) right])))))
    (testing "dependencies emit parents before descendants in both namespaces"
      (let [positions (zipmap (map :id rows) (range))]
        (is (< (positions (records/fn-id "a" :helper)) (positions aid)))
        (is (< (positions (records/fn-id "b" :helper)) (positions bid)))))
    (testing "input order cannot alter any record's identity or contents"
      (is (= (set rows) (set (compose (vec (reverse defs)))))))))


(deftest duplicate-own-slots-and-arg-references-stay-isolated
  (let [defs [{:name :base :args {:value :any} :return-type :any}
              {:name :helper :namespace "a" :parent :base
               :args {:pin {:type :int} :value {:as :left}}}
              {:name :helper :namespace "b" :parent :base
               :args {:pin {:type :text} :value {:as :right}}}
              {:name :caller :namespace "a" :parent :helper
               :args {:pin 7 :left :target}}
              {:name :caller :namespace "b" :parent :helper
               :args {:pin "b" :right {:ref :a/target}}}
              {:name :target :namespace "a" :parent :base :args {:value 1}}
              {:name :target :namespace "b" :parent :base :args {:value 2}}]
        rows (compose defs)
        bs (bindings rows)]
    (doseq [[ns-path pin target] [["a" 7 "a"] ["b" "b" "a"]]]
      (let [caller (records/fn-id ns-path :caller)
            helper (records/fn-id ns-path :helper)
            renamed (if (= ns-path "a") :left :right)]
        (is (= pin (:value (bs [caller (records/slot-id helper :pin)]))))
        (is (= (records/fn-id target :target)
               (:ref-fn-id (bs [caller (records/slot-id helper renamed)]))))))))


(deftest namespace-errors-remain-explicit
  (let [twins [{:name :helper :namespace "a" :type {:x :int}}
               {:name :helper :namespace "b" :type {:y :text}}]]
    (testing "an external bare ref requires qualification"
      (is (= {:type :packages/ambiguous-ref :ref :helper :module-ns "c"}
             (error-data (conj twins {:name :caller :namespace "c" :parent :helper})))))
    (testing "an unknown qualified ref is rejected before sorting"
      (is (= {:type :packages/unresolved-ref :ref :missing/helper}
             (error-data (conj twins {:name :caller :parent :missing/helper})))))
    (testing "same namespace duplicates still fail"
      (is (= :fn-composition/duplicate-names
             (:type (error-data (conj twins (first twins)))))))))


(deftest namespace-cycles-distinguish-evaluation-from-identity
  (testing "qualified parent cycles remain cycles"
    (let [error (error-data [{:name :helper :namespace "a" :parent :b/helper}
                             {:name :helper :namespace "b" :parent :a/helper}])]
      (is (= :fn-composition/circular-dependency (:type error)))
      (is (= #{:a/helper :b/helper} (:remaining error)))))
  (testing "mutual fn-ref targets remain legal even with colliding own names"
    (let [rows (compose [{:name :identity :args {:target {:type :fn-ref}} :return-type :uuid}
                         {:name :ordinary :args {:value :int} :return-type :int}
                         {:name :service :namespace "a" :parent (keyword "" "identity") :args {:target :b/service}}
                         {:name :service :namespace "b" :parent (keyword "" "identity") :args {:target :a/service}}
                         {:name :identity :namespace "c" :parent :ordinary :args {:value 3}}])
          refs (into #{} (keep :ref-fn-id) rows)]
      (is (= #{(records/fn-id "a" :service) (records/fn-id "b" :service)} refs)))))


(deftest inline-anonymous-canonical-identity-is-preserved
  (let [base {:name :base :namespace "lib" :args {:value :int} :return-type :int}
        caller {:name :caller :namespace "app" :parent :base
                :args {:value {:parent :base :args {:value 2}}}}
        qualified (-> caller (assoc :parent :lib/base)
                      (assoc-in [:args :value :parent] :lib/base))]
    (is (= (set (compose [base caller])) (set (compose [base qualified]))))))


(deftest existing-shapes-retain-local-resolution-when-a-new-twin-arrives
  (let [existing [{:name :helper :namespace "a" :type {:left :int}}
                  {:name :wrapper :namespace "a" :parent :helper}]
        name->id {:helper (records/fn-id "a" :helper)
                  :a/helper (records/fn-id "a" :helper)
                  :wrapper (records/fn-id "a" :wrapper)
                  :a/wrapper (records/fn-id "a" :wrapper)}
        shapes {:helper (first existing) :a/helper (first existing)
                :wrapper (second existing) :a/wrapper (second existing)}
        rows (composition/fn-defs->records
               [{:name :helper :namespace "b" :type {:right :text}}
                {:name :caller :namespace "b" :parent :a/wrapper :args {:left 7}}]
               name->id shapes)
        binding ((bindings rows) [(records/fn-id "b" :caller)
                                  (records/slot-id (records/fn-id "a" :helper) :left)])]
    (is (= 7 (:value binding)))))


(deftest existing-external-edge-is-not-retargeted-by-a-new-local-twin
  (let [existing [{:name :helper :namespace "external" :type {:original :int}}
                  {:name :wrapper :namespace "a" :parent :helper}]
        name->id {:helper (records/fn-id "external" :helper)
                  :external/helper (records/fn-id "external" :helper)
                  :wrapper (records/fn-id "a" :wrapper)
                  :a/wrapper (records/fn-id "a" :wrapper)}
        shapes {:helper (first existing) :wrapper (second existing)}
        rows (composition/fn-defs->records
               [{:name :helper :namespace "a" :type {:new-local :text}}
                {:name :caller :namespace "a" :parent :wrapper :args {:original 7}}]
               name->id shapes)
        binding ((bindings rows) [(records/fn-id "a" :caller)
                                  (records/slot-id (records/fn-id "external" :helper) :original)])]
    (is (= 7 (:value binding)))))


(deftest keyword-literals-do-not-become-dependency-references
  (let [defs [{:name :const :args {:value :keyword} :return-type :keyword}
              {:name :helper :namespace "a" :parent :const :args {:value {:value :helper}}}
              {:name :helper :namespace "b" :parent :const :args {:value {:value :a/helper}}}
              {:name :helper :namespace "c" :parent :const :args {:value {:value :c/helper}}}]
        rows (compose defs)]
    (is (= #{:helper :a/helper :c/helper}
           (into #{} (comp (filter #(= :binding (:kind %))) (map :value)) rows)))
    (is (empty? (keep :ref-fn-id rows)))))


(deftest root-namespace-has-the-same-local-preference
  (let [rows (compose [{:name :helper :type {:x :int}}
                       {:name :helper :namespace "other" :type {:y :text}}
                       {:name :caller :parent :helper :args {:x 3}}])]
    (is (= [(records/fn-id nil :helper)]
           (:parent-ids ((fn-rows rows) (records/fn-id nil :caller)))))))


(deftest qualified-variant-tags-remain-data-in-existing-shapes
  (let [choice {:name :choice :namespace "lib" :variant [:kind/a :int :kind/b :text]}
        ids {:choice (records/fn-id "lib" :choice)
             :lib/choice (records/fn-id "lib" :choice)}
        rows (composition/fn-defs->records [{:name :new :namespace "app" :type {:value :int}}]
                                           ids {:choice choice})]
    (is (= "new" (:name (first rows)))))
  (let [rows (compose [{:name :choice :namespace "lib" :variant [:kind/a :int :kind/b :text]}])]
    (is (= "choice" (:name (first rows))))))


(deftest ordinary-map-payloads-do-not-use-definition-grammar
  (let [literal {:map "ordinary value" :tuple "ordinary value"
                 :variant [:kind/a 1] :args "ordinary value" :return-type :kind/a}
        rows (compose [{:name :const :args {:value :jsonb} :return-type :jsonb}
                       {:name :literal :parent :const :args {:value literal}}])]
    (is (= [literal] (into [] (comp (filter #(= :binding (:kind %))) (map :value)) rows)))))
