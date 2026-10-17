(ns graphden.test-infra.browser-canvas-structure-cases
  "Finite authorized display facts for both canvas presentation components.")


(def initial-defaults
  {:kind "empty" :name "" :flat nil :variable false :base-numeric false
   :operator "" :zero false :atom "" :effect-mode "any" :effects [] :ret-name nil :children []})


(defn child
  [label first? last? human compact short-label]
  {:label label :first first? :last last? :human human :compact compact :short short-label :constraint ""})


(def initial-cases
  (mapv #(merge initial-defaults %)
        [{:kind "name" :name "int"}
         {:kind "name" :name "request" :variable true :flat "any"}
         {:kind "list" :children [(child "" true true "integer" "int" "int")]}
         {:kind "record" :children [(child "name" true false "text" "text" "text")
                                    (child "age" false true "integer" "int" "int")]}
         {:kind "fn" :effect-mode "pure" :ret-name "text"
          :children [(child "request" true false "integer" "int" "int")
                     (child "" false true "text" "text" "text")]}
         {:kind "refine" :base-numeric true :operator ">" :zero true
          :children [(child "" true false "integer" "int" "int")
                     {:label "" :first false :last true :human "" :compact nil :short "" :constraint "greater than 0"}]}
         {:kind "constraint-in"
          :children [{:label "" :first true :last false :human "" :compact nil :short "" :constraint "red"}
                     {:label "" :first false :last true :human "" :compact nil :short "" :constraint "blue"}]}]))


(def type-view-cases
  (mapv #(merge {:surface "label" :label "authorized label" :secondary "" :title ""
                 :winner false :multiple false :mode ""} %)
        [{:surface "header"} {:surface "description"} {:surface "constraint"}
         {:surface "resolution-head" :mode "resolved"}
         {:surface "resolution-mark" :mode "chain" :winner true :multiple true}
         {:surface "resolution-tag" :winner false} {:surface "chain-separator"}]))


(def binding-defaults
  {:label "" :secondary "" :source "" :base "" :key ""
   :terminal-source "" :list-source "" :required-source ""
   :ancestor false :has-list false :authenticated false :any false :editable false
   :optional false :terminal false :list-closed false :required false :entries []})


(def binding-cases
  (mapv #(merge binding-defaults %)
        [{:surface "label" :label "request"}
         {:surface "source" :source "ancestor"}
         {:surface "provenance" :base "base" :entries [{:name "first" :first true} {:name "second" :first false}]}
         {:surface "seal-badge" :terminal true :list-source "ancestor" :editable true :optional true}
         {:surface "seal-header" :label "request" :source "child"}
         {:surface "seal-inherited" :key "terminal" :source "ancestor"}
         {:surface "seal-checkbox" :key "list-closed"}
         {:surface "seal-hint" :key "list-closed" :has-list false}
         {:surface "seal-empty"}
         {:surface "seal-reason" :authenticated false}]))
