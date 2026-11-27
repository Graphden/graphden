(ns graphden.input.source-positional-type-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.input.migration :as migration]
    [graphden.input.source-positional-type :as positional]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- fixture
  ([] (fixture [{:value false} {:as :leaf :required false :description "Second item"}]))
  ([items]
   (let [source {:namespace "fixture" :name :directory :parent :consume :args {:xs items}}
         context (parse/prepare-module
                   [{:namespace "fixture" :name :consume :args {:xs {:type [:list :any]}} :return-type :any} source] {} {})
         definition (get-in context [:defs-by-name :fixture/directory])
         rows (parse/parse-fn-def definition (:name->id context) (:defs-by-name context))
         owner-id (:fn-id definition)
         owner (some #(when (= owner-id (:id %)) %) rows)
         input (:id (migration/input-type-record owner))
         fields (filterv #(= :slot (:kind %)) rows)
         members (filterv #(and (= :fn-slot (:kind %)) (= owner-id (:fn-id %))) rows)]
     {:context context :source source :definition definition :rows rows :owner owner-id
      :snapshot {:fns {owner-id (assoc owner :input-type-fn-id input)}
                 :fields (into {} (map (juxt :id identity)) fields)
                 :members {input (mapv #(migration/input-membership-record owner %) members)}}})))


(defn- origin
  [{:keys [context source definition rows]}]
  (positional/origin context source definition rows :leaf))


(deftest original-untyped-positional-field-has-a-physical-any-witness
  (let [{:keys [owner snapshot] :as data} (fixture)
        witness (origin data)]
    (is (= (ids/slot-id owner :leaf) (:slot-id witness)))
    (is (= (ids/primitive-fn-id :any) (:type-fn-id witness)))
    (is (= [:args :xs 1] (:source-path witness)))
    (is (= 1 (:item-position witness)))
    (is (= (ids/binding-list-item-id (:binding-id witness) 1) (:item-id witness)))
    (is (positional/exact? witness {:field :leaf :type nil} snapshot owner))
    (is (not (positional/exact? nil {:field :leaf :type nil} snapshot owner)))
    (is (not (positional/exact? witness {:field :leaf :type :any} snapshot owner)))))


(deftest inconsistent-physical-source-rows-cannot-create-a-type-witness
  (let [data (fixture)
        witness (origin data)
        change (fn [kind id patch]
                 (update data :rows #(mapv (fn [row]
                                             (if (and (= kind (:kind row)) (= id (:id row)))
                                               (merge row patch) row)) %)))]
    (doseq [[kind id patch] [[:slot (:slot-id witness) {:type-fn-id (ids/primitive-fn-id :text)}]
                             [:slot (:slot-id witness) {:required true}]
                             [:slot (:slot-id witness) {:change-revision 1}]
                             [:fn-slot (:membership-id witness) {:position -1}]
                             [:binding-list-item (:item-id witness) {:position 0}]
                             [:binding-list-item (:item-id witness) {:literal true}]
                             [:binding-list-item (:item-id witness) {:value {:as :other}}]]]
      (testing (pr-str [kind patch])
        (is (not (try (origin (change kind id patch)) (catch clojure.lang.ExceptionInfo _ nil))))))
    (is (nil? (origin (assoc-in data [:source :input-format] 2))))
    (is (nil? (origin (assoc-in data [:definition :input-format] 2))))
    (is (nil? (origin (assoc-in data [:source :bind] {}))))
    (is (nil? (origin (fixture [{:as :leaf} {:as :leaf}]))))
    (is (nil? (origin (fixture [{:as :leaf :type :any}]))))
    (is (nil? (origin (fixture [{:value {:as :leaf}}]))))))


(deftest a-current-frozen-field-must-match-the-entire-origin
  (let [{:keys [snapshot owner] :as data} (fixture)
        witness (origin data)
        field (:slot-id witness)
        input (get-in snapshot [:fns owner :input-type-fn-id])
        target {:field :leaf :type nil}
        check #(positional/exact? % target snapshot owner)]
    (doseq [patch [{:version 2} {:slot-id (random-uuid)} {:owner-id (random-uuid)}
                   {:field :other} {:type-fn-id (ids/primitive-fn-id :text)} {:required true}
                   {:membership-position 1} {:membership-id (random-uuid)}
                   {:item-id (random-uuid)} {:item-position 0} {:source-path [:bind :xs 1]}]]
      (is (not (check (merge witness patch)))))
    (doseq [changed [(assoc-in snapshot [:fields field :required] true)
                     (assoc-in snapshot [:fields field :type-fn-id] (ids/primitive-fn-id :text))
                     (assoc-in snapshot [:fields field :change-id] (random-uuid))
                     (assoc-in snapshot [:members input 0 :position] 1)
                     (update-in snapshot [:members input] conj (first (get-in snapshot [:members input])))]]
      (is (not (positional/exact? witness target changed owner))))))
