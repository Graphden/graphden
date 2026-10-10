(ns graphden.editor.component-templates-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.editor.component-templates :as templates]
    [graphden.packages.rebase :as rebase]))


(deftest copied-colors-share-canonical-typed-leaves-without-source-wrapper-access
  (let [root "users.alice.ui-test"
        definitions (templates/definitions root)
        by-name (into {} (map (juxt (juxt :namespace :name) identity)) definitions)
        theme (str root ".theme")
        menu (str root ".menu")]
    (is (= :core.refinements/color
           (get-in by-name [[theme :color-const] :return-type])))
    (is (= (keyword theme "color-const")
           (get-in by-name [[theme :theme-accent-color] :parent])))
    (is (= (keyword theme "color-const")
           (get-in by-name [[menu :account-menu-hover] :parent])))
    (is (= (keyword theme "theme-accent-color")
           (get-in by-name [[menu :theme-accent] :args :value])))
    (is (= (keyword theme "theme-canvas-color")
           (get-in by-name [[menu :theme-canvas-background] :args :value])))
    (is (= "#f8fafc" (get-in by-name [[menu :account-menu-hover] :args :value]))
        "The local hover leaf has its own literal; shared canvas edits do not replace it")
    (is (= :core.collections/zipmap
           (get-in by-name [[menu :account-menu-key-map] :parent])))
    (is (= :core.collections/list (:parent (get by-name [root :ui]))))
    (is (= 3 (count (get-in by-name [[root :ui] :args :items]))))
    (is (= {:ref (keyword (str root ".recents") "recents-view") :type :fn-ref}
           (get-in by-name [[root :_recents-view-id] :args :value])))))


(deftest fixed-abi-qualification-leaves-literal-reference-shaped-data-intact
  (let [definition {:parent :const :args {:value {:value {:parent :const :ref :const :type :const}}}
                    :return-type [:list [:map :text [:secret :text]]]
                    :description "const" :refine {:base :const :constraint {:ref :const}}}
        qualified (rebase/rewrite-references definition #(if (= :const %) :core.logic/const %))]
    (is (= :core.logic/const (:parent qualified)))
    (is (= (get-in definition [:args :value :value]) (get-in qualified [:args :value :value])))
    (is (= {:ref :const} (get-in qualified [:refine :constraint])))
    (is (= (:return-type definition) (:return-type qualified)))
    (is (= :core.logic/const (get-in qualified [:refine :base])))))


(deftest type-constructors-are-not-operation-references
  (let [definition {:parent :list :return-type [:list [:map :text :text]]}
        qualified (rebase/rewrite-references
                    definition #(get {:list :core.collections/list :map :core.hof/map} % %))]
    (is (= :core.collections/list (:parent qualified)))
    (is (= [:list [:map :text :text]] (:return-type qualified)))))
