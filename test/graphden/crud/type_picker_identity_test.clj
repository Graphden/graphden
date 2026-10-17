(ns graphden.crud.type-picker-identity-test
  "Picker enumeration and its shared storage resolver keep namespace identity."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.type-check :as tc]
    [graphden.crud.types-api :as ta]
    [graphden.executor.compile-runtime :as cr]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.sync :as sync]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tctx]
    [graphden.types.core :as types]))


(defn- fixture-graph
  []
  (let [a (random-uuid) b (random-uuid) v (random-uuid)
        int-id (random-uuid) text-id (random-uuid)
        root (random-uuid) ai (random-uuid) bi (random-uuid) vi (random-uuid)
        ar (random-uuid) br (random-uuid) rr (random-uuid)
        tree (random-uuid) other-tree (random-uuid)
        slots (mapv (fn [owner target]
                      {:id (random-uuid) :name "n" :owner owner :type-fn-id target})
                    [ar br rr tree other-tree] [int-id text-id ar tree int-id])]
    {:namespaces [{:id a :name "a"} {:id b :name "b"}
                  {:id v :name "lib@1-0-0"}]
     :fns [{:id int-id :name "int"} {:id text-id :name "text"}
           {:id root :name "_local" :base-fn-id int-id :constraint [:>= 0]}
           {:id ai :name "_local" :namespace-id a :base-fn-id int-id :constraint [:>= 1]}
           {:id bi :name "_local" :namespace-id b :base-fn-id text-id :constraint [:not= ""]}
           {:id vi :name "_local" :namespace-id v :base-fn-id int-id :constraint [:>= 2]}
           {:id ar :name "_inputs" :namespace-id a}
           {:id br :name "_inputs" :namespace-id b}
           {:id rr :name "unique-root-record"}
           {:id tree :name "_tree"} {:id other-tree :name "_tree" :namespace-id b}]
     :slots (mapv #(dissoc % :owner) slots)
     :fn-slots (mapv (fn [s] {:fn-id (:owner s) :slot-id (:id s) :position 0}) slots)
     :bindings [] :list-items []}))


(defn- graph-storage
  [graph]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify sp/StorageCRUD
    (query-entities
      [_ entity where]
      (filterv (fn [row] (every? (fn [[k v]] (= v (get row k))) where))
               (get graph (case entity :fn :fns :ns :namespaces))))))


(deftest canonical-picker-types-and-qualified-storage-resolution
  (let [graph (fixture-graph)
        storage (graph-storage graph)
        context {:storage storage :graph-cache (atom graph)}
        paths (into {} (map (juxt :id :name)) (:namespaces graph))]
    (binding [types/*type-aliases-override* (atom {})
              cr/*per-org-aliases-override* (atom {})]
      (cr/register-type-aliases-from-db! graph ::picker paths)
      (testing "semantic checks use precise aliases, while unique spelling and primitives survive"
        (let [names (into #{} (map :name) (ta/compatible-type-names context :int))]
          (is (contains? names "int"))
          (is (contains? names "/_local"))
          (is (contains? names "a/_local"))
          (is (contains? names "lib@1-0-0/_local"))
          (is (not (contains? names "b/_local")))
          (is (not (contains? names "text")))))
      (testing "record twins do not use the last bare registration"
        (let [names (into #{} (map :name) (ta/compatible-type-names context {:n :int}))]
          (is (contains? names "a/_inputs"))
          (is (not (contains? names "b/_inputs"))))
        (is (= {:n {:n :int}} (types/resolve-alias (keyword "" "unique-root-record"))))
        (is (= {:n (keyword "" "_tree")}
               (types/resolve-alias (keyword "" "_tree"))))
        (is (= {:n :int} (types/resolve-alias :b/_tree))))
      (testing "both picker consumers resolve qualified values to the exact native UUID"
        (doseq [spelling ["/_local" "a/_local" "b/_local" "lib@1-0-0/_local"]]
          (let [row (lookup/query-fn-by-name storage spelling)]
            (is (some? row))
            (is (= (:id row) (tc/resolve-type-fn-id storage spelling)))))
        (is (nil? (lookup/query-fn-by-name storage "missing/_local")))
        (is (thrown? clojure.lang.ExceptionInfo
              (tc/resolve-type-fn-id storage "missing/_local")))
        (is (= "unique-root-record"
               (:name (lookup/query-fn-by-name storage "unique-root-record")))))
      (testing "namespace read filtering happens before enumeration"
        (binding [tctx/*seams-override*
                  (atom {:graph-read-filter-fn
                         #(update % :fns (fn [rows] (filterv (fn [r] (nil? (:namespace-id r))) rows)))})]
          (let [names (into #{} (map :name) (ta/type-name-kinds context))]
            (is (contains? names "_local"))
            (is (not (contains? names "a/_local")))))))))


(deftest qualified-lookup-preserves-explicit-error-policy
  (let [failure (ex-info "namespace denied" {:type :authz/forbidden})
        storage #_{:clj-kondo/ignore [:missing-protocol-method]}
        (reify sp/StorageCRUD
          (query-entities [_ _ _] (throw failure)))]
    (is (nil? (lookup/query-fn-by-name storage "a/_local" true)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"namespace denied"
          (lookup/query-fn-by-name storage "a/_local")))))


(deftest package-declarations-survive-rebuild-by-exact-owner
  (let [nm (keyword (str "_package-type-" (random-uuid)))
        definitions [{:name nm :type {:n [:union :int :null]}}
                     {:name nm :namespace "pkg@1-0-0" :type {:n [:list :text]}}
                     {:name nm :namespace "pkg" :type {:n :bool}}]
        owner-ids (mapv #(ids/fn-id (:namespace %) nm) definitions)
        ns-version (random-uuid) ns-ordinary (random-uuid)
        int-id (random-uuid)
        slots (mapv (fn [_] {:id (random-uuid) :name "n" :type-fn-id int-id}) definitions)
        graph {:fns (into [{:id int-id :name "int"}]
                          (map (fn [id ns-id] {:id id :name (name nm) :namespace-id ns-id})
                               owner-ids [nil ns-version ns-ordinary]))
               :slots slots
               :fn-slots (mapv (fn [id slot] {:fn-id id :slot-id (:id slot) :position 0})
                               owner-ids slots)}
        canonical [(keyword "" (name nm)) (keyword "pkg@1-0-0" (name nm))
                   (keyword "pkg" (name nm))]]
    (binding [types/*type-aliases-override* (atom {})
              cr/*per-org-aliases-override* (atom {})]
      (sync/register-type-aliases! (reverse definitions))
      (doseq [[ref definition] (map vector canonical definitions)]
        (is (= (:type definition) (types/resolve-alias ref))))
      (owned/record-owned-ids! owner-ids)
      (cr/register-type-aliases-from-db! graph ::declared-owner
                                         {ns-version "pkg@1-0-0" ns-ordinary "pkg"})
      (doseq [[ref definition id] (map vector canonical definitions owner-ids)]
        (is (= (:type definition) (types/resolve-alias ref)))
        (is (= (:type definition) (types/package-alias-body id)))))))
