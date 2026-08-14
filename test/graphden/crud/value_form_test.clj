(ns graphden.crud.value-form-test
  "Parallel-safe: no `with-redefs`. The registry-parse test that used
   to stub `exec/execute-by-name` (a per-execute hot-path fn — root
   rebind pinned this NS `^:serial`) now seeds a REAL
   `_value-form-registry` const row through `forms-ctx` and reads it
   back through the executor, JSONB roundtrip included
   (serial-reduction cluster B).

   Tests for `graphden.crud.value-form` — the `/api/value-form`
   resolver: structural classification, form-fn dispatch, refinement
   extraction, the request parse/validate stages, the storage-backed
   slot-type resolution, and the ctx-backed form assembly.

   Pure helpers need no fixture; the storage-backed resolvers go
   through the shared container. The ctx-backed assembly (registry-
   pairs / build-leaf-form / build-form / apply-value-form) runs
   against a minimal in-test forms package — a `vf-const` identity
   base-fn plus the leaf form-fn `:const` rows + the dispatch
   registry — built by `forms-ctx`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.value-form :as vf]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


;; Private resolver internals exercised directly.
(def ^:private collect-bounds     #'vf/collect-bounds)
(def ^:private numeric-bounds     #'vf/numeric-bounds)
(def ^:private enum-of            #'vf/enum-of)
(def ^:private merge-attrs        #'vf/merge-attrs)
(def ^:private build-enum-control #'vf/build-enum-control)
(def ^:private nav-descend        #'vf/nav-descend)
(def ^:private nav-key-type       #'vf/nav-key-type)
(def ^:private nav-item-type      #'vf/nav-item-type)
(def ^:private item-key           #'vf/item-key)
(def ^:private current-value      #'vf/current-value)
(def ^:private value-fits?        #'vf/value-fits?)
(def ^:private type-label         #'vf/type-label)
(def ^:private registry-pairs     #'vf/registry-pairs)
(def ^:private build-leaf-form    #'vf/build-leaf-form)
(def ^:private build-form         #'vf/build-form)
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
;; resolve-form — pure structural classifier
;; ============================================================================

(deftest resolve-form-leaf-test
  (testing "primitives classify as leaf"
    (is (= {:kind :leaf :type :int} (vf/resolve-form :int)))
    (is (= {:kind :leaf :type :text} (vf/resolve-form :text))))
  (testing "a refinement is a single leaf — it dispatches to ONE form-fn"
    (is (= {:kind :leaf :type [:refine :int [:> 0]]}
           (vf/resolve-form [:refine :int [:> 0]])))))


(deftest resolve-form-list-test
  (is (= {:kind :list :element {:kind :leaf :type :int}}
         (vf/resolve-form [:list :int]))))


(deftest resolve-form-record-test
  (let [r (vf/resolve-form {:host :text :port :int})]
    (is (= :record (:kind r)))
    (is (= #{:host :port} (set (map :name (:fields r)))))
    (let [host (first (filter #(= :host (:name %)) (:fields r)))]
      (is (= {:kind :leaf :type :text} (:form host))))))


(deftest resolve-form-union-test
  (let [u (vf/resolve-form [:union :int :text])]
    (is (= :union (:kind u)))
    (testing "each branch keeps its raw type AND its resolved form"
      (is (= #{:int :text} (set (map :type (:branches u)))))
      (is (= #{{:kind :leaf :type :int} {:kind :leaf :type :text}}
             (set (map :form (:branches u))))))))


(deftest resolve-form-nested-test
  (testing "record field that is itself a list recurses"
    (let [r (vf/resolve-form {:tags [:list :text]})
          tags (first (:fields r))]
      (is (= :record (:kind r)))
      (is (= {:kind :list :element {:kind :leaf :type :text}}
             (:form tags))))))


(deftest resolve-form-variant-test
  (testing "a tagged variant desugars to a union of record branches"
    (let [r (vf/resolve-form [:variant :ok :text :err :text])]
      (is (= :union (:kind r)))
      (is (= 2 (count (:branches r))))
      (is (every? #(= :record (:kind (:form %))) (:branches r))))))


(deftest resolve-form-depth-guard-test
  (testing "past the recursion-depth guard any type collapses to a leaf"
    (is (= {:kind :leaf :type {:host :text}}
           (vf/resolve-form {:host :text} 13)))))


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


(deftest build-enum-control-test
  (testing "a keyword-base enum renders colon-prefixed option values"
    (let [sel (build-enum-control "" nil {:base :keyword :members ["get" "post"]})]
      (is (= "select" (first sel)))
      (is (= {"class" "arg-value-edit-input"
              "data-form-field" ""
              "data-field-kind" "enum"}
             (second sel)))
      (is (= [["option" {"value" ":get"} ":get"]
              ["option" {"value" ":post"} ":post"]]
             (drop 2 sel)))))
  (testing "keyword members already colon-prefixed are left alone"
    (is (= ["option" {"value" ":get"} ":get"]
           (nth (build-enum-control "" nil {:base :keyword :members [:get]}) 2))))
  (testing "a non-keyword base keeps member values verbatim"
    (is (= [["option" {"value" "a"} "a"] ["option" {"value" "b"} "b"]]
           (drop 2 (build-enum-control "" nil {:base :text :members ["a" "b"]})))))
  (testing "path and id are threaded onto the <select>"
    (let [attrs (second (build-enum-control "status" "vf-status"
                                            {:base :text :members ["x"]}))]
      (is (= "status" (get attrs "data-field-path")))
      (is (= "vf-status" (get attrs "id"))))))


;; ============================================================================
;; merge-attrs — attribute injection into a control hiccup
;; ============================================================================

(deftest merge-attrs-test
  (testing "merges into an existing attrs map"
    (is (= ["input" {"type" "text" "id" "x"}]
           (merge-attrs ["input" {"type" "text"}] {"id" "x"}))))
  (testing "inserts an attrs map when the control carries none"
    (is (= ["input" {"id" "x"}]
           (merge-attrs ["input"] {"id" "x"}))))
  (testing "a child-only control keeps its child"
    (is (= ["span" {"id" "x"} "txt"]
           (merge-attrs ["span" "txt"] {"id" "x"}))))
  (testing "a non-vector passes through untouched"
    (is (= "x" (merge-attrs "x" {"id" "y"})))))


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


(deftest type-label-test
  (testing "a primitive shows its name"
    (is (= "int" (type-label :int))))
  (testing "a refinement shows its BASE — not the bare word \"refine\""
    (is (= "int" (type-label [:refine :int [:> 0]]))))
  (testing "a list shows [elem]"
    (is (= "[text]" (type-label [:list :text]))))
  (testing "composites get a short word"
    (is (= "record" (type-label {:a :int})))
    (is (= "fn" (type-label [:fn {} :int]))))
  (testing "any other constructor vector shows its head keyword"
    (is (= "union" (type-label [:union :int :text])))
    (is (= "map" (type-label [:map :keyword :int]))))
  (testing "an unclassifiable value falls back to pr-str"
    (is (= "5" (type-label 5)))))


;; ============================================================================
;; Endpoint stages — parse / validate
;; ============================================================================

(deftest validate-value-form-test
  (testing "a binding-id alone identifies the slot"
    (is (nil? (vf/validate-value-form {:binding-id (random-uuid)}))))
  (testing "fn-id + slot-id together identify an unbound free-arg"
    (is (nil? (vf/validate-value-form {:fn-id (random-uuid)
                                       :slot-id (random-uuid)}))))
  (testing "fn-id without slot-id is rejected"
    (is (false? (:ok (vf/validate-value-form {:fn-id (random-uuid)})))))
  (testing "an empty request is rejected"
    (is (false? (:ok (vf/validate-value-form {}))))))


(deftest parse-value-form-request-test
  (testing "uuid strings are coerced to UUIDs"
    (let [bid (random-uuid)
          parsed (vf/parse-value-form-request {:body {:binding-id (str bid)}})]
      (is (= bid (:binding-id parsed)))))
  (testing "malformed / absent ids parse to nil"
    (let [parsed (vf/parse-value-form-request {:body {:binding-id "not-a-uuid"}})]
      (is (nil? (:binding-id parsed)))
      (is (nil? (:fn-id parsed))))))


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
      (finally (sp/close storage)))))


;; ============================================================================
;; ctx-backed form assembly — registry-pairs / build-leaf-form / build-form /
;; apply-value-form, driven against a minimal in-storage forms package.
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


(defn- in-tree?
  "True when value `x` appears anywhere in the nested hiccup `form`."
  [form x]
  (boolean (some #(= x %) (tree-seq coll? seq form))))


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


(deftest build-leaf-form-test
  (let [storage (setup/create-test-storage)]
    (try
      (let [ctx (forms-ctx storage)]
        (testing "a primitive picks its registered form-fn control"
          (let [c (build-leaf-form ctx :int "" nil)]
            (is (= "input" (first c)))
            (is (in-tree? c "number"))))
        (testing "a type with no registered form-fn falls back to _form-json"
          (is (= "textarea" (first (build-leaf-form ctx :bool "" nil)))))
        (testing "a bounded numeric refinement threads HTML min / max"
          (let [c (build-leaf-form ctx [:refine :int [:and [:>= 1] [:<= 65535]]]
                                   "" nil)]
            (is (= 1 (get (second c) "min")))
            (is (= 65535 (get (second c) "max")))))
        (testing "a closed-enum refinement renders a <select>, not an input"
          (let [c (build-leaf-form ctx [:refine :keyword [:in [:get :post]]]
                                   "" nil)]
            (is (= "select" (first c)))
            (is (= "enum" (get (second c) "data-field-kind")))))
        (testing "a non-empty path is threaded onto the control"
          (is (= "host" (get (second (build-leaf-form ctx :int "host" nil))
                             "data-field-path")))))
      (finally (sp/close storage)))))


;; `build-form`'s composite arms render through the app.forms graph
;; structure templates now — covered on the golden clone in
;; `graphden.crud.value-form-graph-test`; the leaf path stays covered by
;; `apply-value-form-test` below.


(deftest apply-value-form-test
  (testing "end-to-end: a bound :int slot yields a number control wrapped
            in a data-form-root div carrying the binding id"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ctx    (forms-ctx storage)
              slot   (setup/create-slot! storage "n" :int)
              fr     (setup/create-base-fn! storage "avf-owner")
              b      (setup/bind-value! storage (:id fr) (:id slot) 5)
              result (vf/apply-value-form {:binding-id (:id b)} ctx)
              [tag attrs control] (:form result)]
          (is (true? (:ok result)))
          (is (= 5 (:value result)))
          (is (= "div" tag))
          (is (contains? attrs "data-form-root"))
          (is (= (str (:id b)) (get attrs "data-binding-id")))
          (is (in-tree? control "number")))
        (finally (sp/close storage))))))
