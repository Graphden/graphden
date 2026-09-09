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


(deftest finding-key-and-suppression-test
  (let [dup-a (fd "a" :page :parent :assoc :args {:map {:value {:class "x"}} :key {:value :t} :value :a/title} :id "id-a")
        dup-b (fd "b" :row :parent :assoc :args {:map {:value {:class "x"}} :key {:value :t} :value :a/title} :id "id-b")
        title (fd "a" :title :parent :const :args {:value {:value "t"}} :id "id-t")
        [f] (findings-for :duplicate-definition [dup-a dup-b title])]
    (testing "fn-defs that carry :id stamp the finding with sorted :fn-ids"
      (is (= ["id-a" "id-b"] (:fn-ids f))))
    (testing "the key is the rule + the sorted ids"
      (is (= [:duplicate-definition ["id-a" "id-b"]] (lint/finding-key f))))
    (testing "a suppressed key drops the finding"
      (is (empty? (findings-for :duplicate-definition [dup-a dup-b title]
                                :suppress #{(lint/finding-key f)}))))
    (testing "a group that gains a member has a new key — the suppression no longer matches"
      (let [dup-c (fd "c" :cell :parent :assoc :args {:map {:value {:class "x"}} :key {:value :t} :value :a/title} :id "id-c")
            fs (findings-for :duplicate-definition [dup-a dup-b dup-c title]
                             :suppress #{(lint/finding-key f)})]
        (is (= [["id-a" "id-b" "id-c"]] (map :fn-ids fs)))))
    (testing "without ids the key falls back to [ns name] pairs"
      (let [[g] (findings-for :duplicate-definition [(dissoc dup-a :id) (dissoc dup-b :id) title])]
        (is (nil? (:fn-ids g)))
        (is (= [:duplicate-definition ["[\"a\" :page]" "[\"b\" :row]"]] (lint/finding-key g)))))))


(deftest platform-fn-test
  (let [platform? #(= "platform" (:namespace %))
        p1 (fd "platform" :_helper :parent :get :args {:key {:value :k}} :id "p1")
        p2 (fd "platform" :_twin :parent :assoc :args {:map {:value {}} :key {:value :k} :value {:value 1}} :id "p2")
        p3 (fd "platform" :_twin2 :parent :assoc :args {:map {:value {}} :key {:value :k} :value {:value 1}} :id "p3")
        user (fd "mine" :_copy :parent :assoc :args {:map {:value {}} :key {:value :k} :value {:value 1}} :id "u1")]
    (testing "an unreferenced platform private is not a finding"
      (is (empty? (findings-for :unreferenced-private [p1] :platform-fn? platform?)))
      (is (= 1 (count (findings-for :unreferenced-private [p1])))))
    (testing "a duplicate group made only of platform fn-defs is dropped"
      (is (empty? (findings-for :duplicate-definition [p2 p3] :platform-fn? platform?))))
    (testing "a user fn-def duplicating a platform one IS a finding, naming both"
      (let [[f] (findings-for :duplicate-definition [p2 user] :platform-fn? platform?)]
        (is (= #{"p2" "u1"} (set (:fn-ids f))))
        (is (= (map (fn [[_ n]] (get {:_twin "p2" :_copy "u1"} n)) (:fns f))
               (:fn-ids f))
            "ids ride in the order of :fns so the two zip")))))


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
                         (fd "a" :_name :parent :get :args {:coll {:as :row} :key {:value :name}})
                         (fd "b" :_name :parent :get :args {:coll {:as :row} :key {:value :name}})
                         (fd "a" :use :parent :assoc :args {:map :a/_name :key {:value :k}})
                         (fd "b" :use :parent :assoc :args {:map :b/_name :key {:value :k}})]
                        {:base-fn-names base-fns})]
      ;; `_dead` is unreferenced (warning); a/use + b/use are the same
      ;; graph once their private `_name` helpers expand (warning); the
      ;; two `_name` accessors themselves are an info-tier duplicate, and
      ;; a/use + b/use also bind one value alike (info-tier fan-in).
      (is (= [:warning :warning :info :info] (map :severity fs)))
      (is (= 2 (count (lint/warnings fs)))))))


(deftest unreachable-private-test
  (let [defs [(fd "a" :_head :parent :assoc :args {:map :a/_tail :key {:value :k}})
              (fd "a" :_tail :parent :get :args {:key {:value :k}})
              (fd "a" :_live :parent :get :args {:key {:value :j}})
              (fd "a" :page :parent :assoc :args {:map :a/_live :key {:value :k}})]]
    (testing "a private referenced only from an unreferenced private is the rest of the dead cluster"
      (is (= [[["a" :_tail]]] (map :fns (findings-for :unreachable-private defs))))
      (is (= [[["a" :_head]]] (map :fns (findings-for :unreferenced-private defs))))
      (is (re-find #"a/_head" (:message (first (findings-for :unreachable-private defs))))))
    (testing "a by-name root keeps its whole subtree alive"
      (is (empty? (findings-for :unreachable-private defs :roots #{:_head}))))
    (testing "a platform private is a live root"
      (is (empty? (findings-for :unreachable-private defs :platform-fn? #(= :_head (:name %))))))
    (testing "a private reached through a type-row field is alive, and so is what it references"
      (is (empty? (findings-for :unreachable-private
                                [(fd "a" :_pred :parent :nil? :args {:value :a/_inner})
                                 (fd "a" :_inner :parent :get :args {:key {:value :k}})
                                 (fd "a" :url :refine {:base :text :pred :a/_pred})]))))))


(deftest shadowed-override-test
  (let [title (fd "a" :title :parent :const :args {:value {:value "t"}})
        cell (fd "a" :cell :parent :assoc :args {:map {:value {:class "x"}} :key {:value :k} :value :a/title})]
    (testing "re-binding args to exactly what the parent binds is one finding naming them"
      (let [[f :as fs] (findings-for :shadowed-override
                                     [title cell (fd "a" :cell2 :parent :a/cell :args {:key {:value :k} :value {:ref :a/title :description "docs"}})])]
        (is (= 1 (count fs)))
        (is (= [["a" :cell2]] (:fns f)))
        (is (= 2 (:weight f)))
        (is (= :warning (:severity f)))
        (is (re-find #"key, value" (:message f)))))
    (testing "a type pin, a rename, a doc-only spec, a list and a different value say something new"
      (is (empty? (findings-for :shadowed-override
                                [title cell
                                 (fd "a" :pinned :parent :a/cell :args {:value {:ref :a/title :type :text}})
                                 (fd "a" :renamed :parent :a/cell :args {:key {:as :the-key}})
                                 (fd "a" :documented :parent :a/cell :args {:key {:description "why"}})
                                 (fd "a" :listed :parent :assoc :args {:map [{:value 1}]})
                                 (fd "a" :listed2 :parent :a/listed :args {:map [{:value 1}]})
                                 (fd "a" :other :parent :a/cell :args {:key {:value :other}})]))))
    (testing "two parents that disagree leave no single value to restate"
      (let [p1 (fd "a" :p1 :parent :assoc :args {:key {:value :k}})
            p2 (fd "a" :p2 :parent :assoc :args {:key {:value :j}})]
        (is (empty? (findings-for :shadowed-override
                                  [p1 p2 (fd "a" :child :parents [:a/p1 :a/p2] :args {:key {:value :k}})])))))
    (testing "the inherited value is found past an intermediate that does not bind the arg"
      (let [mid (fd "a" :mid :parent :a/cell :args {:map {:value {:class "y"}}})]
        (is (= [[["a" :leaf]]]
               (map :fns (findings-for :shadowed-override
                                       [title cell mid (fd "a" :leaf :parent :a/mid :args {:key {:value :k}})]))))))))


(deftest fan-in-extract-parent-test
  (let [zipmap-fns (conj base-fns :zipmap)
        keys-v [{:value :ok} {:value :reason} {:value :error}]
        e1 (fd "a" :e1 :parent :zipmap :args {:keys keys-v :vals [{:value false} {:value :nf} {:value "x"}]})
        e2 (fd "a" :e2 :parent :zipmap :args {:keys keys-v :vals [{:value false} {:value :bad} {:value "y"}]})
        e3 (fd "b" :e3 :parent :zipmap :args {:keys keys-v :vals [{:value false} {:value :nf} {:value "x"}]})]
    (testing "siblings that bind the same three values are a warning, listed once under the shared set"
      (let [[f :as fs] (findings-for :fan-in-extract-parent [e1 e2] :base-fn-names zipmap-fns)]
        (is (= 1 (count fs)))
        (is (= [["a" :e1] ["a" :e2]] (:fns f)))
        (is (= 3 (:weight f)))
        (is (= :warning (:severity f)))
        (is (re-find #"bind keys identically" (:message f)))))
    (testing "an exact copy joins the wider group; the pair it duplicates is the duplicate rule's"
      (let [fs (findings-for :fan-in-extract-parent [e1 e2 e3] :base-fn-names zipmap-fns)]
        (is (= [[["a" :e1] ["a" :e2] ["b" :e3]]] (map :fns fs)))
        (is (= 1 (count (findings-for :duplicate-definition [e1 e2 e3] :base-fn-names zipmap-fns))))))
    (testing "one or two shared values is info — the let-rule's separate child per code path"
      (let [[f] (findings-for :fan-in-extract-parent
                              [(fd "a" :x :parent :assoc :args {:map {:value {}} :key {:value :k} :value {:value 1}})
                               (fd "a" :y :parent :assoc :args {:map {:value {}} :key {:value :k} :value {:value 2}})])]
        (is (= :info (:severity f)))
        (is (= 2 (:weight f)))))
    (testing "different parents, or nothing bound alike, is not a group"
      (is (empty? (findings-for :fan-in-extract-parent
                                [(fd "a" :x :parent :assoc :args {:map {:value {}} :key {:value :k}})
                                 (fd "a" :y :parent :get :args {:coll {:value {}} :key {:value :k}})
                                 (fd "a" :z :parent :assoc :args {:map {:value {:a 1}} :key {:value :j}})]))))))


(deftest deep-hierarchy-test
  (let [chain (fn [n]
                (into [(fd "a" :c1 :parent :get :args {:key {:value :k}})]
                      (map (fn [i]
                             (fd "a" (keyword (str "c" i)) :parent (keyword "a" (str "c" (dec i)))
                                 :args {:default {:value i}})))
                      (range 2 (inc n))))]
    (testing "a chain is reported at its tip only, info from six levels and a warning from eight"
      (let [fs (findings-for :deep-hierarchy (chain 8))]
        (is (= [[["a" :c8]]] (map :fns fs)))
        (is (= 8 (:weight (first fs))))
        (is (= :warning (:severity (first fs))))
        (is (re-find #"a/c1 → a/c2 → .* → a/c8" (:message (first fs)))))
      (is (= [:info] (map :severity (findings-for :deep-hierarchy (chain 6)))))
      (is (empty? (findings-for :deep-hierarchy (chain 5)))))
    (testing "the depth is the LONGEST parent path under multiple inheritance"
      (let [defs (conj (chain 7)
                       (fd "a" :short :parent :get :args {:key {:value :s}})
                       (fd "a" :tip :parents [:a/short :a/c7] :args {:default {:value 0}}))]
        (is (= [[["a" :tip]]] (map :fns (findings-for :deep-hierarchy defs))))))))
