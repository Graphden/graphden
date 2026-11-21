(ns ^:integration ^:serial graphden.packages.primitive-input-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.interface :as exec]
    [graphden.input.migration :as migration]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.sync :as sync]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.test-infra.golden-app :as golden]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as resolution]))


(use-fixtures :once (golden/fixture (ns-name *ns*)))


(def ^:private root (ids/fn-id "inputfixture.numeric" :fixture-floor))
(def ^:private floor (ids/fn-id "core.arithmetic" :floor))


(def ^:private admission
  {:module ["inputfixture" "numeric"] :fn-id root :abi-fn-id floor
   :sha256 "dd9f90d3bf9c0d7636cbf8179ac648bb245dd00fc37bddd821683b8e098ef513"})


(deftest unadmitted-source-fails-before-any-package-implementation
  (let [loads (atom 0)]
    (with-redefs-fn {#'loader/load-module-impls (fn [& _] (swap! loads inc))}
      #(doseq [options [nil
                        {:primitive-input (assoc admission :sha256 "wrong")}
                        {:primitive-input (assoc admission :fn-id (random-uuid))}
                        {:primitive-input (assoc admission :module ["inputfixture" "absent"])}]]
         (is (thrown? clojure.lang.ExceptionInfo (loader/load-packages ["inputfixture"] options)))))
    (is (zero? @loads))))


(deftest pinned-classpath-declaration-syncs-and-executes
  (let [storage (:storage golden/*bootstrap*)
        packages (loader/load-packages ["core" "web" "app" "inputfixture"] {:primitive-input admission})
        sync! #(sync/register-base-fns-from-packages! storage packages)
        {:keys [base-fns]} (sync!)
        input (migration/derived-id :input-type root)
        number-slot (ids/slot-id root :number)
        memberships #(sp/query-entities storage :fn-slot {:fn-id input})
        version #(resolution/resolve-version-ids (vs/unwrap storage) :fn [root input] (vs/current-branch-id storage))
        before (version)
        member-ids (mapv :id (memberships))
        token (str (random-uuid))
        ctx (exec/create-context {:storage storage :base-fns base-fns
                                  :auth-provider (auth/single-token-provider token)})
        _ (runtime/rebuild! ctx)
        app (router/create-router ctx "_app-ring-response")
        post (fn [id args]
               (let [response (router/dispatch app {:request-method :post :uri "/api/execute"
                                                    :headers {"content-type" "application/json"
                                                              "authorization" (str "Bearer " token)}
                                                    :body (json/generate-string {:fn-id id :args args :timeout-ms 30000})})]
                 (json/parse-string (:body response) true)))]
    (is (= input (:input-type-fn-id (sp/read-entity storage :fn root))))
    (is (nil? (:input-type-fn-id (sp/read-entity storage :fn floor))))
    (is (= [number-slot] (mapv :slot-id (memberships))))
    (is (identical? (:fixture-floor base-fns) (:floor base-fns)))
    (is (= -4 (:result (post root {:number -3.2}))))
    (is (= -4 (:result (post floor {:number -3.2}))))
    (is (= "invalid-structured-arguments" (get-in (post root {:unknown 1}) [:error-data :reason])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"primitive admission failed"
          (sync/register-base-fns-from-packages! storage packages {:fixture-floor (:ceil base-fns)})))
    (is (= #{root input} (set (keys before))))
    (sync!)
    (is (= before (version)))
    (is (= member-ids (mapv :id (memberships))))))
