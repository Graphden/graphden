(ns graphden.executor.compile.surface-test
  "Public argument identity across independent renamed calls."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.surface :as surface]
    [graphden.packages.records :as records]
    [graphden.packages.records.types :as record-types]))


(defn- parsed-fixture
  [definitions]
  (let [ids (into {} (map (fn [d] [(:name d) (records/fn-id (:namespace d) (:name d))])) definitions)
        by-name (into {} (map (juxt :name identity)) definitions)
        rows (group-by :kind (concat (records/boot-primitive-records)
                                     ;; parse-fn-def leaves the module structural-type
                                     ;; pass to its caller; HOF lookups need these rows.
                                     (mapcat record-types/inline-fn-type-rows-from-fn-def definitions)
                                     (mapcat #(records/parse-fn-def % ids by-name) definitions)))
        lookup (lookups/build-lookups {:fns (:fn rows) :slots (:slot rows) :fn-slots (:fn-slot rows)
                                       :bindings (:binding rows) :list-items (:binding-list-item rows)})]
    {:ids ids :lookup lookup}))


(defn- fixture
  []
  (parsed-fixture
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
      :args {:outer {:value {:value 11}}}}]))


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


(defn- closure-fixture
  []
  (parsed-fixture
    [{:name :surface-spawn :args {:body {:type [:fn {} :any]}} :return-type :null}
     {:name :surface-invoke :args {:func {:type [:fn {:item :any} :any]}} :return-type :any}
     {:name :surface-worker
      :args {:item {:type :any} :setting {:type :text}
             :optional {:type :text :required false}}
      :return-type :any}
     {:name :surface-loop :parent :surface-invoke
      :args {:func {:as :task :type [:fn {:item :any} :any]}}}
     {:name :surface-template :parent :surface-spawn
      :args {:body :surface-loop :task :surface-worker}}
     {:name :surface-child :parent :surface-template}
     {:name :surface-bound :parent :surface-child :args {:setting "configured"}}
     {:name :surface-local :parent :surface-worker
      :args {:setting "local" :optional "local"}}
     {:name :surface-closed :parent :surface-spawn
      :args {:body :surface-loop :task :surface-local}}
     {:name :surface-cycle :parent :surface-spawn :args {:body :surface-cycle}}
     {:name :surface-env-cycle :parent :surface-spawn
      :args {:body :surface-loop :task :surface-env-cycle}}]))


(deftest env-callables-expose-captures-without-exposing-call-site-parameters
  (let [{:keys [ids lookup]} (closure-fixture)
        entries (surface/public-free-entries (:surface-child ids) lookup)]
    (is (contains? (:fn-typed-fn-ids lookup)
                   (:type-fn-id (get (:slot-by-fn-name lookup) [(:surface-spawn ids) :body]))))
    (is (= #{:setting :optional} (set (map :ext-name entries))))
    (is (every? :captured? entries))
    (is (= {:setting false :optional true}
           (into {} (map (juxt :ext-name :optional?)) entries)))
    (is (= #{:optional}
           (set (map :ext-name (surface/public-free-entries (:surface-bound ids) lookup)))))
    (is (= [] (surface/public-free-entries (:surface-closed ids) lookup))
        "A callback's locally bound values never become caller inputs")
    (is (= [] (surface/public-free-entries (:surface-cycle ids) lookup))
        "Recursive callable references terminate without inventing arguments")
    (is (= [] (surface/public-free-entries (:surface-env-cycle ids) lookup))
        "An env callback referring to its enclosing graph also terminates")))


(deftest a-rename-cannot-relax-the-required-source-input
  (let [{:keys [ids lookup]} (fixture)
        [entry] (surface/public-free-entries (:renamed-child ids) lookup)]
    (is (= :outer (:ext-name entry)))
    (is (false? (:optional? entry)))
    (is (not= (:slot-id entry)
              (:id (get (:slot-by-fn-name lookup) [(:get ids) :coll]))))
    (is (= [] (surface/public-free-entries (:renamed-bound ids) lookup)))))
