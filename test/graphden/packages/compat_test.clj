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


(deftest added-effects-and-incompatible-dependency-bumps-are-breaking
  (testing "a base-fn that starts declaring an effect breaks a restricted-tier consumer; dropping one does not"
    (is (= [[:effect-added :fetch nil]]
           (kinds [{:name :fetch :namespace "pkg" :args {:u :text} :return-type :text :effects #{:network}}]
                  [{:name :fetch :namespace "pkg" :args {:u :text} :return-type :text :effects #{:network :db}}])))
    (is (= [] (compat/breaking-changes
                [{:name :fetch :namespace "pkg" :args {:u :text} :return-type :text :effects #{:network :db}}]
                [{:name :fetch :namespace "pkg" :args {:u :text} :return-type :text :effects #{:network}}]))))
  (testing "a package dependency outside its previous caret range"
    (is (= [{:kind :dependency-incompatible :name "lib" :old "1.2.0" :new "2.0.0"}]
           (compat/incompatible-dependency-bumps
             [{:name "lib" :version "1.2.0"} {:name "util" :version "0.3.1"} {:name "gone" :version "1.0.0"}]
             [{:name "lib" :version "2.0.0"} {:name "util" :version "0.3.9"} {:name "added" :version "1.0.0"}])))
    (is (= [{:kind :dependency-incompatible :name "util" :old "0.3.1" :new "0.4.0"}]
           (compat/incompatible-dependency-bumps [{:name "util" :version "0.3.1"}] [{:name "util" :version "0.4.0"}]))
        "below 1.0 the minor is the compatibility line")
    (is (= [] (compat/incompatible-dependency-bumps [{:name "lib" :version "1.2.0"}] [{:name "lib" :version "1.9.3"}])))))
