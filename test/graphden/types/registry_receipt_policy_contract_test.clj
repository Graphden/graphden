(ns ^:ci/package-impl-consumer graphden.types.registry-receipt-policy-contract-test
  "Check the shipped projection closures without a whole-package sweep."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.parse :as records-parse]
    [graphden.packages.sync :as sync]
    [graphden.types.check :as check]
    [graphden.types.check.narrowing :as narrowing]
    [graphden.types.core :as types]))


(def ^:private loaded (loader/load-packages ["core" "web" "app-base" "app" "registry"]))


(use-fixtures :once
  (fn [test-fn]
    (binding [types/*type-aliases-override* (atom {})]
      (sync/register-type-aliases! (:fn-defs loaded))
      (exec/with-isolated-rich-types
        (fn []
          (doseq [[fn-name fn-def] (:base-fn-defs loaded)]
            (registry/record-rich-types! fn-name fn-def))
          (test-fn))))))


(deftest registry-receipt-projection-closures-retain-only-their-public-inputs
  (let [selected (conj (filterv
                         (fn [{:keys [name parent]}]
                           (and name parent
                                (or (some #(str/starts-with? (clojure.core/name name) %)
                                          ["_export-fn-" "_export-arg-" "_export-secret-" "_export-stripped-"
                                           "_export-kept-" "_export-with-" "_export-without-"
                                           "_actor-" "_request-owner-present" "_owned-branch-policy"
                                           "_mirror-" "_package-pin-" "_pin-remove-" "_materialization-"])
                                    (#{:current-user-label :create-owned-branch!
                                       :secret-path-args :strip-secret-paths :materialize-package-fns :package-version-materialized?
                                       :package-upsert-pin :package-pin-receipt :remove-package-pin-apply
                                       :mirror-store-package-version!} name))))
                         (:fn-defs loaded))
                       {:name :_registry-policy-mirror-error-reader :parent :get
                        :args {:coll :mirror-store-package-version! :key {:value :error} :default nil}})
        extra-defs (into {} (map (fn [[n fd]] [n (assoc fd :name n)])) (:base-fn-defs loaded))
        {:keys [fn-defs name->id]} (records-parse/prepare-module
                                     selected (sync/compute-all-fn-name-ids loaded) extra-defs)
        sorted (mapv check/checker-view
                     (deps/topological-sort fn-defs (constantly false) name->id))]
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
  (doseq [[n expected-args] [[:current-user-label #{}] [:create-owned-branch! #{:branch-name}]
                             [:secret-path-args #{:fn-defs}] [:strip-secret-paths #{:fn-defs}]
                             [:materialize-package-fns #{:ns-root :version :fns}]
                             [:package-version-materialized? #{:ns-root :version :fns}]
                             [:package-upsert-pin #{:pkg-name :pkg-version}]
                             [:package-pin-receipt #{:pkg-name :pkg-version}]
                             [:remove-package-pin-apply #{:pkg-name :expected-id}]
                             [:mirror-store-package-version! #{:pkg-name :version :row :origin-url :origin}]]]
    (is (= expected-args (set (keys (:args (registry/rich-type-of n))))) (str n))))
