(ns graphden.input.source-named-types-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.source :as source]
    [graphden.input.source-named-types :as named-types]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.types.core :as types]))


(defn- declarations
  [file names]
  (let [{module-ns :namespace :keys [fns]} (edn/read-string (slurp (io/resource file)))]
    (into [] (comp (filter #(names (:name %))) (map #(assoc % :namespace module-ns))) fns)))


(defn- package-entries
  [definitions]
  (let [prepared (parse/prepare-module definitions {} {})
        plan (source/verify-module (source/plan-module definitions prepared) prepared)]
    [{:prepared prepared :declarations (into {} (map (juxt :fn-id identity)) (:fn-defs prepared))}
     (into {} (map (juxt :identity identity)) (:definitions plan))]))


(defn- ring-types
  []
  (package-entries
    (into (declarations "packages/core/refinements/fns.edn" #{:http-status :text-map})
          (declarations "packages/core/system/fns.edn" #{:ring-response-shape}))))


(deftest shipped-ring-record-freezes-its-nested-named-types
  (let [[package entries] (ring-types)
        roots {:root {:source {:return-type :ring-response-shape}}}
        {:keys [aliases alias-ids] :as frozen} (named-types/evidence roots (named-types/lookup package entries))
        types (context/call-with-types (context/build-context frozen) #(types/resolve-alias :ring-response-shape))
        status-id (ids/fn-id "core.refinements" :http-status)
        shape-id (ids/fn-id "core.system" :ring-response-shape)]
    (is (= #{:ring-response-shape :http-status :text-map} (set (keys aliases))))
    (is (= status-id (:http-status alias-ids)))
    (is (= shape-id (:ring-response-shape alias-ids)))
    (is (= {:status [:refine :int [:and [:>= 100] [:<= 599]]] :headers [:map :text :text] :body :any} types))
    (let [changed (assoc-in entries [status-id :v2 :declaration-records 0 :constraint] [:>= 0])]
      (is (thrown? clojure.lang.ExceptionInfo (named-types/evidence roots (named-types/lookup package changed)))))))


(deftest shipped-named-list-retains-element-and-items-row-identities
  (let [[package entries] (package-entries
                            (declarations "packages/app/ui-inspector/fns.edn" #{:_inspector-request :_inspector-request-list}))
        lookup-type (named-types/lookup package entries)
        list-id (ids/fn-id "app.ui-inspector" :_inspector-request-list)
        element-id (ids/fn-id "app.ui-inspector" :_inspector-request)
        {:keys [body row rows]} (lookup-type :_inspector-request-list)
        frozen (named-types/evidence {:root {:source {:return-type :_inspector-request-list}}} lookup-type)
        grouped (group-by :kind (mapcat val (:type-rows frozen)))
        ctx (context/build-context (assoc frozen :fields (into {} (map (juxt :id identity)) (:slot grouped))
                                          :members (group-by :fn-id (:fn-slot grouped))))
        type (:type (descriptor/resolve-descriptor ctx list-id))]
    (is (= [:list :_inspector-request] body))
    (is (= element-id (:element-fn-id row)))
    (is (= [(ids/slot-id list-id :items)] (mapv :id (filter #(= :slot (:kind %)) rows))))
    (is (= [:list {:kind :text :tab :text}] type))
    (is (= {:namespace "app.ui-inspector" :name :_inspector-request-list :list :_inspector-request}
           (:origin (lookup-type :_inspector-request-list))))))


(deftest named-lists-resolve-elements-within-their-original-namespace
  (let [[package entries] (package-entries
                            [{:namespace "left" :name :row :type {:id :uuid}}
                             {:namespace "right" :name :row :type {:id :text}}
                             {:namespace "left" :name :rows :list :row}
                             {:namespace "right" :name :rows :list :row}])
        frozen (named-types/evidence {:root {:source {:return-type [:union :left/rows :right/rows]}}}
                                     (named-types/lookup package entries))
        resolved (context/call-with-types (context/build-context frozen)
                                          #(mapv types/resolve-alias [:left/rows :right/rows]))]
    (is (= [[:list {:id :uuid}] [:list {:id :text}]] resolved))
    (is (= [(ids/fn-id "left" :row) (ids/fn-id "right" :row)]
           (mapv #(get-in frozen [:fns (ids/fn-id % :rows) :element-fn-id]) ["left" "right"])))))


(deftest named-list-evidence-refuses-changed-element-membership-and-unverified-origin
  (let [[package entries] (package-entries [{:namespace "fixture" :name :ids :list :uuid}])
        id (ids/fn-id "fixture" :ids)
        row-path [id :v2 :declaration-records]
        changes [(assoc-in entries (conj row-path 0 :element-fn-id) (ids/primitive-fn-id :text))
                 (assoc-in entries (conj row-path 1 :required) false)
                 (assoc-in entries (conj row-path 2 :fn-id) (random-uuid))
                 (update-in entries row-path conj (get-in entries (conj row-path 1)))
                 (assoc-in entries [id :source :list] :text)
                 (assoc-in entries [id :verification] nil)]]
    (doseq [changed changes]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"matching source and row evidence"
            ((named-types/lookup package changed) :fixture/ids)))))
  (let [[package entries] (package-entries [{:namespace "fixture" :name :producer :args {} :return-type :uuid}
                                            {:namespace "fixture" :name :ids :list :producer}])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"matching source and row evidence"
          ((named-types/lookup package entries) :fixture/ids)))))


(deftest named-union-members-keep-their-declaring-namespace-and-stored-constraint
  (let [[package entries] (package-entries
                            [{:namespace "left" :name :row :map {:key :keyword :value :text}}
                             {:namespace "right" :name :row :map {:key :keyword :value :int}}
                             {:namespace "left" :name :result :union [:null :row]}
                             {:namespace "right" :name :result :union [:null :row]}])
        roots {:root {:source {:return-type [:union :left/result :right/result]}}}
        id (ids/fn-id "left" :result)
        frozen (named-types/evidence roots (named-types/lookup package entries))
        ctx (context/build-context frozen)
        resolved (context/call-with-types ctx #(mapv types/resolve-alias [:left/result :right/result]))
        check (named-types/checked-expression ctx (constantly {:status :checked}))]
    (is (= [:union :null :left/row] (get-in frozen [:fns id :constraint])))
    (is (= [:null :row] (get-in frozen [:type-origins id :union])))
    (is (= [(ids/fn-id "left" :row) (ids/fn-id "right" :row)]
           (mapv (:alias-ids frozen) [:left/row :right/row])))
    (is (= [[:union :null [:map :keyword :text]] [:union :null [:map :keyword :int]]] resolved))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match"
          (context/call-with-types ctx #(check {:expected :left/result :actual :right/row
                                                :expression {:kind :value :value {:a 5}}}))))
    (doseq [changed [(assoc-in entries [id :v2 :declaration-records 0 :constraint 2] :right/row)
                     (assoc-in entries [(ids/fn-id "right" :row) :verification] nil)]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"matching source and row evidence"
            (named-types/evidence roots (named-types/lookup package changed)))))))


(deftest named-union-evidence-refuses-recursive-expansion
  (let [[package entries] (package-entries [{:namespace "fixture" :name :one :union [:null :two]}
                                            {:namespace "fixture" :name :two :union [:text :one]}])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"recursive or exceeds"
          (named-types/evidence {:root {:source {:return-type :one}}}
                                (named-types/lookup package entries))))))


(deftest shipped-request-retains-its-erased-union-and-field-identities
  (let [[package entries] (package-entries
                            (into (declarations "packages/core/refinements/fns.edn" #{:http-status :text-map})
                                  (concat (declarations "packages/core/system/fns.edn" #{:ring-response-shape})
                                          (declarations "packages/web/ring-adapter/fns.edn" #{:ring-request-shape}))))
        type [:fn {:arg :ring-request-shape} :ring-response-shape]
        frozen (named-types/evidence {:root {:contract {:fields [{:type type}]}}} (named-types/lookup package entries))
        id (ids/fn-id "web.ring-adapter" :ring-request-shape)
        field-id (ids/slot-id id :body)
        grouped (group-by :kind (mapcat val (:type-rows frozen)))
        ctx (context/build-context (assoc frozen :fields (into {} (map (juxt :id identity)) (:slot grouped))
                                          :members (group-by :fn-id (:fn-slot grouped))))
        contract (descriptor/resolve-descriptor ctx id)
        body (descriptor/find-node contract [field-id])]
    (is (= #{:ring-request-shape :ring-response-shape :http-status :text-map} (set (keys (:aliases frozen)))))
    (is (= (ids/primitive-fn-id :any) (:type-id body)))
    (is (= (types/resolve-alias [:union :null :input-stream :text :jsonb]) (:type body)))
    (is (= [:union :null :input-stream :text :jsonb]
           (get-in frozen [:source-field-types [field-id (ids/primitive-fn-id :any)]])))
    (is (true? (:required body)))
    (let [changed (update-in entries [id :v2 :declaration-records]
                             (fn [rows]
                               (mapv #(cond-> % (= field-id (:id %))
                                              (assoc :type-fn-id (ids/primitive-fn-id :text))) rows)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"matching source and row evidence"
            (named-types/evidence {:root {:contract {:fields [{:type type}]}}} (named-types/lookup package changed)))))))


(deftest transient-primitive-is-not-an-erased-standalone-storage-kind
  (is (false? (descriptor/stored-source-type? :input-stream (ids/primitive-fn-id :any))))
  (is (descriptor/stored-source-type? [:union :null :input-stream :text :jsonb] (ids/primitive-fn-id :any)))
  (is (false? (descriptor/stored-source-type? [:union :null :unknown-native] (ids/primitive-fn-id :any)))))


(deftest named-open-list-refinement-keeps-the-source-constraint-and-slot
  (let [source (first (declarations "packages/web/html/fns.edn" #{:style-tag}))
        [package entries] (package-entries [source])
        owner (ids/fn-id "web.html" :style-tag)
        lookup #(get ((named-types/lookup package %) :style-tag) :body)
        err (fn [entries]
              (try (lookup entries) nil
                   (catch clojure.lang.ExceptionInfo failure (:code (ex-data failure)))))]
    (is (= [:refine [:list :any] [:elem 0 [:= "style"]]] (lookup entries)))
    (doseq [mutate [(fn [row] (cond-> row (= :slot (:kind row)) (assoc :type-fn-id (ids/primitive-fn-id :text))))
                    (fn [row] (cond-> row (= owner (:id row)) (assoc :constraint [:elem 0 [:= "script"]])))]]
      (is (= :named-refinement-evidence-required
             (err (update-in entries [owner :v2 :declaration-records] (partial mapv mutate))))))
    (let [[typed-package typed-entries] (package-entries [(assoc-in source [:refine :base] [:list :int])])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"matching source and row evidence"
            ((named-types/lookup typed-package typed-entries) :style-tag))))))


(deftest named-unconstrained-refinement-uses-the-package-alias-body
  (let [source (declarations "packages/core/refinements/fns.edn" #{:js-source})
        [package entries] (package-entries source)
        id (ids/fn-id "core.refinements" :js-source)
        lookup (fn [entries] ((named-types/lookup package entries) :js-source))]
    (is (= [:refine :text [:any]] (:body (lookup entries))))
    (is (= id (:id (lookup entries))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"matching source and row evidence"
          (lookup (assoc-in entries [id :v2 :declaration-records 0 :constraint] [:any]))))))
