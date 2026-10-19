(ns graphden.types.value-view-contract-test
  "Actual package/checker contract for field values, views and boundary validation."
  (:require
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.packages.sync :as sync]
    [graphden.types.check :as check]
    [graphden.types.core :as types]
    [graphden.types.core.shapes :as shapes]))


(defonce ^:private packages (delay (loader/load-packages ["core" "web"])))


(use-fixtures :each
  (fn [t]
    (binding [types/*type-aliases-override* (atom {})
              shapes/*marker-registry-override* (atom (shapes/markers-snapshot))
              registry/*rich-types-override* (atom {:by-id {} :by-name {}})]
      (let [p @packages]
        (sync/register-type-aliases! (:fn-defs p))
        (doseq [[n fd] (:base-fn-defs p)] (registry/record-rich-types! n fd)))
      (t))))


(defn- checked!
  [fd]
  (check/check-fn-def! fd)
  (registry/rich-type-of (:name fd)))


(deftest color-state-value-can-be-saved-and-view-cannot
  (registry/record-rich-types! :save-color {:args {:color :core.refinements/color} :return-type :bool})
  (checked! {:name :color-value :parent :const :args {:value "#aabbcc"}
             :return-type :core.refinements/color})
  (checked! {:name :color-view :parent :as-ui-view
             :args {:hiccup {:value ["span" {"class" "swatch"} "#aabbcc"]}}})
  (is (= :bool (:return (checked! {:name :save-value :parent :save-color :args {:color :color-value}}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type-check failed"
        (checked! {:name :save-view :parent :save-color :args {:color :color-view}})))
  (registry/record-rich-types! :text-sink {:args {:text :text} :return-type :bool})
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type-check failed"
        (checked! {:name :view-as-text :parent :text-sink :args {:text :color-view}}))))


(deftest broad-data-sink-is-diagnostic-and-retains-the-source-contract
  (checked! {:name :color-view :parent :as-ui-view
             :args {:hiccup {:value ["span" "Color"]}}})
  (doseq [sink [:any :jsonb]]
    (registry/record-rich-types! :data-sink {:args {:value sink} :return-type :bool})
    (let [entry (checked! {:name :broad-view :parent :data-sink :args {:value :color-view}})]
      (is (= [:types/view-to-broad-sink] (mapv :code (:type-hints entry))))
      (is (true? (shapes/type-any? #(and (types/marker-type? %) (= :ui-view (types/marker-tag %)))
                                   (get-in entry [:resolved-bindings :value :type])))))))


(deftest explicit-encoding-does-not-declassify-a-secret-view
  (registry/record-rich-types-raw! :secret-view
                                   {:args {} :return [:secret [:ui-view :text]] :execution #{:jvm-server}})
  (let [entry (checked! {:name :encoded :parent :encode-view :args {:view :secret-view}})]
    (is (= [:secret :text] (:return entry)))
    (is (= #{:jvm-server} (:execution entry)))))


(deftest declarative-ensure-type-uses-a-supported-literal-type-definition
  (let [shape {:colors [:list [:refine :text [:matches "^#[a-fA-F0-9]{6}$"]]]}
        entry (checked! {:name :checked-colors :parent :ensure-type
                         :args {:type {:value shape} :value {:as :input}}})]
    (is (= shape (:return entry)))))


(deftest passthrough-and-child-call-local-recomputation-retain-view-labels
  (checked! {:name :view-parent :parent :as-ui-view
             :args {:hiccup {:value ["span" "Parent"]}}})
  (let [before (:return (registry/rich-type-of :view-parent))
        child (checked! {:name :view-child :parent :view-parent})
        passthrough (checked! {:name :view-copy :parent :const :args {:value :view-child}})]
    (is (= before (:return child)))
    (is (= before (:return passthrough)))
    (is (true? (shapes/type-any? #(= :ui-view (types/marker-tag %)) before))))
  (registry/record-rich-types-raw! :nested-view
                                   {:args {} :return {:colors [:list [:ui-view :text]]}})
  (is (= {:colors [:list [:ui-view :text]]}
         (:return (checked! {:name :nested-copy :parent :const :args {:value :nested-view}})))))


(deftest value-producing-operation-can-consume-a-view-without-declassifying-secret
  (registry/record-rich-types-raw! :secret-view
                                   {:args {} :return [:secret [:ui-view :text]]})
  (let [entry (checked! {:name :view-present? :parent :some? :args {:value :secret-view}})]
    (is (= [:secret :bool] (:return entry)))))


(deftest runtime-validator-cannot-prove-an-unsupported-native-callback-portable
  (let [callback [:fn {} :int #{} #{:browser-main}]
        entry (checked! {:name :claimed-callback :parent :ensure-type
                         :args {:value {:as :raw-callback} :type {:value callback}}})]
    (is (= :any (:return entry)))
    (is (= #{:jvm-server} (:execution entry)))))


(deftest literal-variant-validation-records-a-canonical-static-return
  (let [variant [:variant :ok :int :err :text]
        union (types/desugar-variant variant)]
    (doseq [[declaration expected]
            [[variant union]
             [{:result variant} {:result union}]
             [[:list variant] [:list union]]
             [:core.refinements/result-int union]
             [[:list :core.refinements/result-int] [:list union]]]]
      (let [entry (checked! {:name :checked-variant :parent :ensure-type
                             :args {:type {:value declaration} :value {:as :input}}})]
        (is (= expected (:return entry)))
        (is (true? (types/well-formed? (:return entry))))
        (is (true? (types/subtype? (:return entry) expected)))))))


(deftest recursive-union-declarations-do-not-overflow-static-validation
  (types/register-type-alias! :node [:union :null [:list :node]])
  (doseq [declaration [:node [:union :node :text] {:items [:list :node]}
                       [:variant :ok :node :err :text]
                       {:result [:variant :ok :node :err :text]}]]
    (let [entry (checked! {:name :recursive-input :parent :ensure-type
                           :args {:type {:value declaration} :value {:as :input}}})]
      (is (= :any (:return entry))))))


(deftest nested-and-inherited-broad-sinks-retain-derived-hints
  (doseq [[actual expected path]
          [[{:payload [:ui-view :text]} {:payload :jsonb} [:payload]]
           [[:list [:ui-view :text]] [:list :any] [:element]]
           [[:map :keyword [:ui-view :text]] [:map :keyword :jsonb] [:value]]]]
    (registry/record-rich-types! :data-sink {:args {:input expected} :return-type :bool})
    (registry/record-rich-types-raw! :view-data {:args {} :return actual})
    (let [parent (checked! {:name :bound-view-parent :parent :data-sink :args {:input :view-data}})
          child (checked! {:name :bound-view-child :parent :bound-view-parent})]
      (is (= [{:arg-name :input :path path :code :types/view-to-broad-sink}]
             (mapv #(select-keys % [:arg-name :path :code]) (:type-hints parent))))
      (is (= (:type-hints parent) (:type-hints child)))
      (is (= actual (get-in child [:resolved-bindings :input :type]))))))


(deftest editor-wrapper-fixture-specializes-real-package-callbacks
  (let [definitions (:fns (edn/read-string (slurp "test/fixtures/platform/execution_editor.edn")))
        cb (fn [execution] [:fn {:item :text} :int #{} execution])]
    (doseq [definition definitions] (checked! definition))
    (is (= #{:browser-main :jvm-server} (:execution (registry/rich-type-of :pure-callback))))
    (is (= #{:jvm-server} (:execution (registry/rich-type-of :server-callback))))
    (is (= (cb [:execution-var 'E]) (get-in (registry/rich-type-of :wrapper) [:args :handler])))
    (let [browser (checked! {:name :browser-child :parent :wrapper
                             :args {:handlers {:as :events :type [:map :keyword (cb #{:browser-main})]}}})
          server (registry/rich-type-of :server-child)]
      (is (= (cb #{:browser-main}) (get-in browser [:args :handler])))
      (is (= [:map :keyword (cb #{:browser-main})] (get-in browser [:args :events])))
      (is (= (cb #{:jvm-server}) (get-in server [:args :handler])))
      (doseq [parent [:browser-child :server-child]]
        (is (map? (checked! {:name :pure-use :parent parent :args {:handler :pure-callback}}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Type-check failed"
            (checked! {:name :bad-browser-use :parent :browser-child :args {:handler :server-callback}})))
      (is (map? (checked! {:name :server-use :parent :server-child :args {:handler :server-callback}}))))))
