(ns graphden.input.source-origins-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.source-origins :as origins]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(defn- fixture
  []
  (let [sink {:namespace "fixture" :name :sink :args {:value :any} :return-type :any}
        leaf {:parent :sink :args {:value nil}}
        nested {:parent :sink :args {:value leaf}}
        host {:namespace "fixture" :name :host :parent :sink
              :args {:value {:append [leaf nested] :closed true}}}
        other (assoc host :namespace "other")
        opaque (assoc host :name :opaque :args {:value {:value nested}})]
    [sink host other opaque]))


(deftest exact-enclosing-owners-and-list-positions-survive-ordering
  (let [definitions (fixture)
        prepared (parse/prepare-module definitions {} {})
        result (origins/index prepared definitions)
        reversed (origins/index (parse/prepare-module (vec (reverse definitions)) {} {}) (reverse definitions))
        host (ids/fn-id "fixture" :host)
        paths (into {} (map (juxt :source-path identity))
                    (filter #(= host (:root-owner-id %)) (mapcat val result)))
        outer (get paths [:args :value :append 1])
        inner (get paths [:args :value :append 1 :args :value])]
    (is (= result reversed))
    (is (= 6 (count result)))
    (is (= #{[:args :value :append 0] [:args :value :append 1]
             [:args :value :append 1 :args :value]} (set (keys paths))))
    (is (= host (:enclosing-owner-id outer)))
    (is (= (:fn-id outer) (:enclosing-owner-id inner)))
    (is (not= (:fn-id inner) (:fn-id (get paths [:args :value :append 0]))))
    (is (every? #(= (:fn-id %) (ids/fn-id (get-in % [:declaration :namespace])
                                          (get-in % [:declaration :name]))) (mapcat val result)))))


(deftest altered-preparation-and-duplicate-originals-cannot-supply-an-owner
  (let [definitions (fixture)
        prepared (parse/prepare-module definitions {} {})
        result (origins/index prepared definitions)
        host (ids/fn-id "fixture" :host)
        [child] (filter #(and (= host (:root-owner-id %))
                              (= [:args :value :append 0] (:source-path %))) (mapcat val result))
        id (:fn-id child)
        ref (keyword "fixture" (name (get-in child [:declaration :name])))
        forged (assoc-in prepared [:defs-by-name ref :args :value] false)
        duplicate (origins/index prepared (conj definitions (second definitions)))]
    (is (= child (origins/declaration result id)))
    (is (nil? (origins/declaration (origins/index forged definitions) id)))
    (is (= :ambiguous-inline-owner
           (try (origins/declaration duplicate id) nil
                (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))))


(deftest conflicting-original-owner-is-ambiguous-even-if-only-one-body-matches-preparation
  (let [definitions (fixture)
        prepared (parse/prepare-module definitions {} {})
        host (second definitions)
        conflicting (assoc host :args {:value {:value "different body"}})
        index (origins/index prepared (conj definitions conflicting))
        children (filter #(= (ids/fn-id "fixture" :host) (:root-owner-id %)) (mapcat val index))]
    (is (= 3 (count children)))
    (doseq [{:keys [fn-id]} children]
      (is (= :ambiguous-inline-owner
             (try (origins/declaration index fn-id) nil
                  (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))))))
