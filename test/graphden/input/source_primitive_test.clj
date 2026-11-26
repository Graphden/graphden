(ns graphden.input.source-primitive-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.field-default :as field-default]
    [graphden.input.source :as source]
    [graphden.input.source-primitive :as primitive]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(def ^:private module
  {:input-format 2 :namespace "fixture"
   :fns [{:name :rounding :input (array-map :value {:type :numeric :description "Number"}
                                            :label {:type :text :required false})
          :return-type :int}]})


(def ^:private root (ids/fn-id "fixture" :rounding))


(deftest primitive-preparation-retains-every-legacy-declaration-identity
  (let [authored (first (:fns module))
        original (-> authored
                     (assoc :namespace (:namespace module) :args (:input authored))
                     (dissoc :input))
        prepared (parse/prepare-module [original] {} {})
        proof (first (:definitions (source/verify-module (source/plan-module [original] prepared) prepared)))
        result (primitive/prepare module root)
        identity-keys [:fn-id :input-type-id :contract :declaration-records :origin-ledger]]
    (is (= :authored-source-equivalent (:verification proof)))
    (is (= (select-keys (:v2 proof) identity-keys) (select-keys result identity-keys)))
    (is (= (ids/slot-id root :label) (get-in result [:contract :fields 1 :id])))
    (is (false? (get-in result [:contract :fields 1 :required])))))


(defn- code
  [call]
  (try (call) nil (catch clojure.lang.ExceptionInfo failure (:code (ex-data failure)))))


(deftest primitive-preparation-rejects-unproved-source-shapes
  (doseq [[change expected] [[{:parent :floor} :staged-primitive-inheritance-unsupported]
                             [{:bind {:value [:ref :other]}} :staged-primitive-bindings-unsupported]
                             [{:input {:value [:list :numeric]}} :staged-primitive-type-unsupported]
                             [{:return-type :other} :staged-primitive-type-unsupported]
                             [{:fn-id (random-uuid)} :staged-primitive-declaration-unsupported]]]
    (is (= expected (code #(primitive/prepare (update-in module [:fns 0] merge change) root)))))
  (is (= :staged-root-required (code #(primitive/prepare module (random-uuid)))))
  (is (= :staged-primitive-module-ledger
         (code #(primitive/prepare (update module :fns conj (first (:fns module))) root)))))


(deftest pinned-record-abi-retains-closed-callable-fields-and-scalar-results
  (let [callback [:fn {:string :text} :text #{}]
        contract {:payload {:item :text :callback callback} :actor {:id :uuid}}
        input (update-vals contract #(hash-map :type % :required true))
        declaration {:name :transform :input input :return-type :text}
        source {:namespace "fixture" :input-format 2 :fns [declaration]}
        id (ids/fn-id "fixture" :transform)
        compiled (primitive/prepare source id contract)
        payload (some #(when (= :payload (:name %)) %) (get-in compiled [:contract :fields]))
        field (some #(when (= :callback (:name %)) %) (:fields payload))
        effectful (assoc-in contract [:payload :callback 3] #{:db})
        effectful-input (update-vals effectful #(hash-map :type % :required true))]
    (is (= contract (get-in compiled [:contract :type])))
    (is (= callback (:type field)))
    (is (uuid? (:type-id field)))
    (is (= :staged-primitive-type-unsupported (code #(primitive/prepare source id))))
    (is (= :staged-primitive-type-unsupported
           (code #(primitive/prepare (assoc-in source [:fns 0 :input] effectful-input) id effectful))))
    (is (= :staged-primitive-type-unsupported
           (code #(primitive/prepare (assoc-in source [:fns 0 :return-type] callback) id contract))))))


(def ^:private composed-module
  {:input-format 2 :namespace "fixture"
   :fns [{:name :subtract :input (array-map :left :int :right :int) :return-type :int}
         {:name :from-12 :parent :subtract :bind {:left [:value 12]}}]})


(def ^:private composed-root (ids/fn-id "fixture" :from-12))
(def ^:private composed-parent (ids/fn-id "fixture" :subtract))
(def ^:private visible #{composed-root composed-parent})


(deftest literal-root-has-the-legacy-binding-and-inherited-field-identities
  (doseq [bound [{:left 12} (array-map :right 1 :left 12)]]
    (let [original [{:namespace "fixture" :name :subtract
                     :args (array-map :left :int :right :int) :return-type :int}
                    {:namespace "fixture" :name :from-12 :parent :subtract :args bound}]
          prepared (parse/prepare-module original {} {})
          proof (second (:definitions (source/verify-module (source/plan-module original prepared) prepared)))
          staged (assoc-in composed-module [:fns 1 :bind] (update-vals bound #(vector :value %)))
          actual (second (primitive/prepare-literal-root staged composed-root visible))
          identity-keys [:fn-id :input-type-id :contract :declaration-records :origin-ledger :bindings]
          slots (map #(ids/slot-id composed-parent %) (keys bound))]
      (is (= :authored-source-equivalent (:verification proof)))
      (is (= (select-keys (:v2 proof) identity-keys) (select-keys actual identity-keys)))
      (is (= (set (map #(ids/binding-id composed-root %) slots)) (set (map :id (:bindings actual)))))
      (is (= (set (map vector slots)) (set (map :target-path (:bindings actual))))))))


(deftest literal-root-rejects-other-parent-and-binding-shapes
  (is (= :staged-function-not-visible
         (code #(primitive/prepare-literal-root composed-module composed-root #{composed-root}))))
  (doseq [[patch diagnostic] [[{:parent :unknown} :staged-literal-parent-required]
                              [{:parents [:subtract]} :staged-literal-parent-unsupported]
                              [{:bind {:unowned [:value 12]}} :staged-literal-field-unknown]
                              [{:bind {:left [:ref :subtract]}} :staged-literal-binding-unsupported]
                              [{:bind {:left [:value [12]]}} :staged-literal-binding-unsupported]
                              [{:bind {:left [:value 12] :right [:ref :subtract]}} :staged-literal-binding-unsupported]
                              [{:bind {:left [:value 12] :unknown [:value 1]}} :staged-literal-field-unknown]
                              [{:bind {}} :staged-literal-binding-unsupported]
                              [{:configure {[:left] {:type :int}}} :staged-literal-declaration-unsupported]]]
    (is (= diagnostic
           (code #(primitive/prepare-literal-root (update-in composed-module [:fns 1] merge patch)
                                                  composed-root visible))))))


(deftest optional-default-retains-an-unoccupied-typed-membership
  (let [spec {:type [:union :null :text] :default nil :required false}
        prepare #(primitive/prepare (assoc-in module [:fns 0 :input] {:label %}) root)
        prepared (prepare spec)
        member (first (filter :input-default (:declaration-records prepared)))
        slot (ids/slot-id root :label)]
    (is (= [(:input-type-id prepared) slot {:value nil}]
           ((juxt :fn-id :slot-id :input-default) member)))
    (is (= (ids/fn-id "core.refinements" :nullable-text) (get-in prepared [:contract :fields 0 :type-id])))
    (is (empty? (:bindings prepared)))
    (doseq [invalid [(assoc spec :required true) (dissoc spec :required)
                     (assoc spec :default false) (assoc spec :type [:union :null :int])]]
      (is (= :staged-primitive-type-unsupported (code #(prepare invalid)))))))


(deftest record-member-defaults-are-relative-to-a-declared-occurrence
  (let [member {:type field-default/nullable-text :required false}
        record (fn [child] {:required false :type {:id :text :label child}})
        shape {:id :text :label field-default/nullable-text}
        pin {:left shape :right shape}
        input {:left (record (assoc member :default nil)) :right (record member)}
        prepare #(primitive/prepare (assoc-in module [:fns 0 :input] %) root pin)
        parsed (prepare input)
        rows (group-by :kind (:declaration-records parsed))
        index #(into {} (map (juxt :id identity)) %)
        ctx (context/build-context {:fns (assoc (index (:fn rows)) field-default/nullable-text-id {:constraint field-default/nullable-text})
                                    :fields (index (:slot rows)) :members (group-by :fn-id (:fn-slot rows))})
        input-id (:input-type-id parsed)
        left (ids/slot-id root :left)
        type-id (get-in ctx [:fields left :type-fn-id])
        target (ids/slot-id type-id :label)
        defaults (fn [ctx]
                   (into {} (map (fn [field]
                                   [(:name field) (select-keys (descriptor/node-at field [target]) [:default])]))
                         (:fields (descriptor/resolve-contract ctx root))))
        altered (fn [owner slot envelope]
                  (update-in ctx [:members owner] (fn [members]
                                                    (mapv #(if (= slot (:slot-id %)) (assoc % :input-default envelope) %) members))))
        envelope {:target-path [(str target)] :value "left"}]
    (is (= input (get-in parsed [:source :input])))
    (is (= {:left {:default nil} :right {}} (defaults ctx)))
    (is (= type-id (get-in ctx [:fields (ids/slot-id root :right) :type-fn-id])))
    (is (= {:left {:default "left"} :right {}} (defaults (altered input-id left envelope))))
    (doseq [bad [(assoc envelope :target-path [(str (random-uuid))])
                 (assoc envelope :target-path [(str (ids/slot-id type-id :id))])
                 (assoc envelope :target-path [(str target) (str target)]) (assoc envelope :value false)
                 (assoc envelope :extra true)]]
      (is (= :invalid-field-default (code #(defaults (altered input-id left bad))))))
    (is (= :invalid-field-default (code #(defaults (altered type-id target envelope)))))
    (doseq [bad [(assoc member :default [:ref :private]) (assoc member :required true :default "Ada")
                 (assoc member :type :text :default "Ada")]]
      (is (= :staged-record-member-default-unsupported (code #(prepare (assoc input :left (record bad)))))))))


(deftest grouped-defaults-validate-the-entire-owner-membership
  (let [optional {:type field-default/nullable-text :required false}
        record {:required false :type {:label (assoc optional :default "Ada") :hint (assoc optional :default nil)}}
        shape {:label field-default/nullable-text :hint field-default/nullable-text}
        parsed (primitive/prepare (assoc-in module [:fns 0 :input] {:left record}) root {:left shape})
        rows (group-by :kind (:declaration-records parsed))
        index #(into {} (map (juxt :id identity)) %)
        ctx (context/build-context {:fns (assoc (index (:fn rows)) field-default/nullable-text-id {:constraint field-default/nullable-text})
                                    :fields (index (:slot rows)) :members (group-by :fn-id (:fn-slot rows))})
        input-id (:input-type-id parsed)
        slot (ids/slot-id root :left)
        envelope (:input-default (first (get-in ctx [:members input-id])))
        [a b] (:members envelope)
        defaults (fn [envelope]
                   (let [ctx (update-in ctx [:members input-id] #(mapv (fn [row] (assoc row :input-default envelope)) %))
                         node (descriptor/node-at (descriptor/resolve-contract ctx root) [slot])]
                     (into {} (map (juxt :name #(select-keys % [:default]))) (:fields node))))]
    (is (= {:label {:default "Ada"} :hint {:default nil}} (defaults envelope)))
    (is (= [a b] (sort-by :target-path [a b])))
    (is (= :staged-record-member-default-unsupported
           (code #(primitive/prepare (assoc-in module [:fns 0 :input] {:left (assoc-in record [:type :detail] {:child optional})})
                                     root {:left (assoc shape :detail {:child field-default/nullable-text})}))))
    (doseq [bad [{:members [b a]} {:members [a a]} {:members [b]} {:members []} {:members [a [:oops]]}
                 {:members [a b] :value nil} {:members [a (dissoc b :value)]}
                 {:members [a (assoc b :value [:ref :private])]}
                 {:members (vec (sort-by :target-path [a (assoc b :target-path [(str slot)])]))}
                 {:members [a (assoc b :target-path (into (:target-path a) (:target-path b)))]}]]
      (is (= :invalid-field-default (code #(defaults bad)))))))
