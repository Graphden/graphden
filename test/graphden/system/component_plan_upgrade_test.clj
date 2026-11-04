(ns ^:integration ^:serial graphden.system.component-plan-upgrade-test
  "Upgrade the retired component endpoint through production package bootstrap.
   Uses a fresh fixture DB; no deployed database or external vault is accessed."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.editor.config-resolution :as resolution]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.loader :as loader]
    [graphden.packages.records :as records]
    [graphden.packages.sync :as packages]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.test-infra.seams :as seams]
    [graphden.versioning.identity-repair :as repair]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (setup/create-container-fixture))
(use-fixtures :each exec/with-clean-registry exec/with-isolated-rich-types seams/isolated-seams-fixture)


(def ^:private retired-module "app.ui-components")
(def ^:private retired-names [:_ui-components-plan :_ui-components-plan-handler :api-ui-components-plan])


(defn- with-retired-endpoint
  "Reconstruct only the former declaration surface; its implementation is
   deliberately unavailable so accidentally executing it fails the test."
  [current]
  (let [primitive {:namespace retired-module :args {:input {:type :jsonb}}
                   :return-type :keyword-map :effects #{:db} :taint-propagate? true
                   :impl (fn [& _] (throw (AssertionError. "Retired endpoint executed")))}
        definitions [{:namespace retired-module :name :_ui-components-plan-handler
                      :parent :json-envelope-response :lambda-params [:request]
                      :args {:envelope {:parent :_ui-components-plan :args {:input :parse-json-body}}}}
                     {:namespace retired-module :name :api-ui-components-plan :parent :app.routes.auth/post
                      :args {:path "/ui/components/plan" :handler :_ui-components-plan-handler}}]]
    (-> current
        (assoc-in [:base-fn-defs :_ui-components-plan] primitive)
        (update :base-fn-pairs conj [retired-module :_ui-components-plan])
        (update :fn-defs
                (fn [fns]
                  (into (mapv (fn [definition]
                                (if (and (= "app.routes" (:namespace definition))
                                         (= :api-routes (:name definition)))
                                  (update-in definition [:args :items] conj :api-ui-components-plan)
                                  definition)) fns)
                        definitions))))))


(defn- boot-packages!
  "The same two stages as :exec/base-fns and :exec/fn-entities. No sweep or
   allowlist bypass: this test proves the upgrade rather than fast seeding."
  [storage corpus]
  (let [info (packages/register-base-fns-from-packages! storage corpus)]
    (packages/sync-fn-entities-from-packages! storage corpus info)
    info))


(defn- unrelated-state!
  "Core-owned access metadata and a secret declaration are preservation
   sentinels. Private tenancy grant tables require their own addon coverage."
  [storage]
  (let [namespace (sp/create-entity storage :ns {:name "upgrade-owner" :org-id "upgrade-org"})
        function (sp/create-entity storage :fn {:name "retained" :namespace-id (:id namespace)
                                                :parent-ids [] :org-id "upgrade-org"})
        preference (sp/create-entity storage :ui-pref
                                     {:owner-id "upgrade-owner" :key "theme" :org-id "upgrade-org"
                                      :value {:theme "retained"}})
        artifact (sp/create-entity storage :package-version
                                   {:name "upgrade.retained" :version "1.0.0" :ns-root "upgrade-owner"
                                    :fns [] :dependencies {} :content-hash (apply str (repeat 64 "a"))
                                    :org-id "upgrade-org" :public? false
                                    :secrets [{:path "fixture-only/retained" :fn-id (str (:id function))}]})]
    (mapv (fn [[entity row]] [entity (:id row) (sp/read-entity storage entity (:id row))])
          [[:ns namespace] [:fn function] [:ui-pref preference] [:package-version artifact]])))


(defn- assert-new-plan!
  [storage info]
  (let [ctx (exec/create-context {:storage storage :base-fns (:base-fns info)})]
    ;; Full compile after reconciliation detects retained ghosts with missing
    ;; implementations; a successful declaration-only parse is insufficient.
    (cr/rebuild! ctx)
    (epoch/seed-watermark! storage)
    (let [response (setup/via-graph (assoc info :ctx ctx)
                                    :_editor-configuration-plan-handler
                                    {:request-method :post :uri "/api/ui/configuration/plan"
                                     :headers {"content-type" "application/json"}
                                     :body (json/generate-string {:component "fn-picker"})})
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (true? (:ok body)))
      (is (= (str resolution/defaults-id) (:selection-id body)))
      (is (seq (get-in body [:plan :functions]))))))


(deftest boot-sync-retires-the-whole-component-route-without-history-cleanup
  (let [raw (setup/create-test-storage)]
    (try
      (let [storage (vs/wrap-with-versioning raw "main")
            current (loader/load-packages ["core" "web" "app"])
            old (with-retired-endpoint current)
            _ (boot-packages! storage old)
            named-ids (mapv #(records/fn-id retired-module %) retired-names)
            primitive (first named-ids)
            anonymous (filter #(some #{primitive} (:parent-ids %)) (sp/query-entities raw :fn {}))
            retired (into (set named-ids) (map :id) anonymous)
            sentinel (unrelated-state! storage)]
        (is (= 1 (count anonymous)) "The former inline handler produces one anonymous call")
        (is (= 4 (count retired)))
        (is (every? #(sp/read-entity raw :fn %) retired))
        (is (seq (get (repair/inbound-refs-many storage retired {:live-only? true}) (last named-ids)))
            "Before upgrade the route list really references the old endpoint")
        (let [info (boot-packages! storage current)]
          (is (every? #(nil? (sp/read-entity raw :fn %)) retired)
              "Production sync itself reconciles all four identities")
          (is (some? (sp/read-entity storage :fn
                                     (records/fn-id retired-module :api-editor-configuration-plan))))
          (doseq [[entity id before] sentinel]
            (is (= before (sp/read-entity storage entity id)) (str "Preserve " entity)))
          (assert-new-plan! storage info)))
      (finally (sp/close raw)))))
