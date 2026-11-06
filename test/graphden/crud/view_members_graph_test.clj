(ns ^:serial graphden.crud.view-members-graph-test
  "Graph predicates over canonical concealed view facts, with native parity."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.entities.list :as entity-list]
    [graphden.crud.entities.views :as views]
    [graphden.executor.context :as context]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(defn- call
  [name args]
  (exec/execute-by-name harness/*context* name args))


(defn- cohort
  [rows namespaces definitions effects problems run]
  (let [env {:base {:fns rows :slots [] :fn-slots [] :bindings [] :list-items []}
             :roled-fns (delay rows) :namespaces (delay namespaces) :rev-index (delay {})}
        rich registry/rich-type-of-id
        query sp/query-entities
        ids (set (map :id rows))]
    (with-redefs [entity-list/filter-env (constantly env)
                  registry/rich-type-of-id
                  (fn
                    ([id] (if (ids id) {:effects (get effects id #{})} (rich id)))
                    ([id arg] (get-in (if (ids id) {:effects (get effects id #{})} (rich id)) [:args arg])))
                  sp/query-entities
                  (fn [storage entity where & options]
                    (if (= entity :service)
                      (mapv (fn [id] {:fn-id id}) (:services problems))
                      (apply query storage entity where options)))
                  tenancy/tenant-app-routes
                  (fn [_] (mapv (fn [id] {:handler-fn-id id}) (:apps problems)))]
      (with-redefs-fn
        {#'views/decoded-view-facts (constantly definitions)
         #'views/problem-members (fn [& _]
                                   {:failed (delay (set (:failed problems)))
                                    :lint (delay (set (:lint problems)))})}
        #(run env)))))


(deftest every-axis-and-reference-intersection-match-the-native-contract
  (let [[a b c d e anon unnamed n-app n-tools n-tests n-utils v-db v-tools v-empty v-cycle-a v-cycle-b v-unsupported v-unnamed missing]
        (repeatedly 19 random-uuid)
        rows [{:id a :name "alpha" :namespace-id n-app :role :fn :parent-ids []}
              {:id b :name "beta" :namespace-id n-tools :role :fn :parent-ids [a] :type-error-count 2}
              {:id c :name "gamma" :namespace-id n-tests :role :fn :parent-ids [b]}
              {:id d :name "Record" :namespace-id n-app :role :record :parent-ids []}
              {:id e :name "application" :namespace-id n-utils :role :fn :parent-ids []}
              {:id anon :name "_anon-hidden" :namespace-id n-app :role :fn :parent-ids [a]}
              {:id unnamed :name nil :namespace-id n-app :role :fn :parent-ids []}]
        namespaces [{:id n-app :name "app"} {:id n-tools :name "tools" :parent-id n-app}
                    {:id n-tests :name "tests" :parent-id n-app} {:id n-utils :name "apputils"}]
        definitions [{:id v-db :name "db" :filters {:effects ["db"]}}
                     {:id v-tools :name "tools" :filters {:namespaces ["app/tools"] :views [v-db]}}
                     {:id v-empty :name "empty" :filters {}}
                     {:id v-cycle-a :name "cycle-a" :filters {:views [v-cycle-b]}}
                     {:id v-cycle-b :name "cycle-b" :filters {:effects ["io"] :views [v-cycle-a]}}
                     {:id v-unsupported :name "unsupported" :filters {:effects ["db"]} :unsupported true}
                     {:id v-unnamed :name nil :filters {:effects ["db"]}}]
        effects {a #{:db :app/db} b #{:db :io} c #{:db} e #{:io} anon #{:db} unnamed #{:db}}
        problems {:failed [c] :lint [b] :services [b] :apps [c]}
        cases [{} {:name "  "} {:name "TOOLS.BE"} {:name "alpha"}
               {:effects ["db"]} {:effects ["app/db"]} {:effects [:app/db]}
               {:effects ["app/db" "db"]} {:effects [:db :io]} {:effects "db"}
               {:effects [nil false "db" ":io"]} {:effects ["unknown"]}
               {:uses [a]} {:uses [a b]} {:uses [(str a) "bad" nil a]} {:uses (str a)}
               {:namespaces ["app"]} {:namespaces " app "} {:namespaces ["APP/TOOLS"]}
               {:namespaces ["app"] :exclude ["app/tools"]}
               {:kinds ["types" "services"]} {:kinds ["fn"]} {:kinds ["apps"]}
               {:kinds ["tests"]} {:kinds ["secrets"]} {:kinds ["unknown"]}
               {:problems ["failed" "type-errors"]} {:problems ["lint"]}
               {:unused true} {:unused 0}
               {:uses [a] :effects ["db"] :kinds ["tests"] :namespaces ["app"]}
               {:uses [missing]} {:uses [a missing]}
               {:views [v-db v-tools]} {:views (str v-db)} {:views [v-empty]}
               {:views [v-cycle-a]} {:views [v-unsupported]} {:views [v-unnamed]} {:views [missing]}]]
    (cohort rows namespaces definitions effects problems
            (fn [_]
              (doseq [filters cases]
                (is (= (views/view-members harness/*context* filters)
                       (call "view-members" filters))
                    (pr-str filters)))
              (is (= [b c] (mapv :id (:fns (call "view-members" {:uses [a]})))))
              (is (= [b] (mapv :id (:fns (call "view-members" {:views [v-tools]})))))
              (is (= [c d e] (mapv :id (:fns (call "view-members" {:unused true})))))))))


(deftest graph-selector-intersects-before-the-final-cap
  (let [ids (vec (repeatedly 507 random-uuid))
        rows (mapv (fn [i id] {:id id :name (str "match-" i) :parent-ids [] :role :fn}) (range) ids)]
    (cohort rows [] [] {} {}
            (fn [_]
              (let [result (call "view-members" {:name "match"})]
                (is (= 507 (:total result)))
                (is (true? (:truncated? result)))
                (is (= (subvec ids 0 500) (mapv :id (:fns result)))))
              (let [result (call "view-members" {:name "match-506"})]
                (is (= [(last ids)] (mapv :id (:fns result))))
                (is (= 1 (:total result)))
                (is (false? (:truncated? result))))))))


(deftest canonical-cohort-rejects-forged-or-over-budget-selection
  (let [id (random-uuid) hidden (random-uuid)
        rows [{:id id :name "visible" :parent-ids [] :role :fn}]
        filters (views/normalise-filters {:name "visible"})]
    (cohort rows [] [] {} {}
            (fn [_]
              (doseq [selection [{:ids [hidden] :total 1 :truncated? false}
                                 {:ids [id id] :total 2 :truncated? false}
                                 {:ids (repeat 501 id) :total 501 :truncated? true}
                                 {:ids [id] :total 0 :truncated? false}]]
                (is (= :crud/invalid-graph-selection
                       (:type (ex-data (try (views/project-view-cohort harness/*context* filters (constantly selection))
                                            nil (catch clojure.lang.ExceptionInfo e e)))))))
              (let [snapshot (atom nil)
                    result (views/project-view-cohort
                             harness/*context* filters
                             (fn [facts]
                               (reset! snapshot facts)
                               {:ids [id] :total 1 :truncated? false}))]
                (is (= [id] (mapv :id (:fns result))))
                (is (= #{:id :name :namespace-path :effects :kinds :problems :used-targets :unused?}
                       (set (keys (first (:rows @snapshot))))))
                (is (not (contains? @snapshot :storage)))
                (is (nil? (:unused? (first (:rows @snapshot)))))
                (is (every? #(or (nil? %) (string? %) (keyword? %) (number? %) (boolean? %)
                                 (uuid? %) (coll? %))
                            (tree-seq coll? seq @snapshot)))
                (is (= [id] (mapv :id (:fns (call "view-members"
                                                  {:name "visible" :facts {:rows [{:id hidden :name "forged"}]}
                                                   :candidate {:id hidden :name "forged"}}))))))))))


(deftest public-view-inputs-retain-their-owned-slot-identities
  (let [owner (harness/fn-id "view-members")
        fields #{"name" "views" "uses" "effects" "kinds" "problems" "namespaces" "exclude" "unused"}
        slots (sp/query-entities harness/*storage* :fn-slot {:fn-id owner})]
    (is (= (set (map #(ids/slot-id owner %) fields)) (set (map :slot-id slots))))))


(deftest public-view-members-conceals-real-hidden-composition
  (let [storage harness/*storage*
        [target hidden caller] (repeatedly 3 random-uuid)
        label (str "view-hidden-" (random-uuid))
        previous @entity-list/view-impl-filter]
    (try
      (doseq [row [{:id target :name (str label "-target") :parent-ids []}
                   {:id hidden :name (str label "-owner") :parent-ids [target]}
                   {:id caller :name (str label "-caller") :parent-ids [hidden]}]]
        (sp/create-entity storage :fn row))
      (context/invalidate-graph-cache! harness/*context* [target hidden caller])
      (reset! entity-list/view-impl-filter nil)
      (is (= #{hidden caller} (set (map :id (:fns (call "view-members" {:uses [target]}))))))
      (reset! entity-list/view-impl-filter #(entities/strip-impl-of % #{hidden}))
      (is (= [] (:fns (call "view-members" {:uses [target]}))))
      (is (= [caller] (mapv :id (:fns (call "view-members" {:uses [hidden]})))))
      (is (= [] (:parent-ids (first (:fns (call "view-members" {:name (str label "-owner")}))))))
      (finally (reset! entity-list/view-impl-filter previous)))))


(deftest public-reference-intersection-precedes-cap-on-real-saved-views
  (let [storage harness/*storage*
        root (ids/fn-id "app.views" :explorer-view)
        prefix (str "view-cap-" (random-uuid))
        ns-row (sp/create-entity storage :ns {:name prefix})
        rows (mapv (fn [n] {:id (random-uuid) :name (str prefix "-" (format "%04d" n)) :namespace-id (:id ns-row)}) (range 502))
        _ (sp/create-entities storage :fn rows)
        inner (setup/create-composed-fn! storage (str prefix "-inner") root)
        outer (setup/create-composed-fn! storage (str prefix "-outer") root)]
    (setup/bind-value! storage (:id inner) (ids/slot-id root :namespaces) [prefix])
    (setup/bind-value! storage (:id outer) (ids/slot-id root :also-all) [(:id inner)])
    (context/invalidate-graph-cache!
      harness/*context*
      (into [(:id inner) (:id outer)] (map :id rows)))
    (let [initial (call "view-members" {:views [(:id outer)]})
          selected (set (map :id (:fns initial)))
          omitted (first (remove #(selected (:id %)) rows))
          narrowed (call "view-members" {:views [(:id outer)] :name (:name omitted)})]
      (is (= 502 (:total initial)))
      (is (= 500 (count (:fns initial))))
      (is (true? (:truncated? initial)))
      (is (= [(:id omitted)] (mapv :id (:fns narrowed))))
      (is (= 1 (:total narrowed)))
      (is (false? (:truncated? narrowed))))))


(deftest unmatched-tags-do-not-expand-the-cohort-facts
  (let [[target user] (repeatedly 2 random-uuid)
        rows [{:id target :name "target" :parent-ids [] :role :fn}
              {:id user :name "user" :parent-ids [target] :role :fn :type-error-count 2}]
        filters (views/normalise-filters
                  {:uses (into [target] (repeatedly 256 random-uuid))
                   :problems (into [:type-errors] (map #(keyword (str "unknown-" %))) (range 256))})
        observed (atom nil)]
    (cohort rows [] [] {} {}
            (fn [_]
              (views/project-view-cohort harness/*context* filters
                                         (fn [facts]
                                           (reset! observed (into {} (map (juxt :id identity)) (:rows facts)))
                                           {:ids [] :total 0 :truncated? false}))))
    (is (= {} (:used-targets (get @observed target))))
    (is (= {target true} (:used-targets (get @observed user))))
    (is (= {} (:problems (get @observed target))))
    (is (= {:type-errors true} (:problems (get @observed user))))))
