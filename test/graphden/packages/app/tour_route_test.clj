(ns ^:integration ^:serial graphden.packages.app.tour-route-test
  "`GET /api/tour` end-to-end against the golden DB: the payload the
   editor's tour and catalogue read, with the text base resolved at
   request time (`GRAPHDEN_TUTORIAL_BASE`, else the script's default)."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.system.deploy-config :as deploy-config]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(deftest the-tour-payload-carries-a-resolved-text-base
  ;; The env override composes `:env` → `:coalesce` → `:assoc-in` over the
  ;; script; an unbound OPTIONAL slot anywhere in that chain (get-in's
  ;; :default, once) surfaces as a free arg of the route and every tour
  ;; walk 500s on the first fetch. Executing the handler is the check.
  (let [resp (ga/exec-handler :tour-lessons-handler {})
        body (json/parse-string (:body resp) true)]
    (is (= 200 (:status resp)))
    (testing "the script's default when the environment does not override"
      (is (= "https://graphden.dev/tutorial/" (get-in body [:text :base]))))
    (testing "every row still points at its written lesson"
      (is (seq (:lessons body)))
      (is (every? #(and (:id %) (:slug %) (:reads %)) (:lessons body))))))


(deftest the-deployment-setting-overrides-the-text-base
  ;; `^:serial` — installs the process-global deploy snapshot for the
  ;; length of the test. `GRAPHDEN_TUTORIAL_BASE` is read through
  ;; `:deploy-config`, not `:env`: a tenant request on the cloud runs
  ;; under the effect gate, which excludes `:env`.
  (try
    (deploy-config/install! {:tutorial-base "https://example.test/ru/tutorial/"})
    (let [resp (ga/exec-handler :tour-lessons-handler {})
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (= "https://example.test/ru/tutorial/" (get-in body [:text :base]))))
    (finally (deploy-config/clear!))))
