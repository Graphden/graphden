(ns graphden.library.base-fns.core.hof-test
  "Tests for higher-order function base functions."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.interface :as registry]
    [graphden.library.base-fns.core.test-helpers :as h]
    [graphden.library.interface :as bf]
    [graphden.storage.age.test-setup :as th]
    [graphden.storage.protocol.interface :as sp]))


;; Container for PostgreSQL tests
(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))


(use-fixtures :each
  (th/create-clean-db-fixture #'*container*)
  exec/with-clean-registry)


;; === HOF Setup Helpers ===

(defn- setup-hof-storage
  "Creates storage with helper functions for HOF tests.
   Returns fn-ids for use in HOF tests via executor."
  []
  (let [storage (th/create-test-storage *container*)]
    (h/register-all!)
    ;; Sync base function schemas to storage so HOF can be found
    (registry/sync-defs-to-storage! storage (bf/get-all-defs))

    ;; Create 'double' function: x -> x * 2 (single required arg)
    (let [double-schema (sp/create-entity storage :fn-schema
                                          {:name "double"
                                           :returned-type :int})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id double-schema)
                               :name "x"
                               :type :int
                               :required true})
          double-fn (sp/create-entity storage :fn
                                      {:name "my-double"
                                       :fn-schema-id (:id double-schema)})

          ;; Create 'gt2' predicate: x -> x > 2 (single required arg)
          gt2-schema (sp/create-entity storage :fn-schema
                                       {:name "gt2"
                                        :returned-type :bool})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id gt2-schema)
                               :name "x"
                               :type :int
                               :required true})
          gt2-fn (sp/create-entity storage :fn
                                   {:name "my-gt2"
                                    :fn-schema-id (:id gt2-schema)})

          ;; Create 'add-reducer' function: pair -> pair[0] + pair[1] (single required arg)
          ;; Takes [acc item] as single argument
          add-schema (sp/create-entity storage :fn-schema
                                       {:name "add-reducer"
                                        :returned-type :int})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id add-schema)
                               :name "pair"
                               :type :jsonb
                               :required true})
          add-fn (sp/create-entity storage :fn
                                   {:name "my-add-reducer"
                                    :fn-schema-id (:id add-schema)})

          ;; Create 'get-category' function: x -> :small/:large (single required arg)
          cat-schema (sp/create-entity storage :fn-schema
                                       {:name "get-category"
                                        :returned-type :text})
          _ (sp/create-entity storage :arg-schema
                              {:fn-schema-id (:id cat-schema)
                               :name "x"
                               :type :int
                               :required true})
          cat-fn (sp/create-entity storage :fn
                                   {:name "my-get-category"
                                    :fn-schema-id (:id cat-schema)})]

      ;; Register base function implementations
      (exec/register-base-fn! :double
                              (fn [{:keys [x]} _ctx]
                                (* 2 @x)))

      (exec/register-base-fn! :gt2
                              (fn [{:keys [x]} _ctx]
                                (> @x 2)))

      (exec/register-base-fn! :add-reducer
                              (fn [{:keys [pair]} _ctx]
                                (let [[acc item] @pair]
                                  (+ acc item))))

      (exec/register-base-fn! :get-category
                              (fn [{:keys [x]} _ctx]
                                (if (> @x 5) "large" "small")))

      {:storage storage
       :double-fn-id (:id double-fn)
       :gt2-fn-id (:id gt2-fn)
       :add-fn-id (:id add-fn)
       :cat-fn-id (:id cat-fn)})))


(defn- get-or-create-arg-value!
  "Gets existing arg-value or creates new one."
  [storage arg-schema-id value]
  (if-let [existing (first (sp/query-entities storage :arg-value
                                              {:arg-schema-id arg-schema-id
                                               :value value}))]
    existing
    (sp/create-entity storage :arg-value
                      {:arg-schema-id arg-schema-id
                       :value value})))


(defn- create-arg-value-with-binding!
  "Creates arg-value (or reuses existing) and fn-arg binding."
  [storage fn-id arg-schema-id value]
  (let [av (get-or-create-arg-value! storage arg-schema-id value)]
    (sp/create-entity storage :fn-arg
                      {:fn-id fn-id
                       :arg-schema-id arg-schema-id
                       :arg-value-id (:id av)})
    av))


(defn- create-hof-caller
  "Creates a function that calls a HOF (map/filter/etc) with given fn-id and collection.
   Returns the result of executing the HOF."
  [storage hof-name f-arg-name fn-id coll]
  (let [;; Get HOF fn-schema
        hof-schema (first (sp/query-entities storage :fn-schema {:name hof-name}))
        hof-arg-schemas (sp/query-entities storage :arg-schema {:fn-schema-id (:id hof-schema)})
        f-arg (first (filter #(= f-arg-name (:name %)) hof-arg-schemas))
        coll-arg (first (filter #(= "coll" (:name %)) hof-arg-schemas))
        ;; Create HOF instance with unique name
        hof-fn (sp/create-entity storage :fn
                                 {:name (str "test-" hof-name "-" (random-uuid))
                                  :fn-schema-id (:id hof-schema)})
        ;; Set :f/:pred/:key-fn arg to fn-id
        _ (create-arg-value-with-binding! storage (:id hof-fn) (:id f-arg) fn-id)
        ;; Set :coll arg
        _ (create-arg-value-with-binding! storage (:id hof-fn) (:id coll-arg) coll)
        ctx (exec/create-context {:storage storage})]
    (exec/execute ctx (:id hof-fn) nil)))


(defn- create-reduce-caller
  "Creates a function that calls reduce with given fn-id, init value and collection."
  [storage fn-id init coll]
  (let [reduce-schema (first (sp/query-entities storage :fn-schema {:name "reduce"}))
        reduce-arg-schemas (sp/query-entities storage :arg-schema {:fn-schema-id (:id reduce-schema)})
        f-arg (first (filter #(= "f" (:name %)) reduce-arg-schemas))
        init-arg (first (filter #(= "init" (:name %)) reduce-arg-schemas))
        coll-arg (first (filter #(= "coll" (:name %)) reduce-arg-schemas))
        reduce-fn (sp/create-entity storage :fn
                                    {:name (str "test-reduce-" (random-uuid))
                                     :fn-schema-id (:id reduce-schema)})
        _ (create-arg-value-with-binding! storage (:id reduce-fn) (:id f-arg) fn-id)
        _ (create-arg-value-with-binding! storage (:id reduce-fn) (:id init-arg) init)
        _ (create-arg-value-with-binding! storage (:id reduce-fn) (:id coll-arg) coll)
        ctx (exec/create-context {:storage storage})]
    (exec/execute ctx (:id reduce-fn) nil)))


(defn- create-apply-caller
  "Creates a function that calls apply with given fn-id and args."
  [storage fn-id args]
  (let [apply-schema (first (sp/query-entities storage :fn-schema {:name "apply"}))
        apply-arg-schemas (sp/query-entities storage :arg-schema {:fn-schema-id (:id apply-schema)})
        f-arg (first (filter #(= "f" (:name %)) apply-arg-schemas))
        args-arg (first (filter #(= "args" (:name %)) apply-arg-schemas))
        apply-fn (sp/create-entity storage :fn
                                   {:name (str "test-apply-" (random-uuid))
                                    :fn-schema-id (:id apply-schema)})
        _ (create-arg-value-with-binding! storage (:id apply-fn) (:id f-arg) fn-id)
        _ (create-arg-value-with-binding! storage (:id apply-fn) (:id args-arg) args)
        ctx (exec/create-context {:storage storage})]
    (exec/execute ctx (:id apply-fn) nil)))


;; === HOF Tests ===

(deftest hof-map-test
  (let [{:keys [storage double-fn-id]} (setup-hof-storage)]
    (try
      (testing "map doubles each element"
        (is (= [2 4 6 8 10] (create-hof-caller storage "map" "f" double-fn-id [1 2 3 4 5]))))

      (testing "map on empty collection"
        (is (= [] (create-hof-caller storage "map" "f" double-fn-id []))))
      (finally
        (sp/close storage)))))


(deftest hof-filter-test
  (let [{:keys [storage gt2-fn-id]} (setup-hof-storage)]
    (try
      (testing "filter keeps elements > 2"
        (is (= [3 4 5] (create-hof-caller storage "filter" "pred" gt2-fn-id [1 2 3 4 5]))))

      (testing "filter on empty collection"
        (is (= [] (create-hof-caller storage "filter" "pred" gt2-fn-id []))))

      (testing "filter with no matches"
        (is (= [] (create-hof-caller storage "filter" "pred" gt2-fn-id [1 2]))))
      (finally
        (sp/close storage)))))


(deftest hof-reduce-test
  (let [{:keys [storage add-fn-id]} (setup-hof-storage)]
    (try
      (testing "reduce sums all elements"
        (is (= 15 (create-reduce-caller storage add-fn-id 0 [1 2 3 4 5]))))

      (testing "reduce with different initial value"
        (is (= 25 (create-reduce-caller storage add-fn-id 10 [1 2 3 4 5]))))

      (testing "reduce on empty collection returns init"
        (is (zero? (create-reduce-caller storage add-fn-id 0 []))))
      (finally
        (sp/close storage)))))


(deftest hof-some-test
  (let [{:keys [storage gt2-fn-id]} (setup-hof-storage)]
    (try
      (testing "some finds first truthy result"
        (is (true? (create-hof-caller storage "some" "pred" gt2-fn-id [1 2 3 4]))))

      (testing "some returns nil when no match"
        (is (nil? (create-hof-caller storage "some" "pred" gt2-fn-id [1 2]))))

      (testing "some on empty collection"
        (is (nil? (create-hof-caller storage "some" "pred" gt2-fn-id []))))
      (finally
        (sp/close storage)))))


(deftest hof-every?-test
  (let [{:keys [storage gt2-fn-id]} (setup-hof-storage)]
    (try
      (testing "every? returns true when all match"
        (is (true? (create-hof-caller storage "every?" "pred" gt2-fn-id [3 4 5]))))

      (testing "every? returns false when some don't match"
        (is (false? (create-hof-caller storage "every?" "pred" gt2-fn-id [1 3 5]))))

      (testing "every? on empty collection returns true"
        (is (true? (create-hof-caller storage "every?" "pred" gt2-fn-id []))))
      (finally
        (sp/close storage)))))


(deftest hof-find-first-test
  (let [{:keys [storage gt2-fn-id]} (setup-hof-storage)]
    (try
      (testing "find-first returns first matching element"
        (is (= 3 (create-hof-caller storage "find-first" "pred" gt2-fn-id [1 2 3 4 5]))))

      (testing "find-first returns nil when no match"
        (is (nil? (create-hof-caller storage "find-first" "pred" gt2-fn-id [1 2]))))

      (testing "find-first on empty collection"
        (is (nil? (create-hof-caller storage "find-first" "pred" gt2-fn-id []))))
      (finally
        (sp/close storage)))))


(deftest hof-group-by-test
  (let [{:keys [storage cat-fn-id]} (setup-hof-storage)]
    (try
      (testing "group-by groups by category"
        (let [result (create-hof-caller storage "group-by" "key-fn" cat-fn-id [1 3 6 8 2 10])]
          (is (= [1 3 2] (get result "small")))
          (is (= [6 8 10] (get result "large")))))

      (testing "group-by on empty collection"
        (is (= {} (create-hof-caller storage "group-by" "key-fn" cat-fn-id []))))
      (finally
        (sp/close storage)))))


(deftest hof-sort-by-test
  (let [{:keys [storage double-fn-id]} (setup-hof-storage)]
    (try
      (testing "sort-by sorts by key function result"
        (is (= [1 1 2 3 4 5] (create-hof-caller storage "sort-by" "key-fn" double-fn-id [3 1 4 1 5 2]))))

      (testing "sort-by on empty collection"
        (is (= [] (create-hof-caller storage "sort-by" "key-fn" double-fn-id []))))
      (finally
        (sp/close storage)))))


(deftest hof-apply-test
  (let [{:keys [storage double-fn-id]} (setup-hof-storage)]
    (try
      (testing "apply calls function with single arg"
        (is (= 10 (create-apply-caller storage double-fn-id 5))))

      (testing "apply with different value"
        (is (= 20 (create-apply-caller storage double-fn-id 10))))
      (finally
        (sp/close storage)))))


(deftest hof-identity-test
  (h/register-hof!)

  (testing "identity returns value unchanged"
    (is (= 42 (h/call-base-fn :identity {:x 42})))
    (is (= "hello" (h/call-base-fn :identity {:x "hello"})))
    (is (= [1 2 3] (h/call-base-fn :identity {:x [1 2 3]})))))


(deftest hof-constantly-test
  (h/register-hof!)

  (testing "constantly returns value"
    (is (= 42 (h/call-base-fn :constantly {:x 42})))
    (is (= "always" (h/call-base-fn :constantly {:x "always"})))))


;; === Transducer Integration Tests ===
;; Tests that comp + transduce work correctly with call-site references

(deftest transducer-comp-transduce-integration-test
  (testing "comp + transduce pipeline works with call-site references"
    (let [storage (th/create-test-storage *container*)]
      (try
        ;; Register base functions
        (h/register-all!)
        (registry/sync-defs-to-storage! storage (bf/get-all-defs))

        ;; Setup: create helper predicate and transform functions
        ;; gt2: x -> x > 2
        (let [gt2-schema (sp/create-entity storage :fn-schema
                                           {:name "gt2"
                                            :returned-type :bool})
              _ (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id gt2-schema)
                                   :name "x"
                                   :type :int
                                   :required true})
              gt2-fn (sp/create-entity storage :fn
                                       {:name "my-gt2"
                                        :fn-schema-id (:id gt2-schema)})

              ;; double: x -> x * 2
              double-schema (sp/create-entity storage :fn-schema
                                              {:name "double"
                                               :returned-type :int})
              _ (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id double-schema)
                                   :name "x"
                                   :type :int
                                   :required true})
              double-fn (sp/create-entity storage :fn
                                          {:name "my-double"
                                           :fn-schema-id (:id double-schema)})

              ;; add-reducer: [acc item] -> acc + item
              add-schema (sp/create-entity storage :fn-schema
                                           {:name "add-reducer"
                                            :returned-type :int})
              _ (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id add-schema)
                                   :name "pair"
                                   :type :jsonb
                                   :required true})
              add-fn (sp/create-entity storage :fn
                                       {:name "my-add-reducer"
                                        :fn-schema-id (:id add-schema)})]

          ;; Register implementations
          (exec/register-base-fn! :gt2
                                  (fn [{:keys [x]} _ctx]
                                    (> @x 2)))

          (exec/register-base-fn! :double
                                  (fn [{:keys [x]} _ctx]
                                    (* 2 @x)))

          (exec/register-base-fn! :add-reducer
                                  (fn [{:keys [pair]} _ctx]
                                    (let [[acc item] @pair]
                                      (+ acc item))))

          ;; Create transducer-returning functions and compose them
          ;; The key here is using fn-composition which creates call-sites
          (fn-composition/sync-fns-to-storage! storage
                                               [;; filter-xf: (filter gt2) - returns transducer
                                                {:name :filter-xf
                                                 :parent :filter
                                                 :args {:pred (:id gt2-fn)}}

                                                ;; map-xf: (map double) - returns transducer
                                                {:name :map-xf
                                                 :parent :map
                                                 :args {:f (:id double-fn)}}

                                               ;; Build pair of transducers explicitly
                                               {:name :xf-pair
                                                :parent :pair
                                                :args {:a :filter-xf> :b :map-xf>}}

                                               ;; composed-xf: (comp filter-xf map-xf)
                                               ;; Uses pair to pass functions as vector
                                               {:name :composed-xf
                                                :parent :comp
                                                :args {:fns :xf-pair>}}

                                                ;; final-result: (transduce composed-xf add-reducer 0 [1 2 3 4 5])
                                                {:name :final-result
                                                 :parent :transduce
                                                 :args {:xf :composed-xf>
                                                        :rf (:id add-fn)
                                                        :init 0
                                                        :coll [1 2 3 4 5]}}])

          (let [final-fn (first (sp/query-entities storage :fn {:name "final-result"}))
                ctx (exec/create-context {:storage storage})
                result (exec/execute ctx (:id final-fn) nil)]
            ;; [1 2 3 4 5] -> filter >2 -> [3 4 5] -> map *2 -> [6 8 10] -> sum -> 24
            (is (= 24 result))))
        (finally
          (sp/close storage)))))

  (testing "transducers are lazily composed (single pass)"
    (let [storage (th/create-test-storage *container*)
          filter-count (atom 0)
          map-count (atom 0)]
      (try
        ;; Register base functions
        (h/register-all!)
        (registry/sync-defs-to-storage! storage (bf/get-all-defs))

        ;; Create counting predicate and transform
        (let [counting-pred-schema (sp/create-entity storage :fn-schema
                                                     {:name "counting-pred"
                                                      :returned-type :bool})
              _ (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id counting-pred-schema)
                                   :name "x"
                                   :type :int
                                   :required true})
              counting-pred-fn (sp/create-entity storage :fn
                                                 {:name "my-counting-pred"
                                                  :fn-schema-id (:id counting-pred-schema)})

              counting-map-schema (sp/create-entity storage :fn-schema
                                                    {:name "counting-map"
                                                     :returned-type :int})
              _ (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id counting-map-schema)
                                   :name "x"
                                   :type :int
                                   :required true})
              counting-map-fn (sp/create-entity storage :fn
                                                {:name "my-counting-map"
                                                 :fn-schema-id (:id counting-map-schema)})

              add-schema (sp/create-entity storage :fn-schema
                                           {:name "add-reducer2"
                                            :returned-type :int})
              _ (sp/create-entity storage :arg-schema
                                  {:fn-schema-id (:id add-schema)
                                   :name "pair"
                                   :type :jsonb
                                   :required true})
              add-fn (sp/create-entity storage :fn
                                       {:name "my-add-reducer2"
                                        :fn-schema-id (:id add-schema)})]

          ;; Register implementations with counting
          (exec/register-base-fn! :counting-pred
                                  (fn [{:keys [x]} _ctx]
                                    (swap! filter-count inc)
                                    (odd? @x)))

          (exec/register-base-fn! :counting-map
                                  (fn [{:keys [x]} _ctx]
                                    (swap! map-count inc)
                                    (* 2 @x)))

          (exec/register-base-fn! :add-reducer2
                                  (fn [{:keys [pair]} _ctx]
                                    (let [[acc item] @pair]
                                      (+ acc item))))

          ;; Create transducer pipeline
          (fn-composition/sync-fns-to-storage! storage
                                               [{:name :counting-filter-xf
                                                 :parent :filter
                                                 :args {:pred (:id counting-pred-fn)}}

                                                {:name :counting-map-xf
                                                 :parent :map
                                                 :args {:f (:id counting-map-fn)}}

                                                ;; Build pair of transducers explicitly
                                                {:name :counting-xf-pair
                                                 :parent :pair
                                                 :args {:a :counting-filter-xf> :b :counting-map-xf>}}

                                                {:name :counting-composed-xf
                                                 :parent :comp
                                                 :args {:fns :counting-xf-pair>}}

                                                {:name :counting-result
                                                 :parent :transduce
                                                 :args {:xf :counting-composed-xf>
                                                        :rf (:id add-fn)
                                                        :init 0
                                                        :coll [1 2 3 4 5 6 7 8 9 10]}}])

          (let [final-fn (first (sp/query-entities storage :fn {:name "counting-result"}))
                ctx (exec/create-context {:storage storage})
                result (exec/execute ctx (:id final-fn) nil)]
            ;; [1..10] -> filter odd [1 3 5 7 9] -> map *2 [2 6 10 14 18] -> sum = 50
            (is (= 50 result))
            ;; Filter should see all 10 elements
            (is (= 10 @filter-count))
            ;; Map should only see the 5 odd elements
            (is (= 5 @map-count))))
        (finally
          (sp/close storage))))))
