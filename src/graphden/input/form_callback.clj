(ns graphden.input.form-callback
  "Verify the original decoder callback and bind its two nested reads to the supplied value."
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.migration :as migration]
    [graphden.input.rows :as rows]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.types :as records]))


(def callback-id (ids/fn-id "web.crud" :_form-decoded-value))
(def ^:private if-id (ids/fn-id "core.logic" :if))
(def ^:private predicate-id (ids/fn-id "core.logic" :is-a?))
(def ^:private last-id (ids/fn-id "core.collections" :last))


(def ^:private original
  {:namespace "web.crud" :name :_form-decoded-value :parent :if :lambda-params [:value]
   :args {:test {:parent :is-a? :args {:value {:as :value} :type {:value :vector}}}
          :then {:parent :last :args {:coll {:as :value}}}
          :else {:as :value}}})


(defn- require-proof!
  [pred]
  (when-not pred (throw (ex-info "Invalid form decoder callback evidence" {:type :input/form-proof :code :unverified-callback}))))


(defn- ref-use
  [ctx entry branch native]
  (let [slot (ids/slot-id if-id branch)
        use-id (ids/binding-id callback-id slot)
        row (get-in ctx [:bindings use-id])
        child (:ref-fn-id row)
        argument (first (filter #(= [:args branch] (:source-path %)) (get-in entry [:legacy :arguments])))]
    (require-proof! (and (= (select-keys row [:fn-id :target-path :scope-path :expression-kind])
                            {:fn-id callback-id :target-path [slot] :scope-path [] :expression-kind "ref"})
                         (= {:fn-id callback-id :slot-id slot :binding-id use-id} (:origin argument))
                         (uuid? child) (= child (:inline-fn-id argument) (get-in entry [:v2 :inline-definitions [slot] :fn-id]))
                         (= [native] (get-in ctx [:fns child :parent-ids]))))
    {:use use-id :child child}))


(defn- declare-field
  [ctx owner field former new-type]
  (let [id (ids/slot-id owner field)
        input-type (get-in ctx [:fns owner :input-type-fn-id])
        type-id (get-in ctx [:fields id :type-fn-id])]
    (require-proof! (and (uuid? input-type) (uuid? type-id)
                         (= former (get-in ctx [:type-bodies input-type field])
                            (get-in ctx [:source-field-types [id type-id]]))
                         (= type-id (records/resolve-type-ref new-type (:alias-ids ctx)))))
    (-> ctx (assoc-in [:type-bodies input-type field] new-type)
        (assoc-in [:source-field-types [id type-id]] new-type))))


(defn install
  [frozen entry]
  (let [{:keys [context options]} frozen
        parameter (ids/slot-id callback-id :value)
        before (analysis/analyze-effective-input context callback-id options)
        lookup (get-in before [:public-input :routes])
        test-use (ref-use context entry :test predicate-id)
        then-use (ref-use context entry :then last-id)
        type-slot (ids/slot-id predicate-id :type)
        test-type-row (get-in context [:bindings (ids/binding-id (:child test-use) type-slot)])
        guarded-source (ids/slot-id (:child then-use) :value)
        inherited-last (ids/slot-id last-id :coll)
        else-field (ids/slot-id if-id :else)
        direct (get-in context [:bindings (ids/binding-id callback-id else-field)])
        nested (get-in context [:bindings (ids/binding-id (:child then-use) inherited-last)])
        specs [[:test test-use (ids/slot-id predicate-id :value)] [:then then-use guarded-source]]]
    (require-proof! (and (= :authored-source-equivalent (:verification entry))
                         (= original (dissoc (:source entry) :source-file :source-line))
                         (= callback-id (:identity entry) (get-in entry [:v2 :fn-id]))
                         (= [if-id] (get-in context [:fns callback-id :parent-ids]))
                         (= ["value"] (get-in context [:fns callback-id :lambda-params]))
                         (= "value" (:expression-kind test-type-row)) (= :vector (:value test-type-row))
                         (= [type-slot] (:target-path test-type-row))
                         (= "projection" (:expression-kind direct) (:expression-kind nested))
                         (= [parameter] (:projection-path direct)) (= [guarded-source] (:projection-path nested))
                         (= :checked (:status before)) (= [[callback-id []] [parameter]] (:address (lookup [:value])))))
    (let [projections
          (mapv (fn [[branch {use-id :use} field]]
                  (let [target [[callback-id [use-id]] [field]]
                        added (migration/derived-id :forwarded-field callback-id use-id field)
                        route (lookup [branch :value])]
                    (require-proof! (and (= target (:address route)) (= :input-field (:kind route))
                                         (:whole-bindable? route) (nil? (get-in context [:bindings added]))))
                    (rows/normalize {:kind :input-binding :id added :fn-id callback-id :scope-path [use-id]
                                     :target-path [field] :expression-kind "projection" :value-present false
                                     :projection-scope-path [] :projection-path [parameter]}))) specs)
          ctx (reduce (fn [ctx row] (assoc-in ctx [:bindings (:id row)] row))
                      (-> context (assoc :snapshot-id (random-uuid))
                          (declare-field callback-id :value 'b :any)
                          (declare-field (:child then-use) :value '[:list a] [:list :any]))
                      projections)
          checked (analysis/analyze-effective-input ctx callback-id options)
          public (get-in checked [:public-input :routes])]
      (require-proof! (and (= :checked (:status checked)) (= #{[] [:value]} (set (keys public)))
                           (= :any (:completion-type (public [:value])))
                           (every? (fn [row]
                                     (let [addr [[callback-id (:scope-path row)] (:target-path row)]
                                           bound (get-in checked [:public-input :bindings addr])]
                                       (and (= (:id row) (:binding-id bound))
                                            (= {:kind :projection :scope [callback-id []] :path [parameter]} (:expression bound))))) projections)
                           (every? #{:any [:list :any]} (map :expected-type (:obligations checked)))))
      {:frozen (assoc frozen :context ctx)
       :rows projections :checked checked
       :proof {:original original :parameter parameter :uses [test-use then-use]
               :type-fks (mapv #(get-in ctx [:fields % :type-fn-id]) [parameter guarded-source])
               :declarations {parameter :any guarded-source [:list :any]}
               :bind {[:test :value] [:project [:value]] [:then :value] [:project [:value]]}}})))
