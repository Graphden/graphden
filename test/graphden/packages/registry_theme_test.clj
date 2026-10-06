(ns ^:integration ^:serial graphden.packages.registry-theme-test
  "Ordinary theme package roundtrip: installation and forks preserve graph identities."
  (:require
    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.editor.theme :as theme]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.packages.registry-fixture :as rf]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.seams :as seams]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once
  seams/isolated-seams-fixture
  (rf/bootstrap-fixture "registry-theme-sender"))


(def expected-payload
  {:mode "light" :tokens {"--gd-flow" "#2563eb" "--bg" "#f8fafc"} :fonts {} :scale 100})


(defn- check-copy!
  [graph-ns forbidden-ids]
  (let [storage (rf/storage)
        root-id (records/fn-id graph-ns :theme)
        type-id (records/fn-id graph-ns :theme-output)
        root (sp/read-entity storage :fn root-id)
        output (sp/read-entity storage :fn type-id)
        own-fns (sp/query-entities storage :fn {:namespace-id (:namespace-id root)})
        own-ids (set (map :id own-fns))
        bindings (sp/query-entities storage :binding {:fn-id (vec own-ids)})
        items (sp/query-entities storage :binding-list-item {:binding-id (mapv :id bindings)})
        refs (set (keep :ref-fn-id (concat bindings items)))]
    (is (= 8 (count own-fns)))
    (is (= type-id (:return-type-fn-id root)))
    (is (= (:namespace-id root) (:namespace-id output)))
    (is (some? (:namespace-id output)))
    (is (empty? (set/intersection forbidden-ids refs)) "No dependency on publisher/template implementation")
    ;; The first theme evaluation must work without a manual execute warmup.
    (is (= {:ok true :payload expected-payload}
           (theme/evaluate (:ctx rf/*bootstrap*) {:fn-id (str root-id) :org "public" :owner "anonymous"})))
    (is (= expected-payload (exec/execute-with-named-args (:ctx rf/*bootstrap*) root-id {})))))


(defn- sync-definitions!
  [definitions]
  (rf/run-named "sync-fn-defs-branch!"
                {:branch-id (vs/current-branch-id (rf/storage)) :fn-defs definitions}))


(defn- check-updates!
  [graph-ns template package-args]
  (let [consumer-ns "sharing.consumer"
        consumer-id (records/fn-id consumer-ns :selected-theme)
        installed-ns #(str graph-ns "@" % "-0-0")
        root-ref #(keyword (installed-ns %) "theme")
        evaluate #(theme/evaluate (:ctx rf/*bootstrap*)
                                  {:fn-id (str consumer-id) :org "public" :owner "anonymous"})]
    (sync-definitions! [{:name :selected-theme :namespace consumer-ns :parent :core.logic/const
                         :args {:value {:ref (root-ref 1)}}}])
    (is (= {:ok true :payload expected-payload} (evaluate)))
    (sync-definitions!
      (mapv #(cond-> (assoc % :namespace graph-ns)
               (= :theme-canvas-color (:name %)) (assoc-in [:args :value] "#112233"))
            template))
    (is (:ok (rf/run-named "publish-package"
                           (assoc package-args :pkg-version "2.0.0"
                                  :bundle (rf/run-named "export-namespace" {:root graph-ns})))))
    (doseq [[version payload] [[2 (assoc-in expected-payload [:tokens "--bg"] "#112233")]
                               [1 expected-payload]]]
      (let [result (rf/run-named "update-package-version"
                                 (assoc package-args :pkg-version (str version ".0.0")))]
        (is (:ok result) (pr-str result))
        (is (= 1 (:rewritten-refs result)))
        (is (= [(records/fn-id (installed-ns version) :theme)]
               (mapv :ref-fn-id (sp/query-entities (rf/storage) :binding {:fn-id consumer-id}))))
        (is (= {:ok true :payload payload} (evaluate)))))))


(deftest ordinary-theme-export-install-and-fork-preserve-local-type-and-value-dependencies
  (let [graph-ns "sharing.author-theme"
        template (:fns (edn/read-string (slurp "resources/packages/app/ui-theme-template/fns.edn")))
        source-ids (set (map #(records/fn-id graph-ns (:name %)) template))
        shipped-ids (set (map #(records/fn-id "app.ui-theme-template" (:name %)) template))
        package-name "sharing.personal-colors"
        package-args {:pkg-name package-name :pkg-version "1.0.0"}]
    (sync-definitions! (mapv #(assoc % :namespace graph-ns) template))
    (check-copy! graph-ns shipped-ids)
    (let [bundle (rf/run-named "export-namespace" {:root graph-ns})]
      (is (= 8 (count (:fns bundle))))
      (is (= 1 (count (filter #(= :theme-output (:name %)) (:fns bundle)))))
      (is (:ok (rf/run-named "publish-package" (assoc package-args :bundle bundle))))
      (testing "reference installation owns its remapped type and value dependencies"
        (let [result (rf/run-named "install-package" package-args)]
          (is (:ok result) (pr-str result))
          (when (:ok result)
            (check-copy! (str graph-ns "@1-0-0") (set/union source-ids shipped-ids)))))
      (testing "update and rollback recheck the changed consumer before returning"
        (check-updates! graph-ns template package-args))
      (testing "a second project forks the published bundle without the publisher's graph"
        (let [recipient (setup/bootstrap-crud-graph-from-golden!
                          "registry-theme-recipient" ["core" "web" "app" "registry" "mcp"])]
          (binding [rf/*bootstrap* recipient]
            (is (nil? (sp/read-entity (rf/storage) :fn (records/fn-id graph-ns :theme))))
            (is (:ok (rf/run-named "publish-package" (assoc package-args :bundle bundle))))
            (let [result (rf/run-named "fork-package" package-args)]
              (is (:ok result) (pr-str result))
              (when (:ok result)
                (check-copy! graph-ns shipped-ids)
                (is (empty? (sp/query-entities (rf/storage) :package-install {:package-name package-name})))))))))))
