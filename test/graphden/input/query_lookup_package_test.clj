(ns graphden.input.query-lookup-package-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.executor.interface :as executor]
    [graphden.executor.registry.core :as registry]
    [graphden.input.form-query :as form-query]
    [graphden.input.generated-source :as generated]
    [graphden.input.package-execution :as execution]
    [graphden.input.query-lookup-package :as query]
    [graphden.input.source-certification :as certification]
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


(def ^:private query-modules
  {"core/refinements" #{:nullable-text :nullable-keyword :text-map :path-segment}
   "core/collections" #{:get :get-in} "core/logic" #{:coalesce :const} "core/strings" #{:str-to-keyword}
   "web/ring-adapter" #{:ring-request-shape}
   "web/crud-delete" #{:_query-param-raw :_query-param-string-key :_query-param-keyword-key :_query-param-fallback :query-param}})


(defn- source
  ([] (source query-modules))
  ([modules]
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
        (is (= 3 (ByteArrayInputStream/.available body))))
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


(deftest staged-queries-read-their-shared-checked-declarations-from-the-sidecar
  (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
            types/*type-aliases-override* (atom {})]
    (let [module "fixture"
          owners [{:name :one :parent :query-param :args {:param-name {:value "a"}}}
                  {:name :two :parent :query-param :args {:param-name {:value "b"}}}]
          forms (into (source) (map #(assoc % :namespace module)) owners)
          package (snapshot/package-index (parse/prepare-module forms {} {}) forms)
          {:keys [fn-defs base-fn-defs]} (loader/load-packages ["web"])
          _ (sync/register-type-aliases! fn-defs)
          _ (doseq [[name {module-ns :namespace :as base}] base-fn-defs]
              (registry/record-rich-types! (ids/fn-id module-ns name) name base))
          roots (:by-id @registry/*rich-types-override*)
          impls (into {} (map (fn [[name base]] [(ids/fn-id (:namespace base) name) (:impl base)])) base-fn-defs)
          owner-ids (mapv #(ids/fn-id module (:name %)) owners)
          visible (set (keys (:declarations package)))
          {payload :bytes :keys [sha256] :as content} (generated/emit package owner-ids roots)
          artifact (edn/read-string (String. ^bytes payload StandardCharsets/UTF_8))
          [one two] (:owners artifact)
          replay #(generated/install package owner-ids roots visible %)
          installed (replay content)
          legacy {:namespace module :fns owners}
          proposed (mapv #(dissoc (get-in installed [:query-lookups % :proposed]) :namespace) owner-ids)
          stage (assoc (pinned-stage (assoc legacy :fns proposed :input-format 2))
                       :visible-fn-ids visible :legacy-module legacy)
          ring {:request-method :get :uri "/" :headers {} :body nil :async-channel nil :query-string "a=first&b=second"}]
      (is (= sha256 (:sha256 (generated/emit package (reverse owner-ids) roots))))
      (is (= (set owner-ids) (set (map :owner-id (:owners artifact)))))
      (is (= 2 (count (get-in artifact [:modules 0 :fns])) (count (:generated-ids one))))
      (is (= (:generated-ids one) (:generated-ids two)))
      (is (= #{"web.crud"} (set (map :namespace (:modules artifact)))))
      (is (= #{:core.logic/const :core.logic/coalesce} (set (map :parent (get-in artifact [:modules 0 :fns])))))
      (doseq [[owner value] (map vector owner-ids ["first" "second"])]
        (let [normalized (get-in installed [:query-lookups owner :package])
              materialized (set (keys (:staged-proposals normalized)))
              compiled (execution/compile-staged-module installed owner roots impls stage)]
          (is (= (set (:generated-ids one)) materialized))
          (is (= {:sha256 sha256} (:generated-source compiled)))
          (is (= value ((:invoke compiled) {:request ring} {})))))
      (is (= "first" ((:invoke (execution/compile-staged-module package (first owner-ids) roots impls
                                                                (assoc stage :generated-source
                                                                       (assoc content :owner-ids owner-ids))))
                      {:request ring} {})))
      (doseq [changed [(assoc-in artifact [:modules 0 :fns 0 :parent] :core.logic/const)
                       (assoc-in artifact [:owners 0 :source-sha256] "other")
                       (update-in artifact [:modules 0 :fns] pop)
                       (update-in artifact [:modules 0 :fns] conj {:name :extra :parent :core.logic/const})]]
        (is (= :generated-source-ledger-mismatch (rejection #(replay (pinned-stage changed))))))
      (is (= :generated-source-ledger-mismatch
             (rejection #(generated/install package [(first owner-ids)] roots visible content))))
      (is (= :generated-source-owner-required
             (rejection #(generated/emit package (conj owner-ids (first owner-ids)) roots))))
      (is (= :staged-module-checksum-mismatch
             (rejection #(replay (assoc content :sha256 "invalid")))))
      (is (= :generated-source-not-visible
             (rejection #(generated/install package owner-ids roots (disj visible (ids/fn-id "core.logic" :const)) content)))))))


(deftest registry-form-fallback-uses-the-same-callback-parameter-in-both-branches
  (let [more {"core/refinements" #{:positive-int :non-negative-int}
              "core/collections" #{:update-keys :update-vals :last}
              "core/logic" #{:is-a? :if :ensure-type :and :some?}
              "core/strings" #{:str-contains?}
              "web/crud-parse" #{:_form-decoded :_request-body :_form-decode-raw :parse-form-body :_form-body-kw-callback
                                 :form-decode :_form-body? :parse-form-body-kw :_form-decoded-value :_request-content-type}
              "registry/registry" #{:_pkg-install-name-raw :_pkg-install-form}}
        forms (source (merge-with into query-modules more))
        owner (ids/fn-id "app.registry" :_pkg-install-name-raw)
        definition (first (filter #(= :_pkg-install-name-raw (:name %)) forms))
        prepared (parse/prepare-module forms {} {})]
    (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
              types/*type-aliases-override* (atom {})]
      (let [{:keys [fn-defs base-fn-defs]} (loader/load-packages ["web"])
            _ (sync/register-type-aliases! fn-defs)
            impls (into {} (for [{module-ns :namespace :keys [name]} forms :let [entry (get base-fn-defs name)] :when entry]
                             (let [id (ids/fn-id module-ns name)]
                               (registry/record-rich-types! id name entry) [id (:impl entry)])))
            roots (:by-id @registry/*rich-types-override*)
            support (remove #{definition} forms)
            verified (certification/verify-plan prepared (vec support) {:definitions [definition]} roots)
            package (:package verified)
            proposal (:proposed (form-query/prepare package owner roots))
            module {:namespace "app.registry" :fns [(dissoc definition :namespace)]}
            staged (assoc module :input-format 2 :fns [(dissoc proposal :namespace)])
            receipt (assoc (pinned-stage staged) :legacy-module module :visible-fn-ids (set (keys (:declarations package))))
            {:keys [invoke analysis input-origin]} (execution/compile-staged-module package owner roots impls receipt)
            call (fn [query-string body]
                   (invoke {:value {:request {:request-method :post :uri "/install" :body body
                                              :headers {"content-type" "application/x-www-form-urlencoded"}
                                              :query-string query-string :async-channel nil}}} {}))]
        (is (every? (fn [[path route]]
                      (or (not= :input-field (:kind route))
                          (= [:value :request] (vec (take 2 path)))))
                    (get-in analysis [:public-input :routes])))
        (is (= 2 (count (get-in input-origin [:form-callback :rows]))))
        (is (= "last" (call "" "name=first&name=last")))
        (is (= "hello world" (call "" "name=hello+world")))
        (is (= "" (call "" nil)))
        (is (= "winner" (call "name=winner" "name=%JZ")))
        (is (= :staged-function-not-visible
               (rejection #(execution/compile-staged-module package owner roots impls
                                                            (update receipt :visible-fn-ids disj (ids/fn-id "core.collections" :update-vals))))))))))
