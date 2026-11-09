(ns graphden.input.ir-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.compiler-fixture :as fixture]
    [graphden.input.ir :as ir]))


(def definitions
  {:root {:input {:user {:name :text :unused :text} :result :text}
          :return :text :bindings {[:user :name] {:value "Ada"} [:result] {:ref :child :id :use}}}
   :child {:input {:x :text} :return :text}})


(defn- structural-model
  []
  ;; A deliberately explicit checker seam for pure BB lowering tests. These
  ;; assertions prove routing, not typing; compile-test uses the real checker.
  (fixture/model definitions :root
                 (fn [{:keys [substitution]}]
                   {:status :checked :substitution substitution})))


(defn- lower
  [{:keys [analysis context]}]
  (ir/lower-checked analysis {:snapshot-id (:snapshot-id context)
                              :implementations (fixture/implementations definitions
                                                                        {:root (fn [_ _]) :child (fn [_ _])})}))


(deftest checked-plan-seam-lowers-with-free-required-parameters
  (let [model (structural-model)
        plan (lower model)]
    (is (= :checked (get-in model [:analysis :status])))
    (is (= :literal (get-in plan [:expressions (fixture/address :root [] :root [:user :name]) :op])))
    (is (= :invoke (get-in plan [:expressions (fixture/address :root [] :root [:result]) :op])))
    (is (= #{(fixture/address :root [] :root [:user :unused])
             (fixture/address :root [:use] :child [:x])} (:parameters plan)))
    (is (= (:parameters plan) (:required-addresses plan)))))


(deftest incomplete-checking-and-runtime-obligations-are-never-dropped
  (let [model (structural-model)
        code (fn [model] (try (lower model) (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))]
    (is (= :unchecked-plan (code (assoc-in model [:analysis :status] :incomplete))))
    (is (= :unchecked-plan (code (assoc-in model [:analysis :coverage :type-checker] false))))
    (is (= :snapshot-mismatch (code (assoc-in model [:context :snapshot-id] (random-uuid)))))
    (is (= :runtime-obligations-required (code (assoc-in model [:analysis :obligations] [{:kind :value-type}]))))
    (is (= :runtime-obligations-required (code (assoc-in model [:analysis :scopes [] :obligations] [{}]))))
    (is (= :invalid-closure-plan (code (assoc-in model [:analysis :closures] {:x {}}))))))


(deftest special-base-semantics-and-missing-abi-provenance-reject
  (let [{:keys [analysis context]} (structural-model)
        impls (fixture/implementations definitions {:root (fn [_ _]) :child (fn [_ _])})
        target (fixture/id :child)
        code (fn [impls]
               (try (ir/lower-checked analysis {:snapshot-id (:snapshot-id context) :implementations impls})
                    (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))]
    (is (= :resolved-implementation-required (code (update-in impls [target] dissoc :root-entry))))
    (is (= :implementation-abi-mismatch (code (assoc-in impls [target :argument-paths] {}))))
    (is (= :compile-time-value-compilation-required
           (code (assoc-in impls [target :root-entry :compile-time-value?] true))))
    (is (= :lazy-sequence-abi-required
           (code (assoc-in impls [target :root-entry :lazy-seq-args] #{:x}))))))
