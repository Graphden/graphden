(ns graphden.lint.core-test
  "Rule-by-rule contract of the graph lint over tiny hand-written
   fn-def sets — the package corpus itself is gated by `bb graph-lint`."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.lint.core :as lint]))


(def ^:private base-fns
  [:get :assoc :nil? :const])


(defn- fd
  [nsp n & {:as more}]
  (merge {:name n :namespace nsp} more))


(defn- findings-for
  [rule fn-defs & {:as opts}]
  (filterv #(= rule (:rule %))
           (lint/lint fn-defs (merge {:base-fn-names base-fns} opts))))


(deftest duplicate-definition-test
  (testing "two named fn-defs with the same parent + bound args are one definition written twice"
    (let [fs (findings-for :duplicate-definition
                           [(fd "a" :page-attrs :parent :assoc
                                :args {:map {:value {:class "x"}} :key {:value :title} :value :a/title})
                            (fd "a" :title :parent :const :args {:value {:value "t"}})
                            (fd "b" :row-attrs :parent :assoc
                                :description "docs differ, structure does not"
                                :args {:map {:value {:class "x"}} :key {:value :title}
                                       :value {:ref :a/title :description "per-binding docs too"}})])]
      (is (= 1 (count fs)))
      (is (= :warning (:severity (first fs))))
      (is (= [["a" :page-attrs] ["b" :row-attrs]] (:fns (first fs))))
      (is (= 3 (:weight (first fs))))))

  (testing "a small accessor is info, not a warning — the let-rule's separate child per code path"
    (let [fs (findings-for :duplicate-definition
                           [(fd "a" :_name :parent :get :args {:coll {:as :row} :key {:value :name} :default nil})
                            (fd "b" :_name :parent :get :args {:coll {:as :row} :key {:value :name} :default nil})])]
      (is (= [:info] (map :severity fs)))
      (is (= 1 (:weight (first fs))))))

  (testing "a nil literal weighs nothing — `:default nil` spells out no default"
    (let [fs (findings-for :duplicate-definition
                           [(fd "a" :_id :parent :get :args {:coll :a/row :key {:value :id} :default nil})
                            (fd "b" :_id :parent :get :args {:coll :a/row :key {:value :id} :default nil})
                            (fd "a" :row :parent :const :args {:value {:value {}}})])]
      (is (= 2 (:weight (first fs))))))

  (testing "a rename-only binding weighs nothing"
    (let [fs (findings-for :duplicate-definition
                           [(fd "a" :_missing? :parent :nil? :args {:value {:as :resolved}})
                            (fd "b" :_missing? :parent :nil? :args {:value {:as :resolved}})])]
      (is (zero? (:weight (first fs))))
      (is (= :info (:severity (first fs))))))

  (testing "different return-type or lambda-params is a different definition"
    (is (empty? (findings-for :duplicate-definition
                              [(fd "a" :x :parent :get :args {:key {:value :k}} :return-type :text)
                               (fd "b" :x :parent :get :args {:key {:value :k}} :return-type :int)])))
    (is (empty? (findings-for :duplicate-definition
                              [(fd "a" :x :parent :get :args {:key {:value :k}} :lambda-params [:m])
                               (fd "b" :x :parent :get :args {:key {:value :k}})]))))

  (testing "generated anonymous rows and type-rows are never subjects"
    (is (empty? (findings-for :duplicate-definition
                              [(fd "a" :_anon-1 :parent :get :args {:key {:value :k} :coll :a/m})
                               (fd "b" :_anon-2 :parent :get :args {:key {:value :k} :coll :a/m})
                               (fd "a" :m :type {:k :text})
                               (fd "a" :shape :type {:k :text})
                               (fd "b" :shape :type {:k :text})])))))


(deftest duplicate-after-expansion-test
  (testing "the same graph split across differently-named private helpers is found once expanded"
    (let [fs (findings-for :duplicate-after-expansion
                           [(fd "a" :_key :parent :const :args {:value {:value :title}})
                            (fd "a" :attrs :parent :assoc
                                :args {:map {:value {:class "x"}} :key :a/_key :value :a/_key})
                            (fd "b" :_k :parent :const :args {:value {:value :title}})
                            (fd "b" :attrs :parent :assoc
                                :args {:map {:value {:class "x"}} :key :b/_k :value :b/_k})])]
      (is (= 1 (count fs)))
      (is (= [["a" :attrs] ["b" :attrs]] (:fns (first fs))))
      (is (= :warning (:severity (first fs))))
      ;; 3 own bindings + 1 bound value inside each expanded helper (2 sites)
      (is (= 5 (:weight (first fs))))))

  (testing "refs to PUBLIC fn-defs are identities, not expanded — sharing one is not a duplicate"
    (is (empty? (findings-for :duplicate-after-expansion
                              [(fd "a" :key :parent :const :args {:value {:value :title}})
                               (fd "a" :attrs :parent :assoc :args {:map {:value {}} :key :a/key})
                               (fd "b" :attrs :parent :assoc :args {:map {:value {}} :key :a/key})]))))

  (testing "a shallow-identical group is reported by the shallow rule only"
    (is (empty? (findings-for :duplicate-after-expansion
                              [(fd "a" :x :parent :assoc :args {:map {:value {}} :key {:value :k}})
                               (fd "b" :x :parent :assoc :args {:map {:value {}} :key {:value :k}})])))))


(deftest unreferenced-private-test
  (let [defs [(fd "a" :_dead :parent :get :args {:key {:value :k}})
              (fd "a" :_live :parent :get :args {:key {:value :k}})
              (fd "a" :page :parent :assoc :args {:map :a/_live :key {:value :k}})
              (fd "a" :_router :parent :get :args {:key {:value :r}})
              (fd "a" :public-unused :parent :get :args {:key {:value :p}})]]
    (testing "a private fn-def nothing references is a warning; public ones are vocabulary"
      (is (= [[["a" :_dead]]]
             (map :fns (findings-for :unreferenced-private defs :roots #{:_router})))))
    (testing "the by-name entry-point registry exempts"
      (is (= #{["a" :_dead] ["a" :_router]}
             (into #{} (map (comp first :fns)) (findings-for :unreferenced-private defs)))))
    (testing "a reference from a type-row field counts"
      (is (empty? (findings-for :unreferenced-private
                                [(fd "a" :_pred :parent :nil? :args {:value {:as :v}})
                                 (fd "a" :url :refine {:base :text :pred :a/_pred})]))))))


(deftest private-alias-test
  (testing "a private fn-def that only renames its parent is info"
    (let [fs (findings-for :private-alias
                           [(fd "a" :_now :parent :const)
                            (fd "a" :use :parent :assoc :args {:map :a/_now :key {:value :k}})
                            (fd "a" :_typed :parent :const :return-type :int)
                            (fd "a" :use2 :parent :assoc :args {:map :a/_typed :key {:value :k}})])]
      (is (= [[["a" :_now]]] (map :fns fs)))
      (is (= [:info] (map :severity fs))))))


(deftest resolve-ref-test
  (let [idx (lint/build-index [(fd "a" :x :parent :get) (fd "b" :x :parent :get) (fd "a" :y :parent :get)]
                              base-fns)]
    (testing "bare names resolve only when unique; qualified names always; base-fns by name"
      (is (= :ambiguous (first (lint/resolve-ref idx :x))))
      (is (= ["b" :x] (lint/fn-key (second (lint/resolve-ref idx :b/x)))))
      (is (= ["a" :y] (lint/fn-key (second (lint/resolve-ref idx :y)))))
      (is (= [:base :get] (lint/resolve-ref idx :get)))
      (is (nil? (lint/resolve-ref idx :just-a-keyword))))))


(deftest ordering-test
  (testing "warnings sort before info"
    (let [fs (lint/lint [(fd "a" :_dead :parent :get :args {:key {:value :k}})
                         (fd "a" :_alias :parent :const)
                         (fd "a" :use :parent :assoc :args {:map :a/_alias :key {:value :k}})]
                        {:base-fn-names base-fns})]
      (is (= [:warning :info] (map :severity fs)))
      (is (= 1 (count (lint/warnings fs)))))))
