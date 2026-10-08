(ns ^:integration graphden.executor.configuration-composition-test
  "Ordinary stored graphs construct configured callables without a partial
   primitive. The diagnostic query has SQL's arguments/effects, but no network."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-eager :as ce]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as context]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.test-infra.golden-app :as ga]
    [graphden.types.check :as check]
    [graphden.types.core :as types]
    [graphden.versioning.branch-local :as branch-local]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (ga/fixture (ns-name *ns*) ["core" "web"]))


(def ^:dynamic *query-calls* nil)


(defbase diagnostic-query
  [url user password sql params]
  (cr/record-effect! :db)
  (cr/record-effect! :network)
  (let [row {:url url :user user :sql sql :params (vec params)
             :authenticated (= password (str "fixture-" user "-secret"))}]
    (swap! *query-calls* conj row)
    [row]))


(def ^:private query-type
  [:fn {:request {:sql :text :params [:list :jsonb]}} [:secret [:list :jsonb]] #{:db :network}])


(defn- definitions
  []
  [{:name :cfg-diagnostic-query
    :args {:url {:type :text} :user {:type :text}
           :password {:type [:secret :text]} :sql {:type :text}
           :params {:type [:list :jsonb]}}
    :return-type [:secret [:list :jsonb]] :effects #{:db :network}}
   {:name :cfg-prod-secret :parent :secret-leaf
    :args {:in {:value "fixture-prod-secret"}}}
   {:name :cfg-test-secret :parent :secret-leaf
    :args {:in {:value "fixture-test-secret"}}}
   {:name :cfg-input-sql :parent :get
    :args {:coll {:as :request :type {:sql :text :params [:list :jsonb]}}
           :key {:value :sql} :default nil}
    :return-type :text}
   {:name :cfg-input-params :parent :get
    :args {:coll {:as :request :type {:sql :text :params [:list :jsonb]}}
           :key {:value :params} :default nil}
    :return-type [:list :jsonb]}
   {:name :cfg-query-body :parent :cfg-diagnostic-query
    :args {:sql :cfg-input-sql :params :cfg-input-params}
    :lambda-params [:request]}
   ;; Declare the callable SLOT before binding it. A simultaneous
   ;; {:ref X :type T} asserts T about X's evaluated return, not its callable.
   {:name :cfg-query-constructor :parent :const
    :args {:value {:type query-type}}
    :return-type query-type}
   {:name :cfg-make-query :parent :cfg-query-constructor
    :args {:value :cfg-query-body}}
   {:name :cfg-prod-query :parent :cfg-make-query
    :args {:url "jdbc:fixture:prod" :user "prod" :password :cfg-prod-secret}}
   {:name :cfg-test-query :parent :cfg-make-query
    :args {:url "jdbc:fixture:test" :user "test" :password :cfg-test-secret}}
   {:name :cfg-request :parent :const
    :args {:value {:value {:sql "revision-one" :params [1]}}}}
   {:name :cfg-application :parent :call
    :args {:func {:as :query :type query-type} :arg :cfg-request}
    :lambda-params []
    :return-type [:secret [:list :jsonb]]}
   {:name :cfg-prod-run :parent :cfg-application :args {:query :cfg-prod-query}}
   {:name :cfg-test-run :parent :cfg-application :args {:query :cfg-test-query}}
   ;; Real service compositions, inspected but never started in this fixture.
   ;; The query result stays secret; it is not a public HTTP response body.
   {:name :cfg-prod-start :parent :interval
    :args {:every-ms 60000 :fn :cfg-application :query :cfg-prod-query}}
   {:name :cfg-test-start :parent :interval
    :args {:every-ms 1000 :fn :cfg-application :query :cfg-test-query}}])


(defn- fn-id
  [fn-name]
  (records/fn-id nil fn-name))


(defn- branch-context
  [storage]
  (context/create-context {:storage storage :base-fns (exec/get-default-registry)}))


(defn- prepared-graph
  [branch-name]
  (exec/register-base-fn! :cfg-diagnostic-query diagnostic-query)
  (let [base (:storage ga/*bootstrap*)
        storage (vs/switch-branch base (:id (vs/create-branch! base branch-name)))
        ctx (branch-context storage)
        defs (definitions)]
    ;; This helper parses the complete module, persists ordinary graph rows,
    ;; and publishes checked rich types. Do not accept its fault-tolerant
    ;; sweep alone: fail this characterization on any contract mismatch.
    (setup/sync-and-invalidate! ctx storage defs)
    (check/check-all-defs! defs)
    {:storage storage :ctx ctx}))


(deftest constructor-returns-independent-typed-callables-without-running-query
  (binding [*query-calls* (atom [])]
    (let [{:keys [ctx]} (prepared-graph "configuration-callables")
          prod (exec/execute ctx (fn-id :cfg-prod-query) {})
          test-query (exec/execute ctx (fn-id :cfg-test-query) {})]
      (testing "construction returns callable values and performs no query"
        (is (fn? prod))
        (is (fn? test-query))
        (is (= [] @*query-calls*))
        (is (= (fn-id :cfg-query-body) (:graphden.executor/fn-id (meta prod)))))
      (testing "configuration is closed while SQL and parameters remain call-time inputs"
        (is (= [{:url "jdbc:fixture:prod" :user "prod" :sql "first" :params [1]
                 :authenticated true}]
               (prod {:sql "first" :params [1]})))
        (is (= [{:url "jdbc:fixture:test" :user "test" :sql "second" :params [2]
                 :authenticated true}]
               (test-query {:sql "second" :params [2]})))
        (is (= "prod" (:user (first (prod {:sql "third" :params [3]})))))
        (is (= ["first" "second" "third"] (mapv :sql @*query-calls*)))
        (is (= "jdbc:fixture:prod"
               (:url (first (prod {:sql "extra-key" :params [] :url "must-not-reconfigure"}))))))
      (testing "the constructor and consumer retain structural types and effects"
        (is (= [:secret query-type] (:return (registry/rich-type-of :cfg-make-query))))
        (is (= query-type (types/callable-signature (:return (registry/rich-type-of :cfg-make-query)))))
        (is (= [:secret [:list :jsonb]] (:return (registry/rich-type-of :cfg-application))))
        (is (= {:url :text :user :text :password [:secret :text]}
               (:args (registry/rich-type-of :cfg-make-query))))
        (is (= #{:db :network} (:effects (registry/rich-type-of :cfg-prod-run))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)effect"
              (binding [cr/*allowed-effects* #{:db}]
                (test-query {:sql "forbidden" :params []})))))
      (testing "the old inline override remains a rejected attempt to strip secret taint"
        (let [error (try
                      (check/check-fn-def!
                        {:name :cfg-invalid-constructor :parent :const
                         :args {:value {:ref :cfg-query-body
                                        :type (assoc query-type 2 [:list :jsonb])}}})
                      nil
                      (catch clojure.lang.ExceptionInfo ex ex))]
          (is (= :bindings/type-override-strips-marker (:type (ex-data error))))))
      (testing "real graph invocation retains secret trace redaction"
        (let [trace (ce/new-path-trace {:capture-values? true})]
          (binding [cr/*path-trace* trace ce/*traced-fn-ids* (atom ce/trace-all)]
            (is (true? (:authenticated (first (exec/execute ctx (fn-id :cfg-prod-run) {}))))))
          (let [entries (:entries @trace)]
            (is (some #(= :secret (:hidden %)) entries))
            (is (not (str/includes? (pr-str entries) "fixture-prod-secret")))))))))


(defn- named-binding
  [storage owner arg-name]
  (let [slots (into {} (map (juxt :id :name)) (sp/query-entities storage :slot {}))]
    (some #(when (= arg-name (get slots (:slot-id %))) %)
          (sp/query-entities storage :binding {:fn-id (fn-id owner)}))))


(defn- root-refs
  [storage]
  (into {} (for [owner [:cfg-prod-start :cfg-test-start :cfg-prod-run :cfg-test-run]]
             [owner (:ref-fn-id (named-binding storage owner "query"))])))


(deftest repeated-shared-code-merge-preserves-prod-and-test-root-identities
  (binding [*query-calls* (atom [])]
    (let [{target :storage} (prepared-graph "configuration-production")
          source (vs/switch-branch target (:id (vs/create-branch! target "configuration-test")))
          original-refs (root-refs target)
          request-binding (named-binding source :cfg-request "value")
          prod-period (named-binding source :cfg-prod-start "every-ms")]
      (is (= {:cfg-prod-start (fn-id :cfg-prod-query)
              :cfg-test-start (fn-id :cfg-test-query)
              :cfg-prod-run (fn-id :cfg-prod-query)
              :cfg-test-run (fn-id :cfg-test-query)} original-refs))
      (testing "runtime roots and secret leaves retain their seeded protection"
        (doseq [owner [:cfg-prod-start :cfg-test-start :cfg-prod-secret :cfg-test-secret]]
          (is (true? (branch-local/effective-branch-local? (vs/unwrap target) (fn-id owner))))))
      (sp/update-entity source :binding (:id prod-period) {:value 250})
      (doseq [revision ["revision-two" "revision-three"]]
        (sp/update-entity source :binding (:id request-binding)
                          {:value {:sql revision :params [2]} :value-present true})
        (vs/merge-branch! target (vs/current-branch-id source))
        (let [ctx (branch-context target)
              prod (first (exec/execute ctx (fn-id :cfg-prod-run) {}))
              test-query (first (exec/execute ctx (fn-id :cfg-test-run) {}))]
          (is (= [revision revision] [(:sql prod) (:sql test-query)]))
          (is (= ["prod" "test"] [(:user prod) (:user test-query)]))
          (is (= original-refs (root-refs target)))
          (is (= 60000 (:value (named-binding target :cfg-prod-start "every-ms"))))
          (is (= 250 (:value (named-binding source :cfg-prod-start "every-ms")))))))))


(deftest the-same-constructor-works-with-production-sql-query
  (let [base (:storage ga/*bootstrap*)
        storage (vs/switch-branch base (:id (vs/create-branch! base "configuration-real-sql")))
        ctx (branch-context storage)
        helpers (filterv #(contains? #{:cfg-input-sql :cfg-input-params :cfg-query-constructor}
                                     (:name %))
                         (definitions))
        defs (into helpers
                   [{:name :cfg-real-query-body :parent :sql-query
                     :args {:sql :cfg-input-sql :params :cfg-input-params}
                     :lambda-params [:request]}
                    {:name :cfg-real-make-query :parent :cfg-query-constructor
                     :args {:value :cfg-real-query-body}}])
        {:keys [jdbc-url username password]} (pth/get-container-config ga/*container*)]
    (setup/sync-and-invalidate! ctx storage defs)
    (check/check-all-defs! defs)
    (testing "constructing a callable never attempts to open its JDBC URL"
      (is (fn? (exec/execute ctx (fn-id :cfg-real-make-query)
                             {:url "jdbc:not-a-driver" :user "unused" :password "unused"}))))
    (testing "invocation uses the existing PG fixture, with no new runtime or pool"
      (let [query (exec/execute ctx (fn-id :cfg-real-make-query)
                                {:url jdbc-url :user username :password password})]
        (is (= [{:label "parameter-one"}] (query {:sql "SELECT ?::text AS label" :params ["parameter-one"]})))
        (is (= [{:label "parameter-two"}] (query {:sql "SELECT ?::text AS label" :params ["parameter-two"]})))))))
