(ns graphden.executor.compile.surface-test
  "Public argument identity across independent renamed calls."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.surface :as surface]
    [graphden.packages.records :as records]))


(defn- fixture
  []
  (let [definitions
        [{:name :get :namespace "core.collections"
          :args {:coll {:type :any} :key {:type :any} :default {:type :any}} :return-type :any}
         {:name :list :namespace "core.collections" :args {:items {:type [:list :any]}} :return-type [:list :any]}
         {:name :left :namespace "surface" :parent :get
          :args {:coll {:as :left} :key {:value :value} :default nil}}
         {:name :right :namespace "surface" :parent :get
          :args {:coll {:as :right} :key {:value :value} :default nil}}
         {:name :pair :namespace "surface" :parent :list :args {:items [:left :right]}}
         {:name :left-bound :namespace "surface" :parent :pair :args {:left {:value {:value 7}}}}
         {:name :renamed :namespace "surface" :parent :left :args {:left {:as :outer}}}
         {:name :renamed-child :namespace "surface" :parent :renamed}
         {:name :renamed-bound :namespace "surface" :parent :renamed-child
          :args {:outer {:value {:value 11}}}}]
        ids (into {} (map (fn [d] [(:name d) (records/fn-id (:namespace d) (:name d))])) definitions)
        by-name (into {} (map (juxt :name identity)) definitions)
        rows (group-by :kind (concat (records/boot-primitive-records)
                                     (mapcat #(records/parse-fn-def % ids by-name) definitions)))
        lookup (lookups/build-lookups {:fns (:fn rows) :slots (:slot rows) :fn-slots (:fn-slot rows)
                                       :bindings (:binding rows) :list-items (:binding-list-item rows)})]
    {:ids ids :lookup lookup}))


(deftest independent-renames-of-one-primitive-remain-separate-public-inputs
  (let [{:keys [ids lookup]} (fixture)
        entries (surface/public-free-entries (:pair ids) lookup)]
    (is (= #{:left :right} (set (:names (surface/surface-names (:pair ids) lookup)))))
    (is (= #{:left :right} (set (map :ext-name entries))))
    (is (= 2 (count (set (map :slot-id entries)))))))


(deftest binding-one-independent-rename-does-not-close-its-sibling
  (let [{:keys [ids lookup]} (fixture)]
    (is (= [:right] (:names (surface/surface-names (:left-bound ids) lookup))))
    (is (= [:right] (mapv :ext-name (surface/public-free-entries (:left-bound ids) lookup))))))


(deftest inherited-renames-still-present-one-hole-and-bind-through-the-chain
  (let [{:keys [ids lookup]} (fixture)]
    (testing "one inherited chain exposes its closest rename, once"
      (is (= [:outer] (mapv :ext-name (surface/public-free-entries (:renamed-child ids) lookup)))))
    (testing "binding that rename closes the hole"
      (is (= [] (surface/public-free-entries (:renamed-bound ids) lookup))))))
