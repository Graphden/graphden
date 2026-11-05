(ns graphden.crud.value-form-test
  "Canonical type matching, refinement interpretation and storage resolvers.
   Registry parsing uses a minimal real graph; construction and presentation
   policy is covered through app.forms in value-form-graph-test."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.value-form :as vf]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-isolated-rich-types)


;; Private resolver internals exercised directly.
(def ^:private collect-bounds     #'vf/collect-bounds)
(def ^:private numeric-bounds     #'vf/numeric-bounds)
(def ^:private enum-of            #'vf/enum-of)
(def ^:private nav-descend        #'vf/nav-descend)
(def ^:private nav-key-type       #'vf/nav-key-type)
(def ^:private nav-item-type      #'vf/nav-item-type)
(def ^:private item-key           #'vf/item-key)
(def ^:private current-value      #'vf/current-value)
(def ^:private value-fits?        #'vf/value-fits?)
(def ^:private registry-pairs     #'vf/registry-pairs)
(def ^:private inheritance-chain-info #'vf/inheritance-chain-info)


;; ============================================================================
;; inheritance-chain-info — level-order BFS over the parent-ids closure
;; ============================================================================

(deftest inheritance-chain-info-mi-diamond-test
  (testing "an MI diamond dedups the shared root + keeps closer-wins endpoints"
    (let [storage (setup/create-test-storage)]
      (try
        ;; leaf ─┬─ a ─┐
        ;;       └─ b ─┴─ c (shared base-fn root)
        (let [c    (setup/create-base-fn! storage (str "vf-chain-c-" (random-uuid)))
              a    (sp/create-entity storage :fn {:name (str "vf-chain-a-" (random-uuid))
                                                  :parent-ids [(:id c)]})
              b    (sp/create-entity storage :fn {:name (str "vf-chain-b-" (random-uuid))
                                                  :parent-ids [(:id c)]})
              leaf (sp/create-entity storage :fn {:name (str "vf-chain-leaf-" (random-uuid))
                                                  :parent-ids [(:id a) (:id b)]})
              {:keys [ids fn-map]} (inheritance-chain-info storage (:id leaf))]
          (is (= (:id leaf) (first ids)) "selected fn at position 0")
          (is (= (:id c) (last ids)) "shared root is deepest → last")
          (is (= 4 (count ids)) "root appears once — diamond deduped, not twice")
          (is (= #{(:id leaf) (:id a) (:id b) (:id c)} (set ids))
              "every ancestor reachable through the closure")
          (is (= #{(:id leaf) (:id a) (:id b) (:id c)} (set (keys fn-map)))
              "each ancestor row cached for downstream :name reads")
          (is (= (:name c) (:name (get fn-map (:id c))))
              "cached row carries the real fields (batched read populated it)"))
        (finally (sp/close storage))))))


;; ============================================================================
;; pick-form-fn — registry dispatch by subtype, most-specific wins
;; ============================================================================

(def ^:private registry
  [[:text "_form-text"] [:int "_form-number"]
   [:numeric "_form-number"] [:any "_form-json"]])


(deftest pick-form-fn-test
  (testing "the most-specific accepting type wins over wider supertypes"
    (is (= "_form-number" (vf/pick-form-fn registry :int)))
    (is (= "_form-text" (vf/pick-form-fn registry :text))))
  (testing "a refinement dispatches via its base type"
    (is (= "_form-number" (vf/pick-form-fn registry [:refine :int [:> 0]]))))
  (testing "no specific match falls through to the :any entry"
    (is (= "_form-json" (vf/pick-form-fn registry :bool))))
  (testing "an empty registry yields nil"
    (is (nil? (vf/pick-form-fn [] :int)))))


(deftest pick-form-fn-marker-typed-slot-test
  ;; A hide-result marker type dispatches to the secret-binding
  ;; widget via a VECTOR registry key — [:secret :any] accepts any
  ;; [:secret T] leaf and outranks the :any fallback. The editor JS
  ;; routes purely on the widget name in the response; no tag names
  ;; live client-side.
  (let [reg [[:text "_form-text"] [:any "_form-json"]
             [[:secret :any] "_form-secret-binding"]]]
    (is (= "_form-secret-binding" (vf/pick-form-fn reg [:secret :text]))
        "marker leaf picks the widget row over :any")
    (is (= "_form-text" (vf/pick-form-fn reg :text))
        "plain text unaffected")))


(deftest exact-form-fn-test
  ;; The pre-decomposition tier: a form-fn registered for a whole
  ;; composite type (the hiccup-node union) wins over structural
  ;; decomposition — but ONLY on exact structural equality, so the
  ;; generic :any / :jsonb rows can never swallow record / union
  ;; editors.
  (let [hiccup-union [:union :null :text :int :float :bool [:list :any]]
        reg [[:text "_form-text"] [:any "_form-json"]
             [hiccup-union "_form-hiccup"]]]
    (testing "the exact composite row matches"
      (is (= "_form-hiccup" (vf/exact-form-fn reg hiccup-union))))
    (testing "a member-order permutation still matches — the rich-types
              registry canonicalizes unions in sorted order"
      (is (= "_form-hiccup"
             (vf/exact-form-fn reg [:union :bool :float :int :null :text
                                    [:list :any]]))))
    (testing "a merely-compatible type does NOT match — no subtype fallback"
      (is (nil? (vf/exact-form-fn reg [:union :null :text])))
      (is (nil? (vf/exact-form-fn reg {:host :text}))))
    (testing "generic rows only match themselves"
      (is (= "_form-json" (vf/exact-form-fn reg :any)))
      (is (nil? (vf/exact-form-fn [[:any "_form-json"]] [:union :int :text]))))))


(deftest pick-form-fn-js-source-prefers-textarea-over-text-input-test
  ;; Block 3.3 — when a slot declares `:js-source`, the registry's
  ;; `js-source -> _form-js-source` entry must outrank the wider
  ;; `text -> _form-text` entry. Pin this here so a future
  ;; registry reorder / rename doesn't silently downgrade JS-body
  ;; editing to a single-line input.
  (let [reg [[:text "_form-text"] [:js-source "_form-js-source"]]]
    (is (= "_form-js-source" (vf/pick-form-fn reg :js-source))
        "the textarea widget wins for the dedicated alias")
    (is (= "_form-text" (vf/pick-form-fn reg :text))
        "the single-line widget still wins for plain :text")))


;; ============================================================================
;; collect-bounds / numeric-bounds — refinement → HTML min/max
;; ============================================================================

(deftest collect-bounds-test
  (testing "inclusive bounds pass through"
    (is (= {:min 1} (collect-bounds [:>= 1] true)))
    (is (= {:max 100} (collect-bounds [:<= 100] true))))
  (testing "exclusive bounds nudge by one for an integer base"
    (is (= {:min 1} (collect-bounds [:> 0] true)))
    (is (= {:max 99} (collect-bounds [:< 100] true))))
  (testing "exclusive bounds pass through unchanged for a real base"
    (is (= {:min 0} (collect-bounds [:> 0] false)))
    (is (= {:max 100} (collect-bounds [:< 100] false))))
  (testing "an :and constraint collects both ends"
    (is (= {:min 1 :max 65535}
           (collect-bounds [:and [:>= 1] [:<= 65535]] true))))
  (testing "non-range constraints contribute nothing"
    (is (= {} (collect-bounds [:= 5] true)))
    (is (= {} (collect-bounds [:in [1 2 3]] true)))
    (is (= {} (collect-bounds :not-a-vector true)))))


(deftest numeric-bounds-test
  (testing "a bounded integer refinement yields min + max"
    (is (= {:min 1 :max 65535}
           (numeric-bounds [:refine :int [:and [:>= 1] [:<= 65535]]]))))
  (testing "a non-numeric base yields nil"
    (is (nil? (numeric-bounds [:refine :text [:= "x"]]))))
  (testing "a plain type (not a refinement) yields nil"
    (is (nil? (numeric-bounds :int))))
  (testing "a refinement with no range bounds yields nil"
    (is (nil? (numeric-bounds [:refine :int [:in [1 2]]])))))


;; ============================================================================
;; enum-of — closed-enum detection
;; ============================================================================

(deftest enum-of-test
  (testing "a closed-enum refinement is recognised"
    (is (= {:base :keyword :members [:get :post]}
           (enum-of [:refine :keyword [:in [:get :post]]]))))
  (testing "a non-:in refinement is not an enum"
    (is (nil? (enum-of [:refine :int [:> 0]]))))
  (testing "a plain type is not an enum"
    (is (nil? (enum-of :int)))))


;; ============================================================================
;; nav-typed sequence helpers — per-position type walk
;; ============================================================================

(deftest nav-descend-test
  (testing "a record field is followed by its keyword key"
    (is (= :int (nav-descend {:a :int :b :text} :a))))
  (testing "an unknown / nil key into a record yields :any"
    (is (= :any (nav-descend {:a :int} :missing)))
    (is (= :any (nav-descend {:a :int} nil))))
  (testing "a list descends to its element type for any key"
    (is (= :int (nav-descend [:list :int] 0))))
  (testing "a bare :sequence descends to :any (untyped element)"
    (is (= :any (nav-descend :sequence :k))))
  (testing "jsonb / any pass through; a scalar dead-ends"
    (is (= :jsonb (nav-descend :jsonb :k)))
    (is (nil? (nav-descend :int :k)))))


(deftest nav-key-type-test
  (testing "a record yields a closed keyword-enum of its (sorted) fields"
    (is (= [:refine :keyword [:in [:a :b]]]
           (nav-key-type {:b :text :a :int}))))
  (testing "an open map keys by free keyword, a list / sequence by int"
    (is (= :keyword (nav-key-type :jsonb)))
    (is (= :int (nav-key-type [:list :int])))
    (is (= :int (nav-key-type :sequence))))
  (testing "a scalar has no valid key"
    (is (nil? (nav-key-type :int)))))


(deftest item-key-test
  (testing "a keyword-valued item contributes its keyword as the nav key"
    (is (= :name (item-key {:value :name}))))
  (testing "a ref item is a dynamic segment — it has no static key"
    (is (nil? (item-key {:value :name :ref-fn-id (random-uuid)}))))
  (testing "a non-keyword literal is a dynamic segment"
    (is (nil? (item-key {:value "name"})))
    (is (nil? (item-key {:value 0})))))


(deftest nav-item-type-test
  (let [storage (setup/create-test-storage)]
    (try
      (let [slot (setup/create-slot! storage "path" :sequence)
            fr   (setup/create-base-fn! storage "nav-owner")
            bnd  (sp/create-entity storage :binding
                                   {:fn-id (:id fr) :slot-id (:id slot)
                                    :list-append true})
            i0   (sp/create-entity storage :binding-list-item
                                   {:binding-id (:id bnd) :position 0
                                    :value :user :literal true})
            i1   (sp/create-entity storage :binding-list-item
                                   {:binding-id (:id bnd) :position 1
                                    :value :name :literal true})
            nav  {:user {:name :text :age :int}}]
        (testing "the first segment keys into the root structure"
          (is (= [:refine :keyword [:in [:user]]]
                 (nav-item-type storage (:id bnd) (:id i0) nav))))
        (testing "a later segment keys into the structure the prefix reached"
          (is (= [:refine :keyword [:in [:age :name]]]
                 (nav-item-type storage (:id bnd) (:id i1) nav))))
        (testing "an item-id past the end walks the full prefix — a scalar
                  path has no further key"
          (is (nil? (nav-item-type storage (:id bnd) (random-uuid) nav))))
        (testing "a nil binding-id resolves to nil"
          (is (nil? (nav-item-type storage nil (:id i0) nav)))))
      (finally (sp/close storage)))))


;; ============================================================================
;; current-value — the literal bound at the edit site
;; ============================================================================

(deftest current-value-test
  (let [storage (setup/create-test-storage)]
    (try
      (testing "with a binding-id, reads the literal on the binding row"
        (let [slot (setup/create-slot! storage "cv1" :int)
              fr   (setup/create-base-fn! storage "cv-owner-1")
              b    (setup/bind-value! storage (:id fr) (:id slot) 42)]
          (is (= 42 (current-value storage {:binding-id (:id b)})))))
      (testing "with an item-id, reads the list-item row — not the binding"
        (let [slot (setup/create-slot! storage "cv2" :sequence)
              fr   (setup/create-base-fn! storage "cv-owner-2")
              b    (sp/create-entity storage :binding
                                     {:fn-id (:id fr) :slot-id (:id slot)
                                      :list-append true})
              it   (sp/create-entity storage :binding-list-item
                                     {:binding-id (:id b) :position 0 :value 7})]
          (is (= 7 (current-value storage {:binding-id (:id b)
                                           :item-id (:id it)})))))
      (testing "an unbound free-arg (no ids) has no current value"
        (is (nil? (current-value storage {}))))
      (finally (sp/close storage)))))


;; ============================================================================
;; value-fits? — union active-branch picker
;; ============================================================================

(deftest value-fits?-test
  (testing "a literal fits a branch it is a subtype of"
    (is (true? (boolean (value-fits? 5 :int))))
    (is (true? (boolean (value-fits? "x" :text)))))
  (testing "a literal does not fit an unrelated branch"
    (is (not (value-fits? "x" :int))))
  (testing "a nil value fits any branch (caller falls back to branch 0)"
    (is (true? (boolean (value-fits? nil :int)))))
  (testing "a literal satisfying a refinement fits it"
    (is (true? (boolean (value-fits? 8080 [:refine :int [:and [:>= 1] [:<= 65535]]]))))))


;; ============================================================================
;; resolve-slot-effective-type — storage-backed type resolution
;; ============================================================================

(deftest resolve-slot-effective-type-test
  (let [storage (setup/create-test-storage)]
    (try
      (testing "a primitive-typed slot resolves to its primitive"
        (let [slot (setup/create-slot! storage "n" :int)
              fr   (setup/create-base-fn! storage "owner-1")
              b    (setup/bind-value! storage (:id fr) (:id slot) 5)]
          (is (= :int (vf/resolve-slot-effective-type
                        storage {:binding-id (:id b)})))))

      (testing "an explicit type-override pin wins over the slot's type"
        (let [slot (setup/create-slot! storage "n2" :int)
              fr   (setup/create-base-fn! storage "owner-2")
              b    (sp/create-entity storage :binding
                                     {:fn-id (:id fr)
                                      :slot-id (:id slot)
                                      :value 5
                                      :type-override-fn-id
                                      (get setup/primitive-fn-ids :text)})]
          (is (= :text (vf/resolve-slot-effective-type
                         storage {:binding-id (:id b)})))))

      (testing "an unbound fn+slot resolves via the slot's declared type"
        (let [slot (setup/create-slot! storage "n3" :bool)
              fr   (setup/create-base-fn! storage "owner-3")]
          (is (= :bool (vf/resolve-slot-effective-type
                         storage {:fn-id (:id fr) :slot-id (:id slot)})))))

      (testing "a bound descendant retains its parent's explicit type pin"
        (let [slot (setup/create-slot! storage "inherited-value" :any)
              parent (setup/create-base-fn! storage "pinned-parent")
              child (sp/create-entity storage :fn
                                      {:name "pinned-child" :parent-ids [(:id parent)]})
              _ (sp/create-entity storage :binding
                                  {:fn-id (:id parent) :slot-id (:id slot)
                                   :type-override-fn-id (get setup/primitive-fn-ids :text)})
              binding (setup/bind-value! storage (:id child) (:id slot) "hello")]
          (is (= :text (vf/resolve-slot-effective-type storage {:binding-id (:id binding)})))
          (let [provenance (vf/slot-type-provenance storage {:binding-id (:id binding)})]
            (is (= :override (:winner provenance)))
            (is (= (:id parent) (get-in provenance [:tiers 0 :source :fn-id]))))
          (sp/update-entity storage :binding (:id binding)
                            {:type-override-fn-id (get setup/primitive-fn-ids :int)})
          (is (= :int (vf/resolve-slot-effective-type storage {:binding-id (:id binding)})))
          (sp/update-entity storage :binding (:id binding) {:type-override-fn-id nil})
          (is (= :text (vf/resolve-slot-effective-type storage {:binding-id (:id binding)})))))

      (testing "a list-item resolves to the list's element type"
        (let [list-fn (sp/create-entity storage :fn
                                        {:name "int-list"
                                         :element-fn-id (get setup/primitive-fn-ids :int)})
              slot    (setup/create-slot! storage "items" (:id list-fn))
              fr      (setup/create-base-fn! storage "owner-4")
              b       (setup/bind-value! storage (:id fr) (:id slot) [])]
          (is (= :int (vf/resolve-slot-effective-type
                        storage {:binding-id (:id b)
                                 :item-id (random-uuid)})))))

      (testing "a list-item under a slot that degraded to bare :sequence
                falls back to the rich-types registry's declared arg
                element type (the hiccup :children case)"
        (let [slot (setup/create-slot! storage "children" :sequence)
              fr   (setup/create-base-fn! storage "owner-5")
              _    (registry/record-rich-types-raw!
                     :owner-5 {:return :any
                               :args {:children [:list :hiccup-node]}
                               :effects #{}})
              b    (sp/create-entity storage :binding
                                     {:fn-id (:id fr) :slot-id (:id slot)
                                      :list-append true})]
          (is (= :hiccup-node (vf/resolve-slot-effective-type
                                storage {:binding-id (:id b)
                                         :item-id (random-uuid)}))
              "declared [:list :hiccup-node] beats the :any fallback
               (the alias resolves at form-classification time)")))
      (finally (sp/close storage)))))


(deftest resolve-slot-effective-type-identity-keyed-test
  ;; Regression: tier-2 (backward-unified slot-types) must
  ;; be keyed by fn IDENTITY, not by name — a leftover same-named fn
  ;; (e.g. from a deleted tutorial-branch run) used to dictate another
  ;; fn's value-form shape through the name-keyed registry lookup.
  (let [storage (setup/create-test-storage)]
    (try
      (let [slot     (setup/create-slot! storage "value" :any)
            stale-id (random-uuid)
            _        (registry/record-rich-types-raw!
                       stale-id :dup-owner
                       {:return {:body :text :status :int}
                        :args {}
                        :slot-types {:value {:body :text :status :int}}
                        :effects #{}})
            fresh    (sp/create-entity
                       storage :fn
                       {:id (random-uuid)
                        :name "dup-owner"
                        :return-type-fn-id (get setup/primitive-fn-ids :any)})]
        (testing "a same-named OTHER fn's recorded slot-types don't leak in"
          (is (= :any (vf/resolve-slot-effective-type
                        storage {:fn-id (:id fresh) :slot-id (:id slot)}))
              "tier-2 skipped (no id-keyed entry) — falls to the declared
               slot type instead of the stale fn's record shape"))
        (testing "the fn's OWN recorded slot-types still apply on id match"
          (registry/record-rich-types-raw!
            (:id fresh) :dup-owner
            {:return :any
             :args {}
             :slot-types {:value {:body :text :status :int}}
             :effects #{}})
          (is (= {:body :text :status :int}
                 (vf/resolve-slot-effective-type
                   storage {:fn-id (:id fresh) :slot-id (:id slot)})))))
      (finally (sp/close storage)))))


;; ============================================================================
;; Registry parsing, driven
;; against a minimal in-storage forms package.
;; ============================================================================

(defn- forms-ctx
  "Seed `storage` with a minimal value-form forms package — the
   `vf-const` identity base-fn, the leaf form-fn `:const` rows, and the
   `_value-form-registry` dispatch list — and return an executor ctx
   over it. Enough to drive the ctx-backed stages without loading the
   real `app.forms` package. The 2-arity overrides the registry row's
   value so parse-shape tests can seed non-default entries."
  ([storage]
   (forms-ctx storage
              [["text" "_form-text"] ["int" "_form-number"]
               ["numeric" "_form-number"] ["any" "_form-json"]]))
  ([storage registry-value]
   (exec/register-base-fn! :vf-const (setup/fn-impl [value] value))
   (let [const (setup/create-base-fn! storage "vf-const")
         vslot (setup/create-slot! storage "value" :any)
         _     (setup/attach-slot! storage (:id const) (:id vslot) 0)
         form! (fn [nm hiccup]
                 (let [f (setup/create-composed-fn! storage nm (:id const))]
                   (setup/bind-value! storage (:id f) (:id vslot) hiccup)
                   f))]
     (form! "_form-text"
            ["input" {"type" "text" "class" "arg-value-edit-input"
                      "data-form-field" "" "data-field-kind" "text"}])
     (form! "_form-number"
            ["input" {"type" "number" "class" "arg-value-edit-input"
                      "data-form-field" "" "data-field-kind" "number"}])
     (form! "_form-json"
            ["textarea" {"class" "arg-value-edit-input"
                         "data-form-field" "" "data-field-kind" "json"}])
     (form! "_value-form-registry" registry-value)
     (exec/create-context {:storage storage}))))


(deftest registry-pairs-test
  (testing "reads :_value-form-registry into [[type-kw fn-name] …] pairs"
    (let [storage (setup/create-test-storage)]
      (try
        (is (= [[:text "_form-text"] [:int "_form-number"]
                [:numeric "_form-number"] [:any "_form-json"]]
               (registry-pairs (forms-ctx storage))))
        (finally (sp/close storage))))))


(deftest registry-pairs-vector-type-keys-parse-test
  ;; The graph registry rows are JSONB-round-tripped strings; a VECTOR
  ;; first element becomes a structural keyword vector. Seeded as a
  ;; REAL registry const row and read back through the executor — the
  ;; parse sees the genuine post-roundtrip shape, no stubbing.
  (testing "a vector type-name parses to a structural keyword vector"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ctx (forms-ctx storage
                             [["text" "_form-text"]
                              [["secret" "any"] "_form-secret-binding"]])]
          (is (= [[:text "_form-text"]
                  [[:secret :any] "_form-secret-binding"]]
                 (registry-pairs ctx))))
        (finally (sp/close storage))))))


(deftest registry-pairs-missing-registry-test
  (testing "a ctx with no registry fn yields [] — the resolver degrades
            to the _form-json fallback rather than throwing"
    (let [storage (setup/create-test-storage)]
      (try
        (is (= [] (registry-pairs (exec/create-context {:storage storage}))))
        (finally (sp/close storage))))))


(deftest resolve-slot-effective-type-degraded-marker-test
  ;; A `[:secret :text]` arg is stored on the slot row as its inner
  ;; `:text` (a marker is not a type-fn). The value-form must still
  ;; dispatch the secret-binding widget for a CHILD of the declaring
  ;; fn — the closest ancestor's rich `:args` entry is the type the
  ;; checker enforces; the row is its degraded projection.
  (let [storage (setup/create-test-storage)]
    (try
      (let [slot  (setup/create-slot! storage "password" :text)
            base  (setup/create-base-fn! storage (str "vf-sec-base-" (random-uuid)))
            _     (setup/attach-slot! storage (:id base) (:id slot) 0)
            _     (registry/record-rich-types-raw!
                    (:id base) (keyword (:name base))
                    {:return :int
                     :args {:password [:secret :text] :sql :text}
                     :effects #{}})
            child (sp/create-entity storage :fn {:name (str "vf-sec-child-" (random-uuid))
                                                 :parent-ids [(:id base)]})]
        (testing "the child's free :password edits as the declared marker type"
          (is (= [:secret :text]
                 (vf/resolve-slot-effective-type storage {:fn-id (:id child) :slot-id (:id slot)}))))
        (testing "…and so does the declaring fn's own slot"
          (is (= [:secret :text]
                 (vf/resolve-slot-effective-type storage {:fn-id (:id base) :slot-id (:id slot)}))))
        (testing "a row that is NOT a projection of the declared type keeps winning"
          (let [other (setup/create-slot! storage "sql" :int)]
            (setup/attach-slot! storage (:id base) (:id other) 1)
            (is (= :int (vf/resolve-slot-effective-type storage {:fn-id (:id child) :slot-id (:id other)}))
                ":int is not what :text degrades to — the row's own type stands")))
        (testing "only a MARKER promotes — a list declaration keeps the row's :sequence for the whole-slot form"
          (let [items (setup/create-slot! storage "items" :sequence)]
            (setup/attach-slot! storage (:id base) (:id items) 2)
            (registry/record-rich-types-raw!
              (:id base) (keyword (:name base))
              {:return :int
               :args {:password [:secret :text] :sql :text :items [:list :text]}
               :effects #{}})
            (is (= :sequence (vf/resolve-slot-effective-type storage {:fn-id (:id child) :slot-id (:id items)}))
                "the list form readers type into stays; the element rule lives in the item path")))
        (testing "provenance's slot tier reads the marker too"
          (let [prov (vf/slot-type-provenance storage {:fn-id (:id child) :slot-id (:id slot)})
                slot-tier (some #(when (= :slot (:key %)) %) (:tiers prov))]
            (is (= :slot (:winner prov)))
            (is (= [:secret :text] (:type slot-tier))))))
      (finally (sp/close storage)))))
