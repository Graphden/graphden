(ns ^:integration ^:serial graphden.editor.theme-integration-test
  "Normal imported graphs evaluated by the actual HTTP handler and executor."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.fn-execution :as execution]
    [graphden.editor.theme :as theme]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.context :as context]
    [graphden.packages.records :as records]
    [graphden.packages.sync :as pkg-sync]
    [graphden.storage.postgres.notify :as notify]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.system.init.services]
    [graphden.test-infra.golden-app :as ga]
    [graphden.test-infra.impls :as impls]
    [graphden.types.diagnostics :as diagnostics]
    [graphden.versioning.storage.core :as vs]
    [integrant.core :as ig]))


(use-fixtures :once
  (ga/fixture (ns-name *ns*))
  (impls/impls-fixture "storage" "branches"))


(defn- submit
  [id]
  (let [response (ga/exec-handler :_ui-theme-evaluate-handler
                                  {:request-method :post :uri "/api/ui/theme/evaluate"
                                   :headers {"content-type" "application/json"}
                                   :body (json/generate-string {:fn-id (str id) :org "public" :owner "anonymous" :args {}})})]
    {:status (:status response) :body (json/parse-string (:body response) true)}))


(deftest ordinary-import-evaluates-through-http-without-exporting-implementation
  (binding [router/*active-router-override* (atom nil)
            diagnostics/*diagnostics-override* (atom {})]
    (let [ctx (:ctx ga/*bootstrap*)
          graph-ns "test.personal-theme"
          defs (:fns (edn/read-string (slurp "resources/packages/app/ui-theme-template/fns.edn")))
          id (records/fn-id graph-ns :theme)]
      ((impls/impl-of :sync-fn-defs-branch!)
       {:branch-id (vs/current-branch-id (:storage ctx))
        :fn-defs (mapv #(assoc % :namespace graph-ns) defs)} ctx)
      (is (= {:status 200
              :body {:ok true :payload {:mode "light" :tokens {:--gd-flow "#2563eb" :--bg "#f8fafc"}
                                        :fonts {} :scale 100}}}
             (submit id)))
      ;; HTTP and direct entry agree; neither requires view-impl or a browser
      ;; plan, and the response contains only the ordinary theme result.
      (is (:ok (theme/evaluate ctx {:fn-id (str id) :org "public" :owner "anonymous"})))
      (is (= {:status 403 :body {:ok false :reason "unavailable"}}
             (submit (random-uuid))))
      ;; Deliberately inconsistent trusted test closures verify the runtime
      ;; backstop, independent of the static purity selection above it.
      (let [replace-root (fn [body]
                           (assoc ctx :compiled-registry
                                  (atom (assoc @(:compiled-registry ctx) id
                                               (fn [_ _] (body))))))
            effects (atom [])
            effectful (replace-root #(do (runtime/record-effect! :network)
                                         (swap! effects conj :leaked)
                                         {:tokens {"--bg" "#123"}}))]
        (is (false? (:ok (theme/evaluate effectful {:fn-id (str id) :org "public" :owner "anonymous"}))))
        (is (empty? @effects))
        (let [stopped (promise)
              slow (replace-root #(try (Thread/sleep 10000)
                                       {:tokens {"--bg" "#123"}}
                                       (finally (deliver stopped true))))
              result (theme/evaluate slow {:fn-id (str id) :org "public" :owner "anonymous"})]
          (is (false? (:ok result)))
          (is (not (contains? result :execution-id)))
          (is (true? (deref stopped 3000 :worker-not-cancelled))))))))


(defn- create-theme
  [input]
  (let [response (ga/exec-handler :_ui-theme-create-handler
                                  {:request-method :post :uri "/api/ui/theme/create"
                                   :headers {"content-type" "application/json"}
                                   :body (json/generate-string input)})]
    {:status (:status response) :body (json/parse-string (:body response) true)}))


(defn- expect-recovery
  [response]
  (is (= 422 (:status response)))
  (is (= #{:ok :reason :namespace} (set (keys (:body response)))))
  (is (= {:ok false :reason "create-failed"} (select-keys (:body response) [:ok :reason])))
  (is (string? (get-in response [:body :namespace])))
  (get-in response [:body :namespace]))


(deftest create-template-uses-normal-writes-under-existing-parent-or-root
  (binding [router/*active-router-override* (atom nil)
            diagnostics/*diagnostics-override* (atom {})]
    (let [ctx (:ctx ga/*bootstrap*)
          storage (:storage ctx)
          parent (entities/create-entity "ns" {:name "theme-owner" :parent-id nil} ctx)
          before (set (map :id (sp/query-entities storage :ns {})))]
      (doseq [parent-id [(:id parent) nil]]
        (let [result (create-theme {:namespace-id (some-> parent-id str) :owner "anonymous"})
              path (get-in result [:body :namespace])
              added (remove #(contains? before (:id %)) (sp/query-entities storage :ns {}))
              child (first (filter #(and (= parent-id (:parent-id %))
                                         (= (last (str/split (or path "") #"\.")) (:name %))) added))]
          (is (= 200 (:status result)))
          (is (true? (get-in result [:body :ok])))
          (is (string? path))
          (is (re-matches #"(?:theme-owner\.)?theme_[0-9a-f-]{36}" (or path "")))
          (is (some? child))
          (is (= 8 (count (sp/query-entities storage :fn {:namespace-id (:id child)}))))
          (is (= 200 (:status (submit (records/fn-id path :theme)))))))
      (is (= parent (sp/read-entity storage :ns (:id parent)))))))


(deftest create-template-refuses-stale-owner-and-caller-destination-before-writes
  (let [ctx (:ctx ga/*bootstrap*)
        storage (:storage ctx)
        before (set (map :id (sp/query-entities storage :ns {})))]
    (doseq [input [{:namespace-id nil :owner "different-user"}
                   {:namespace-id nil}
                   {:namespace-id "malformed" :owner "anonymous"}
                   {:namespace-id (str (random-uuid)) :owner "anonymous"}
                   {:namespace-id nil :owner "anonymous" :namespace "core"}
                   {:namespace-id nil :owner "anonymous" :fn-defs []}]]
      (is (= {:status 403 :body {:ok false :reason "create-unavailable"}}
             (create-theme input)))
      (is (= before (set (map :id (sp/query-entities storage :ns {}))))))
    ;; The same primitive used by the regular entity API remains the writer;
    ;; a deployment's normal namespace guard can reject root or child creation.
    (with-redefs [entities/create-entity
                  (fn [& _] (throw (ex-info "private grant details" {:type :authz/forbidden})))]
      (is (= {:status 422 :body {:ok false :reason "create-failed"}}
             (create-theme {:namespace-id nil :owner "anonymous"}))))
    (is (= before (set (map :id (sp/query-entities storage :ns {})))))))


(deftest failed-template-sync-removes-only-new-fns-and-reports-the-reserved-namespace
  (binding [router/*active-router-override* (atom nil)
            diagnostics/*diagnostics-override* (atom {})]
    (let [ctx (:ctx ga/*bootstrap*)
          storage (:storage ctx)
          before-ns (set (map :id (sp/query-entities storage :ns {})))
          before-fns (set (map :id (sp/query-entities storage :fn {})))
          original-sync pkg-sync/sync-bundle!
          partial-import (atom nil)]
      (with-redefs [pkg-sync/sync-bundle!
                    (fn [target defs]
                      ;; Real namespace + fn rows land before the simulated
                      ;; later storage failure. Rollback must remove these too.
                      (reset! partial-import (original-sync target (take 3 defs)))
                      (throw (ex-info "private failed import" {:secret "unreported"})))]
        (expect-recovery (create-theme {:namespace-id nil :owner "anonymous"})))
      (is (= 3 (count @partial-import)))
      (let [added (remove #(contains? before-ns (:id %)) (sp/query-entities storage :ns {}))]
        (is (= 1 (count added)))
        (is (empty? (sp/query-entities storage :fn {:namespace-id (:id (first added))}))))
      (is (= before-fns (set (map :id (sp/query-entities storage :fn {}))))))))


(deftest fn-definition-id-is-the-importers-exact-identity
  (let [ctx (:ctx ga/*bootstrap*)
        evaluate #(runtime/execute ctx (ga/fn-id :fn-def-id) {:fn-def %})]
    (is (= (records/fn-id "owner.theme" :theme)
           (evaluate {:namespace "owner.theme" :name :theme :description "ignored"})))
    (is (= (records/fn-id nil :theme) (evaluate {:name :theme})))
    (is (not= (evaluate {:namespace "owner.a" :name :theme})
              (evaluate {:namespace "owner.b" :name :theme})))))


(deftest rollback-preserves-a-concurrent-replacement-identity
  (binding [router/*active-router-override* (atom nil)
            diagnostics/*diagnostics-override* (atom {})]
    (let [storage (:storage (:ctx ga/*bootstrap*))
          original-sync pkg-sync/sync-bundle!
          own-ids (atom [])
          concurrent (atom nil)
          insert-error (atom nil)]
      (with-redefs [pkg-sync/sync-bundle!
                    (fn [target defs]
                      (let [ids (original-sync target (take 3 defs))
                            first-row (sp/read-entity target :fn (first ids))]
                        (reset! own-ids ids)
                        ;; A second writer removes the just-written fn and
                        ;; recreates its name with a new identity. Live names
                        ;; remain unique; compensation must preserve the new fn.
                        (binding [vs/*tombstone-delete?* true]
                          (sp/delete-entity target :fn (first ids)))
                        (try
                          (let [created (sp/create-entity target :fn
                                                          {:id (random-uuid)
                                                           :name (:name first-row)
                                                           :namespace-id (:namespace-id first-row)
                                                           :parent-ids (:parent-ids first-row)})]
                            ;; Compare persisted rows on both sides: create's
                            ;; sparse return omits nullable/version defaults.
                            (reset! concurrent (sp/read-entity target :fn (:id created))))
                          (catch Exception error
                            (reset! insert-error {:message (ex-message error) :data (ex-data error)})))
                        (throw (ex-info "simulated late sync failure" {}))))]
        (expect-recovery (create-theme {:namespace-id nil :owner "anonymous"})))
      (is (nil? @insert-error) (pr-str @insert-error))
      (is (= 3 (count @own-ids)))
      (is (some? @concurrent))
      (is (every? nil? (map #(sp/read-entity storage :fn %) @own-ids)))
      (is (= @concurrent (sp/read-entity storage :fn (:id @concurrent))))
      (is (some? (sp/read-entity storage :ns (:namespace-id @concurrent)))))))


(deftest delayed-write-notifications-preserve-theme-snapshot-guard
  (binding [router/*active-router-override* (atom nil)
            diagnostics/*diagnostics-override* (atom {})]
    (let [events (atom [])
          emitter-meta (meta (notify/make-emitter nil))
          emitter (with-meta
                    (fn [event]
                      (swap! events conj
                             (notify/parse-payload
                               (notify/format-payload
                                 (assoc event :emitter (::notify/emitter-id emitter-meta))))))
                    emitter-meta)
          ctx (assoc (:ctx ga/*bootstrap*) :notify-emitter emitter)
          active (router/create-router ctx "_ui-theme-evaluate-handler")
          listener {:callbacks (atom #{})}
          component (ig/init-key :exec/service-reconciler
                                 {:context ctx :packages {:seeded-services []}
                                  :notify-listener listener
                                  :reconcile-fn (fn [& _] nil)
                                  :stop-all-fn (fn [& _] nil)})
          handle (:notify-callback component)
          apply-execute execution/apply-execute]
      (router/set-active-router! active)
      (try
        (binding [ga/*bootstrap* (assoc ga/*bootstrap* :ctx ctx)]
          (let [created (create-theme {:namespace-id nil :owner "anonymous"})
                id (records/fn-id (get-in created [:body :namespace]) :theme)
                input {:fn-id (str id) :org "public" :owner "anonymous"}]
            (is (= 200 (:status created)))
            ;; A real CRUD mutation performs local invalidation before its
            ;; event enters the delayed transport. Replaying that event must
            ;; not invalidate a later, already-current theme evaluation.
            (entities/update-entity "fn" id {:description "updated theme"} ctx)
            (let [event (last @events)
                  child (vs/create-branch! (:storage ctx) "theme-notify-child")
                  child-ctx (router/ctx-for active (:id child))
                  child-before (context/invalidation-epoch child-ctx)
                  before (context/invalidation-epoch ctx)]
              (is (notify/own-event? emitter event))
              (with-redefs [execution/apply-execute
                            (fn [& args]
                              (let [result (apply apply-execute args)]
                                (handle event)
                                result))]
                (is (:ok (theme/evaluate ctx input))))
              (is (= before (context/invalidation-epoch ctx)))
              (is (< child-before (context/invalidation-epoch child-ctx))
                  "own echoes still propagate to cached inheriting branches")
              ;; Foreign and older emitters still invalidate the result.
              (doseq [external [(assoc event :emitter (str (random-uuid)))
                                (dissoc event :emitter)]]
                (with-redefs [execution/apply-execute
                              (fn [& args]
                                (let [result (apply apply-execute args)]
                                  (handle external)
                                  result))]
                  (is (= "graph-changed" (:code (theme/evaluate ctx input)))))))
            ;; A genuine local mutation after execution still discards the
            ;; result even though its future notification is an own echo.
            (with-redefs [execution/apply-execute
                          (fn [& args]
                            (let [result (apply apply-execute args)]
                              (entities/update-entity "fn" id {:description "later write"} ctx)
                              result))]
              (is (= "graph-changed" (:code (theme/evaluate ctx input)))))))
        (finally
          (ig/halt-key! :exec/service-reconciler component))))))
