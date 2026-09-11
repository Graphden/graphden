(ns graphden.packages.app.forms-impls-test
  "Unit tests for the `app/forms` package's impls — the `/api/value-form`
   base-fns behind the editor's type-aware value editor.

   `graphden.crud.value-form` has its own thorough tests; what has none
   is this file — the five-line shim layer that decides WHICH arguments
   each library call receives. Three of its five impls hardcode
   positional arguments (`build-form`'s `path` / `id`) or a storage
   precondition, and those decisions are invisible from the src tests.
   If `build-form`'s arg order slips, every control silently gains a
   `data-field-path` and the editor collects composite fields into the
   wrong keys.

   Storage-backed impls are exercised only for their refusal arm — the
   happy path needs a real graph and is covered by
   `graphden.crud.value-form-test` / `value-form-graph-test`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "app" "forms"))


(defn- call
  "Invoke a base-fn impl the way the executor does: args as delays.
   `delay` is a macro, so the map cannot be built with `update-vals`."
  ([kw args] (call kw args nil))
  ([kw args ctx]
   ((impls/impl-of kw)
    (into {} (map (fn [[k v]] [k (delay v)])) args)
    ctx)))


;; =============================================================================
;; :resolve-form — the structural classification the editor dispatches on
;; =============================================================================

(deftest resolve-form-classifies-each-structural-kind
  ;; The `:kind` keyword IS the editor's dispatch key (record →
  ;; fieldset, union → branch select, list → JSON editor, leaf →
  ;; registry-picked control). A drift here renders the wrong widget
  ;; for a whole class of types.
  (testing "a primitive is a leaf carrying its resolved type"
    (is (= {:kind :leaf :type :int} (call :resolve-form {:type-expr :int}))))
  (testing "a list descends into its element type"
    (let [d (call :resolve-form {:type-expr [:list :text]})]
      (is (= :list (:kind d)))
      (is (= {:kind :leaf :type :text} (:element d)))))
  (testing "a record exposes one entry per field, in order"
    (let [d (call :resolve-form {:type-expr {:host :text :port :int}})]
      (is (= :record (:kind d)))
      (is (= #{:host :port} (set (map :name (:fields d)))))
      (is (= {:kind :leaf :type :int}
             (:form (first (filter #(= :port (:name %)) (:fields d))))))))
  (testing "a union exposes one branch per member"
    (let [d (call :resolve-form {:type-expr [:union :int :text]})]
      (is (= :union (:kind d)))
      (is (= [:int :text] (mapv :type (:branches d)))))))


(deftest resolve-form-guards-against-runaway-nesting
  (testing "past the depth cap a composite degrades to a leaf instead of recursing"
    ;; A self-referential / pathologically deep record type must not
    ;; blow the stack while a user merely hovers an arg.
    (let [deep (reduce (fn [t _] [:list t]) :int (range 20))
          d (call :resolve-form {:type-expr deep})
          kinds (loop [x d acc []]
                  (if (= :list (:kind x))
                    (recur (:element x) (conj acc :list))
                    (conj acc (:kind x))))]
      (is (= :leaf (last kinds)))
      (is (< (count kinds) 20) "the walk stopped before the full 20 levels"))))


;; =============================================================================
;; :build-form — the positional args the shim hardcodes
;; =============================================================================

(deftest build-form-renders-a-closed-enum-at-the-root-path
  ;; A closed-enum leaf is the one arm that needs no executor registry,
  ;; so it pins the shim's two hardcoded positions: `path` is "" and
  ;; `id` is nil at the root. If those ever swap or pick up a value,
  ;; the root control gains a `data-field-path` / `id` it must not have
  ;; — the editor then POSTs the whole value under a phantom field key.
  (let [desc {:kind :leaf :type [:refine :keyword [:in [:get :post]]]}
        [tag attrs & opts] (call :build-form {:form desc :current-value nil})]
    (testing "a closed enum becomes a <select> of its members"
      (is (= "select" tag))
      (is (= "enum" (get attrs "data-field-kind")))
      (is (= [["option" {"value" ":get"} ":get"]
              ["option" {"value" ":post"} ":post"]]
             (vec opts))))
    (testing "the root control carries neither a field path nor a label id"
      (is (nil? (get attrs "data-field-path")))
      (is (nil? (get attrs "id"))))))


;; =============================================================================
;; Storage precondition — the refusal arm of the three ctx-backed shims
;; =============================================================================

(deftest storage-backed-shims-refuse-a-context-without-storage
  (testing "each raises the typed :execution-error/missing-storage, never an NPE"
    ;; The web layer maps this `:type` to a clean error response; a bare
    ;; NullPointerException from inside the resolver would surface as an
    ;; untyped 500 and page the operator.
    (doseq [[kw args] [[:_slot-effective-type-raw {:parsed {}}]
                       [:current-slot-value {:parsed {}}]
                       [:slot-type-provenance {:parsed {}}]]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (call kw args {}))
                  (str kw " must refuse a storage-less ctx"))]
        (is (= :execution-error/missing-storage (:type (ex-data e))))))))
