(ns graphden.types.validator-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.types.core :as types]
    [graphden.types.validator :as validator]))


(use-fixtures :each
  (fn [t] (binding [types/*type-aliases-override* (atom {})] (t))))


(deftest validators-follow-the-declared-data-shape
  (doseq [[t value] [[:int 3] [:text "blue"] [:bool false] [:null nil]
                     [{:colors [:list :text]} {:colors ["red" "blue"]}]
                     [[:map :keyword [:union :null :int]] {:a nil :b 4}]
                     [[:refine :int [:and [:>= 0] [:<= 255]]] 128]
                     [[:refine :text [:matches "^#[a-fA-F0-9]{6}$"]] "#aabbcc"]
                     [[:secret {:color :text}] {:color "red"}]]]
    (is (= value ((validator/build t) value)))))


(deftest declaration-aliases-and-variants-use-the-same-validator
  (types/register-type-alias! :color [:refine :text [:matches "^#[a-fA-F0-9]{6}$"]])
  (types/register-type-alias! :color-record {:color :color})
  (is (= {:color "#aabbcc"} (validator/validate! :color-record {:color "#aabbcc"})))
  (is (= {:kind :ok :value 1}
         (validator/validate! [:union {:kind [:refine :keyword [:= :ok]] :value :int}
                               {:kind [:refine :keyword [:= :error]] :message :text}]
                              {:kind :ok :value 1}))))


(deftest unsupported-declarations-fail-before-seeing-an-input
  (doseq [t [[:fn {} :int #{} #{:browser-main}] 'a :missing
             [:refine :text [:call :arbitrary-predicate]]
             [:refine :text [:matches "["]]
             {:items [:list [:refine :int [:call :unknown]]]}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot build a validator" (validator/build t)))))


(deftest errors-have-paths-without-input-values-or-dynamic-keys
  (doseq [[t value path]
          [[{:colors [:list :int]} {:colors [1 "secret-value"]} [:colors 1]]
           [{:color :text} {} [:color]]
           [[:map :text :int] {"secret-key" "secret-value"} [0 :value]]
           [[:refine :int [:>= 0]] -99 []]]]
    (let [e (try (validator/validate! t value) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= {:type :execution-error/type-mismatch
              :errors [{:path path :code (cond
                                           (= value {}) :missing-field
                                           (= value -99) :refinement-mismatch
                                           :else :type-mismatch)}]}
             (ex-data e)))
      (is (not (re-find #"secret|99" (str (ex-message e) (ex-data e))))))))


(deftest variant-declarations-and-recursive-aliases-have-explicit-outcomes
  (is (= {:tag :ok :value 3}
         (validator/validate! [:variant :ok :int :err :text] {:tag :ok :value 3})))
  (types/register-type-alias! :node {:next :node})
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot build a validator"
        (validator/build :node))))


(deftest checked-types-and-runtime-validators-share-variant-normalization
  (let [variant [:variant :ok :int :err :text]
        union (types/desugar-variant variant)]
    (types/register-type-alias! :outcome union)
    (doseq [[declaration canonical value]
            [[variant union {:tag :ok :value 3}]
             [{:result variant} {:result union} {:result {:tag :err :value "no"}}]
             [[:list variant] [:list union] [{:tag :ok :value 3}]]
             [[:map :keyword :outcome] [:map :keyword union] {:x {:tag :ok :value 3}}]]]
      (is (= canonical (validator/checked-type declaration)))
      (is (true? (types/well-formed? (validator/checked-type declaration))))
      (is (= value (validator/validate! declaration value))))))


(deftest recursive-unions-refuse-construction-before-alias-normalization
  (doseq [body [[:union :null [:list :node]]
                {:next [:list :node]}
                [:list [:map :keyword :node]]]]
    (types/register-type-alias! :node body)
    (doseq [declaration [:node [:union :node :text] {:items [:list :node]}]]
      (let [e (try (validator/build declaration) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :types/unsupported-validator (:type (ex-data e)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot build a validator"
            (validator/checked-type declaration))))))


(deftest variant-normalization-does-not-rewrite-literal-refinement-operands
  (let [literal [:variant :ok :int :err :text]
        t [:refine [:list :any] [:= literal]]]
    (is (= t (validator/checked-type t)))
    (is (= literal (validator/validate! t literal)))))
