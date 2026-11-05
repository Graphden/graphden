(ns ^:serial graphden.packages.type-seed-test
  "Seed-only callable dispatch regressions: no database or full type sweep.
   Serial because the alias diagnostic regression replaces a global reader."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records :as records]
    [graphden.packages.records.types :as record-types]
    [graphden.packages.type-seed :as seed]
    [graphden.types.core :as types]))


(use-fixtures :each
  (fn [run]
    (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
              registry/*per-org-rich-override* (atom {})
              types/*type-aliases-override* (atom {})]
      (run))))


(def ^:private callable [:fn {:arg :any} :any])


(def ^:private producer
  {:name :recursive-value :namespace "seed"
   :parent :get
   :args {:coll {:as :state :type :keyword-map}
          :key {:value :recur} :default nil}
   :return-type callable
   :effects #{:db}
   :lambda-params []})


(defn- failure
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))


(defn- compile-bindings
  [definitions name]
  (let [ids (into {} (map (fn [d] [(:name d) (records/fn-id (:namespace d) (:name d))])) definitions)
        by-name (into {} (map (juxt :name identity)) definitions)
        rows (concat (records/boot-primitive-records)
                     (mapcat record-types/inline-fn-type-rows-from-fn-def definitions)
                     (mapcat #(records/parse-fn-def % ids by-name) definitions))
        grouped (group-by :kind rows)
        graph {:fns (:fn grouped) :slots (:slot grouped)
               :fn-slots (:fn-slot grouped) :bindings (:binding grouped)
               :list-items (:binding-list-item grouped)}]
    (bindings/collect-bindings (get ids name) (lookups/build-lookups graph))))


(deftest ordinary-bindings-preserve-callable-producer-dispatch-without-sweep
  (let [definitions [{:name :get :namespace "core.collections"
                      :args {:coll {:type :any} :key {:type :any} :default {:type :any}}
                      :return-type :any}
                     {:name :invoke :namespace "core.system"
                      :args {:func {:type callable} :arg {:type :any}}
                      :return-type :any}
                     producer
                     {:name :consumer :namespace "seed" :parent :invoke
                      :args {:func :recursive-value :arg {:value 7}}}]
        errors (seed/seed! definitions)
        signature (registry/rich-type-of-id (records/fn-id "seed" :recursive-value))
        func (first (filter #(= :func (:base-name %))
                            (compile-bindings definitions :consumer)))]
    (is (= {} errors))
    (is (nil? (seed/assert-callable-returns! definitions errors)))
    (is (= (types/resolve-alias callable) (:return signature)))
    (is (= #{:db} (:effects signature)))
    (is (= [] (:lambda-params signature)))
    (is (true? (:is-fn func)))
    (is (true? (:produces-callable? func)) "invoke must evaluate the getter, not wrap it")))


(deftest callable-guard-does-not-use-a-same-named-or-stale-entry
  (let [id (records/fn-id "seed" :recursive-value)
        other (assoc producer :namespace "other")]
    (seed/seed! [other])
    (testing "a namesake cannot stand in for the missing identity"
      (is (= {:type :packages/callable-seed-missing
              :fn-id id :fn-name :recursive-value :seed-failed? false}
             (failure #(seed/assert-callable-returns! [producer] {})))))
    (seed/seed! [producer])
    (testing "a previous valid signature cannot hide failure on a new declaration"
      (let [invalid (assoc-in producer [:args :coll :type] 42)
            errors (seed/seed! [invalid])]
        (is (= :invalid-arg-type (:type (ex-data (get errors id)))))
        (is (true? (:seed-failed? (failure #(seed/assert-callable-returns! [invalid] errors)))))))))


(deftest binding-fallback-retains-explicit-type-errors
  (let [invalid (assoc producer :args (array-map :default nil :coll {:type 42}))
        id (records/fn-id "seed" :recursive-value)
        errors (seed/seed! [invalid])]
    (is (= :invalid-arg-type (:type (ex-data (get errors id)))))
    (is (nil? (registry/rich-type-of-id id)))
    (is (= :packages/callable-seed-missing
           (:type (failure #(seed/assert-callable-returns! [invalid] errors)))))))


(deftest callable-guard-preserves-markers-and-resolves-declared-aliases
  (types/register-type-alias! :callback [:secret callable])
  (let [definition (assoc producer :return-type :callback)
        errors (seed/seed! [definition])
        id (records/fn-id "seed" :recursive-value)]
    (is (= {} errors))
    (is (nil? (seed/assert-callable-returns! [definition] errors)))
    (is (= (types/resolve-alias [:secret callable]) (:return (registry/rich-type-of-id id))))
    (registry/record-rich-types-raw! id :recursive-value {:return (types/resolve-alias callable)})
    (is (= :packages/callable-seed-missing
           (:type (failure #(seed/assert-callable-returns! [definition] {})))))))


(deftest non-callable-seed-errors-still-belong-to-the-normal-sweep
  (let [definition (assoc producer :return-type :text :args {:coll {:type 42}})
        errors (seed/seed! [definition])]
    (is (= 1 (count errors)))
    (is (nil? (seed/assert-callable-returns! [definition] errors)))))


(deftest unresolved-non-callable-alias-retains-its-normal-diagnostic
  (let [definition (assoc producer :return-type :fixture/ambiguous)]
    (with-redefs [types/resolve-alias
                  (fn [_] (throw (ex-info "ambiguous alias" {:type :types/ambiguous-alias})))]
      (let [errors (seed/seed! [definition])]
        (is (= :types/ambiguous-alias (:type (ex-data (first (vals errors))))))
        (is (nil? (seed/assert-callable-returns! [definition] errors)))))))
