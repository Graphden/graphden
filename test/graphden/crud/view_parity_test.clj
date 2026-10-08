(ns ^:serial graphden.crud.view-parity-test
  "Real graph reads for saved-view compatibility, intersections and live axes.
   Serial because the installed Apps callback is shared with the addon."
  (:require
    [clojure.set :as set]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.executor.context :as context]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.diagnostics :as diagnostics]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (setup/create-container-fixture) exec/with-isolated-rich-types)


(defn- view-base!
  [storage]
  (let [base (setup/create-base-fn! storage "explorer-view")
        slots (into {} (map-indexed
                         (fn [position [arg type]]
                           (let [slot (setup/create-slot! storage (name arg) type)]
                             (setup/attach-slot! storage (:id base) (:id slot) position)
                             [arg (:id slot)]))
                         [[:name :text] [:uses :fn-ref] [:also :fn-ref]
                          [:uses-all :sequence] [:also-all :sequence]
                          [:namespaces :sequence] [:problems :sequence]]))]
    {:id (:id base) :slots slots}))


(defn- member-ids
  [ctx filters]
  (into #{} (map :id) (:fns (entities/view-members ctx filters))))


(deftest nested-view-intersection-happens-before-the-result-cap
  (let [storage (setup/create-test-storage)]
    (try
      (let [ns-row (sp/create-entity storage :ns {:name "cap-probe"})
            rows (mapv (fn [n]
                         {:id (random-uuid) :name (format "cap-member-%04d" n)
                          :namespace-id (:id ns-row)}) (range 502))
            _ (sp/create-entities storage :fn rows)
            {:keys [id slots]} (view-base! storage)
            view (setup/create-composed-fn! storage "cap-view" id)
            _ (setup/bind-value! storage (:id view) (:namespaces slots) ["cap-probe"])
            ctx (context/create-context {:storage storage})
            initial (entities/view-members ctx {:views [(:id view)]})
            omitted (first (set/difference (set (map :id rows)) (set (map :id (:fns initial)))))
            label (:name (sp/read-entity storage :fn omitted))]
        (is (= 502 (:total initial)))
        (is (= 500 (count (:fns initial))))
        (is (true? (:truncated? initial)))
        (is (= #{omitted} (member-ids ctx {:views [(:id view)] :name label}))))
      (finally (sp/close storage)))))


(deftest missing-and-empty-views-cannot-broaden-other-clauses
  (let [storage (setup/create-test-storage)]
    (try
      (let [{:keys [id]} (view-base! storage)
            empty-view (setup/create-composed-fn! storage "empty-view" id)
            _ (setup/create-base-fn! storage "match-probe")
            ctx (context/create-context {:storage storage})
            missing (random-uuid)]
        (is (= #{} (member-ids ctx {:name "match-probe" :views [(:id empty-view)]})))
        (let [answer (entities/view-members ctx {:name "match-probe" :views [missing]})]
          (is (= [] (:fns answer)))
          (is (= {:views [missing]} (:missing answer)))))
      (finally (sp/close storage)))))


(deftest legacy-and-list-identities-decode-with-inherited-list-append
  (let [storage (setup/create-test-storage)]
    (try
      (let [{:keys [id slots]} (view-base! storage)
            const-row (setup/create-base-fn! storage "const")
            value-slot (setup/create-slot! storage "value" :any)
            _ (setup/attach-slot! storage (:id const-row) (:id value-slot) 0)
            a (setup/create-base-fn! storage "view-target-a")
            b (setup/create-base-fn! storage "view-target-b")
            adapter (setup/create-composed-fn! storage "_view-identity" (:id const-row))
            _ (sp/create-entity storage :binding
                                {:fn-id (:id adapter) :slot-id (:id value-slot)
                                 :ref-fn-id (:id b) :type-override-fn-id (:fn-ref setup/primitive-fn-ids)})
            parent (setup/create-composed-fn! storage "parent-view" id)
            child (setup/create-composed-fn! storage "child-view" (:id parent))
            _ (setup/bind-ref! storage (:id parent) (:uses slots) (:id a))
            parent-list (sp/create-entity storage :binding
                                          {:fn-id (:id parent) :slot-id (:uses-all slots) :list-append true})
            _ (sp/create-entity storage :binding-list-item
                                {:binding-id (:id parent-list) :position 0 :ref-fn-id (:id adapter)})
            child-list (sp/create-entity storage :binding
                                         {:fn-id (:id child) :slot-id (:uses-all slots) :list-append true})
            _ (sp/create-entity storage :binding-list-item
                                {:binding-id (:id child-list) :position 0 :value (:id a)})
            _ (setup/bind-value! storage (:id child) (:problems slots) ["type-errors" "failed"])
            ctx (context/create-context {:storage storage})
            listed (into {} (map (juxt :id identity)) (entities/list-explorer-views ctx))]
        (is (= #{(:id a) (:id b)} (set (get-in listed [(:id child) :filters :uses]))))
        (is (= ["type-errors" "failed"] (get-in listed [(:id child) :filters :problems])))
        (is (nil? (:unsupported (get listed (:id child))))))
      (finally (sp/close storage)))))


(deftest kinds-and-problems-intersect-on-the-current-branch
  (binding [diagnostics/*diagnostics-override* (atom {})]
    (let [storage (setup/create-branch-versioned-test-storage)]
      (try
        (let [type-row (sp/create-entity storage :fn {:name "parity-type"})
              base (setup/create-base-fn! storage "parity-fn")
              branch (vs/current-branch-id storage)
              ctx (context/create-context {:storage storage})]
          (diagnostics/record! branch (:id base) [{:message "wrong fn"}])
          (diagnostics/record! (random-uuid) (:id type-row) [{:message "other branch"}])
          (is (= #{} (member-ids ctx {:name "parity-" :kinds ["types"] :problems ["type-errors"]})))
          (diagnostics/record! branch (:id type-row) [{:message "wrong type"}])
          (is (= #{(:id type-row)}
                 (member-ids ctx {:name "parity-" :kinds ["types"] :problems ["type-errors"]}))))
        (finally (sp/close storage))))))


(deftest apps-use-the-installed-org-reader-and-do-not-count-as-plain-fns
  (let [storage (setup/create-test-storage)
        saved-reader @tenancy/list-tenant-app-routes-fn]
    (try
      (let [app (setup/create-base-fn! storage "app-probe")
            plain (setup/create-base-fn! storage "plain-probe")
            seen (atom [])
            ctx (context/create-context {:storage storage})]
        (reset! tenancy/list-tenant-app-routes-fn nil)
        (is (= #{} (member-ids ctx {:kinds ["apps"]})))
        (reset! tenancy/list-tenant-app-routes-fn
                (fn [org]
                  (swap! seen conj org)
                  [{:handler-fn-id (:id app)} {:handler-fn-id (random-uuid)}]))
        (is (= #{(:id app)} (member-ids ctx {:kinds ["apps"]})))
        (is (= #{(:id plain)} (member-ids ctx {:kinds ["fn"]})))
        (is (= [tenancy/public-org tenancy/public-org] @seen)))
      (finally
        (reset! tenancy/list-tenant-app-routes-fn saved-reader)
        (sp/close storage)))))


(deftest computed-clauses-are-explicit-and-never-broaden-a-view
  (let [storage (setup/create-test-storage)]
    (try
      (let [{:keys [id slots]} (view-base! storage)
            computation (setup/create-base-fn! storage "computed-name")
            view (setup/create-composed-fn! storage "computed-view" id)
            _ (setup/bind-ref! storage (:id view) (:name slots) (:id computation))
            ctx (context/create-context {:storage storage})
            listed (first (filter #(= (:id view) (:id %)) (entities/list-explorer-views ctx)))
            members (entities/view-members ctx {:views [(:id view)] :name "computed"})]
        (is (= [:name] (:unsupported listed)))
        (is (= [] (:fns members)))
        (is (= [(:id view)] (:unsupported-views members))))
      (finally (sp/close storage)))))
