(ns graphden.types.stored-contracts-test
  "Cold registry, type API and layout projections of stored execution contracts."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities.seq :as sequence]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry.core :as registry]
    [graphden.layout.bindings :as bindings]
    [graphden.layout.data :as data]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.types :as records-types]
    [graphden.types.check.execution :as execution]
    [graphden.types.core :as types]))


(use-fixtures :each
  (fn [t]
    (binding [types/*type-aliases-override* (atom {})
              registry/*rich-types-override* (atom {:by-id {} :by-name {}})
              cr/*per-org-aliases-override* (atom {})]
      (t))))


(def ^:private callback [:fn {} :int #{} #{:browser-main}])


(defn- graph-of
  [definitions]
  (let [rows (parse/parse-module definitions)
        grouped (group-by :kind rows)]
    {:fns (into (mapv (fn [primitive]
                        {:id (ids/primitive-fn-id primitive)
                         :name (name primitive) :parent-ids []}) ids/primitive-names)
                (:fn grouped))
     :slots (:slot grouped) :fn-slots (:fn-slot grouped)
     :bindings (:binding grouped) :list-items (:binding-list-item grouped)}))


(deftest cold-registry-and-type-api-retain-anonymous-contract-fields
  (let [collection [:list callback]
        mapped [:map :keyword callback]
        graph (graph-of [{:name :holder :namespace "user" :type {:handlers collection}}
                         {:name :handler-list :namespace "user" :list mapped}
                         {:name :handler-map :namespace "user" :map {:key :keyword :value callback}}
                         {:name :refined :namespace "user"
                          :refine {:base collection :constraint [:not= []]}}])
        ctx {:graph-cache (atom graph)}]
    (cr/register-type-aliases-from-db! graph ::cold {"user" "user"})
    (is (= {:handlers collection} (types/resolve-alias :user/holder)))
    (is (= [:list mapped] (types/resolve-alias :user/handler-list)))
    (is (= mapped (types/resolve-alias :user/handler-map)))
    (is (= [:refine collection [:not= []]] (types/resolve-alias :user/refined)))
    (is (= {:handlers collection}
           (get-in (types-api/rich-types-with-type-rows ctx) [:holder :return])))))


(deftest collection-layout-kind-and-sequence-editing-share-the-stored-shape
  (doseq [[shape kind] [[[:list callback] :sequence]
                        [[:map :keyword callback] :jsonb]]]
    (let [graph (graph-of [{:name :source :namespace "user" :args {:handlers {:type shape}}}
                           {:name :renamed :namespace "user" :parent :source
                            :args {:handlers {:as :events :type shape}}}])
          owner (ids/fn-id "user" :source)
          renamed (ids/fn-id "user" :renamed)
          slot-id (ids/slot-id owner :handlers)
          views (data/derive-fn-slot-views graph)
          source-row (first (filter #(and (= owner (:fn-id %)) (= slot-id (:slot-id %))) views))
          renamed-row (first (filter #(and (= renamed (:fn-id %)) (= "events" (:name %))) views))
          binding (sequence/find-sequence-binding {:graph-cache (atom graph)} renamed)]
      (is (= kind (:type source-row)))
      (is (= kind (:type renamed-row)))
      (is (= (= :sequence kind) (bindings/sequence-anchor? renamed-row)))
      (if (= :sequence kind)
        (do (is (= {:fn-id renamed :slot-id slot-id} (select-keys binding [:fn-id :slot-id])))
            (is (= (first (filter #(= renamed (:fn-id %)) (:bindings graph))) binding)))
        (is (nil? binding))))))


(deftest missing-or-cyclic-type-identities-carry-no-browser-proof
  (let [cycle (random-uuid)
        index {:fn-by-id {cycle {:id cycle :element-fn-id cycle}}}
        shape (records-types/stored-type-ref cycle index)]
    (is (= [:list :jsonb] shape))
    (is (= :jsonb (records-types/stored-type-ref (random-uuid) index)))
    (is (false? (execution/claim-compatible? shape [:list callback])))))


(deftest cold-named-self-references-retain-their-identity
  (let [graph (graph-of [{:name :node :namespace "user" :type {:next :node}}
                         {:name :node-list :namespace "user" :list :node-list}])
        ctx {:graph-cache (atom graph)}]
    (cr/register-type-aliases-from-db! graph ::recursive {"user" "user"})
    (is (= {:next :user/node} (get (types/aliases-snapshot) :user/node)))
    (is (= [:list :user/node-list] (get (types/aliases-snapshot) :user/node-list)))
    (is (= {:next :node} (get-in (types-api/rich-types-with-type-rows ctx) [:node :return])))
    (is (= [:list :node-list] (get-in (types-api/rich-types-with-type-rows ctx) [:node-list :return])))
    (is (false? (execution/claim-compatible? (types/resolve-alias :user/node-list) [:list callback])))))


(deftest legacy-collection-fields-keep-their-primitive-contracts-after-cold-registration
  (doseq [[shape primitive] [[[:list :int] :sequence]
                             [[:list [:fn {} :int]] :sequence]
                             [[:map :keyword [:fn {} :int #{:db}]] :jsonb]]]
    (let [graph (graph-of [{:name :legacy-holder :namespace "user" :type {:handlers shape}}])]
      (cr/register-type-aliases-from-db! graph ::legacy {"user" "user"})
      (is (= {:handlers primitive} (types/resolve-alias :user/legacy-holder))))))
