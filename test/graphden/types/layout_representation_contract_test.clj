(ns graphden.types.layout-representation-contract-test
  "Check layout, representation and error composition without a whole corpus sweep."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.packages.sync :as sync]
    [graphden.test-infra.type-contract :as type-contract]
    [graphden.types.check :as check]
    [graphden.types.check.narrowing :as narrowing]
    [graphden.types.core :as types]))


(def ^:private loaded (loader/load-packages ["core" "web" "app"]))


(use-fixtures :once
  (fn [test-fn]
    (binding [types/*type-aliases-override* (atom {})]
      (sync/register-type-aliases! (:fn-defs loaded))
      ;; A partial checker fixture needs its own dependency signatures. Ambient
      ;; package seeds contain declarations, not inferred transitive free slots.
      (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
                registry/*per-org-rich-override* (atom {})]
        (doseq [[fn-name fn-def] (:base-fn-defs loaded)]
          (registry/record-rich-types! fn-name fn-def))
        (test-fn)))))


(deftest presentation-closures-have-valid-types
  (let [{sorted :fn-defs}
        (type-contract/prepared-closure
          loaded
          (fn [{:keys [name] fn-namespace :namespace}]
            (and name
                 (#{"app.layout" "app.reprs" "app.forms" "web.http-client" "web.errors"} fn-namespace)
                 (not (#{:_layout-api-handler :_layout-api-body} name)))))]
    ;; Match sync's two passes: seed inferred signatures, then enforce every
    ;; contract with caller refinements and referenced literal returns.
    (binding [check/*ref-return-memo* (atom {})]
      (doseq [definition sorted]
        (try (check/check-fn-def! definition)
             (catch clojure.lang.ExceptionInfo _))))
    (let [caller-narrowings (narrowing/build-caller-narrowings sorted)
          overrides (narrowing/build-ref-return-overrides sorted)]
      (binding [check/*ref-return-memo* (atom {})]
        (doseq [definition sorted]
          (narrowing/check-fn-def-with-narrowings! definition caller-narrowings overrides)))))
  (doseq [[fn-name expected] [[:parse-json-body #{:request}]
                              [:_parse-layout-body #{:request}]
                              [:_fn-slot-seals #{:fn-id}]
                              [:_layout-strip-facts-apply #{:elements :graph}]
                              [:_layout-literal-reprs-apply #{:elements :graph}]
                              [:slot-type-provenance #{:parsed}]
                              [:render-value-repr #{:value :fn-id}]
                              [:form-registry-target #{:registry :type-expr}]
                              [:form-registry-selector #{:dispatch}]
                              [:_form-exact-target #{:registry :type-expr}]
                              [:_form-bounds #{:type-expr}]
                              [:_form-value-fits? #{:value :type-expr}]
                              [:trace-headers #{}]]]
    (is (= expected (set (keys (:args (registry/rich-type-of fn-name)))))
        (str fn-name))))
