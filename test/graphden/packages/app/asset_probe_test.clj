(ns ^:integration graphden.packages.app.asset-probe-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.test-infra.graph-harness :as gh]))


(def ^:dynamic *container* nil)


(use-fixtures :once
  setup/ensure-build-hashes-fixture
  (pth/create-container-fixture #'*container*)
  (gh/graph-fixture (str (ns-name *ns*))))


(deftest editor-css-handler-serves-baseline
  (let [resp1 (gh/exec-name :_editor-css-handler {:request {:uri "/assets/editor.css"}})
        resp2 (gh/exec-name :_editor-css-handler {:request {:uri "/assets/editor.css"}
                                                  :path "/assets/editor.css"})]
    (is (= 200 (:status resp1)))
    (is (= 200 (:status resp2))
        (str "handler must ignore a caller-env :path — got " (:status resp2)))))


(deftest plain-read-resource-pin-probe
  (is (string? (gh/exec-name :_auth-css {})) "pinned read-resource, no caller args")
  (is (string? (gh/exec-name :_auth-css {:path "/bogus"}))
      "caller :path must NOT override the pin on a :read-resource descendant"))
