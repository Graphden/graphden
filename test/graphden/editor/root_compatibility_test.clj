(ns graphden.editor.root-compatibility-test
  (:require
    [cheshire.core :as json]
    [clojure.java.shell :as sh]
    [clojure.test :refer [deftest is testing]]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.root-compatibility :as compatibility]
    [graphden.executor.browser-plan :as browser]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.storage.core :as versioned]))


(def ^:private root (ids/fn-id "app.ui-config" :editor-root))
(def ^:private target (ids/fn-id "personal.menu" :view))
(def ^:private old (str (ids/fn-id "app.ui-account-menu" :root-view)))
(def ^:private const-id (str (ids/fn-id "core.logic" :const)))
(def ^:private assoc-id (str (ids/fn-id "core.collections" :assoc)))


(defn- literal
  [value]
  {:kind "literal" :value (browser/encode-value value)})


(def ^:private root-plan
  {:format 1 :primitiveAbi 1 :entries {"root" "root"}
   :inputs {"root" {:accepted [] :required [] :destinations []}}
   :primitives [{:id assoc-id :op "assoc"} {:id const-id :op "const"}]
   :functions [{:id "root" :primitive assoc-id :aliases [] :env []
                :args [{:name "map" :expr (literal {})} {:name "key" :expr (literal :view)}
                       {:name "value" :expr {:kind "closure" :fn old :lambdaParams ["input"] :translation []}}]}
               {:id old :primitive const-id :aliases [] :env []
                :args [{:name "value" :expr (literal "default")}]}]})


(def ^:private personal-plan
  {:format 1 :primitiveAbi 1 :entries {"view" (str target)}
   :inputs {(str target) {:accepted ["state"] :required ["state"] :destinations []}}
   :primitives [{:id const-id :op "const"}]
   :functions [{:id (str target) :primitive const-id :aliases [] :env []
                :args [{:name "value" :expr {:kind "read" :slot "personal-state" :name "state"}}]}]})


(defn- adapt
  [configuration]
  (with-redefs [tenancy/current-user-id (constantly :owner)
                resolution/capture (fn [storage authorize! policy]
                                     (is (= ::storage storage))
                                     (is (= ::authorize authorize!))
                                     (is (= ::policy policy))
                                     {:id ::authored :configuration configuration})
                source/collect-closure (fn [storage roots authorize!]
                                         (is (= [target] (vec roots)))
                                         (is (= [::storage ::authorize] [storage authorize!]))
                                         {:fns []})
                source/collect-namespaces (fn [& _] [])
                snapshot/export-snapshot (fn [captured _base-fns entries policy]
                                           (is (= {:graph {:fns []} :namespaces []} captured))
                                           (is (= {:view target} entries))
                                           (is (= ::policy policy))
                                           personal-plan)]
    (compatibility/adapt-plan {} ::storage ::authorize ::policy root root-plan)))


(deftest selected-personal-view-is-invoked-through-the-root-field
  (let [plan (adapt {:account-menu {:view target}})
        script (str "const g=require('./resources/packages/app/ui-preview/browser-runtime.js');let s='';"
                    "process.stdin.on('data',x=>s+=x);process.stdin.on('end',()=>{"
                    "const r=g.createRuntime(JSON.parse(s)).run('root');"
                    "process.stdout.write(g.invoke(r.get(g.keyword('view')),new Map([[g.keyword('state'),'personal copy']])));});")
        result (sh/sh "node" "-e" script :in (json/generate-string plan))]
    (is (zero? (:exit result)) (:err result))
    (is (= "personal copy" (:out result)))
    (is (= old (get-in root-plan [:functions 0 :args 2 :expr :fn])) "Input plan stays immutable")
    (is (not= old (get-in plan [:functions 0 :args 2 :expr :fn])))))


(deftest a-custom-root-or-missing-principal-never-enters-legacy-resolution
  (with-redefs [tenancy/current-user-id (constantly :owner)
                resolution/capture (fn [& _] (throw (ex-info "Unexpected resolution" {})))]
    (is (= root-plan (compatibility/adapt-plan {} nil nil nil (random-uuid) root-plan)))
    (with-redefs [tenancy/current-user-id (constantly nil)]
      (is (= root-plan (compatibility/adapt-plan {} nil nil nil root root-plan))))))


(deftest revoked-personal-source-never-becomes-a-default-fallback
  (with-redefs [tenancy/current-user-id (constantly :owner)
                resolution/capture (fn [& _] {:id ::authored :configuration {:recents {:view target}}})
                source/collect-closure (fn [& _] (throw (ex-info "Source refused" {:reason :visibility-denied})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Source refused"
          (compatibility/adapt-plan {} nil nil nil root root-plan)))))


(deftest legacy-preferences-are-exactly-owner-and-branch-scoped
  (let [owner (random-uuid) branch (random-uuid) manifest (random-uuid)
        row {:owner-id owner :key "components" :value {:fn-id (str manifest) :branch-id (str branch) :org "org"}}]
    (with-redefs [tenancy/current-user-id (constantly owner)
                  tenancy/current-org (constantly "org")
                  versioned/current-branch-id (constantly branch)
                  resolution/capture (fn [& _] {:id resolution/defaults-id :configuration {}})
                  sp/query-entities (fn [_ entity where options]
                                      (is (= :ui-pref entity))
                                      (is (= {:owner-id owner :key "components"} where))
                                      (is (= {:limit 2} options))
                                      [row])
                  sp/read-entity (fn [_ _ id] (is (= manifest id)) nil)]
      (testing "missing selected identity is refused before source reads"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Personal root adapter"
              (compatibility/adapt-plan {} nil (fn [& _]) nil root root-plan))))
      (testing "the same preference cannot select source on a foreign branch"
        (with-redefs [versioned/current-branch-id (constantly (random-uuid))
                      sp/read-entity (fn [& _] (throw (ex-info "Unexpected source read" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Personal UI graph"
                (compatibility/adapt-plan {} nil nil nil root root-plan)))))
      (testing "another owner uses their own query, never a client owner selector"
        (with-redefs [tenancy/current-user-id (constantly :other-owner)
                      sp/query-entities (fn [_ _ where _]
                                          (is (= :other-owner (:owner-id where))) [])]
          (is (= root-plan (compatibility/adapt-plan {} nil nil nil root root-plan))))))))
