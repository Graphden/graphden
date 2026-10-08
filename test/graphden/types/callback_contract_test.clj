(ns graphden.types.callback-contract-test
  "Check shipped callback contracts without a full golden type sweep.
   Runtime form parsing and marketplace aggregation keep their PG tests."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
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
