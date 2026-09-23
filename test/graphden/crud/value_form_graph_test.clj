(ns graphden.crud.value-form-graph-test
  "`build-form` over the REAL app.forms graph — the composite wrappers
   (record group/field, union select/branches) are graph structure
   templates since the round-2 decomposition, so the composite arms need
   the synced package (the minimal hand-seeded fixture in
   `value-form-test` covers only the leaf registry). Moved here onto the
   default golden clone; the light-fixture ns keeps the resolve and
   leaf-path tests. The POST /api/value-form endpoint itself (parse →
   validate → apply) is the `:value-form-handler` graph fn-def, driven
   below the way production reaches it."
  (:require
    [cheshire.core :as cheshire]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.value-form :as vf]
    [graphden.executor.test-setup :as setup]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(defn- in-tree?
  [form x]
  (boolean (some #(= x %) (tree-seq coll? seq form))))


(deftest build-form-composites
  (let [ctx harness/*context*]
    (testing "a record descriptor becomes a labelled fieldset"
      (let [f (vf/build-form ctx (vf/resolve-form {:host :text :port :int})
                             "" nil {:host "h" :port 8080})]
        (is (in-tree? f "value-form-group"))
        (is (in-tree? f "value-form-field"))
        (is (in-tree? f "host"))
        (is (in-tree? f "port"))))
    (testing "a union descriptor renders a branch <select> plus branches,
              pre-selecting the branch the current value fits"
      (let [int-fit  (vf/build-form ctx (vf/resolve-form [:union :int :text])
                                    "" nil 5)
            text-fit (vf/build-form ctx (vf/resolve-form [:union :int :text])
                                    "" nil "hello")]
        (is (in-tree? int-fit "value-form-union"))
        (is (= "0" (get (nth int-fit 1) "data-union-active")))
        (is (= "1" (get (nth text-fit 1) "data-union-active")))
        (is (in-tree? int-fit "value-form-union-branch"))))
    (testing "a list descriptor falls back to a JSON editor"
      (is (= "textarea"
             (first (vf/build-form ctx (vf/resolve-form [:list :int])
                                   "" nil [1 2])))))
    (testing "a leaf descriptor delegates to build-leaf-form"
      (is (= "input"
             (first (vf/build-form ctx (vf/resolve-form :int) "" nil 7)))))
    (testing "a composite type with an EXACT registry row skips
              decomposition — :hiccup-node renders the single EDN
              textarea, not a 6-branch union editor"
      (let [f (vf/build-form ctx (vf/resolve-form :hiccup-node)
                             "" nil ["div" {"class" "x"} "hi"])]
        (is (= "textarea" (first f)))
        ;; The const's attr keys keywordize on the JSONB round trip
        ;; (they re-stringify in the JSON response the editor reads).
        (is (= "edn" (get (second f) :data-field-kind))
            "hiccup-node dispatches to the EDN textarea")
        (is (not (in-tree? f "value-form-union"))
            "no branch selector for a registered composite")))
    (testing "the exact tier does not swallow an UNregistered union —
              structural decomposition still applies"
      (is (in-tree? (vf/build-form ctx (vf/resolve-form [:union :int :text])
                                   "" nil 5)
                    "value-form-union")))))


;; =============================================================================
;; POST /api/value-form — the production `:value-form-handler`
;; =============================================================================

(defn- value-form!
  "POST `body` to `:value-form-handler`; the decoded JSON envelope."
  [body]
  (let [graph {:ctx harness/*context*
               :all-name->id {:value-form-handler (harness/fn-id "value-form-handler")
                              :pg-query (harness/fn-id "pg-query")}}
        resp (setup/via-graph graph :value-form-handler
                              {:uri "/api/value-form" :request-method :post
                               :headers {"content-type" "application/json"}
                               :body (cheshire/generate-string body)})]
    (cheshire/parse-string (str (:body resp)) true)))


(defn- bound-int-slot!
  "A base fn with an `:int` slot bound to 5. Returns the binding row."
  []
  (let [storage harness/*storage*
        slot (setup/create-slot! storage "n" :int)
        owner (setup/create-base-fn! storage (str "vfh-owner-" (random-uuid)))]
    (setup/bind-value! storage (:id owner) (:id slot) 5)))


(deftest value-form-handler-validation-test
  (testing "an empty request is rejected"
    (is (false? (:ok (value-form! {})))))
  (testing "fn-id without slot-id is rejected"
    (is (false? (:ok (value-form! {:fn-id (str (random-uuid))})))))
  (testing "a malformed binding-id parses to nil — rejected like an absent one"
    (is (false? (:ok (value-form! {:binding-id "not-a-uuid"})))))
  (testing "fn-id + slot-id together identify an unbound free-arg"
    (is (true? (:ok (value-form! {:fn-id (str (random-uuid))
                                  :slot-id (str (random-uuid))}))))))


(deftest value-form-handler-bound-slot-test
  (testing "a bound :int slot yields a number control wrapped in a
            data-form-root div carrying the binding id"
    (let [b (bound-int-slot!)
          {:keys [ok value form]} (value-form! {:binding-id (str (:id b))})
          [tag attrs control] form]
      (is (true? ok))
      (is (= 5 value))
      (is (= "div" tag))
      (is (contains? attrs :data-form-root))
      (is (= (str (:id b)) (:data-binding-id attrs)))
      (is (in-tree? control "number")))))


(deftest value-form-handler-as-test
  (testing "`as` (the editor's \"as:\" chooser) picks the form's type"
    (let [b (bound-int-slot!)
          as-text (value-form! {:binding-id (str (:id b)) :as "text"})
          [_ _ control] (:form as-text)]
      (is (true? (:ok as-text)))
      (is (in-tree? control "text") "a text control, not the slot's number one")))
  (testing "a blank or non-string `as` is dropped — the slot's own type wins"
    (let [b (bound-int-slot!)]
      (doseq [as ["" 7]]
        (let [[_ _ control] (:form (value-form! {:binding-id (str (:id b)) :as as}))]
          (is (in-tree? control "number") (str "as " (pr-str as))))))))
