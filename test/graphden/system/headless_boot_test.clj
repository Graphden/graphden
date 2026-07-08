(ns ^:integration graphden.system.headless-boot-test
  "Track B (PACKAGE_DISTRIBUTION § 12): graphden consumed as a library WITHOUT
   the `app` package — the headless path a git-dep consumer takes when it wants
   the executor + storage primitives but not the editor / default web-server.
   Proves the primitives load + sync and the executor evaluates a fn with
   `:package-names [\"core\" \"storage\" \"web\"]`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]))


(use-fixtures :once (setup/create-container-fixture))


(deftest boots-headless-without-app-package
  (let [storage (setup/create-test-storage)
        {:keys [ctx all-name->id]} (setup/bootstrap-crud-graph! storage ["core" "storage" "web"])]
    (testing "the app / editor package is NOT loaded"
      (is (nil? (get all-name->id :web-server))
          "the app package's :web-server (editor/default HTTP listener) is absent"))
    (testing "core + web primitives loaded; the executor evaluates a fn headless"
      (is (some? (get all-name->id :add)) "core :add is present")
      (is (= 6 (exec/execute-with-named-args ctx (get all-name->id :add) {:nums [1 2 3]}))
          "the executor runs with no app package"))
    (testing "a web primitive is present (web loaded, its transitive deps resolved)"
      (is (some? (get all-name->id :router))
          ":router from the web package loaded — [core storage web] is a valid closure"))))
