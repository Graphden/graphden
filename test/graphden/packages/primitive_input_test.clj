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
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.system.init.packages]
    [graphden.test-infra.golden-app :as golden]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as resolution]
    [integrant.core :as ig]))


(use-fixtures :once (golden/fixture (ns-name *ns*)))


(def ^:private root (ids/fn-id "inputfixture.numeric" :fixture-floor))
(def ^:private child (ids/fn-id "inputfixture.numeric" :fixture-floor-negative))
(def ^:private floor (ids/fn-id "core.arithmetic" :floor))


(def ^:private admission
  {:module ["inputfixture" "numeric"] :fn-id root :abi-fn-id floor
   :sha256 "b9916eb06942752882460e8503147148878736c384bf5bea081236f470e5ecb2"})


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
        packages (ig/init-key :app/packages {:package-names ["core" "web" "app" "inputfixture"] :primitive-input admission})
        sync! #(ig/init-key :exec/base-fns {:storage storage :packages packages})
        {:keys [base-fns] :as base-info} (sync!)
        _ (ig/init-key :exec/fn-entities {:storage storage :packages packages :base-fns base-info})
        input (migration/derived-id :input-type root)
        child-input (migration/derived-id :input-type child)
        number-slot (ids/slot-id root :number)
        binding (ids/binding-id child number-slot)
        memberships #(sp/query-entities storage :fn-slot {:fn-id input})
        version #(resolution/resolve-version-ids (vs/unwrap storage) :fn [root input child child-input]
                                                 (vs/current-branch-id storage))
        binding-version #(resolution/resolve-version-ids (vs/unwrap storage) :input-binding [binding]
                                                         (vs/current-branch-id storage))
        before (version)
        before-binding (binding-version)
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
    (is (= child-input (:input-type-fn-id (sp/read-entity storage :fn child))))
    (is (= [root] (:parent-ids (sp/read-entity storage :fn child))))
    (is (nil? (:input-type-fn-id (sp/read-entity storage :fn floor))))
    (is (= [number-slot] (mapv :slot-id (memberships))))
    (is (= [number-slot] (mapv :slot-id (sp/query-entities storage :fn-slot {:fn-id child-input}))))
    (is (identical? (:fixture-floor base-fns) (:floor base-fns)))
    (is (= -4 (:result (post root {:number -3.2}))))
    (is (= -4 (:result (post floor {:number -3.2}))))
    (is (= -8 (:result (post child {}))))
    (is (= "unknown-input-field" (get-in (post child {:number 1}) [:error-data :reason])))
    (is (= "invalid-structured-arguments" (get-in (post root {:unknown 1}) [:error-data :reason])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"primitive admission failed"
          (ig/init-key :exec/base-fns {:storage storage :packages packages
                                       :extra-base-fns {:fixture-floor (:ceil base-fns)}})))
    (is (= #{root input child child-input} (set (keys before))))
    (is (= #{binding} (set (keys before-binding))))
    (sync!)
    (is (= before (version)))
    (is (= before-binding (binding-version)))
    (is (= member-ids (mapv :id (memberships))))))
