(ns graphden.packages.compat-test
  "Breaking-change detection between package versions — pure over the
   exported fn-def maps."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.compat :as compat]))


(defn- kinds
  [old new]
  (mapv (juxt :kind :fn :arg) (compat/breaking-changes old new)))


(def ^:private base
  [{:name :greet :namespace "pkg" :args {:who :text :loud {:type :bool :required false}} :return-type :text}
   {:name :user :namespace "pkg" :type {:id :uuid :nick :text}}
   {:name :hello :namespace "pkg" :parent :greet :args {:who {:value "world"} :loud {:as :shout}}}
   {:name :_helper :namespace "pkg" :parent :greet :args {:who {:value "x"}}}])


(deftest identical-and-additive-versions-are-compatible
  (is (= [] (compat/breaking-changes base base)))
  (testing "a new fn-def, a new optional slot, a filled-in free arg, a wider arg, a narrower return"
    (let [new [{:name :greet :namespace "pkg"
                :args {:who [:union :text :keyword] :loud {:type :bool :required false}
                       :suffix {:type :text :required false}}
                :return-type [:refine :text [:matches "\\S"]]}
               {:name :user :namespace "pkg" :type {:id :uuid :nick :text :bio {:type :text :required false}}}
               {:name :hello :namespace "pkg" :parent :greet
                :args {:who {:value "world"} :loud {:as :shout} :suffix {:value "!"}}}
               {:name :wave :namespace "pkg" :parent :greet :args {:who {:value "hi"}}}]]
      (is (= [] (compat/breaking-changes base new)))))
  (testing "private fn-defs never count"
    (is (= [] (compat/breaking-changes base (remove #(= :_helper (:name %)) base))))))


(deftest removals-and-narrowings-are-breaking
  (testing "a public fn-def removed"
    (is (= [[:fn-removed :user nil]]
           (kinds base (remove #(= :user (:name %)) base)))))
  (testing "a base-fn slot removed, a required slot added, an arg narrowed, a return widened"
    (is (= [[:arg-removed :greet :loud]
            [:arg-narrowed :greet :who]
            [:arg-required-added :greet :tone]
            [:return-widened :greet nil]]
           (kinds [{:name :greet :namespace "pkg" :args {:who :int :loud :bool} :return-type :text}]
                  [{:name :greet :namespace "pkg" :args {:who [:refine :int [:> 0]] :tone :keyword} :return-type :any}]))))
  (testing "a record field removed / required field added"
    (is (= [[:arg-removed :user :nick] [:arg-required-added :user :email]]
           (kinds [{:name :user :namespace "pkg" :type {:id :uuid :nick :text}}]
                  [{:name :user :namespace "pkg" :type {:id :uuid :email :text}}]))))
  (testing "a refinement / union type-row reshaped"
    (is (= [[:type-changed :port nil]]
           (kinds [{:name :port :namespace "pkg" :refine {:base :int :constraint [:> 0]}}]
                  [{:name :port :namespace "pkg" :refine {:base :int :constraint [:> 1024]}}])))))


(deftest composed-fn-def-contract-changes-are-breaking
  (testing "parents changed"
    (is (= [[:parents-changed :hello nil]]
           (kinds [{:name :hello :namespace "pkg" :parent :greet :args {:who {:value "w"}}}]
                  [{:name :hello :namespace "pkg" :parent :shout :args {:who {:value "w"}}}]))))
  (testing "a binding dropped → the consumer now has to supply the arg"
    (is (= [[:arg-unbound :hello :who]]
           (kinds [{:name :hello :namespace "pkg" :parent :greet :args {:who {:value "w"} :loud {:value true}}}]
                  [{:name :hello :namespace "pkg" :parent :greet :args {:loud {:value true}}}]))))
  (testing "a rename's public name changed"
    (is (= [[:arg-renamed :hello :loud]]
           (kinds [{:name :hello :namespace "pkg" :parent :greet :args {:loud {:as :shout}}}]
                  [{:name :hello :namespace "pkg" :parent :greet :args {:loud {:as :yell}}}]))))
  (testing "an own-slot declaration narrowed; a metadata-only `{:as k …}` override is not a rename"
    (is (= [[:arg-narrowed :hello :n]]
           (kinds [{:name :hello :namespace "pkg" :parent :greet :args {:n {:type :int} :loud {:as :loud :required false}}}]
                  [{:name :hello :namespace "pkg" :parent :greet :args {:n {:type [:refine :int [:> 0]]} :loud {:as :loud}}}]))))
  (testing "role flip"
    (is (= [[:role-changed :hello nil]]
           (kinds [{:name :hello :namespace "pkg" :parent :greet :args {}}]
                  [{:name :hello :namespace "pkg" :args {:x :int} :return-type :int}])))))


(deftest change-records-carry-old-and-new
  (let [[c] (compat/breaking-changes
              [{:name :greet :namespace "pkg" :args {:who :text} :return-type :text}]
              [{:name :greet :namespace "pkg" :args {:who :keyword} :return-type :text}])]
    (is (= {:kind :arg-narrowed :fn :greet :arg :who :old :text :new :keyword} c))))
