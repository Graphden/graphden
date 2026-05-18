(ns graphden.crud.value-form-test
  "Tests for `graphden.crud.value-form` — the `/api/value-form`
   resolver: structural classification, form-fn dispatch, refinement
   extraction, the request parse/validate stages, and the storage-
   backed slot-type resolution.

   Pure helpers need no fixture; `resolve-slot-effective-type` goes
   through the shared container. `apply-value-form` itself executes
   the `app.forms` package fn-defs and is exercised end-to-end via the
   `/api/value-form` endpoint rather than here."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.value-form :as vf]
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
(def ^:private value-fits?        #'vf/value-fits?)
(def ^:private type-label         #'vf/type-label)


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
  (testing "jsonb / any pass through; a scalar dead-ends"
    (is (= :jsonb (nav-descend :jsonb :k)))
    (is (nil? (nav-descend :int :k)))))


(deftest nav-key-type-test
  (testing "a record yields a closed keyword-enum of its (sorted) fields"
    (is (= [:refine :keyword [:in [:a :b]]]
           (nav-key-type {:b :text :a :int}))))
  (testing "an open map keys by free keyword, a list by int"
    (is (= :keyword (nav-key-type :jsonb)))
    (is (= :int (nav-key-type [:list :int]))))
  (testing "a scalar has no valid key"
    (is (nil? (nav-key-type :int)))))


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
    (is (= "fn" (type-label [:fn {} :int])))))


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
                                      (get setup/primitive-fn-ids :text)
                                      :override-kind :fixed})]
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
