(ns graphden.types.callback-contract-test
  "Check shipped callback contracts without a full golden type sweep.
   Runtime form parsing and marketplace aggregation keep their PG tests."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.packages.sync :as sync]
    [graphden.types.check :as check]
    [graphden.types.core :as types]))


(def ^:private loaded (loader/load-packages ["core" "web" "registry"]))


(use-fixtures :once
  (fn [t]
    (binding [types/*type-aliases-override* (atom {})]
      (sync/register-type-aliases! (:fn-defs loaded))
      (exec/with-isolated-rich-types
        (fn []
          (doseq [[fn-name fn-def] (:base-fn-defs loaded)]
            (registry/record-rich-types! fn-name fn-def))
          (t))))))


(deftest callbacks-preserve-their-required-input-and-computed-return-test
  (let [by-name (into {} (map (juxt :name identity)) (:fn-defs loaded))]
    (doseq [[fn-name expected-args expected-return]
            [[:_form-body-kw-callback {:value :text} :keyword]
             [:_mkv-package-name {:item {:package-name :text}} :text]]]
      (testing (str fn-name " is checked from its shipped definition")
        (check/check-fn-def! (get by-name fn-name))
        (let [rt (registry/rich-type-of fn-name)]
          (is (= expected-args (:args rt)))
          (is (= expected-return (:return rt))))))))


(deftest dependency-metadata-projections-preserve-secret-markers-and-redact-rows
  (let [by-name (into {} (map (juxt :name identity)) (:fn-defs loaded))]
    ;; Check the complete shipped closure without swallowing checker failures.
    ;; This also guards the ordinary, unmarked publish consumer contract.
    (doseq [fn-name [:_dep-name :_dep-version :_dep-old-index :_dep-new-index
                     :_dep-old-version :_dep-new-version :_dep-incompatible?
                     :_dep-change-row :incompatible-dependency-bumps]]
      (check/check-fn-def! (get by-name fn-name)))
    (let [contract (registry/rich-type-of :incompatible-dependency-bumps)]
      (is (= #{:old-deps :new-deps} (set (keys (:args contract)))))
      (is (false? (types/contains-hide-result-marker? (:return contract)))))
    (doseq [[projection field row-position]
            [[:_dep-name :name 1] [:_dep-version :version 2]]]
      ;; The public dependency schema is unmarked. Specialize the shipped
      ;; projection's generic get boundary with marked metadata to verify its
      ;; actual get -> str -> zipmap flow, without weakening that schema.
      (let [projected-name (keyword (str "secret-dependency-" (name field)))
            row-name (keyword (str "secret-dependency-row-" (name field)))
            marked-row-type (assoc {:name :text :version :text} field [:secret :text])
            projected (-> (get by-name projection)
                          (assoc :name projected-name)
                          (assoc-in [:args :parts 0 :args :coll :type] marked-row-type))
            response (-> (get by-name :_dep-change-row)
                         (assoc :name row-name)
                         (assoc-in [:args :vals]
                                   (assoc [{:value :dependency-incompatible}
                                           {:value "private-package"}
                                           {:value "1.0.0"} {:value "2.0.0"}]
                                          row-position projected-name)))]
        (testing (str "secret dependency " field " reaches the result sink")
          (check/check-fn-def! projected)
          (is (= [:secret :text] (:return (registry/rich-type-of projected-name))))
          (check/check-fn-def! response)
          (is (types/contains-hide-result-marker? (:return (registry/rich-type-of row-name))))
          (is (= {:status :succeeded :result nil :tainted? true}
                 (persist/redact-outcome row-name
                                         {:status :succeeded
                                          :result {:kind :dependency-incompatible
                                                   :name "private-package"
                                                   :old "1.0.0" :new "2.0.0"}}))))))))
