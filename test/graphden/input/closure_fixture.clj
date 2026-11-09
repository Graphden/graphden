(ns graphden.input.closure-fixture
  (:require
    [graphden.input.analysis :as analysis]
    [graphden.input.compiler-fixture :as fixture]))


(def definitions
  {:root {:input {:body [:fn {:item :int} :int #{}] :factor :int} :return [:fn {:item :int} :int #{}]
          :bindings {[:body] {:ref :body :id :body-use} [:factor] {:ref :factor}}}
   :body {:input {:item :int :factor :int} :return :int :lambda-params [:item]}
   :factor {:input {} :return :int}})


(defn model
  "The factor is a lazy outer reference captured through an explicit scoped
   projection. Pass the real checker in native tests, a marked seam in BB."
  [definitions checker]
  (let [{:keys [context]} (fixture/model definitions :root checker)
        row {:id (fixture/id :capture-factor) :fn-id (fixture/id :root)
             :scope-path [(fixture/id :body-use)] :target-path (fixture/field-path :body [:factor])
             :expression-kind "projection" :projection-scope-path []
             :projection-path (fixture/field-path :root [:factor])}
        context (assoc-in context [:bindings (:id row)] row)]
    {:definitions definitions :context context
     :analysis (analysis/analyze-effective-input context (fixture/id :root) {:check-expression checker})}))
