(ns graphden.input.obligations-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.context :as context]
    [graphden.input.obligations :as obligations]
    [graphden.input.variables :as variables]))


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


(deftest a-concrete-dictionary-implies-a-broader-read-check-in-either-order
  (let [owner (random-uuid)
        field (random-uuid)
        address [[owner []] [field]]
        dictionary [:map :uuid [:list [:map :keyword :any]]]
        open-values [:map :uuid :any]
        union [:union :null [:list :any] open-values]
        graph {:scopes {[] {:configured-contract {:fields [{:id field :type dictionary}]}}}}
        separate (fn [checks]
                   (obligations/separate
                     (assoc graph :diagnostics
                            (mapv #(hash-map :code :runtime-type-obligation :address address :expected-type %) checks))))]
    (doseq [narrow [dictionary open-values]
            broad (if (= narrow dictionary) [open-values union] [union])
            checks [[narrow broad] [broad narrow]]]
      (let [result (separate checks)]
        (is (= [[address narrow :field-read]]
               (mapv (juxt :address :expected-type :trigger) (:obligations result))))))
    (doseq [checks [[[:map :keyword :any] union]
                    [dictionary [:map :uuid :text]]
                    [{:id :uuid} union]]]
      (is (= :conflicting-runtime-obligations
             (try (separate checks) nil
                  (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))))))


(deftest a-checked-list-retains-its-element-validation
  (let [owner (random-uuid)
        field (random-uuid)
        address [[owner []] [field]]
        narrow [:list [:list :any]]
        graph {:scopes {[] {:configured-contract {:fields [{:id field :type narrow}]}}}}
        separate (fn [types]
                   (obligations/separate
                     (assoc graph :diagnostics
                            (mapv #(hash-map :code :runtime-type-obligation :address address :expected-type %) types))))]
    (doseq [broad [[:list :any] [:union :null [:list :any]]]
            types [[narrow broad] [broad narrow]]]
      (is (= [narrow] (mapv :expected-type (:obligations (separate types))))))
    (doseq [other [[:list :text] [:list :keyword] '[:list a]]]
      (is (= :conflicting-runtime-obligations
             (try (separate [narrow other]) nil
                  (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))))))


(deftest equal-shapes-from-independent-binders-remain-distinct-checks
  (let [owner (random-uuid)
        field (random-uuid)
        address [[owner []] [field]]
        rich '[:union :null [:map a :any]]
        left (variables/type-in-scope [owner [(random-uuid)]] rich)
        right (variables/type-in-scope [owner [(random-uuid)]] rich)
        graph {:scopes {[] {:configured-contract {:fields [{:id field :type left}]}}}
               :diagnostics (mapv #(hash-map :code :runtime-type-obligation :address address :expected-type %) [left right])}]
    (is (not= left right))
    (is (= :conflicting-runtime-obligations
           (try (obligations/separate graph)
                nil
                (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))))


(deftest checks-use-the-frozen-body-of-their-named-type
  (let [field (random-uuid)
        address [[(random-uuid) []] [field]]
        rich [:union :keyword :null]
        configured {:scopes {[] {:configured-contract {:fields [{:id field :type :tag}]}}}}
        diag {:code :runtime-type-obligation :address address}
        check (fn [diagnostics] (:obligations (obligations/separate (assoc configured :diagnostics diagnostics))))]
    (context/call-with-types {:aliases {:tag rich}}
                             (fn []
                               (doseq [diagnostics [[diag (assoc diag :expected-type rich)]
                                                    [(assoc diag :expected-type rich) diag]]]
                                 (is (= [rich] (mapv :expected-type (check diagnostics)))))
                               (is (thrown? clojure.lang.ExceptionInfo (check [diag (assoc diag :expected-type :text)])))))))
