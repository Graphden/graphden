(ns ^:integration ^:serial graphden.editor.config-resolution-integration-test
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.type-check :as type-check]
    [graphden.editor.component-config :as config]
    [graphden.editor.config-activation :as activation]
    [graphden.editor.config-preparation :as preparation]
    [graphden.editor.config-receipts :as receipts]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.config-templates :as templates]
    [graphden.editor.config-undo :as undo]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.composition.core :as composition]
    [graphden.executor.context :as context]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.source-contract :as source-contract]
    [graphden.graph.metadata :as metadata]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.postgres.crud :as pg-crud]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.golden-app :as ga]
    [graphden.types.check :as check]
    [graphden.types.check.literals :as literals]
    [graphden.types.check.provenance :as provenance]
    [graphden.types.core :as types]
    [graphden.util.ns-path :as ns-path]
    [graphden.versioning.storage.core :as versioned]))


(def ^:private default-component-roles
  #{:account-menu :fn-picker :recents :inspector :explorer :tutorial-catalog :canvas})


(defn- create-legacy-record!
  [storage namespace-row]
  (let [path (:name namespace-row)
        interfaces (templates/interfaces)
        index (fn [value-fn]
                (reduce (fn [result {:keys [name] path :namespace :as definition}]
                          (assoc result name (value-fn definition)
                                 (keyword path (clojure.core/name name)) (value-fn definition)))
                        {} interfaces))
        definition {:name :legacy :namespace path :parent :app.ui-components/ui-components
                    :args {:menu-initial {:ref :app.ui-account-menu/account-menu-initial}
                           :menu-update {:ref :app.ui-account-menu/account-menu-update}
                           :menu-view {:ref :app.ui-fn-picker/picker-view}
                           :picker-view {:ref :app.ui-fn-picker/picker-view}}}
        records (composition/fn-defs->records
                  [definition] (merge (ids/primitive-fn-ids) (index #(ids/fn-id (:namespace %) (:name %))))
                  (index identity))]
    (doseq [entity [:fn :slot :fn-slot :binding :binding-list-item]
            row (filter #(= entity (:kind %)) records)]
      (sp/create-entity storage entity
                        (cond-> (dissoc row :kind)
                          (= entity :fn) (assoc :namespace-id (:id namespace-row)))))
    (let [id (ids/fn-id path :legacy)]
      (check/check-fn-def! (type-check/reconstruct-fn-def storage id))
      id)))


(deftest explorer-optional-diff-preserves-a-stored-pinned-personal-child
  (let [storage (:storage ga/*bootstrap*)
        path (str "pinned-explorer-" (random-uuid))
        namespace-row (sp/create-entity storage :ns {:name path})
        old-shape (->> (edn/read-string
                         (slurp "test/graphden/lint/explorer_toolbar_pre_dry.edn"))
                       :fns
                       (some #(when (= :_explorer-toolbar-inputs (:name %)) (:type %))))
        current-definitions (:fns (edn/read-string
                                    (slurp "resources/packages/app/ui-explorer/fns.edn")))
        current-shape (some #(when (= :_explorer-toolbar-inputs (:name %)) (:type %))
                            current-definitions)
        default-definition (some #(when (= :_explorer-diff-default (:name %)) %)
                                 current-definitions)
        definition {:name :view :namespace path
                    :parent :app.ui-explorer/_explorer-input-surface
                    :args {:inputs {:as :inputs :type :_explorer-toolbar-inputs}}}
        id (ids/fn-id path :view)]
    (is (= old-shape current-shape)
        "The shipped toolbar record keeps the old pinned personal contract")
    (composition/sync-fns-to-storage! storage [definition] {path (:id namespace-row)})
    (let [stored (type-check/reconstruct-fn-def storage id)]
      (is (= :app.ui-explorer/_explorer-toolbar-inputs
             (get-in stored [:args :inputs :type])))
      (is (some? (registry/rich-type-of :app.ui-explorer/_explorer-input-surface)))
      (is (some? (check/check-fn-def! stored))
          "The stored old child still passes strict type checking"))
    (is (some? (registry/rich-type-of :app.ui-explorer/_explorer-diff-read)))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :invalid-diff-default
                                :parent :app.ui-explorer/_explorer-diff-read
                                :args {:diff {:value "not a diff record"}}}))
        "The optional slot rejects a value outside nullable Diff")
    (let [value {:kind nil :title "" :summary nil :branch "" :counts {}}
          inferred (literals/classify-literal value)
          declared (types/resolve-alias :_explorer-diff-input)]
      (is (= {:kind :null :title :text :summary :null
              :branch :text :counts :empty-map}
             inferred))
      (is (= :_explorer-diff-input (:return-type default-definition)))
      (is (true? (:return-type-wide? default-definition)))
      (is (not= inferred declared)
          "The empty default has a narrower literal type")
      (is (types/subtype? inferred declared)
          "The explicit wide return is only the declared Diff record"))))


(deftest inspector-optional-compare-preserves-a-stored-pinned-personal-child
  (let [storage (:storage ga/*bootstrap*)
        path (str "pinned-inspector-" (random-uuid))
        namespace-row (sp/create-entity storage :ns {:name path})
        old-inputs {:selected :bool :name :text :namespace :text :description :text
                    :parents [:list :text] :has-return :bool :narrow :bool :surface :text
                    :usages :_inspector-usages-payload}
        definitions (:fns (edn/read-string
                            (slurp "resources/packages/app/ui-inspector/fns.edn")))
        current-inputs (some #(when (= :_inspector-inputs (:name %)) (:type %)) definitions)
        definition {:name :surface :namespace path
                    :parent :app.ui-inspector/_inspector-surface
                    :args {:inputs {:as :inputs :type :_inspector-inputs}}}
        id (ids/fn-id path :surface)]
    (is (= old-inputs current-inputs)
        "The installed Inspector record is unchanged for explicitly pinned children")
    (composition/sync-fns-to-storage! storage [definition] {path (:id namespace-row)})
    (let [stored (type-check/reconstruct-fn-def storage id)]
      (is (= :app.ui-inspector/_inspector-inputs
             (get-in stored [:args :inputs :type])))
      (is (some? (check/check-fn-def! stored))))
    (is (some? (registry/rich-type-of :app.ui-inspector/_inspector-diff-read)))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)type-check failed"
          (check/check-fn-def! {:name :invalid-inspector-diff
                                :parent :app.ui-inspector/_inspector-diff-read
                                :args {:diff {:value "not a diff record"}}}))
        "The optional slot rejects a value outside nullable Inspector Diff")
    (let [view-id (ids/fn-id "app.ui-inspector" :inspector-view)
          authorized (tenancy/browser-source-policy storage)
          graph (source/collect-manifest storage view-id authorized)
          namespaces (source/collect-namespaces storage graph)
          policy (snapshot/capture-policy @(registry/active-rich-types-atom))
          plan (snapshot/export-snapshot {:graph graph :namespaces namespaces}
                                         {} {:view view-id} policy)
          inputs (get-in plan [:inputs (get-in plan [:entries "view"])])]
      (is (= ["diff" "inputs" "state"] (:accepted inputs))
          "Installed PostgreSQL source exports the compare projection")
      (is (= ["inputs" "state"] (:required inputs))))))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(use-fixtures :each
  (fn [test-fn]
    ;; Tests in this namespace share one golden DB. The legacy migration
    ;; proof creates a components preference that must not select a source
    ;; in another test's fresh-home scenario.
    (let [storage (:storage ga/*bootstrap*)
          clear! #(doseq [row (sp/query-entities storage :ui-pref
                                                 {:owner-id (tenancy/current-user-id) :key "components"})]
                    (sp/delete-entity storage :ui-pref (:id row)))]
      (clear!)
      (try (test-fn) (finally (clear!))))))


(deftest first-use-http-setup-preview-apply-reconcile-and-undo-keep-no-baseline-editor
  (let [ctx (:ctx ga/*bootstrap*) storage (:storage ctx)
        home-name (str "first-use-profile-" (random-uuid))
        home-id (:id (sp/create-entity storage :ns {:name home-name}))
        submit (fn [operation body]
                 ;; Direct graph handlers omit the real router's epoch healing.
                 (epoch/seed-watermark! storage)
                 (let [response (ga/exec-handler
                                  (keyword (str "_editor-configuration-" operation "-handler"))
                                  {:request-method :post :uri (str "/api/ui/configuration/" operation)
                                   :headers {"content-type" "application/json"}
                                   :body (json/generate-string body)})]
                   {:status (:status response) :body (json/parse-string (:body response) true)}))]
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      ;; Configured tenancy homes are provisioned by account lifecycle. This
      ;; fresh authority has no editor root even when other randomly ordered
      ;; tests have created one in the shared golden DB.
      (tenancy/install-personal-namespace-fn!
        (constantly {:namespace-path home-name :source :fixture :isolated? false}))
      (is (empty? (sp/query-entities storage :fn {:namespace-id home-id})))
      (let [setup (submit "prepare" {:home true})
            initial (submit "plan" {:component "fn-picker"})
            preview (submit "preview" {:operation "personalize"
                                       :command {:component "fn-picker"
                                                 :expected-preference (get-in initial [:body :configuration-preference])
                                                 :expected-configuration-revision (get-in initial [:body :configuration-revision])}})
            original (get-in preview [:body :receipt])]
        (is (= 200 (:status setup) (:status initial) (:status preview)))
        (is (= (str home-id) (get-in setup [:body :home :id])))
        (is (true? (get-in setup [:body :setup])))
        (is (empty? (sp/query-entities storage :fn {:namespace-id home-id})))
        (is (nil? (:previous original)))
        (is (= (get-in initial [:body :configuration-revision]) (:restore-revision original)))
        (let [applied (submit "apply" (get-in preview [:body :request]))
              receipt {:current-id (:id original)
                       :expected-configuration-revision (:configuration-revision original)
                       :cohort-revision (:cohort-revision original)
                       :previous (:previous original) :functions (:function-receipts original)}
              confirmed (submit "reconcile" receipt)
              undone (submit "undo" receipt)]
          (is (= 200 (:status applied) (:status confirmed) (:status undone)))
          (is (true? (get-in applied [:body :committed])))
          (is (true? (get-in confirmed [:body :confirmed])))
          (is (= (set (:functions original)) (set (get-in undone [:body :deleted]))))
          (is (empty? (sp/query-entities storage :fn {:namespace-id home-id})))
          (is (some? (sp/read-entity storage :ns home-id)))
          (let [confirmation {:receipt receipt :scope (get-in preview [:body :request :scope])
                              :restore-revision (:restore-revision original)}
                outcome (submit "reconcile-undo" confirmation)]
            (is (= 200 (:status outcome)))
            (is (true? (get-in outcome [:body :undone])))
            (is (= (str resolution/defaults-id) (get-in outcome [:body :id])))
            (is (= 409 (:status (submit "reconcile-undo"
                                        (assoc confirmation :restore-revision (str/join (repeat 64 "a"))))))))
          (is (= (str resolution/defaults-id) (get-in (submit "plan" {:component "fn-picker"}) [:body :selection-id]))))))))


(defn- current-configuration
  [storage]
  (let [policy (snapshot/capture-policy @(registry/active-rich-types-atom))]
    (source/with-snapshot storage #(resolution/capture %1 %2 policy))))


(deftest reviewed-cleanup-keeps-original-receipts-and-rejects-edits-after-consent
  (let [ctx (:ctx ga/*bootstrap*) storage (:storage ctx)
        home-name (str "review-profile-" (random-uuid))
        home (sp/create-entity storage :ns {:name home-name})]
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      (tenancy/install-personal-namespace-fn!
        (constantly {:namespace-path home-name :source :fixture :isolated? false}))
      (epoch/seed-watermark! storage)
      (let [before (current-configuration storage)
            result (activation/personalize! ctx {:component "fn-picker" :expected-preference {:present false :value nil}
                                                 :expected-configuration-revision (:revision before)})
            original {:current-id (:id result) :expected-configuration-revision (:configuration-revision result)
                      :cohort-revision (:cohort-revision result) :previous (:previous result)
                      :functions (:function-receipts result)}
            input {:receipt original :scope (activation/request-scope (:scope before))
                   :restore-revision (:restore-revision result)}
            role-id (:entry-id result)]
        (sp/update-entity storage :fn role-id {:description "Intentional lesson edit"})
        (epoch/seed-watermark! storage)
        (is (= :stale-configuration
               (try (undo/undo! ctx original) nil
                    (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
        (epoch/seed-watermark! storage)
        (let [rows (sp/read-entities storage :fn (:functions result))
              review (receipts/cleanup-preview! ctx input)]
          (is (= rows (sp/read-entities storage :fn (:functions result))))
          (is (= original (:receipt input)))
          (is (= (set (:functions result)) (set (map :id (:delete review)))))
          (is (not= (:cohort-revision original) (get-in review [:request :cohort-revision])))
          (is (= resolution/defaults-id (get-in review [:restore :id])))
          (let [external (sp/create-entity storage :fn {:name "kept-caller" :namespace-id (:id home)})
                binding (sp/create-entity storage :binding
                                          {:fn-id (:id external) :slot-id (ids/slot-id (ids/fn-id "core.logic" :const) :value)
                                           :ref-fn-id role-id})]
            (epoch/seed-watermark! storage)
            (is (= :graph-write/functions-in-use
                   (try (receipts/cleanup-preview! ctx input) nil
                        (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))
            (is (= rows (sp/read-entities storage :fn (:functions result))))
            (sp/delete-entity storage :binding (:id binding))
            (sp/delete-entity storage :fn (:id external)))
          (sp/update-entity storage :fn role-id {:description "Edited after visible review"})
          (epoch/seed-watermark! storage)
          (is (= :stale-configuration
                 (try (undo/undo! ctx (:request review)) nil
                      (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
          (is (= (set (:functions result)) (set (keys (sp/read-entities storage :fn (:functions result))))))
          (epoch/seed-watermark! storage)
          (let [review (receipts/cleanup-preview! ctx input)
                outcome (undo/undo! ctx (:request review))]
            (is (= (set (:functions result)) (set (:deleted outcome))))
            (epoch/seed-watermark! storage)
            (is (:undone (receipts/reconcile-undo! ctx (assoc input :receipt (:request review)
                                                              :restore-revision (:restore-revision review)))))
            (is (some? (sp/read-entity storage :ns (:id home))))))))))


(deftest preview-stages-original-receipts-without-writing-and-apply-verifies-them
  (let [ctx (:ctx ga/*bootstrap*) storage (:storage ctx)
        home-name (str "preview-profile-" (random-uuid))
        home (sp/create-entity storage :ns {:name home-name})]
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      (tenancy/install-personal-namespace-fn!
        (constantly {:namespace-path home-name :source :fixture :isolated? false}))
      (epoch/seed-watermark! storage)
      (preparation/prepare! ctx {:create true})
      (epoch/seed-watermark! storage)
      (let [before (current-configuration storage)
            original (sp/query-entities storage :fn {:namespace-id (:id home)})
            preview (activation/preview!
                      ctx {:operation "personalize"
                           :command {:component "fn-picker" :expected-preference {:present false :value nil}
                                     :expected-configuration-revision (:revision before)}})
            request (:request preview)
            receipt (:receipt preview)]
        (is (= original (sp/query-entities storage :fn {:namespace-id (:id home)})))
        (is (= "editor" (:name (sp/read-entity storage :fn (:id before)))))
        (is (= 6 (count (:functions receipt))))
        (is (activation/revision? (:cohort-revision receipt)))
        (is (activation/revision? (:configuration-revision receipt)))
        (is (= :stale-configuration
               (try (activation/apply-proposal! ctx (assoc-in request [:scope :branch-id] (str (random-uuid)))) nil
                    (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
        (is (= original (sp/query-entities storage :fn {:namespace-id (:id home)})))
        (let [applied (activation/apply-proposal! ctx request)
              wire (json/parse-string (json/generate-string applied) true)]
          (is (true? (:committed applied)))
          (is (empty? (:publication-warnings applied)))
          (is (= receipt (dissoc wire :committed :changed-functions :publication-warnings)))
          ;; Direct fixture calls bypass the next request's router refresh.
          (epoch/seed-watermark! storage)
          (is (= :stale-configuration
                 (try (activation/apply-proposal! ctx request) nil
                      (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
          (epoch/seed-watermark! storage)
          (let [original-receipt {:current-id (:id receipt)
                                  :expected-configuration-revision (:configuration-revision receipt)
                                  :cohort-revision (:cohort-revision receipt)
                                  :previous (:previous receipt) :functions (:function-receipts receipt)}
                rows-before (sp/read-entities storage :fn (:functions applied))
                confirmed (receipts/reconcile! ctx original-receipt)
                role-id (:entry-id applied)
                original-description (:description (get rows-before role-id))]
            (is (true? (:confirmed confirmed)))
            (is (= (:cohort-revision receipt) (:cohort-revision confirmed)))
            (is (= rows-before (sp/read-entities storage :fn (:functions applied))))
            ;; An uncertain original receipt must never adopt later edits.
            (sp/update-entity storage :fn role-id {:description "Authored after APPLY"})
            (epoch/seed-watermark! storage)
            (is (= :stale-configuration
                   (try (receipts/reconcile! ctx original-receipt) nil
                        (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
            (is (= "Authored after APPLY" (:description (sp/read-entity storage :fn role-id))))
            (sp/update-entity storage :fn role-id {:description original-description})
            (epoch/seed-watermark! storage)
            (let [undone (undo/undo! ctx original-receipt)]
              (is (= (set (:functions applied)) (set (:deleted undone))))
              (is (= (:id before) (:id (current-configuration storage))))))
          (epoch/seed-watermark! storage)
          (is (= :identity-collision
                 (try (activation/apply-proposal! ctx request) nil
                      (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
          (is (nil? (sp/read-entity storage :fn (:id applied)))))))))


(deftest shared-custom-view-is-inherited-into-the-current-personal-home
  (let [ctx (:ctx ga/*bootstrap*) storage (:storage ctx)
        first-name (str "shared-profile-" (random-uuid))
        second-name (str "consumer-profile-" (random-uuid))
        first-home (sp/create-entity storage :ns {:name first-name})
        second-home (sp/create-entity storage :ns {:name second-name})
        install-home! (fn [path]
                        (tenancy/install-personal-namespace-fn!
                          (constantly {:namespace-path path :source :fixture :isolated? false})))]
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      (install-home! first-name)
      (epoch/seed-watermark! storage)
      (preparation/prepare! ctx {:create true})
      (epoch/seed-watermark! storage)
      (let [first-current (current-configuration storage)
            shared (activation/personalize! ctx {:component "fn-picker" :expected-preference {:present false :value nil}
                                                 :expected-configuration-revision (:revision first-current)})]
        (is (= (:id first-home) (:namespace-id (sp/read-entity storage :fn (:entry-id shared)))))
        (install-home! second-name)
        (epoch/seed-watermark! storage)
        (preparation/prepare! ctx {:create true})
        (epoch/seed-watermark! storage)
        (activation/activate! ctx {:selection-id (:id shared) :expected-preference {:present false :value nil}
                                   :expected-configuration-revision (:revision (current-configuration storage))})
        (epoch/seed-watermark! storage)
        (let [before (current-configuration storage)
              personalized (activation/personalize! ctx {:component "fn-picker" :expected-preference {:present false :value nil}
                                                         :expected-configuration-revision (:revision before)})
              child (sp/read-entity storage :fn (:entry-id personalized))]
          (is (not (resolution/owned-writable-view? storage before :fn-picker)))
          (is (true? (:created personalized)))
          (is (= 6 (count (:functions personalized))))
          (is (= (:id second-home) (:namespace-id child)))
          (is (= [(:entry-id shared)] (:parent-ids child)))
          (is (empty? (sp/query-entities storage :binding {:fn-id (:id child)})))
          (is (some? (sp/read-entity storage :fn (:entry-id shared)))))))))


(deftest reserved-cohort-identities-cannot-be-recreated-after-tombstones
  (let [ctx (:ctx ga/*bootstrap*) storage (:storage ctx)
        home (sp/create-entity storage :ns {:name (str "reserved-profile-" (random-uuid))})
        nonce (random-uuid)
        policy (snapshot/capture-policy @(registry/active-rich-types-atom))
        authorize! (tenancy/browser-source-policy storage)]
    (writer/with-write [storage :graph]
                       (let [first-plan (preparation/prepared-records storage home {} authorize! policy nonce)
                             second-plan (preparation/prepared-records storage home {} authorize! policy nonce)]
                         (is (= (:records first-plan) (:records second-plan)))
                         (is (= (:id first-plan) (:id second-plan)))
                         (is (= [(:id first-plan)] (preparation/create-records! storage (:records first-plan))))
                         (binding [versioned/*tombstone-delete?* true]
                           (sp/delete-entity storage :fn (:id first-plan)))
                         (is (nil? (sp/read-entity storage :fn (:id first-plan))))
                         (is (some? (sp/read-entity (versioned/unwrap storage) :fn (:id first-plan))))
                         (is (= :identity-collision
                                (try (preparation/prepared-records storage home {} authorize! policy nonce) nil
                                     (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
                         (is (nil? (sp/read-entity storage :fn (:id first-plan))))))))


(deftest inherited-personalization-retains-other-groups-and-protects-new-role-body-edits
  (let [ctx (:ctx ga/*bootstrap*) storage (:storage ctx)
        home-name (str "profile-" (random-uuid))
        home (sp/create-entity storage :ns {:name home-name})]
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      (tenancy/install-personal-namespace-fn!
        (constantly {:namespace-path home-name :source :fixture :isolated? false}))
      (epoch/seed-watermark! storage)
      (preparation/prepare! ctx {:create true})
      (epoch/seed-watermark! storage)
      (let [before (current-configuration storage)
            picker (activation/personalize! ctx {:component "fn-picker" :expected-preference {:present false :value nil}
                                                 :expected-configuration-revision (:revision before)})]
        (is (= 6 (count (:functions picker))))
        (is (= [(get-in before [:configuration :fn-picker :view])]
               (:parent-ids (sp/read-entity storage :fn (:entry-id picker)))))
        (is (empty? (sp/query-entities storage :binding {:fn-id (:entry-id picker)})))
        (epoch/seed-watermark! storage)
        (let [current (current-configuration storage)]
          (is (= (:configuration-revision picker) (:revision current)))
          (is (= (:entry-id picker) (get-in current [:configuration :fn-picker :view])))
          (is (= (dissoc (:configuration before) :fn-picker) (dissoc (:configuration current) :fn-picker)))
          (let [again (activation/personalize! ctx {:component "fn-picker" :expected-preference {:present false :value nil}
                                                    :expected-configuration-revision (:revision current)})]
            (is (false? (:created again)))
            (is (= (:entry-id picker) (:entry-id again)))
            (is (empty? (:functions again))))
          (let [recents (activation/personalize! ctx {:component "recents" :expected-preference {:present false :value nil}
                                                      :expected-configuration-revision (:revision current)})]
            (is (= 12 (count (:functions recents))))
            (epoch/seed-watermark! storage)
            (let [current (current-configuration storage)
                  receipts (:function-receipts recents)
                  undo-input {:current-id (:id recents) :expected-configuration-revision (:configuration-revision recents)
                              :cohort-revision (:cohort-revision recents) :previous (:previous recents) :functions receipts}]
              (is (= (:entry-id picker) (get-in current [:configuration :fn-picker :view])))
              (is (= (:configuration-revision recents) (:revision current)))
              ;; This changes actual own source of a new role child, while its
              ;; identity metadata and normal terminal manifest view stay equal.
              (sp/create-entity storage :binding
                                {:fn-id (:entry-id recents) :slot-id (ids/slot-id (ids/fn-id "core.collections" :zipmap) :keys)
                                 :value [:styles :tree :hidden] :value-present true})
              (epoch/seed-watermark! storage)
              (is (= (:revision current) (:revision (current-configuration storage))))
              (is (= :stale-configuration
                     (try (undo/undo! ctx undo-input) nil
                          (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
              (is (= (set (:functions recents)) (set (keys (sp/read-entities storage :fn (:functions recents))))))
              (is (= (:id home) (:namespace-id (sp/read-entity storage :fn (:id recents))))))))))))


(deftest activation-replaces-versioned-name-without-changing-other-branch-identity
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        home-name (str "profile-" (random-uuid))
        home (sp/create-entity storage :ns {:name home-name})]
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      (tenancy/install-personal-namespace-fn!
        (constantly {:namespace-path home-name :source :fixture :isolated? false}))
      (epoch/seed-watermark! storage)
      (let [initial (preparation/prepare! ctx {:create true})
            original (sp/read-entity storage :fn (:id initial))
            legacy-id (create-legacy-record! storage home)
            branch (versioned/create-branch! storage (str "personal-" (random-uuid)))
            child (versioned/switch-branch storage (:id branch))
            child-ctx (context/create-context {:storage child})
            policy (snapshot/capture-policy @(registry/active-rich-types-atom))
            current (source/with-snapshot child #(resolution/capture %1 %2 policy))
            input {:selection-id legacy-id :expected-preference {:present false :value nil}
                   :expected-configuration-revision (:revision current)}]
        (epoch/seed-watermark! child)
        (let [result (activation/activate! child-ctx input)
              old-active (sp/read-entity child :fn (:id initial))
              old-other (sp/read-entity storage :fn (:id initial))
              new-active (sp/read-entity child :fn (:id result))]
          (is (:created result))
          (is (not= (:id initial) (:id result)))
          (is (= "editor" (:name new-active)))
          (is (= "editor" (:name old-other)))
          (is (= (:name old-active) (get-in result [:previous :name])))
          (is (= (:parent-ids original) (:parent-ids old-active) (:parent-ids old-other)))
          (is (nil? (sp/read-entity storage :fn (:id result))))
          (is (not-any? #{(:id initial)} (:functions result)))
          (epoch/seed-watermark! child)
          (let [before (sp/query-entities child :fn {:namespace-id (:id home)})]
            (is (= :stale-configuration
                   (try (activation/activate! child-ctx input) nil
                        (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
            (is (= before (sp/query-entities child :fn {:namespace-id (:id home)}))))
          (let [policy (snapshot/capture-policy @(registry/active-rich-types-atom))
                current (source/with-snapshot child #(resolution/capture %1 %2 policy))
                undo-input {:current-id (:id result) :expected-configuration-revision (:configuration-revision result)
                            :cohort-revision (:cohort-revision result)
                            :previous (:previous result) :functions (:function-receipts result)}
                original-create pg-crud/create-entity
                deletes (atom 0) denied-renames (atom 0)]
            (is (= (:revision current) (:configuration-revision result)))
            (let [another-branch (versioned/create-branch! child (str "inherited-" (random-uuid)))
                  another (versioned/switch-branch child (:id another-branch))
                  another-ctx (context/create-context {:storage another})]
              (epoch/seed-watermark! another)
              (is (= :stale-configuration
                     (try (undo/undo! another-ctx undo-input) nil
                          (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
              (is (some? (sp/read-entity another :fn (:id result)))))
            (sp/update-entity child :fn (:id result) {:description "An edit after activation"})
            (epoch/seed-watermark! child)
            (is (= :stale-configuration
                   (try (undo/undo! child-ctx undo-input) nil
                        (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
            (is (= "An edit after activation" (:description (sp/read-entity child :fn (:id result)))))
            (sp/update-entity child :fn (:id result) {:description (:description new-active)})
            (epoch/seed-watermark! child)
            (binding [pg-crud/*create-entity-override*
                      (fn [ds entity data fields]
                        (when (and (= :fn-version entity) (:deleted-at data)) (swap! deletes inc))
                        (when (and (= :fn-version entity) (= (:id initial) (:fn-id data)) (= "editor" (:name data)))
                          (swap! denied-renames inc)
                          (throw (ex-info "Injected rename refusal" {:type :authz/forbidden})))
                        (binding [pg-crud/*create-entity-override* nil]
                          (original-create ds entity data fields)))]
              (is (= :authz/forbidden
                     (try (undo/undo! child-ctx undo-input) nil
                          (catch clojure.lang.ExceptionInfo error (:type (ex-data error)))))))
            (is (= (count (:functions result)) @deletes))
            (is (= 1 @denied-renames))
            (is (= (:name old-active) (:name (sp/read-entity child :fn (:id initial)))))
            (is (= (set (:functions result)) (set (keys (sp/read-entities child :fn (:functions result))))))
            ;; PostgreSQL's graph sequence also advances on rolled-back writes.
            ;; A direct helper cannot skip the real router's policy healing.
            (is (= :policy-refresh-required
                   (try (undo/undo! child-ctx undo-input) nil
                        (catch clojure.lang.ExceptionInfo error (:reason (ex-data error))))))
            (epoch/seed-watermark! child)
            (let [restored (undo/undo! child-ctx undo-input)]
              (is (= (:id initial) (:id restored)))
              (is (= (set (:functions result)) (set (:deleted restored))))
              (is (= "editor" (:name (sp/read-entity child :fn (:id initial)))))
              (is (= (:parent-ids original) (:parent-ids (sp/read-entity child :fn (:id initial)))))
              (is (nil? (sp/read-entity child :fn (:id result))))
              (is (= "editor" (:name (sp/read-entity storage :fn (:id initial))))))))))))


(defn- changed-paths
  [before after]
  (letfn [(walk
            [path before after]
            (cond
              (= before after) []
              (and (map? before) (map? after))
              (mapcat #(walk (conj path %) (get before %) (get after %))
                      (set (concat (keys before) (keys after))))
              :else [path]))]
    (vec (walk [] before after))))


(defn- validate-with-safe-provenance-diagnostic!
  [storage captured id policy]
  (let [matches? provenance/matches?
        check! check/check-fn-def!
        mismatch (atom nil)]
    (try
      (with-redefs [check/check-fn-def!
                    (fn [definition]
                      (try (check! definition)
                           (catch clojure.lang.ExceptionInfo error
                             (println "Safe fresh checker diagnostic"
                                      (select-keys (ex-data error) [:type :expected :actual :declared :computed]))
                             (throw error))))
                    provenance/matches?
                    (fn [signature definition]
                      (let [result (matches? signature definition)]
                        (when-not result (reset! mismatch definition))
                        result))]
        (snapshot/validate-source! captured id policy))
      (catch clojure.lang.ExceptionInfo error
        (let [id (:fn-id (ex-data error))
              full-definition (when id (some-> (type-check/reconstruct-fn-def storage id) check/checker-view))]
          (println "Safe source proof diagnostic"
                   {:reason (:reason (ex-data error)) :fn-id id
                    :full-source-provenance? (matches? (get-in policy [:rich :by-id id]) full-definition)
                    :storage-witness? (provenance/matches-storage-input?
                                        (get-in policy [:rich :by-id id])
                                        (get (source-contract/fingerprints (:graph captured)) id))
                    :changed-input-paths (changed-paths full-definition @mismatch)}))
        (throw error)))))


(deftest installed-protected-resolver-and-default-configuration-pass-fresh-source-checks
  (let [ctx (:ctx ga/*bootstrap*)
        policy (snapshot/capture-policy
                 @(or (:rich-types-atom ctx) (registry/active-rich-types-atom)))]
    (source/with-snapshot
      (:storage ga/*bootstrap*)
      (fn [storage authorize!]
        (let [graph (source/collect-manifest storage resolution/resolver-id authorize!)
              captured {:graph graph :namespaces (source/collect-namespaces storage graph)}
              scope {:namespace-path nil :name "editor" :principal-id "anonymous"
                     :org "public" :branch-id (random-uuid)}
              capability (metadata/function-lookup storage authorize! scope)
              selected-reference (resolution/resolve-identity captured scope capability)
              defaults (source/collect-manifest storage (:id selected-reference) authorize!)
              default-snapshot {:graph defaults :namespaces (source/collect-namespaces storage defaults)}
              configuration (config/configuration defaults (:id selected-reference))]
          (is (= resolution/defaults-id (:id selected-reference)))
          (is (nil? (validate-with-safe-provenance-diagnostic! storage captured resolution/resolver-id policy)))
          (is (nil? (validate-with-safe-provenance-diagnostic! storage default-snapshot resolution/defaults-id policy)))
          (is (= default-component-roles (set (keys configuration))))
          (let [body (source/collect-closure storage (mapcat vals (vals configuration)) authorize!)
                paths (set (vals (ns-path/path-map (source/collect-namespaces storage body))))]
            ;; Safe namespace metadata only, retained for the subsequent
            ;; normal namespace-grant publication audit; no source values.
            (spit "/tmp/gd-personal-config-default-source-namespaces.edn" (pr-str (sort paths)))
            (is (every? #(or (= % "core") (= % "web") (= % "app")
                             (str/starts-with? % "core.") (= % "web.html")
                             (str/starts-with? % "app.ui-")) paths))))))))


(deftest installed-default-component-bodies-export-from-certified-storage-input
  (let [ctx (:ctx ga/*bootstrap*)
        policy (snapshot/capture-policy
                 @(or (:rich-types-atom ctx) (registry/active-rich-types-atom)))]
    (source/with-snapshot
      (:storage ga/*bootstrap*)
      (fn [storage authorize!]
        (let [resolved (resolution/capture storage authorize! policy)]
          (doseq [[component entries] (:configuration resolved)]
            (let [body (source/collect-closure storage (vals entries) authorize!)
                  captured {:graph body :namespaces (source/collect-namespaces storage body)}
                  check! check/check-fn-def!
                  plan (with-redefs [check/check-fn-def!
                                     (fn [definition]
                                       (try (check! definition)
                                            (catch clojure.lang.ExceptionInfo error
                                              (println "Safe component checker diagnostic"
                                                       {:component component
                                                        :checker (select-keys (ex-data error)
                                                                              [:type :expected :actual :declared :computed])})
                                              (throw error))))]
                         (snapshot/export-snapshot captured (:base-fns ctx) entries policy))]
              (is (map? plan) (name component)))))))))


(deftest actual-personal-editor-child-resolves-with-shared-default-dependencies
  (let [storage (:storage ga/*bootstrap*)
        capture (fn []
                  (let [policy (snapshot/capture-policy @(registry/active-rich-types-atom))]
                    (source/with-snapshot storage
                                          (fn [storage authorize!]
                                            (resolution/capture storage authorize! policy)))))
        defaults (capture)
        home (sp/create-entity storage :ns {:name "personal"})
        editor (sp/create-entity storage :fn
                                 {:name "editor" :namespace-id (:id home)
                                  :parent-ids [resolution/defaults-id]})]
    (check/check-fn-def! (type-check/reconstruct-fn-def storage (:id editor)))
    (let [personal (capture)]
      (is (= resolution/defaults-id (:id defaults)))
      (is (= (:id editor) (:id personal)))
      (is (= (:configuration defaults) (:configuration personal)))
      (is (not= (:revision defaults) (:revision personal)))
      (is (= [resolution/defaults-id] (:parent-ids (sp/read-entity storage :fn (:id editor)))))
      (is (empty? (sp/query-entities storage :binding {:fn-id (:id editor)})))
      (is (empty? (sp/query-entities storage :fn-slot {:fn-id (:id editor)})))
      (sp/update-entity storage :fn (:id editor) {:description "Personal authored description"})
      (is (not= (:revision personal) (:revision (capture)))))
    ;; Historical matches are candidates, not the current selected identity.
    (sp/update-entity storage :fn (:id editor) {:name "old-editor"})
    (is (= resolution/defaults-id (:id (capture))))
    (sp/update-entity storage :fn (:id editor) {:name "editor"})
    (is (= (:id editor) (:id (capture))))
    (sp/delete-entity storage :fn (:id editor))
    (is (= resolution/defaults-id (:id (capture))))))


(deftest explicit-preparation-creates-only-an-inherited-ordinary-editor-root
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        home-name (str "profile-" (random-uuid))
        home (sp/create-entity storage :ns {:name home-name})]
    (epoch/seed-watermark! storage)
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      (tenancy/install-personal-namespace-fn!
        (constantly {:namespace-path home-name :source :fixture :isolated? false}))
      (let [result (preparation/prepare! ctx {:create true})
            root (sp/read-entity storage :fn (:id result))]
        (is (:created result))
        (is (= [(:id result)] (:functions result)))
        (is (= "editor" (:name root)))
        (is (= (:id home) (:namespace-id root)))
        (is (= [resolution/defaults-id] (:parent-ids root)))
        (is (empty? (sp/query-entities storage :binding {:fn-id (:id root)})))
        ;; These direct helper calls bypass HTTP branch-router validation.
        ;; Publication checked the new root; absorb only that completed local
        ;; mutation before simulating the next request.
        (epoch/seed-watermark! storage)
        (is (= {:id (:id result) :created false :functions [] :namespaces []}
               (preparation/prepare! ctx {:create true})))
        (let [policy (snapshot/capture-policy @(or (:rich-types-atom ctx) (registry/active-rich-types-atom)))
              captured (source/with-snapshot storage #(resolution/capture %1 %2 policy))]
          (is (= (:id result) (:id captured)))
          (is (= default-component-roles (set (keys (:configuration captured))))))))))


(deftest preparation-retains-legacy-record-and-observes-later-authored-field-edits
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        home-name (str "legacy-profile-" (random-uuid))
        home (sp/create-entity storage :ns {:name home-name})
        old-id (create-legacy-record! storage home)
        preference (sp/create-entity storage :ui-pref
                                     {:owner-id (tenancy/current-user-id) :key "components"
                                      :org-id (tenancy/current-org)
                                      :value {:fn-id (str old-id) :org (tenancy/current-org)
                                              :branch-id (str (versioned/current-branch-id storage))}})
        capture (fn []
                  (let [policy (snapshot/capture-policy @(registry/active-rich-types-atom))]
                    (source/with-snapshot storage #(resolution/capture %1 %2 policy))))]
    (epoch/seed-watermark! storage)
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      (tenancy/install-personal-namespace-fn!
        (constantly {:namespace-path home-name :source :fixture :isolated? false}))
      (let [result (preparation/prepare! ctx {})
            captured (capture)
            old-binding (some #(when (= "menu-view" (:name (sp/read-entity storage :slot (:slot-id %)))) %)
                              (sp/query-entities storage :binding {:fn-id old-id}))]
        (is (:committed result))
        (is (not= old-id (:id result)))
        (is (not-any? #{old-id} (:functions result)))
        (is (empty? (:namespaces result)))
        (is (= preference (sp/read-entity storage :ui-pref (:id preference))))
        (is (= default-component-roles (set (keys (:configuration captured)))))
        (is (= (ids/fn-id "app.ui-fn-picker" :picker-view)
               (get-in captured [:configuration :account-menu :view])))
        (is (sp/read-entity storage :fn old-id))
        (is (some? old-binding))
        (sp/update-entity storage :binding (:id old-binding)
                          {:ref-fn-id (ids/fn-id "app.ui-account-menu" :account-menu-view)})
        (check/check-fn-def! (type-check/reconstruct-fn-def storage old-id))
        (is (= (ids/fn-id "app.ui-account-menu" :account-menu-view)
               (get-in (capture) [:configuration :account-menu :view])))
        (epoch/seed-watermark! storage)
        (is (= (:id result) (:id (preparation/prepare! ctx {:create true}))))))))


(deftest ordinary-default-and-personal-configuration-use-the-same-plan-handler
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        home-name (str "plan-profile-" (random-uuid))
        _ (sp/create-entity storage :ns {:name home-name})
        submit (fn []
                 (let [response (ga/exec-handler
                                  :_editor-configuration-plan-handler
                                  {:request-method :post :uri "/api/ui/configuration/plan"
                                   :headers {"content-type" "application/json"}
                                   :body (json/generate-string {:component "fn-picker"})})]
                   {:status (:status response) :body (json/parse-string (:body response) true)}))]
    (epoch/seed-watermark! storage)
    (binding [tenancy/*seams-override* (atom (tenancy/seams-isolation-seed))]
      (tenancy/install-personal-namespace-fn!
        (constantly {:namespace-path home-name :source :fixture :isolated? false}))
      (let [default (submit)
            prepared (preparation/prepare! ctx {:create true})]
        (epoch/seed-watermark! storage)
        (let [personal (submit)]
          (is (= 200 (:status default) (:status personal)))
          (is (true? (get-in default [:body :ok])))
          (is (true? (get-in personal [:body :ok])))
          (is (= (str resolution/defaults-id) (get-in default [:body :selection-id])))
          (is (= (str (:id prepared)) (get-in personal [:body :selection-id])))
          (is (= (get-in default [:body :plan]) (get-in personal [:body :plan])))
          (is (string? (get-in personal [:body :configuration-revision]))))))))
