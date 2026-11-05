(ns ^:integration ^:serial graphden.packages.registry-ui-extension-test
  "A two-component editor extension crosses a package boundary by graph reference."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.editor.component-config :as config]
    [graphden.editor.component-export-fixture :as export-fixture]
    [graphden.editor.component-templates :as templates]
    [graphden.editor.components :as components]
    [graphden.executor.browser-source :as source]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.packages.registry-fixture :as rf]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.seams :as seams]
    [graphden.test-infra.shared-bootstrap :as sb]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once seams/isolated-seams-fixture
  exec/with-isolated-rich-types
  (fn [f]
    ;; Personal editor preparation validates the complete built-in closure.
    ;; The golden clone skips that type sweep, so overlay the cached snapshot
    ;; before its context is compiled and before the extension is installed.
    (reset! registry-core/*rich-types-override*
            (sb/ensure-swept-rich-types! ["core" "web" "app" "registry" "mcp"]))
    (f))
  (rf/bootstrap-fixture "registry-ui-extension-sender"))


(defn- two-component-definitions
  [root]
  (let [items [(keyword root "_keymap-descriptor")
               (keyword root "_appearance-descriptor")]
        definitions (mapv (fn [definition]
                            (if (= :ui (:name definition))
                              (assoc-in definition [:args :items] items)
                              definition))
                          (templates/definitions root))]
    (templates/definition-closure definitions [(keyword root "ui")])))


(deftest two-component-package-installs-and-selects-in-another-project
  (let [root "sharing.ui-extension"
        name "sharing.keyboard-and-appearance"
        args {:pkg-name name :pkg-version "1.0.0"}
        definitions (two-component-definitions root)]
    (is (some #(= :appearance-title (:name %)) definitions))
    (is (some #(= :keymap-view (:name %)) definitions))
    (rf/run-named "sync-fn-defs-branch!"
                  {:branch-id (vs/current-branch-id (rf/storage)) :fn-defs definitions})
    (let [bundle (rf/run-named "export-namespace" {:root root})
          recipient (setup/bootstrap-crud-graph-from-golden!
                      "registry-ui-extension-recipient" ["core" "web" "app" "registry" "mcp"])]
      (is (<= (count definitions) (count (:fns bundle)))
          "The exporter also materializes anonymous expression functions")
      (is (seq (:dependencies bundle)))
      (binding [rf/*bootstrap* recipient]
        (let [storage (rf/storage)
              installed-root (str root "@1-0-0")
              root-id (records/fn-id installed-root :ui)]
          (is (nil? (sp/read-entity storage :fn (records/fn-id root :ui))))
          (is (:ok (rf/run-named "publish-package" (assoc args :bundle bundle))))
          (is (:ok (rf/run-named "install-package" args)))
          (is (some? (sp/read-entity storage :fn root-id)))
          (source/with-snapshot storage
                                (fn [snapshot authorize!]
                                  (let [graph (source/collect-manifest snapshot root-id authorize!)]
                                    (is (= #{:keymap :appearance}
                                           (set (keys (config/configuration graph root-id))))))))
          (sp/create-entity storage :ui-pref
                            {:owner-id "anonymous" :key "components"
                             :value {:fn-id (str root-id)
                                     :branch-id (str (vs/current-branch-id storage))
                                     :org "public"}})
          (let [prepared (export-fixture/prepare! (:ctx rf/*bootstrap*) root-id)
                failure components/failure-facts
                diagnostic (atom nil)]
            (with-redefs [components/failure-facts
                          (fn [error]
                            (reset! diagnostic (select-keys (ex-data error) [:type :reason :stage]))
                            (failure error))]
              (doseq [component ["keymap" "appearance"]]
                (reset! diagnostic nil)
                (let [exported (export-fixture/export! (:ctx rf/*bootstrap*) root-id component)]
                  (is (:ok exported) (str component " " @diagnostic)))))
            ;; The ordinary root itself retains the installed package. Remove
            ;; this fixture cohort before checking preference-only pin guards.
            (binding [vs/*tombstone-delete?* true]
              (doseq [id (:functions prepared)] (sp/delete-entity storage :fn id))))
          (let [pin (first (sp/query-entities storage :package-install {:package-name name}))
                own (first (sp/query-entities storage :ui-pref
                                              {:owner-id "anonymous" :key "components"}))
                uninstall #(setup/via-graph recipient :_uninstall-handler
                                            {:request-method :delete
                                             :query-params {"name" name "expected-id" (str (:id pin))}
                                             :headers {}})]
            (is (= 409 (:status (uninstall))) "An active personal root keeps its package pin")
            (sp/delete-entity storage :ui-pref (:id own))
            (let [other (sp/create-entity storage :ui-pref
                                          {:owner-id "another-owner" :key "components"
                                           :value (:value own)})]
              (is (= 409 (:status (uninstall))) "Another owner's selection also keeps the pin")
              (sp/delete-entity storage :ui-pref (:id other)))
            (is (= 200 (:status (uninstall))) "Unpin succeeds after both selections are cleared")
            (is (nil? (sp/read-entity storage :package-install (:id pin))))))))))
