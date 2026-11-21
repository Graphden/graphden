(ns graphden.input.source-forward-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.source :as source]
    [graphden.input.source-forward :as forward]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- fixture
  [args]
  (let [forms [{:name :combine :args {:left {:type :any} :right {:type :any}} :return-type :any}
               {:name :encode :args {:data {:type :text :required true}} :return-type :text}
               {:name :encode-body :parent :encode}
               {:name :response :parent :combine :args args}]
        declarations (mapv #(assoc % :namespace "fixture") forms)
        prepared (parse/prepare-module declarations {} {})]
    [prepared (last (:definitions (source/plan-module declarations prepared)))]))


(defn- rejection
  [prepared entry]
  (try
    (forward/verify-forwarded-field prepared entry :data)
    (catch clojure.lang.ExceptionInfo failure (:code (ex-data failure)))))


(deftest forwarded-field-keeps-its-own-id-and-the-exact-reference-use
  (let [[prepared entry] (fixture {:left :encode-body :data {:type :text}})
        owner (ids/fn-id "fixture" :response)
        source-id (ids/slot-id owner :data)
        target-id (ids/slot-id (ids/fn-id "fixture" :encode) :data)
        edge (ids/binding-id owner (ids/slot-id (ids/fn-id "fixture" :combine) :left))
        proof (forward/verify-forwarded-field prepared entry :data)
        tampered (update-in entry [:legacy :arguments]
                            (fn [arguments]
                              (mapv #(cond-> % (:source-slot-id %)
                                             (assoc :source-slot-id source-id)) arguments)))]
    (is (= :blocked (:status entry)))
    (is (= source-id (:field-id proof)))
    (is (= (ids/fn-slot-id owner source-id) (:membership-id proof)))
    (is (= (ids/fn-id "fixture" :encode-body) (:target-fn-id proof)))
    (is (= [:text :text] ((juxt :source-type :target-type) proof)))
    (is (= [[owner []] [source-id]] (:source-address proof)))
    (is (= [[owner [edge]] [target-id]] (:target-address proof)))
    (is (= proof (forward/verify-forwarded-field prepared entry :data)))
    (is (= :unproved-field-origin (rejection prepared tampered)))))


(deftest forwarding-does-not-conflate-two-uses-of-one-function
  (let [[prepared entry] (fixture {:left :encode-body :right :encode-body :data {:type :text}})]
    (is (= :nonunique-reference-use (rejection prepared entry)))))
