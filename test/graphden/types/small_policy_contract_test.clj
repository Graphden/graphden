(ns ^:ci/package-impl-consumer graphden.types.small-policy-contract-test
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


(def ^:private loaded (loader/load-packages ["core" "web" "app-base"]))


(use-fixtures :once
  (fn [test-fn]
    (binding [types/*type-aliases-override* (atom {})]
      (sync/register-type-aliases! (:fn-defs loaded))
      (exec/with-isolated-rich-types
        (fn []
          (doseq [[fn-name fn-def] (:base-fn-defs loaded)]
            (registry/record-rich-types! fn-name fn-def))
          (test-fn))))))


(deftest small-policy-projection-closures-retain-only-their-public-inputs
  (let [selected (filterv
                   (fn [{:keys [name parent]}]
                     (and name parent
                          (or (some #(str/starts-with? (clojure.core/name name) %)
                                    ["_pad-" "_ui-pref-" "_live-"])
                              (#{:str-clip :non-blank? :str-pad-left :str-pad-right
                                 :header-get :_accept-encoding :pick-encoding :ui-prefs-read} name))))
                   (:fn-defs loaded))
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
  (doseq [[n expected-args] [[:str-clip #{:string :limit}] [:non-blank? #{:string}]
                             [:str-pad-left #{:string :length :pad}] [:str-pad-right #{:string :length :pad}]
                             [:header-get #{:headers :header-name}] [:pick-encoding #{:headers}]
                             [:ui-prefs-read #{}] [:_live-service-endpoint #{:rows}]]]
    (is (= expected-args (set (keys (:args (registry/rich-type-of n))))) (str n)))
  (registry/record-rich-types! :_small-policy-secret-source
                               {:args {} :return-type [:secret :text]})
  (doseq [[parent extra] [[:non-blank? {}] [:str-clip {:limit 2}]
                          [:str-pad-left {:length 4 :pad "0"}] [:str-pad-right {:length 4 :pad "0"}]]]
    (let [n (keyword (str "secret-small-policy-" (name parent)))]
      ;; These text slots reject secret-typed references rather than exposing
      ;; their contents through an unmarked scalar result.
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type-check failed"
            (check/check-fn-def! {:name n :parent parent
                                  :args (assoc extra :string :_small-policy-secret-source)}))
          (str parent " rejects secret content"))))
  (doseq [n [:non-blank? :str-clip :str-pad-left :str-pad-right :header-get :pick-encoding]]
    (is (empty? (:effects (registry/rich-type-of n))) (str n))))
