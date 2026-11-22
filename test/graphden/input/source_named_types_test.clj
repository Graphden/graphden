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
        resolve (named-types/lookup package entries)
        list-id (ids/fn-id "app.ui-inspector" :_inspector-request-list)
        element-id (ids/fn-id "app.ui-inspector" :_inspector-request)
        {:keys [body row rows]} (resolve :_inspector-request-list)
        frozen (named-types/evidence {:root {:source {:return-type :_inspector-request-list}}} resolve)
        grouped (group-by :kind (mapcat val (:type-rows frozen)))
        ctx (context/build-context (assoc frozen :fields (into {} (map (juxt :id identity)) (:slot grouped))
                                          :members (group-by :fn-id (:fn-slot grouped))))
        type (:type (descriptor/resolve-descriptor ctx list-id))]
    (is (= [:list :_inspector-request] body))
    (is (= element-id (:element-fn-id row)))
    (is (= [(ids/slot-id list-id :items)] (mapv :id (filter #(= :slot (:kind %)) rows))))
    (is (= [:list {:kind :text :tab :text}] type))
    (is (= {:namespace "app.ui-inspector" :name :_inspector-request-list :list :_inspector-request}
           (:origin (resolve :_inspector-request-list))))))


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
