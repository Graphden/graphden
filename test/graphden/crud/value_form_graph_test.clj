(ns graphden.crud.value-form-graph-test
  "`build-form` over the REAL app.forms graph — the composite wrappers
   (record group/field, union select/branches) are graph structure
   templates since the round-2 decomposition, so the composite arms need
   the synced package (the minimal hand-seeded fixture in
   `value-form-test` covers only the leaf registry). Moved here onto the
   default golden clone; the light-fixture ns keeps the parse/resolve
   and leaf-path tests."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.value-form :as vf]
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
