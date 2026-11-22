(ns graphden.input.source-annotations-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.input.format :as format]
    [graphden.input.source :as source]
    [graphden.input.source-annotations :as annotations]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.types.core.shapes :as shapes]))


(defn- fixture
  [spec]
  (let [base {:name :base :namespace "fixture" :args {:title :text :head {:type [:list :text]}} :return-type :text}
        child {:name :child :namespace "fixture" :parent :base :args spec}
        definitions [base child]
        context (parse/prepare-module definitions {} {})
        plan (source/plan-module definitions context)]
    {:context context :plan plan :entry (second (:definitions (source/verify-module plan context)))}))


(deftest exact-free-annotations-retain-rows-and-do-not-bind-a-value-test
  (let [args {:title {:as :title :type :text :description "Document title"}
              :head {:as :head :type [:list :text] :description "Extra head elements"}}
        {:keys [entry]} (fixture args)
        owner (:identity entry)
        base (ids/fn-id "fixture" :base)
        rows (get-in entry [:v2 :bindings])]
    (is (= :authored-source-equivalent (:verification entry)) (:blockers entry))
    (is (= {[:title] {:type :text :description "Document title"}
            [:head] {:type [:list :text] :description "Extra head elements"}}
           (get-in entry [:proposed :configure])))
    (is (not (contains? (:proposed entry) :bind)))
    (is (empty? (get-in entry [:v2 :binding-state])))
    (is (= #{(ids/binding-id owner (ids/slot-id base :title))
             (ids/binding-id owner (ids/slot-id base :head))}
           (set (map :id rows))))
    (is (every? #(and (nil? (:expression-kind %)) (false? (:value-present %))) rows))
    (is (= #{"Document title" "Extra head elements"} (set (map :description rows))))))


(deftest metadata-and-type-drift-cannot-use-the-original-annotation-test
  (let [{:keys [context plan]} (fixture {:title {:as :title :type :text :description "Original"}})]
    (doseq [change [#(assoc-in % [:configure [:title] :description] "Changed")
                    #(assoc-in % [:configure [:title] :type] :any)
                    #(update % :configure dissoc [:title])]]
      (let [changed (update-in plan [:definitions 1 :proposed] change)
            entry (second (:definitions (source/verify-module changed context)))]
        (is (= :blocked (:status entry)))))))


(deftest renamed-field-description-stays-on-the-original-binding
  (let [spec {:as :public-title :type :text :description "Authored title"}
        {:keys [entry context plan]} (fixture {:title spec})
        owner (:identity entry)
        field (ids/slot-id (ids/fn-id "fixture" :base) :title)
        alias (ids/slot-id owner :public-title)
        [row] (get-in entry [:v2 :bindings])
        slot (some #(when (= alias (:id %)) %) (get-in entry [:v2 :declaration-records]))]
    (is (= :authored-source-equivalent (:verification entry)) (:blockers entry))
    (is (= (dissoc spec :as) (get-in entry [:proposed :configure [:title]])))
    (is (= {:type :text :required false} (get-in entry [:proposed :input :public-title])))
    (is (= [:project [:public-title]] (get-in entry [:proposed :bind :title])))
    (is (= {:id (ids/binding-id owner field) :target-path [field] :projection-path [alias]
            :expression-kind "projection" :description "Authored title"}
           (select-keys row [:id :target-path :projection-path :expression-kind :description])))
    (is (nil? (:description slot)))
    (doseq [change [#(assoc-in % [:configure [:title] :description] "Edited")
                    #(update % :configure dissoc [:title])
                    #(assoc-in % [:configure [:public-title]] (dissoc spec :as))
                    #(assoc-in % [:input :public-title :description] "Authored title")]]
      (let [changed (update-in plan [:definitions 1 :proposed] change)
            result (second (:definitions (source/verify-module changed context)))]
        (is (= :blocked (:status result)))))))


(deftest description-only-rename-retains-the-original-any-slot
  (let [definitions [{:namespace "fixture" :name :base :args {:value :any} :return-type :any}
                     {:namespace "fixture" :name :renamed :parent :base
                      :args {:value {:as :body :description "Body"}}}]
        context (parse/prepare-module definitions {} {})
        entry (last (:definitions (source/verify-module (source/plan-module definitions context) context)))]
    (is (= :authored-source-equivalent (:verification entry)) (:blockers entry))
    (is (= {:body {:type :any :required false}} (get-in entry [:proposed :input])))
    (is (= {[:value] {:description "Body"}} (get-in entry [:proposed :configure])))
    (is (= "Body" (get-in entry [:v2 :bindings 0 :description])))))


(deftest described-rename-allows-descendants-to-use-the-same-alias-slot
  (let [definitions [{:namespace "fixture" :name :base :args {:value :text} :return-type :text}
                     {:namespace "fixture" :name :renamed :parent :base
                      :args {:value {:as :body :type :text :description "Body documentation"}}}
                     {:namespace "fixture" :name :child :parent :renamed :args {:body "content"}}]
        context (parse/prepare-module definitions {} {})
        report (source/verify-module (source/plan-module definitions context) context)
        child (last (:definitions report))
        alias (ids/slot-id (ids/fn-id "fixture" :renamed) :body)]
    (is (= {:supported 3 :blocked 0} (:counts report)))
    (is (= :authored-source-equivalent (:verification child)))
    (is (= [alias] (get-in child [:v2 :bindings 0 :target-path])))
    (is (= "content" (get-in child [:v2 :bindings 0 :value])))))


(deftest described-renames-refuse-unknown-policies-and-nontext-descriptions
  (doseq [spec [{:as :public-title :description false}
                {:as :public-title :description nil}
                {:as :public-title :description "Title" :terminal true}
                {:as :public-title :description "Title" :required true}]]
    (is (= :blocked (:status (:entry (fixture {:title spec})))))))


(deftest same-name-noop-retains-its-empty-binding-row-without-a-type-assertion
  (let [{:keys [entry context plan]} (fixture {:title {:as :title}})
        row (first (get-in entry [:v2 :bindings]))
        owner (:identity entry)
        field (ids/slot-id (ids/fn-id "fixture" :base) :title)
        changed (source/verify-module (assoc-in plan [:definitions 1 :proposed :configure [:title] :type] :text) context)]
    (is (= :authored-source-equivalent (:verification entry)) (:blockers entry))
    (is (= {[:title] {}} (get-in entry [:proposed :configure])))
    (is (= {:kind :input-binding :id (ids/binding-id owner field) :fn-id owner
            :scope-path [] :target-path [field] :expression-kind nil :value-present false} row))
    (is (empty? (get-in entry [:v2 :binding-state])))
    (is (= :blocked (get-in changed [:definitions 1 :status])))))


(deftest annotations-do-not-admit-narrowing-or-extra-policy-test
  (doseq [spec [{:as :title :type :any}
                {:as :title :type :text :required true}
                {:as :title :type :text :terminal true}
                {:as :title :type :text :description false}]]
    (is (= :blocked (:status (:entry (fixture {:title spec})))))))


(deftest inline-annotation-spelling-and-argument-order-round-trip-test
  (let [body {:parent :base
              :args (array-map :head (array-map :description "Head" :type [:list :text] :as :head)
                               :title (array-map :as :title))}
        definitions [{:name :base :namespace "fixture" :args {:title :text :head {:type [:list :text]}} :return-type :text}
                     {:name :sink :namespace "fixture" :args {:value :text} :return-type :text}
                     {:name :inline :namespace "fixture" :parent :sink :args {:value body}}]
        context (parse/prepare-module definitions {} {})
        entry (last (:definitions (source/verify-module (source/plan-module definitions context) context)))
        migrated (get-in entry [:proposed :bind :value 1])
        restored (format/inline-origin migrated)]
    (is (= :authored-source-equivalent (:verification entry)) (:blockers entry))
    (is (= body restored))
    (is (= (pr-str (:args body)) (pr-str (:args restored))))
    (is (= [:head :title] (:legacy-args-order migrated)))
    (is (nil? (format/inline-origin {:parent :base :legacy-args-order [:head :title]})))
    (is (nil? (format/inline-origin (assoc migrated :legacy-args-order [:head :head]))))
    (is (nil? (format/inline-origin (assoc-in migrated [:configure [:head] :required] true))))))


(defn- resource-definitions
  [path]
  (let [module (edn/read-string (slurp (io/resource path)))]
    (mapv #(assoc % :namespace (:namespace module)) (:fns module))))


(deftest real-page-inline-distinguishes-noop-metadata-from-reference-forwarding-test
  (binding [shapes/*marker-registry-override* (atom {:secret {:monotone? true :hide-result? true}})]
    (let [core-modules (:modules (edn/read-string (slurp (io/resource "packages/core/package.edn"))))
          page (first (resource-definitions "packages/app/page/fns.edn"))
          definitions (into (vec (mapcat #(resource-definitions (str "packages/core/" % "/fns.edn")) core-modules))
                            (conj (resource-definitions "packages/web/html/fns.edn") page))
          context (parse/prepare-module definitions {} {})
          owner (some #(when (and (= "app.page" (:namespace %)) (= :html-page-rendered (:name %))) %) (:fn-defs context))
          child-name (get-in owner [:args :hiccup])
          child (get-in context [:defs-by-name child-name])
          rows (parse/parse-prepared-module (assoc context :fn-defs [child]))
          original (assoc (get-in page [:args :hiccup]) :name (:name child) :namespace (:namespace child))
          annotations (mapv #(annotations/annotation context original rows %) [:title :head :scripts])
          planned (first (:definitions (source/plan-module [page] context)))
          paths (set (map :source-path (:blockers planned)))]
      (is (every? some? annotations))
      (is (= ["Document `<title>` text."
              "Extra `<head>` hiccup nodes — typically `:graphden-page-head` for the default stylesheet, or `{:value []}` for none."
              "End-of-body `<script>` hiccup nodes — typically `:graphden-runtime-scripts` for the standard dispatcher bundle, or `{:value []}` for none."]
             (mapv :description annotations)))
      (is (nil? (annotations/annotation context original rows :body)))
      (is (not-any? paths [[:args :hiccup :args :title] [:args :hiccup :args :head] [:args :hiccup :args :scripts]]))
      (is (contains? paths [:args :hiccup :args :body]))
      (is (= :blocked (:status planned))))))
