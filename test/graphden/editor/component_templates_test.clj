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
    (is (= [(keyword root "_menu-descriptor")
            (keyword root "_picker-descriptor")
            (keyword root "_recents-descriptor")
            (keyword root "_inspector-descriptor")
            (keyword root "_explorer-descriptor")]
           (get-in by-name [[root :ui] :args :items])))
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


(deftest selected-inspector-copies-usages-composition-and-its-fixed-interfaces
  (let [root "users.alice.ui-usages"
        definitions (templates/selected-definitions root :inspector nil nil)
        parents (set (map :parent definitions))
        interfaces (set (map #(keyword (:namespace %) (name (:name %)))
                             (templates/interface-definitions)))
        operations #{:core.collections/vals :core.hof/group-by :core.hof/sort-by
                     :core.strings/str-lower :core.strings/str-trim :core.strings/str-contains?}]
    (doseq [operation operations]
      (is (contains? parents operation) (str operation " is qualified in the copied graph"))
      (is (contains? interfaces operation) (str operation " has its shipped parse-time interface")))
    (is (some #(= :_usages-view (:name %)) definitions))
    (is (every? #(contains? #{root (str root ".inspector")} (:namespace %)) definitions)
        "The section remains part of the Inspector component, without a theme or another personal group")))


(deftest type-constructors-are-not-operation-references
  (let [definition {:parent :list :return-type [:list [:map :text :text]]}
        qualified (rebase/rewrite-references
                    definition #(get {:list :core.collections/list :map :core.hof/map} % %))]
    (is (= :core.collections/list (:parent qualified)))
    (is (= [:list [:map :text :text]] (:return-type qualified)))))


(deftest every-template-body-has-a-reserved-owned-namespace
  (let [root "users.alice.ui-test"
        reserved (into #{root} (map #(str root "." %)) templates/namespace-suffixes)]
    (is (= reserved (set (map :namespace (templates/definitions root)))))
    (is (= 7 (count reserved)))))


(deftest explorer-template-stays-finite-and-within-existing-receipt-bounds
  (let [root "users.alice.ui-explorer"
        selected (templates/selected-definitions root :explorer nil nil)
        full (templates/definitions root)]
    (is (< (count selected) 512))
    (is (< (count full) 1000))
    (is (every? #(contains? #{root (str root ".explorer")} (:namespace %)) selected))
    (is (every? (set (map :name selected)) [:explorer-toolbar-initial :explorer-toolbar-update :explorer-toolbar-view]))))


(deftest copied-recents-request-union-retains-local-member-identities
  (let [root "users.alice.ui-test"
        recents-namespace (str root ".recents")
        definitions (templates/definitions root)
        union (first (filter #(and (= recents-namespace (:namespace %)) (= :_recents-request (:name %))) definitions))]
    (is (= [:_recents-persist-request :_recents-navigate-request]
           (:union union)))
    (doseq [member (:union union)]
      (is (boolean (some #(and (= recents-namespace (:namespace %))
                               (= member (:name %))) definitions))
          "Every copied union member resolves inside the same bundle"))))
