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
