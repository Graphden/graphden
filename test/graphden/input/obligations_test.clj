(ns graphden.input.obligations-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.obligations :as obligations]))


(deftest coincident-field-read-checks-retain-the-configured-type-in-either-order
  (let [owner (random-uuid)
        field (random-uuid)
        address [[owner []] [field]]
        scheme '[:fn {:arg b} c]
        graph {:scopes {[] {:configured-contract {:fields [{:id field :type scheme}]}}}}
        configured {:code :runtime-type-obligation :address address}
        inherited #(assoc configured :expected-type %)
        separate #(obligations/separate (assoc graph :diagnostics %))]
    (doseq [diagnostics [[configured (inherited :any)] [(inherited :any) configured]
                         [configured configured (inherited scheme)] [(inherited :any) (inherited :any)]]
            :let [result (separate diagnostics)
                  expected (if (every? #(= :any (:expected-type %)) diagnostics) :any scheme)]]
      (is (= :checked (:status result)))
      (is (= [[address expected :field-read]] (mapv (juxt :address :expected-type :trigger) (:obligations result))))
      (is (= (:obligations result) (get-in result [:scopes [] :obligations]))))
    (doseq [diagnostics [[configured (inherited :text)] [(inherited :text) configured]]]
      (is (= {:type :input/unresolved-contract :code :conflicting-runtime-obligations
              :address address :kind :value-type :trigger :field-read}
             (try (separate diagnostics)
                  nil
                  (catch clojure.lang.ExceptionInfo error (ex-data error))))))))
