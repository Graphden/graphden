(ns ^:integration ^:serial graphden.editor.component-creation-integration-test
  "The normal graph handlers create a fixed bundle in one JDBC transaction."
  (:require
    [cheshire.core :as json]
    [clojure.java.shell :as sh]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities.delete-batch :as deletion]
    [graphden.crud.entities.function-batch-budget :as batch-budget]
    [graphden.crud.type-check :as reconstruction]
    [graphden.editor.component-bundle :as bundle]
    [graphden.editor.component-config :as config]
    [graphden.editor.component-creation :as creation]
    [graphden.editor.component-templates :as templates]
    [graphden.editor.components :as components]
    [graphden.editor.preference-write :as preferences]
    [graphden.executor.browser-plan :as browser-plan]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.context :as context]
    [graphden.packages.records.ids :as records]
    [graphden.storage.postgres.crud :as pg-crud]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.test-infra.golden-app :as ga]
    [graphden.test-infra.impls :as impls]
    [graphden.types.check :as check]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as resolution]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      Connection)))


(def ^:dynamic *destination-id* nil)


(defn- writable-destination-fixture
  [test-fn]
  (let [storage (:storage ga/*bootstrap*)
        destination (sp/create-entity storage :ns
                                      {:name (str "component-test-" (random-uuid))})]
    (binding [*destination-id* (:id destination)]
      (test-fn))))


(use-fixtures :once
  (ga/fixture (ns-name *ns*))
  (impls/impls-fixture "storage" "branches")
  writable-destination-fixture)


(defn- submit
  [handler input]
  (let [diagnostics (atom [])
        classify creation/failure-response
        response (with-redefs [creation/failure-response
                               (fn [error]
                                 (swap! diagnostics conj
                                        (assoc (select-keys (ex-data error) [:type :reason :fn-id :http-status])
                                               :exception-class (Class/.getName (class error))
                                               :frames (into [] (comp (filter #(str/starts-with?
                                                                                 (StackTraceElement/.getClassName %) "graphden."))
                                                                      (take 6)
                                                                      (map #(vector (StackTraceElement/.getClassName %)
                                                                                    (StackTraceElement/.getMethodName %)
                                                                                    (StackTraceElement/.getLineNumber %))))
                                                             (Throwable/.getStackTrace error))))
                                 (classify error))]
                   (ga/exec-handler handler
                                    {:request-method :post :uri "/api/ui/components/create"
                                     :headers {"content-type" "application/json"}
                                     :body (json/generate-string input)}))]
    (cond-> {:status (:status response) :body (json/parse-string (:body response) true)}
      (seq @diagnostics) (assoc :diagnostics @diagnostics))))


(defn- preview
  ([] (preview nil))
  ([component]
   (submit :_ui-components-create-preview-handler
           (cond-> {:owner "anonymous" :namespace-id (str *destination-id*)}
             component (assoc :component component)))))


(defn- profile-type-publication
  "Count/timestamp actual SQL by finite table family, never SQL or parameters."
  [f stats]
  (let [original jdbc/execute!
        reader (fn [connection sql & opts]
                 (let [table (case (second (re-find #"(?i)\bFROM\s+\"(fn_slot|binding_list_item|binding|slot|fn|ns)(?:_version)?\"" (first sql)))
                               "fn" :fn "fn_slot" :fn-slot "slot" :slot
                               "binding" :binding "binding_list_item" :binding-list-item "ns" :ns :other)
                       start (System/nanoTime)]
                   (try (apply original connection sql opts)
                        (finally
                          (swap! stats update table
                                 (fn [prior]
                                   {:count (inc (or (:count prior) 0))
                                    :elapsed-ms (+ (or (:elapsed-ms prior) 0)
                                                   (/ (- (System/nanoTime) start) 1000000.0))}))))))]
    (with-redefs [jdbc/execute! reader] (f))))


(defn- diagnosed-apply
  "Report only finite phase names/timings if the real handler throws.
   Delegates each watched function unchanged; this serial fixture owns the spies."
  [f]
  (let [phases (atom [])
        watched [#'creation/plan #'creation/create! #'creation/publish
                 #'context/invalidate-graph-cache! #'recheck/record-imported-fn-types!]
        wrappers (into {} (map (fn [v]
                                 [v (let [original @v]
                                      (fn [& args]
                                        (let [start (System/nanoTime)
                                              queries (atom {})
                                              index (dec (count (swap! phases conj
                                                                       {:stage (:name (meta v))
                                                                        :complete false
                                                                        :sql-profile queries})))]
                                          (try (if (= v #'recheck/record-imported-fn-types!)
                                                 (profile-type-publication #(apply original args) queries)
                                                 (apply original args))
                                               (finally
                                                 (when (= v #'recheck/record-imported-fn-types!)
                                                   (println "Type publication SQL profile:" (pr-str @queries)))
                                                 (swap! phases update index assoc :complete true
                                                        :elapsed-ms (long (/ (- (System/nanoTime) start) 1000000))
                                                        :sql-profile @queries))))))])
                               watched))]
    (with-redefs-fn wrappers
      #(try (assoc (f) :creation-phases @phases)
            (catch Exception error
              (println "Creation phase diagnostic:"
                       (pr-str (mapv (fn [phase]
                                       (update phase :sql-profile
                                               (fn [profile] (if (map? profile) profile @profile)))) @phases)))
              (throw error))))))


(defn- apply-preview
  [reserved]
  ;; Preserve the first refusal instead of masking it with an apply(nil) 400.
  (if (= 200 (:status reserved))
    (diagnosed-apply #(submit :_ui-components-create-apply-handler (get-in reserved [:body :request])))
    reserved))


(defn- ids
  [storage entity]
  (set (map :id (sp/query-entities storage entity {}))))


(defn- export-diagnosed
  "Keep diagnostic type shapes in test assertions, never endpoint responses."
  [ctx component]
  (let [diagnostics (atom [])
        watched [#'source/collect-manifest #'config/configuration
                 #'snapshot/validate-source! #'snapshot/export-snapshot #'check/check-fn-def!]
        wrappers (into {} (map (fn [v]
                                 [v (let [original @v]
                                      (fn [& args]
                                        (try (apply original args)
                                             (catch Exception error
                                               (swap! diagnostics conj
                                                      {:stage (:name (meta v))
                                                       :error (select-keys (ex-data error)
                                                                           [:type :reason :fn-id :fn-name :arg-name
                                                                            :expected :actual :declared :computed])})
                                               (throw error)))))])) watched)]
    {:result (with-redefs-fn wrappers #(components/export-current ctx {:component component}))
     :component component :diagnostics @diagnostics}))


(deftest created-manifest-exports-its-passive-and-stateful-views
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        created (apply-preview (preview))
        manifest (get-in created [:body :manifest])
        id (get-in manifest [:roots :configuration-id])]
    (is (:ok (:body created)) (pr-str created))
    (if-let [prior (first (sp/query-entities storage :ui-pref {:owner-id "anonymous" :key "components"}))]
      (sp/update-entity storage :ui-pref (:id prior)
                        {:value {:fn-id id :branch-id (str (vs/current-branch-id storage)) :org "public"}})
      (sp/create-entity storage :ui-pref
                        {:owner-id "anonymous" :key "components" :org-id "public"
                         :value {:fn-id id :branch-id (str (vs/current-branch-id storage)) :org "public"}
                         :updated-at (java.time.Instant/now)}))
    (epoch/seed-watermark! storage)
    (let [{:keys [result] :as diagnosed} (export-diagnosed ctx "recents")]
      (is (:ok result) (pr-str diagnosed)))
    (let [{:keys [result] :as diagnosed} (export-diagnosed ctx "canvas")]
      (is (:ok result) (pr-str diagnosed))
      (is (= (get-in manifest [:roots :canvas-id]) (get-in result [:roots :canvas-id]))))
    (let [{:keys [result] :as diagnosed} (export-diagnosed ctx "canvas-values")]
      (is (:ok result) (pr-str diagnosed))
      (is (= (get-in manifest [:roots :canvas-values-id]) (get-in result [:roots :canvas-values-id]))))
    (let [{:keys [result] :as diagnosed} (export-diagnosed ctx "tutorial-catalog")]
      (is (:ok result) (pr-str diagnosed))
      (is (= (get-in manifest [:roots :tutorial-catalog-id])
             (get-in result [:roots :tutorial-catalog-id])))
      (when (:ok result)
        (let [lesson (fn [id chapter title]
                       {:id id :chapter chapter :title title :done false :stale false :fresh false
                        :allowed true :text-only false :need-short "" :done-version 0 :version 1})
              input {:lessons [(lesson "01" "A" "Match one") (lesson "02" "B" "Other")
                               (lesson "03" "A" "Match three")]
                     :query "match" :resume {:active false :ended false :id "" :title "" :step 0 :steps 0}
                     :text-only-copy "text only"}
              request {:plan (:plan result)
                       :cases [{:entry "view" :inputs {"inputs" (browser-plan/encode-value input)}}]}
              {:keys [exit out err]} (sh/sh "node" "tools/runtime-test/browser-plan-runner.js"
                                            :in (json/generate-string request))]
          (is (zero? exit) err)
          (when (zero? exit)
            (let [response (first (json/parse-string-strict out true))
                  view (when-let [wire (:value response)] (browser-plan/decode-value wire))]
              (is (nil? (:error response)) (:error response))
              (is (= :div (first (:intro view))))
              (is (= ["A"] (mapv :chapter (:sections view))))
              (is (= ["01" "03"] (mapv :id (get-in view [:sections 0 :rows])))))))))))


(defn- graph-identities
  [storage]
  (into {} (map (fn [entity] [entity (ids storage entity)]))
        [:ns :fn :slot :fn-slot :binding :binding-list-item]))


(defn- with-function-count
  [target f]
  (let [original templates/definitions]
    (with-redefs [templates/definitions
                  (fn [root]
                    (let [existing (original root)
                          extra (- target (count existing))]
                      (when (neg? extra)
                        (throw (ex-info "Function count target is below the shipped bundle" {})))
                      (into existing
                            (map (fn [index]
                                   {:name (keyword (str "_batch-proof-" index))
                                    :namespace (str root ".inspector")
                                    :parent :core.logic/const
                                    :args {:value 0} :return-type :int}))
                            (range extra))))]
      (f))))


(deftest full-cohort-over-1000-creates-and-deletes-by-exact-receipt
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        target (max 1001 (count (templates/definitions "proof.root")))
        before (graph-identities storage)]
    (with-function-count target
      #(let [reserved (preview)
             created (apply-preview reserved)
             receipts (get-in created [:body :manifest :functions])]
         (is (= 200 (:status reserved)))
         (is (= 200 (:status created)))
         (is (= target (count receipts)))
         (is (= (get-in reserved [:body :manifest])
                (get-in created [:body :manifest])))
         (let [deleted (deletion/delete-receipts! ctx receipts)]
           (is (= target (count (:deleted deleted))))
           (is (empty? (:already-absent deleted)))
           (is (= (:fn before) (:fn (graph-identities storage)))))))))


(deftest full-cohort-budget-refuses-over-bound-before-create
  (let [storage (:storage ga/*bootstrap*)
        reserved (preview)
        before (graph-identities storage)]
    (with-function-count batch-budget/max-functions
      #(is (= batch-budget/max-functions
              (count (get-in (preview) [:body :manifest :functions])))))
    (with-function-count (inc batch-budget/max-functions)
      #(do
         (is (= 422 (:status (preview))))
         (let [result (submit :_ui-components-create-apply-handler
                              (get-in reserved [:body :request]))]
           (is (= 422 (:status result)))
           (is (false? (get-in result [:body :committed]))))))
    (is (= before (graph-identities storage)))))


(deftest imported-bundle-recheck-shares-namespace-metadata
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        _ (preferences/write! storage "components" nil false nil)
        created (apply-preview (preview "explorer"))
        fn-ids (mapv #(parse-uuid (:id %)) (get-in created [:body :manifest :functions]))
        queries (atom 0)
        connections (atom [])
        profile (atom {})
        original jdbc/execute!
        reader (fn [connection sql & opts]
                 (when (re-find #"(?i)\bFROM\s+\"ns\"" (first sql))
                   (swap! queries inc)
                   (swap! connections conj
                          (when (instance? Connection connection)
                            {:read-only (Connection/.isReadOnly ^Connection connection)
                             :isolation (Connection/.getTransactionIsolation ^Connection connection)})))
                 (apply original connection sql opts))]
    (is (:ok (:body created))
        (pr-str {:status (:status created)
                 :diagnostics (mapv #(select-keys % [:type :reason :http-status :exception-class])
                                    (:diagnostics created))}))
    (when (:ok (:body created))
      (is (seq fn-ids))
      (with-redefs [jdbc/execute! reader]
        (profile-type-publication
          #(recheck/record-imported-fn-types! ctx (vs/current-branch-id storage) fn-ids) profile))
      (is (pos? @queries) "observe actual decorated namespace reads")
      (is (= 1 @queries) "a read-only import recheck shares its namespace metadata")
      (is (= [{:read-only true :isolation Connection/TRANSACTION_REPEATABLE_READ}] @connections))
      (is (pos? (get-in @profile [:fn :count] 0)) "observe actual function metadata reads")
      (is (<= (reduce + (map :count (vals @profile))) (* 16 (count fn-ids)))
          (str "publication must avoid repeated metadata/item reads: " (pr-str @profile)))
      (let [batches (quot (+ (count fn-ids) 255) 256)]
        (doseq [family [:binding :binding-list-item]]
          (is (<= (get-in @profile [family :count] 0) (* 16 batches))
              (str "import owner reads must scale with bounded batches, not function count: "
                   (pr-str (select-keys @profile [:binding :binding-list-item]))))))
      (with-redefs [jdbc/execute! reader]
        (recheck/record-imported-fn-types! ctx (vs/current-branch-id storage) fn-ids))
      (is (= 2 @queries) "a later import obtains fresh metadata instead of a global cached view")
      (let [bound-id (:fn-id (first (sp/query-entities storage :binding {:fn-id fn-ids})))
            row (or (when bound-id (sp/read-entity storage :fn bound-id))
                    (some #(let [row (sp/read-entity storage :fn %)]
                             (when (seq (:parent-ids row)) row)) fn-ids))
            read-name (fn []
                        (jdbc/with-transaction [connection (tx/datasource storage)
                                                {:isolation :repeatable-read :read-only true}]
                                               (let [snapshot (tx/with-connection storage connection)
                                                     ordinary (reconstruction/reconstruct-fn-def snapshot (:id row))]
                                                 (reconstruction/call-with-reconstruction-memo
                                                   snapshot
                                                   (fn []
                                                     (reconstruction/prefetch-reconstruction-owners! snapshot #{(:id row)})
                                                     (let [first-read (reconstruction/reconstruct-fn-def snapshot (:id row))
                                                           repeated-read (reconstruction/reconstruct-fn-def snapshot (:id row))]
                                                       (is (= ordinary first-read) "prefetched owner reconstructs the same graph")
                                                       (is (= first-read repeated-read) "metadata reuse preserves reconstruction")
                                                       (:name repeated-read)))))))]
        (is (some? row))
        (is (= (keyword (:name row)) (read-name)))
        (try
          (sp/update-entity storage :fn (:id row) {:name "metadata-freshness-proof"})
          (is (= :metadata-freshness-proof (read-name))
              "a later snapshot observes changed metadata for the same identity")
          (finally
            (sp/update-entity storage :fn (:id row) {:name (:name row)})))))))


(deftest preview-is-read-only-and-apply-is-exact-create-only
  (let [storage (:storage ga/*bootstrap*)
        before (graph-identities storage)
        source (sp/read-entity storage :fn config/configuration-id)
        reserved (preview)]
    (is (= 200 (:status reserved)) (pr-str reserved))
    (is (true? (get-in reserved [:body :ok])))
    (is (= before (graph-identities storage)))
    (let [namespaces (get-in reserved [:body :manifest :namespaces])
          root (:path (first namespaces))
          paths (mapv :path namespaces)]
      (is (= (set (map :namespace (templates/definitions root))) (set paths))
          "preview reserves exactly the authored template namespaces")
      (is (= (count paths) (count (set paths)))
          "each authored namespace is reserved once"))
    (is (batch-budget/accepted-count?
          (count (get-in reserved [:body :manifest :functions])))
        "the full editable UI template must fit the atomic cleanup budget")
    (let [created (apply-preview reserved)
          manifest (get-in created [:body :manifest])]
      (is (= 200 (:status created)) (pr-str created))
      (is (true? (get-in created [:body :committed])))
      (is (= #{'plan 'create! 'publish 'invalidate-graph-cache! 'record-imported-fn-types!}
             (set (map :stage (:creation-phases created))))
          "phase probes must observe the real write and derived-state publication")
      (is (every? :complete (:creation-phases created)))
      (is (= (get-in reserved [:body :manifest]) manifest))
      (doseq [row (:functions manifest)]
        (let [actual (sp/read-entity storage :fn (parse-uuid (:id row)))]
          (is (= (:name row) (:name actual)))
          (is (= (parse-uuid (:namespace-id row)) (:namespace-id actual)))))
      (is (= source (sp/read-entity storage :fn config/configuration-id)))
      (let [after (graph-identities storage)
            duplicate (apply-preview reserved)]
        (is (= 409 (:status duplicate)))
        (is (false? (get-in duplicate [:body :committed])))
        (is (= after (graph-identities storage)))))))


(deftest exact-manifest-or-session-change-refuses-before-writing
  (let [storage (:storage ga/*bootstrap*)
        reserved (preview)
        command (get-in reserved [:body :request])
        before (graph-identities storage)]
    (doseq [changed [(assoc command :expected-state "changed")
                     (assoc command :root-id (str (random-uuid)))
                     (assoc command :owner "other-account")
                     (assoc command :branch-id (str (random-uuid)))
                     (assoc command :fn-defs [])]]
      (let [result (submit :_ui-components-create-apply-handler changed)]
        (is (false? (get-in result [:body :ok])))
        (is (false? (get-in result [:body :committed])))
        (is (= before (graph-identities storage)))))))


(deftest mid-bundle-sql-failure-rolls-back-namespaces-and-every-owned-row
  (let [storage (:storage ga/*bootstrap*)
        reserved (preview)
        before (graph-identities storage)
        writes (atom 0)
        original @#'pg-crud/create-entities]
    (binding [pg-crud/*create-entities-override*
              (fn [datasource entity data fields]
                (when (and (= entity :binding-list-item) (= 2 (swap! writes inc)))
                  (throw (ex-info "Injected SQL failure after an earlier batch" {})))
                (binding [pg-crud/*create-entities-override* nil]
                  (original datasource entity data fields)))]
      (let [result (apply-preview reserved)]
        (is (= 500 (:status result)) "A rolled-back internal SQL failure is not an authorization refusal")
        (is (false? (get-in result [:body :committed])))
        (is (= before (graph-identities storage)))))
    (is (<= 2 @writes) "a prior item chunk committed inside the outer transaction")
    (is (:ok (:body (apply-preview reserved))) "same reservation can be retried after rollback")))


(deftest duplicate-identity-in-different-batch-refuses-before-writing
  (let [storage (:storage ga/*bootstrap*)
        before (graph-identities storage)
        original @#'bundle/parse]
    (with-redefs [bundle/parse
                  (fn [& args]
                    (let [parsed (apply original args)
                          first-fn (first (filter #(= :fn (:kind %)) (:records parsed)))]
                      (update parsed :records conj
                              (assoc first-fn :name :_duplicate-after-first-chunk))))]
      (let [result (preview)]
        (is (= 409 (:status result)))
        (is (= before (graph-identities storage)))))))


(deftest mismatched-batch-result-rolls-back-the-whole-cohort
  (let [storage (:storage ga/*bootstrap*)
        reserved (preview)
        before (graph-identities storage)
        original @#'vs/create-entities-versioned!]
    ;; The PG batch seam sits below the versioned adapter, which returns
    ;; authored rows; inject at that adapter to exercise create!'s receipt.
    (with-redefs-fn
      {#'vs/create-entities-versioned!
       (fn [base branch entity rows]
         (let [created (original base branch entity rows)]
           (if (= entity :binding-list-item)
             (assoc (vec created) 0 (assoc (first created) :id (random-uuid)))
             created)))}
      #(let [result (apply-preview reserved)]
         (is (= 403 (:status result)))
         (is (false? (get-in result [:body :committed])))
         (is (= before (graph-identities storage)))))))


(deftest quota-refusal-returns-429-and-rolls-back-the-bundle
  (let [storage (:storage ga/*bootstrap*)
        reserved (preview)
        before (graph-identities storage)
        original @#'pg-crud/create-entities]
    (binding [pg-crud/*create-entities-override*
              (fn [datasource entity data fields]
                (when (= entity :fn)
                  (throw (ex-info "Private quota diagnostic"
                                  {:type :quota/entity-limit :org "private-org" :entity :fn})))
                (binding [pg-crud/*create-entities-override* nil]
                  (original datasource entity data fields)))]
      (let [result (apply-preview reserved)]
        (is (= 429 (:status result)))
        (is (= {:ok false :committed false
                :reason "Your plan's graph limit has been reached. Free capacity or upgrade your plan before creating UI graphs."}
               (:body result)))
        (is (= before (graph-identities storage)))))))


(defn- guarded-jdbc-reader
  [original bodies configuration-id queries]
  (fn [connection sql & opts]
    (when (re-find #"(?i)\bSELECT\b" (first sql))
      (let [targets? (boolean (some #(contains? bodies (str %)) (rest sql)))
            body? (boolean (re-find #"(?i)\b(?:FROM|JOIN)\s+\"(?:binding|fn_slot)(?:_version)?\"" (first sql)))]
        (swap! queries conj {:retained-root? (boolean (some #(= configuration-id (str %)) (rest sql)))
                             :retained-target? targets? :body? body?})
        (when (and targets? body?)
          (throw (ex-info "Retained body must not be read" {:type :test/forbidden-body-read})))))
    (apply original connection sql opts)))


(deftest two-independent-copies-export-only-the-selected-component
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        manifests (mapv (fn [_] (get-in (apply-preview (preview)) [:body :manifest])) (range 2))
        roots (mapv #(get-in % [:roots :configuration-id]) manifests)]
    (is (apply not= roots))
    (doseq [manifest manifests]
      (let [id (parse-uuid (get-in manifest [:roots :configuration-id]))
            preference {:fn-id (str id) :branch-id (str (vs/current-branch-id storage)) :org "public"}
            prior (first (sp/query-entities storage :ui-pref {:owner-id "anonymous" :key "components"}))]
        (if prior
          (sp/update-entity storage :ui-pref (:id prior) {:value preference})
          (sp/create-entity storage :ui-pref {:owner-id "anonymous" :key "components" :value preference
                                              :org-id "public" :updated-at (java.time.Instant/now)}))
        ;; Publication updated this ctx's graph/type slices. No concurrent
        ;; writer exists in this serial fixture; certify that exact basis.
        (epoch/seed-watermark! storage)
        (doseq [component ["account-menu" "fn-picker" "recents" "inspector"]]
          (let [queries (atom [])
                component-roots {"account-menu" [:menu-id :menu-update-id]
                                 "fn-picker" [:picker-id]
                                 "recents" [:recents-id :recents-initial-id :recents-update-id]
                                 "inspector" [:inspector-id :inspector-initial-id :inspector-update-id]}
                body-ids (into #{} (map #(get-in manifest [:roots %]))
                               (mapcat val (dissoc component-roots component)))
                reader (guarded-jdbc-reader jdbc/execute! body-ids (str id) queries)
                _ (with-redefs [jdbc/execute! reader]
                    (is (thrown? Exception
                          (resolution/call-with-fresh-memos
                            #(sp/query-entities storage :binding {:fn-id (parse-uuid (first body-ids))}))))
                    (is (some #(and (:body? %) (:retained-target? %)) @queries)))
                _ (reset! queries [])
                {:keys [result] :as diagnosed} (with-redefs [jdbc/execute! reader]
                                                 (export-diagnosed ctx component))]
            (is (some :retained-root? @queries) "actual selected root metadata was read")
            (is (not-any? #(and (:body? %) (:retained-target? %)) @queries)
                "nonselected component identities never cause body reads")
            (is (:ok result) (pr-str diagnosed))
            (is (= (str id) (:selection-id result)))
            (is (= (str id) (get-in result [:roots :configuration-id])))
            (is (empty? (set/intersection
                          (into #{} (map :id) (get-in result [:plan :functions]))
                          (into #{} (mapcat #(map :id (:functions %)))
                                (remove #{manifest} manifests)))))))))))


(deftest legacy-four-slot-preferences-remain-functional-and-missing-components-fallback
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        applied (apply-preview (preview))
        manifest (get-in applied [:body :manifest])
        publication (some #(when (= 'record-imported-fn-types! (:stage %)) %)
                          (:creation-phases applied))
        batches (quot (+ (count (:functions manifest)) 255) 256)
        root (:path (first (:namespaces manifest)))
        id (records/fn-id root :legacy-ui)
        definition {:name :legacy-ui :namespace root :parent :app.ui-components/ui-components
                    :args {:menu-initial (keyword (str root ".menu") "account-menu-initial")
                           :menu-update (keyword (str root ".menu") "account-menu-update")
                           :menu-view (keyword (str root ".menu") "account-menu-view")
                           :picker-view (keyword (str root ".picker") "picker-view")}}
        preference {:fn-id (str id) :branch-id (str (vs/current-branch-id storage)) :org "public"}]
    (is (= 200 (:status applied)))
    (is (:complete publication) "the full legacy import publishes its types synchronously")
    (doseq [family [:binding :binding-list-item]]
      (is (<= (get-in publication [:sql-profile family :count] 0) (* 16 batches))
          (str "full import owner reads must scale with batches: " family)))
    ((impls/impl-of :sync-fn-defs-branch!)
     {:branch-id (vs/current-branch-id storage) :fn-defs [definition]} ctx)
    (if-let [prior (first (sp/query-entities storage :ui-pref {:owner-id "anonymous" :key "components"}))]
      (sp/update-entity storage :ui-pref (:id prior) {:value preference})
      (sp/create-entity storage :ui-pref {:owner-id "anonymous" :key "components" :value preference
                                          :org-id "public" :updated-at (java.time.Instant/now)}))
    (epoch/seed-watermark! storage)
    (doseq [component ["account-menu" "fn-picker"]]
      (is (:ok (components/export-current ctx {:component component}))))
    (is (= "component-missing" (:code (components/export-current ctx {:component "recents"}))))
    (is (= "builtin" (:fallback (components/export-current ctx {:component "recents"}))))))


(defn- activate-selected
  [storage manifest]
  (let [preference {:fn-id (get-in manifest [:roots :configuration-id])
                    :branch-id (str (vs/current-branch-id storage)) :org "public"}
        result (preferences/write! storage "components" preference true (:expected-preference manifest))]
    ;; These fixtures call handlers directly, without the production router
    ;; that advances the policy watermark after the completed graph writes.
    (when (= :saved result) (epoch/seed-watermark! storage))
    result))


(defn- selective-retention-proof
  [component]
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        _ (preferences/write! storage "components" nil false nil)
        theme-before (sp/query-entities storage :ui-pref {:owner-id "anonymous" :key "theme"})
        first-copy (get-in (apply-preview (preview "fn-picker")) [:body :manifest])
        first-config (get-in first-copy [:roots :configuration-id])
        picker-id (get-in first-copy [:roots :picker-id])
        picker-ns (:id (first (filter #(= "picker" (:name %)) (:namespaces first-copy))))
        bodies (into #{} (map :id) (filter #(= picker-ns (:namespace-id %)) (:functions first-copy)))
        leaf (first (filter #(= "picker-active-background" (:name %)) (:functions first-copy)))
        binding (first (sp/query-entities storage :binding {:fn-id (java.util.UUID/fromString (:id leaf))}))
        original-query jdbc/execute!
        queries (atom [])]
    (is (= #{"picker"} (set (map :name (rest (:namespaces first-copy))))))
    (is (= :saved (activate-selected storage first-copy)))
    ;; A selected graph is identified by UUID, including an anonymous root.
    ;; Recheck the authored edit rather than retaining a stale certificate.
    (sp/update-entity storage :fn (java.util.UUID/fromString first-config) {:name nil})
    (sp/update-entity storage :binding (:id binding) {:value "#fed7aa" :value-present true})
    (let [changed [(java.util.UUID/fromString first-config) (java.util.UUID/fromString (:id leaf))]]
      (context/invalidate-graph-cache! ctx changed)
      (recheck/record-imported-fn-types! ctx (vs/current-branch-id storage) changed))
    (epoch/seed-watermark! storage)
    (epoch/note-graph-epoch-validated! storage)
    (let [reader (guarded-jdbc-reader original-query bodies first-config queries)
          _ (with-redefs [jdbc/execute! reader]
              ;; Positive control: prove this observer rejects an actual old
              ;; body query on the same decorated graph storage.
              (is (thrown? Exception
                    (resolution/call-with-fresh-memos
                      #(sp/query-entities storage :binding
                                          {:fn-id (java.util.UUID/fromString (:id leaf))}))))
              (is (some #(and (:body? %) (:retained-target? %)) @queries)))
          _ (reset! queries [])
          created (with-redefs [jdbc/execute! reader]
                    (apply-preview (preview component)))
          added (get-in created [:body :manifest])]
      (is (= 200 (:status created)) (pr-str created))
      (is (some :retained-root? @queries) "actual selected root metadata was read")
      (is (some :retained-target? @queries) "retained entry metadata was read")
      (is (not-any? #(and (:body? %) (:retained-target? %)) @queries))
      (is (= #{component} (set (map :name (rest (:namespaces added))))))
      (when (= component "explorer")
        (is (<= (count (:functions added)) 512)
            "the selective Explorer copy must fit its existing function budget"))
      (is (empty? (set/intersection (set (map :id (:functions first-copy))) (set (map :id (:functions added))))))
      (is (= :saved (activate-selected storage added)))
      (is (= "#fed7aa" (:value (sp/read-entity storage :binding (:id binding)))))
      (is (= theme-before (sp/query-entities storage :ui-pref {:owner-id "anonymous" :key "theme"})))
      (let [{:keys [result] :as diagnosed} (export-diagnosed ctx component)]
        (is (:ok result) (pr-str (assoc diagnosed :retained-configuration-id first-config)))
        (is (= picker-id (get-in result [:roots :picker-id])))
        (is (not= first-config (:selection-id result))))
      (let [before (graph-identities storage)
            duplicate (preview "fn-picker")]
        (is (= 409 (:status duplicate)))
        (is (= before (graph-identities storage)))))))


(deftest selective-copy-retains-edited-components-without-reading-their-bodies-or-changing-theme
  (selective-retention-proof "recents"))


(deftest selective-explorer-retains-anonymous-manifest-and-edited-picker-without-body-reads
  (selective-retention-proof "explorer"))


(deftest selective-inspector-retains-anonymous-manifest-and-edited-picker-without-body-reads
  (selective-retention-proof "inspector"))


(deftest selective-apply-rejects-a-changed-server-preference-before-any-create
  (let [storage (:storage ga/*bootstrap*)
        _ (preferences/write! storage "components" nil false nil)
        reserved (preview "recents")
        other (get-in (apply-preview (preview "fn-picker")) [:body :manifest])]
    (is (= :saved (activate-selected storage other)))
    (let [before (graph-identities storage)
          stale (apply-preview reserved)]
      (is (= 409 (:status stale)))
      (is (= before (graph-identities storage))))))


(deftest legacy-selection-addition-adapts-original-identities-without-copying-old-groups
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        _ (preferences/write! storage "components" nil false nil)
        full (get-in (apply-preview (preview)) [:body :manifest])
        root (:path (first (:namespaces full)))
        legacy-id (records/fn-id root :legacy-addition)
        definition {:name :legacy-addition :namespace root :parent :app.ui-components/ui-components
                    :args {:menu-initial (keyword (str root ".menu") "account-menu-initial")
                           :menu-update (keyword (str root ".menu") "account-menu-update")
                           :menu-view (keyword (str root ".menu") "account-menu-view")
                           :picker-view (keyword (str root ".picker") "picker-view")}}]
    ((impls/impl-of :sync-fn-defs-branch!) {:branch-id (vs/current-branch-id storage) :fn-defs [definition]} ctx)
    (preferences/write! storage "components"
                        {:fn-id (str legacy-id) :branch-id (str (vs/current-branch-id storage)) :org "public"} false nil)
    (epoch/seed-watermark! storage)
    (let [created (apply-preview (preview "recents"))
          added (get-in created [:body :manifest])]
      (is (= 200 (:status created)) (pr-str created))
      (is (= #{"recents"} (set (map :name (rest (:namespaces added))))))
      (is (empty? (set/intersection (set (map :id (:functions full))) (set (map :id (:functions added))))))
      (is (= :saved (activate-selected storage added)))
      (let [{:keys [result] :as diagnosed} (export-diagnosed ctx "recents")]
        (is (:ok result) (pr-str diagnosed))
        (is (= (get-in full [:roots :menu-id]) (get-in result [:roots :menu-id])))
        (is (= (get-in full [:roots :picker-id]) (get-in result [:roots :picker-id])))))))
