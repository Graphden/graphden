(ns graphden.input.query-lookup-package-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.executor.interface :as executor]
    [graphden.executor.registry.core :as registry]
    [graphden.input.package-execution :as execution]
    [graphden.input.query-lookup-package :as query]
    [graphden.input.source-snapshot :as snapshot]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.sync :as sync]
    [graphden.types.core :as types]
    [graphden.util.hex :as hex])
  (:import
    (java.io
      ByteArrayInputStream)
    (java.nio.charset
      StandardCharsets)
    (java.security
      MessageDigest)
    (org.httpkit.server
      AsyncChannel)))


(defn- source
  []
  (let [modules {"core/refinements" #{:nullable-text :nullable-keyword :text-map :path-segment}
                 "core/collections" #{:get :get-in} "core/logic" #{:coalesce :const} "core/strings" #{:str-to-keyword}
                 "web/ring-adapter" #{:ring-request-shape}
                 "web/crud-delete" #{:_query-param-raw :_query-param-string-key :_query-param-keyword-key :_query-param-fallback :query-param}}]
    (vec (for [[path names] modules
               :let [{module-ns :namespace :keys [fns]} (edn/read-string (slurp (io/resource (str "packages/" path "/fns.edn"))))]
               f fns :when (contains? names (:name f))]
           (assoc f :namespace module-ns)))))


(defn- pinned-stage
  [module]
  (let [bs (String/.getBytes (pr-str module) StandardCharsets/UTF_8)]
    {:bytes bs :sha256 (hex/encode (MessageDigest/.digest (MessageDigest/getInstance "SHA-256") bs))}))


(defn- rejection
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))


(deftest original-query-owner-replays-with-its-native-result-and-original-static-type
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            types/*type-aliases-override* (atom {})]
    (let [{module-ns :namespace :keys [fns]} (edn/read-string (slurp (io/resource "packages/registry/registry/fns.edn")))
          original (first (filter #(= :_pkg-fork-name-raw (:name %)) fns))
          owner (ids/fn-id module-ns (:name original))
          lookup-fallback {:namespace "fixture" :name :lookup-fallback :parent :coalesce
                           :args {:value (keyword module-ns (name (:name original))) :default {:value "local"}}}
          fallback-id (ids/fn-id "fixture" :lookup-fallback)
          forms (conj (source) (assoc original :namespace module-ns) lookup-fallback)
          package (snapshot/package-index (parse/prepare-module forms {} {}) forms)
          {:keys [fn-defs base-fn-defs]} (loader/load-packages ["web"])
          _ (sync/register-type-aliases! fn-defs)
          implementations (into {} (for [{name :name module :namespace} forms :when (contains? base-fn-defs name)
                                         :let [id (ids/fn-id module name) entry (get base-fn-defs name)]]
                                     (do (registry/record-rich-types! id name entry) [id (:impl entry)])))
          roots (:by-id @registry/*rich-types-override*)
          {:keys [proposed]} (query/prepare package owner roots)
          legacy {:namespace module-ns :fns [original]}
          stage (assoc legacy :input-format 2 :fns [(dissoc proposed :namespace)])
          visible (set (keys (:declarations package)))
          compile-stage (fn [module visible]
                          (execution/compile-staged-module
                            package owner roots implementations
                            (assoc (pinned-stage module) :legacy-module legacy :visible-fn-ids visible)))
          {:keys [invoke input-origin analysis]} (compile-stage stage visible)
          ctx {:compiled-registry (atom {owner invoke}) :base-fns {}}
          ring {:request-method :get :uri "/" :query-string "" :headers {} :body nil :async-channel nil}
          run #(executor/execute ctx owner {:request (merge ring %)})]
      (let [package (query/certify package owner roots)
            compiled (execution/compile-package package fallback-id roots implementations)]
        (is (= "local" ((:invoke compiled) {:value {:request ring}} {})))
        (is (= "remote" ((:invoke compiled) {:value {:request (assoc ring :query-string "name=remote")}} {}))))
      (is (= #{:request} (:accepted input-origin)))
      (is (= [:request] (mapv :name (get-in analysis [:public-input :root :fields]))))
      (is (true? (get-in analysis [:public-input :routes [:request] :required])))
      (is (= [:union :null :text] (get-in analysis [:scopes [] :signature :return])))
      (is (zero? (run {"extra" true :query-params {"name" 0}})))
      (is (= {:v 5} (run {7 nil :query-params {:name {:v 5}}})))
      (is (= "c" (run {:query-string "name=c"})))
      (let [body (ByteArrayInputStream. (byte-array [1 2 3]))
            request {:body body :async-channel (AsyncChannel. nil nil)}]
        (is (= "query" (run (assoc request :query-string "name=query"))))
        (is (= "middleware" (run (assoc request :query-params {"name" "middleware"}))))
        (is (nil? (run request)))
        (is (= 3 (.available body))))
      (is (= :unknown-input-field (rejection #(executor/execute ctx owner {:request ring :param-name "old"}))))
      (is (= :unverified-staged-declaration
             (rejection #(compile-stage (assoc-in stage [:fns 0 :return-type] :any) visible))))
      (is (= :unverified-staged-declaration
             (rejection #(compile-stage (assoc-in stage [:fns 0 :parent] :core.logic/coalesce) visible))))
      (is (= :native-view-source-mismatch
             (rejection #(execution/compile-staged-module
                           (query/certify package owner roots) owner
                           (dissoc roots (ids/fn-id "core.collections" :get)) implementations
                           (assoc (pinned-stage stage) :legacy-module legacy :visible-fn-ids visible)))))
      (doseq [donor [(ids/fn-id "web.crud" :_query-param-raw) (ids/fn-id "core.logic" :const)]]
        (is (= :staged-function-not-visible (rejection #(compile-stage stage (disj visible donor)))))))))
