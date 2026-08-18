(ns ^:integration graphden.packages.app.asset-override-test
  "UI Step 1 — the asset-override tier: `:read-resource-overridable`
   shadows classpath frontend assets with per-branch
   `:resource-override` rows, bundles build through it, and
   `:frontend-hash-effective` folds the overrides into every asset
   URL's `?v=` so the browser's immutable cache steps aside."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.test-infra.graph-harness :as gh]))


(def ^:dynamic *container* nil)


(use-fixtures :once
  setup/ensure-build-hashes-fixture
  (pth/create-container-fixture #'*container*)
  (gh/graph-fixture (str (ns-name *ns*))))


(def ^:private css-path "packages/app/editor/components.css")


(deftest override-shadows-classpath-and-rolls-the-hash-test
  (let [storage (:storage gh/*graph*)
        baseline (gh/exec-name :read-resource-overridable {:path css-path})
        hash0 (gh/exec-name :frontend-hash-effective {})]
    (testing "without an override, the classpath baseline is served"
      (is (string? baseline))
      (is (str/includes? baseline "--gd-"))
      (is (= 12 (count hash0)) "effective hash = the baked short hash"))

    (testing "an override row shadows the classpath content"
      (sp/create-entity storage :resource-override
                        {:path css-path
                         :content "/* overridden */ .card{border:0}"})
      (is (= "/* overridden */ .card{border:0}"
             (gh/exec-name :read-resource-overridable {:path css-path}))))

    (testing "the bundle chain picks the override up"
      (let [css (gh/exec-name :_graphden-components-css {})]
        (is (str/includes? (str css) "/* overridden */"))))

    (testing "the effective hash rolled — asset URLs will re-fetch"
      (let [hash1 (gh/exec-name :frontend-hash-effective {})]
        (is (= 12 (count hash1)))
        (is (not= hash0 hash1))))

    (testing "an unrelated path still reads its classpath baseline"
      (is (str/includes?
            (str (gh/exec-name :read-resource-overridable
                               {:path "packages/app/editor/editor-styles.css"}))
            "arg-value-edit")))

    (testing "deleting the override restores the baseline AND the hash"
      (let [row (first (sp/query-entities storage :resource-override
                                          {:path css-path}))]
        (sp/delete-entity storage :resource-override (:id row)))
      (is (= baseline (gh/exec-name :read-resource-overridable {:path css-path})))
      (is (= hash0 (gh/exec-name :frontend-hash-effective {}))))))


(def ^:private form-headers
  {"content-type" "application/x-www-form-urlencoded"})


(deftest assets-panel-handlers-test
  (let [storage (:storage gh/*graph*)
        panel0 (:body (gh/exec-name :_partial-assets-panel-handler {}))]
    (testing "the panel lists bundle files, all baseline"
      (is (str/includes? panel0 "data-assets-panel"))
      (is (str/includes? panel0 css-path))
      (is (str/includes? panel0 "packages/app/editor/editor-sidebar.js"))
      (is (not (str/includes? panel0 "gd-asset-chip-override"))))

    (testing "the edit partial prefills the classpath baseline, no revert offered"
      (let [resp (gh/exec-name :_partial-asset-edit-handler
                               {:request {:query-string (str "path=" css-path)}})]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "--gd-"))
        (is (str/includes? (:body resp) "/api/assets/save"))
        (is (not (str/includes? (:body resp) "gd-asset-revert-btn")))))

    (testing "save upserts an override; the refreshed panel shows it"
      (let [resp (gh/exec-name :_asave-handler
                               {:request {:request-method :post
                                          :headers form-headers
                                          :body (str "path=" css-path
                                                     "&content=%2F*+panel+*%2F")}})]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "gd-asset-chip-override")))
      (is (= "/* panel */"
             (gh/exec-name :read-resource-overridable {:path css-path}))))

    (testing "a second save updates in place — no duplicate row"
      (gh/exec-name :_asave-handler
                    {:request {:request-method :post
                               :headers form-headers
                               :body (str "path=" css-path "&content=v2")}})
      (is (= "v2" (gh/exec-name :read-resource-overridable {:path css-path})))
      (is (= 1 (count (sp/query-entities storage :resource-override
                                         {:path css-path})))))

    (testing "the edit partial now prefills the override and offers revert"
      (let [resp (gh/exec-name :_partial-asset-edit-handler
                               {:request {:query-string (str "path=" css-path)}})]
        (is (str/includes? (:body resp) "v2"))
        (is (str/includes? (:body resp) "gd-asset-revert-btn"))))

    (testing "an orphan override (unknown path) still surfaces in the panel"
      (sp/create-entity storage :resource-override
                        {:path "no/such/file.css" :content "x"})
      (is (str/includes? (:body (gh/exec-name :_partial-assets-panel-handler {}))
                         "no/such/file.css"))
      (let [row (first (sp/query-entities storage :resource-override
                                          {:path "no/such/file.css"}))]
        (sp/delete-entity storage :resource-override (:id row))))

    (testing "revert drops the override; the panel returns to baseline"
      (let [resp (gh/exec-name :_arev-handler
                               {:request {:query-string (str "path=" css-path)}})]
        (is (= 200 (:status resp)))
        (is (not (str/includes? (:body resp) "gd-asset-chip-override"))))
      (is (str/includes?
            (str (gh/exec-name :read-resource-overridable {:path css-path}))
            "--gd-")))

    (testing "a body without a path is a no-op, not a blank-path row"
      (let [resp (gh/exec-name :_asave-handler
                               {:request {:request-method :post
                                          :headers form-headers
                                          :body "content=zzz"}})]
        (is (= 200 (:status resp))))
      (is (= [] (sp/query-entities storage :resource-override {}))))))
