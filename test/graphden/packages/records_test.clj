(ns graphden.packages.records-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.records :as r]))


;; -----------------------------------------------------------------------------
;; Determinism — same input → same UUIDs

(deftest fn-id-deterministic
  (is (= (r/fn-id "core.system" :ring-response-shape)
         (r/fn-id "core.system" :ring-response-shape))
      "fn-id is deterministic"))


(deftest slot-id-deterministic
  (let [own (r/fn-id "core.system" :ring-response-shape)]
    (is (= (r/slot-id own :status)
           (r/slot-id own :status)))))


(deftest different-namespaces-give-different-ids
  (is (not= (r/fn-id "core.system" :foo)
            (r/fn-id "web.http" :foo))))


;; -----------------------------------------------------------------------------
;; Boot primitives

(deftest boot-primitives-shape
  (let [recs (r/boot-primitive-records)]
    (is (= 14 (count recs)))
    (is (every? #(= :fn (:kind %)) recs))
    (is (every? #(empty? (:parent-ids %)) recs))
    (is (every? #(nil? (:base-fn-id %)) recs))
    (is (every? #(nil? (:constraint %)) recs))))


(deftest primitive-fn-ids-stable
  (let [m1 (r/primitive-fn-ids)
        m2 (r/primitive-fn-ids)]
    (is (= m1 m2))
    (is (= 14 (count m1)))
    (is (contains? m1 :int))
    (is (contains? m1 :text))
    (is (contains? m1 :jsonb))))


;; -----------------------------------------------------------------------------
;; Record-type parsing

(deftest record-type-basic
  (let [recs (r/parse-fn-def
               {:name :user-shape
                :namespace "examples"
                :type {:name :text :age :int}
                :description "A user"}
               {})
        fns (filter #(= :fn (:kind %)) recs)
        slots (filter #(= :slot (:kind %)) recs)
        fn-slots (filter #(= :fn-slot (:kind %)) recs)]
    (is (= 1 (count fns)) "exactly one fn-row")
    (is (= 2 (count slots)) "two slots — one per field")
    (is (= 2 (count fn-slots)) "two junction rows")
    (let [fn-row (first fns)]
      (is (= "user-shape" (:name fn-row)))
      (is (nil? (:base-fn-id fn-row)))
      (is (nil? (:constraint fn-row)))
      (is (= [] (:parent-ids fn-row))))
    (testing "slot names match fields"
      (is (= #{"name" "age"} (set (map :name slots)))))
    (testing "slot type-fn-id points to primitive"
      (let [primitives (r/primitive-fn-ids)
            slots-by-name (into {} (map (juxt :name identity)) slots)]
        (is (= (:text primitives) (:type-fn-id (slots-by-name "name"))))
        (is (= (:int primitives) (:type-fn-id (slots-by-name "age"))))))))


;; -----------------------------------------------------------------------------
;; Refinement-type parsing

(deftest refinement-type-basic
  (let [recs (r/parse-fn-def
               {:name :positive-int
                :namespace "core.refinements"
                :refine {:base :int :constraint [:> 0]}
                :description "An int > 0"}
               {})
        fns (filter #(= :fn (:kind %)) recs)
        slots (filter #(= :slot (:kind %)) recs)
        fn-slots (filter #(= :fn-slot (:kind %)) recs)]
    (testing "produces fn + a single :value slot + its junction"
      (is (= 1 (count fns)))
      (is (= 1 (count slots)))
      (is (= 1 (count fn-slots))))
    (let [fn-row (first fns)]
      (is (= [:> 0] (:constraint fn-row)))
      (is (= (:int (r/primitive-fn-ids)) (:base-fn-id fn-row))))
    (testing "auto-emitted slot is named :value with type pointing at base"
      (let [s (first slots)]
        (is (= "value" (:name s)))
        (is (= (:int (r/primitive-fn-ids)) (:type-fn-id s)))))))


;; -----------------------------------------------------------------------------
;; List-type parsing

(deftest list-type-basic
  (let [recs (r/parse-fn-def
               {:name :int-list
                :namespace "core.collections"
                :list :int}
               {})
        fns (filter #(= :fn (:kind %)) recs)
        slots (filter #(= :slot (:kind %)) recs)
        fn-slots (filter #(= :fn-slot (:kind %)) recs)]
    (testing "produces fn + a single :items slot + its junction"
      (is (= 1 (count fns)))
      (is (= 1 (count slots)))
      (is (= 1 (count fn-slots))))
    (let [fn-row (first fns)]
      (is (= (:int (r/primitive-fn-ids)) (:element-fn-id fn-row)))
      (is (nil? (:base-fn-id fn-row)))
      (is (nil? (:constraint fn-row))))
    (testing "auto-emitted slot is named :items with the :sequence primitive"
      (let [s (first slots)]
        (is (= "items" (:name s)))))))


(deftest map-type-basic
  (let [recs (r/parse-fn-def
               {:name :kw-int-map
                :namespace "test"
                :map {:key :keyword :value :int}}
               {})
        fns (filter #(= :fn (:kind %)) recs)]
    (testing "produces a single fn-row, no slots — pure type metadata"
      (is (= 1 (count fns)))
      (is (empty? (filter #(= :slot (:kind %)) recs))))
    (let [fn-row (first fns)]
      (is (= [:map :keyword :int] (:constraint fn-row)))
      (is (nil? (:base-fn-id fn-row)))
      (is (nil? (:element-fn-id fn-row))))))


(deftest tuple-type-basic
  (let [recs (r/parse-fn-def
               {:name :text-int-pair
                :namespace "test"
                :tuple [:text :int]}
               {})
        fns (filter #(= :fn (:kind %)) recs)]
    (testing "produces a single fn-row, no slots — pure type metadata"
      (is (= 1 (count fns)))
      (is (empty? (filter #(= :slot (:kind %)) recs))))
    (is (= [:tuple :text :int] (:constraint (first fns))))))


;; -----------------------------------------------------------------------------
;; Composed fn-def parsing

(deftest composed-fn-def-emits-bindings
  (let [parent-id (r/fn-id "examples" :user-shape)
        recs (r/parse-fn-def
               {:name :alice
                :namespace "examples"
                :parent :user-shape
                :args {:name "Alice" :age 30}}
               {:user-shape parent-id})
        fns (filter #(= :fn (:kind %)) recs)
        bindings (filter #(= :binding (:kind %)) recs)]
    (is (= 1 (count fns)))
    (is (= 2 (count bindings)) "one binding per :args entry")
    (let [fn-row (first fns)]
      (is (= [parent-id] (:parent-ids fn-row))))
    (testing "bindings are :fixed by default"
      (is (every? #(= :fixed (:override-kind %)) bindings)))
    (testing "binding values match"
      (let [by-slot (into {} (map (juxt :slot-id :value)) bindings)]
        (is (= #{"Alice" 30} (set (vals by-slot))))))))


;; -----------------------------------------------------------------------------
;; Inline anonymous composite — appears as field type

(deftest inline-composite-in-record
  (let [recs (r/parse-fn-def
               {:name :outer
                :namespace "examples"
                :type {:user {:name :text :age :int}
                       :status :int}}
               {})
        fns (filter #(= :fn (:kind %)) recs)]
    (testing "produces TWO fn-rows: outer + anonymous inner"
      (is (= 2 (count fns))))
    (testing "inner has anonymous-hash, no name"
      (let [anon (first (filter #(nil? (:name %)) fns))]
        (is (some? anon))
        (is (some? (:anonymous-hash anon)))))))


;; -----------------------------------------------------------------------------
;; Primitive references resolve directly

(deftest primitive-types-resolve
  (let [recs (r/parse-fn-def
               {:name :foo
                :namespace "test"
                :type {:n :int :s :text}}
               {})
        slots (filter #(= :slot (:kind %)) recs)]
    (is (every? (fn [s] (some? (:type-fn-id s))) slots))))


;; -----------------------------------------------------------------------------
;; parse-module two-pass

(deftest parse-module-multi-fn
  (let [defs [{:name :user-shape :namespace "test"
               :type {:name :text}}
              {:name :alice :namespace "test"
               :parent :user-shape :args {:name "A"}}]
        recs (r/parse-module defs)
        fns (filter #(= :fn (:kind %)) recs)]
    (is (= 2 (count fns)))
    (testing "alice's parent-ids points to user-shape's id"
      (let [user-id (r/fn-id "test" :user-shape)
            alice (first (filter #(= "alice" (:name %)) fns))]
        (is (= [user-id] (:parent-ids alice)))))))


;; -----------------------------------------------------------------------------
;; Composed parsing — rename slots, MI, type-pin via ancestor.
;; These exercise `parse-composed`'s decomposition (composed-own-fn,
;; collect-exposed-names, build-rename-slot-records,
;; build-binding-and-items, ancestor-type-pin).

(deftest composed-rename-creates-new-slot
  (testing "{:as :renamed} on a parent's slot creates a NEW rename slot owned by the child"
    (let [defs [{:name :base :namespace "test"
                 :args {:port {:type :int :required true}}}
                {:name :renamer :namespace "test"
                 :parent :base
                 :args {:port {:as :listen-port}}}]
          recs (r/parse-module defs)
          slots (filter #(= :slot (:kind %)) recs)
          renamer-id (r/fn-id "test" :renamer)
          rename-slot (first (filter #(and (= "listen-port" (:name %))
                                           (= renamer-id (:fn-id (->> recs
                                                                      (filter (fn [r]
                                                                                (and (= :fn-slot (:kind r))
                                                                                     (= (:id %) (:slot-id r)))))
                                                                      first))))
                                     slots))]
      (testing "rename slot exists with the exposed name"
        (is (some? rename-slot)
            "rename slot named :listen-port should be emitted")))))


(deftest composed-rename-explicit-type-override
  (testing "{:as :renamed :type T} on a NEW name pins the rename slot's type to T"
    (let [defs [{:name :base :namespace "test"
                 :args {:port {:type :int :required true}}}
                {:name :renamer :namespace "test"
                 :parent :base
                 :args {:port {:as :listen-port :type :text}}}]
          recs (r/parse-module defs)
          renamer-id (r/fn-id "test" :renamer)
          rename-slot (->> recs
                           (filter #(and (= :slot (:kind %))
                                         (= "listen-port" (:name %))
                                         (= (r/slot-id renamer-id "listen-port")
                                            (:id %))))
                           first)
          text-primitive-id (get (r/primitive-fn-ids) :text)]
      (is (some? rename-slot)
          ":renamer must own a rename slot named :listen-port")
      (is (= text-primitive-id (:type-fn-id rename-slot))
          "explicit :type :text on the rename binding pins the slot's type"))))


(deftest composed-rename-inherits-type-via-ancestor
  (testing "MI rename inherits :type from an ancestor binding's :type pin"
    (let [defs [{:name :assoc-empty :namespace "test"
                 :args {:value {:type :any :required true}}}
                ;; assoc-fn pins :value to :fn via no-op rename.
                {:name :assoc-fn :namespace "test"
                 :parent :assoc-empty
                 :args {:value {:as :value :type :fn}}}
                ;; assoc-handler renames :value → :handler. The parent's
                ;; :type :fn pin should be inherited via ancestor-type-pin.
                {:name :assoc-handler :namespace "test"
                 :parent :assoc-fn
                 :args {:value {:as :handler}}}]
          recs (r/parse-module defs)
          handler-fn-id (r/fn-id "test" :assoc-handler)
          handler-slot (->> recs
                            (filter #(and (= :slot (:kind %))
                                          (= "handler" (:name %))
                                          (= (r/slot-id handler-fn-id "handler") (:id %))))
                            first)
          fn-primitive-id (get (r/primitive-fn-ids) :fn)]
      (is (some? handler-slot)
          ":assoc-handler must own a rename slot named :handler")
      (is (= fn-primitive-id (:type-fn-id handler-slot))
          "rename slot inherits :fn type from :assoc-fn ancestor's :type pin"))))


(deftest composed-mi-merges-parent-ids
  (testing ":parents [:a :b] produces parent-ids vector with both ids in order"
    (let [defs [{:name :a :namespace "test" :args {:x {:type :int :required true}}}
                {:name :b :namespace "test" :args {:y {:type :int :required true}}}
                {:name :child :namespace "test"
                 :parents [:a :b]}]
          recs (r/parse-module defs)
          fns (filter #(= :fn (:kind %)) recs)
          child (first (filter #(= "child" (:name %)) fns))]
      (is (some? child))
      (is (= [(r/fn-id "test" :a) (r/fn-id "test" :b)]
             (:parent-ids child))))))


(deftest composed-binding-list-items
  (testing "vector arg-value on a sequence-typed slot emits binding-list-items"
    (let [defs [;; A list-type fn whose :items slot is sequence-typed.
                {:name :int-list :namespace "test" :list :int}
                ;; A composed fn binding the sequence with two items.
                {:name :two-ints :namespace "test"
                 :parent :int-list
                 :args {:items [1 2]}}]
          recs (r/parse-module defs)
          items (filter #(= :binding-list-item (:kind %)) recs)]
      (is (= 2 (count items)) "two literal items")
      (is (= [0 1] (mapv :position (sort-by :position items))))
      (is (= [1 2] (mapv :value (sort-by :position items)))))))


;; -----------------------------------------------------------------------------
;; :required narrowing — descendant binding flips inherited optional → required

(deftest binding-required-narrowing-emits-required-true
  (testing "{:required true} on a binding emits a binding row with :required=true"
    (let [defs [{:name :base-with-opt :namespace "test"
                 ;; Base-fn-style declaration so the slot is created
                 ;; with :required false on this fn.
                 :args {:flag {:type :bool :required false}}
                 :return-type :bool}
                {:name :child-narrows :namespace "test"
                 :parent :base-with-opt
                 :args {:flag {:required true}}}]
          recs (r/parse-module defs)
          bindings (filter #(= :binding (:kind %)) recs)
          child-binding (first (filter #(= (r/fn-id "test" :child-narrows)
                                           (:fn-id %))
                                       bindings))]
      (is (some? child-binding) "child has a binding row for :flag")
      (is (true? (:required child-binding))
          "binding carries :required=true to mark the narrowing")
      (is (nil? (:value child-binding))
          ":required true alone shouldn't synthesize a value")
      (is (nil? (:ref-fn-id child-binding))
          ":required true alone shouldn't synthesize a ref"))))


(deftest binding-required-false-passes-through-for-typecheck-rejection
  (testing "{:required false} reaches the binding row — sync-time guard rejects later"
    (let [defs [{:name :base-with-opt :namespace "test"
                 :args {:flag {:type :bool :required false}}
                 :return-type :bool}
                {:name :child-attempts-widen :namespace "test"
                 :parent :base-with-opt
                 :args {:flag {:required false}}}]
          recs (r/parse-module defs)
          bindings (filter #(= :binding (:kind %)) recs)
          child-binding (first (filter #(= (r/fn-id "test" :child-attempts-widen)
                                           (:fn-id %))
                                       bindings))]
      (is (some? child-binding))
      (is (false? (:required child-binding))
          "loader passes :required=false through; types/check.clj rejects on sync"))))


(deftest binding-value-present-distinguishes-literal-nil-from-no-binding
  (testing "{:args {:x nil}} → binding with :value-present? true (pinned to literal nil)"
    (let [defs [{:name :base-with-x :namespace "test"
                 :args {:x {:type :jsonb :required false}}
                 :return-type :bool}
                {:name :child-pins-nil :namespace "test"
                 :parent :base-with-x
                 :args {:x nil}}]
          recs (r/parse-module defs)
          child-binding (first (filter #(and (= :binding (:kind %))
                                             (= (r/fn-id "test" :child-pins-nil)
                                                (:fn-id %)))
                                       recs))]
      (is (some? child-binding) "child emits a binding row")
      (is (nil? (:value child-binding)))
      (is (true? (:value-present child-binding))
          ":x nil binds value to literal nil — flag must distinguish from absent")
      (is (nil? (:ref-fn-id child-binding)))))
  (testing "{:args {:x {:as :renamed}}} → no :value-present? (slot stays free)"
    (let [defs [{:name :base-with-y :namespace "test"
                 :args {:y {:type :jsonb}}
                 :return-type :bool}
                {:name :child-just-renames :namespace "test"
                 :parent :base-with-y
                 :args {:y {:as :renamed}}}]
          recs (r/parse-module defs)
          child-binding (first (filter #(and (= :binding (:kind %))
                                             (= (r/fn-id "test" :child-just-renames)
                                                (:fn-id %)))
                                       recs))]
      (when (some? child-binding)
        (is (not (true? (:value-present child-binding)))
            "pure rename must not flip the value-present flag")))))
