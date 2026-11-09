(ns graphden.input.descriptor-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.context :as context]
    [graphden.input.core :as input]
    [graphden.input.descriptor :as descriptor]
    [graphden.packages.records.ids :as ids]))


(defn- fixture
  []
  (let [[owner person root name-field left-field right-field alias] (repeatedly 7 random-uuid)
        text-id (get (ids/primitive-fn-ids) :text)
        member (fn [id] {:id (random-uuid) :slot-id id :position 0})]
    {:owner owner :person person :root root :alias alias
     :name-field name-field :left-field left-field :right-field right-field
     :context (context/build-context
                {:fns {owner {:id owner :input-type-fn-id root}}
                 :fields {name-field {:id name-field :name "name" :type-fn-id text-id :required true}
                          left-field {:id left-field :name "left" :type-fn-id alias :required true}
                          right-field {:id right-field :name "right" :type-fn-id person}}
                 :members {person [(member name-field)]
                           root [(member left-field) (member right-field)]}
                 :type-bodies {alias :test/Person}
                 :aliases {:test/Person {:name :text}}
                 :alias-ids {:test/Person person}})}))


(deftest aliases-preserve-shared-field-identities-in-distinct-paths-test
  (let [{:keys [owner context name-field left-field right-field]} (fixture)
        contract (descriptor/resolve-contract context owner)
        left (input/resolve-address contract [owner []] [:left :name])
        right (input/resolve-address contract [owner []] [:right :name])]
    (is (= [left-field name-field] (:path left)))
    (is (= [right-field name-field] (:path right)))
    (is (= :text (:type left)))
    (is (= [[left-field name-field]] (:missing (input/remaining-input contract {} [owner []]))))))


(deftest wrapped-records-retain-their-whole-value-obligations-test
  (let [{:keys [context alias name-field]} (fixture)
        rich [:secret [:refine :test/Person [:present :name]]]
        ctx (assoc-in context [:type-bodies alias] rich)
        resolved (descriptor/resolve-descriptor ctx alias)]
    (is (= [:secret [:refine {:name :text} [:present :name]]] (:type resolved)))
    (is (= [name-field] (mapv :id (:fields resolved))))))


(deftest dynamic-maps-and-unions-do-not-become-record-field-maps-test
  (let [dictionary (random-uuid) union (random-uuid) owner (random-uuid)
        ctx (context/build-context
              {:type-bodies {dictionary [:map :keyword :text]
                             union [:union {:x :text} {:x :int}]}
               :fns {owner {:input-type-fn-id union}}})]
    (is (not (contains? (descriptor/resolve-descriptor ctx dictionary) :fields)))
    (is (= #{{:x :text} {:x :int}}
           (set (:alternatives (descriptor/resolve-descriptor ctx union)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
          (descriptor/resolve-contract ctx owner)))))


(deftest unknown-or-recursive-types-never-degrade-to-any-test
  (let [id (random-uuid)]
    (doseq [snapshot [{:type-bodies {id :Missing}}
                      {:type-bodies {id {:field :text}}}
                      {:fns {id {:base-fn-id id}}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
            (descriptor/resolve-descriptor (context/build-context snapshot) id))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"recursive"
          (descriptor/resolve-descriptor
            (context/build-context {:type-bodies {id :Loop} :aliases {:Loop [:list :Loop]}}) id)))))


(deftest contexts-require-frozen-maps-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
        (context/build-context {:aliases (atom {})}))))


(deftest executable-rows-never-supply-record-memberships-test
  (let [{:keys [context owner root]} (fixture)]
    (doseq [row [{:return-type-fn-id (get (ids/primitive-fn-ids) :text)}
                 {:parent-ids [(random-uuid)]}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"could not resolve"
            (descriptor/resolve-contract (assoc-in context [:fns root] row) owner))))))


(deftest source-and-storage-requiredness-have-explicit-adapter-policies-test
  (let [{:keys [context owner right-field]} (fixture)
        right-required (fn [ctx]
                         (->> (descriptor/resolve-contract ctx owner) :fields
                              (filter #(= right-field (:id %))) first :required))]
    (is (false? (right-required context)))
    (is (true? (right-required (assoc context :field-default-required? true))))
    (is (false? (right-required (-> context (assoc :field-default-required? true)
                                    (assoc-in [:fields right-field :required] nil)))))))
