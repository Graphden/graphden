(ns graphden.lint.similar-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.lint.similar :as similar]))


(def ^:private base-names
  [:const :count :equal? :filter :get :hiccup :if :list :str :zipmap])


(defn- toolbar-fixture
  []
  (let [{:keys [fns] :as fixture}
        (edn/read-string (slurp (io/resource "graphden/lint/explorer_toolbar_pre_dry.edn")))]
    (mapv #(assoc % :namespace (:namespace fixture)) fns)))


(defn- analyze
  [fns]
  (similar/analyze fns {:base-fn-names base-names}))


(defn- member-names
  [group]
  (set (map second (:fns group))))


(deftest real-toolbar-template-test
  (let [fns (toolbar-fixture)
        result (analyze fns)
        groups (map member-names (:groups result))]
    (is (= 59 (count fns)) "The fixture is the exact required source closure, not a synthetic sketch")
    (is (zero? (count (:skipped result))))
    (is (= 2 (count groups)))
    (is (some #{#{:_explorer-button-fn :_explorer-button-types :_explorer-button-secrets}} groups))
    (is (some #{#{:_explorer-attrs-fn :_explorer-attrs-types :_explorer-attrs-secrets
                  :_explorer-attrs-failed :_explorer-attrs-type-errors :_explorer-attrs-lint}} groups))
    (is (not-any? #(contains? % :_explorer-button-failed) groups)
        "The visually similar failed/error/lint buttons differ in branch outputs")
    (is (every? #(>= (count (:difference-paths %)) 3) (:groups result)))
    (is (not (str/includes? (pr-str result) "Only functions"))
        "The advisory exposes paths and identities, never literal content")
    (is (= result (analyze (vec (reverse fns))))
        "Input order cannot change the report")))


(deftest contract-differences-do-not-coalesce-test
  (let [fns (toolbar-fixture)
        changed (mapv (fn [fd]
                        (case (:name fd)
                          :_explorer-button-types (assoc-in fd [:args :tag] "div")
                          :_explorer-glyph-secrets (assoc fd :effects [:io])
                          fd)) fns)
        groups (map member-names (:groups (analyze changed)))]
    (is (not-any? #(and (contains? % :_explorer-button-fn)
                        (contains? % :_explorer-button-types)) groups)
        "Hiccup tags are structural, not replaceable terminal copy")
    (is (not-any? #(and (contains? % :_explorer-button-fn)
                        (contains? % :_explorer-button-secrets)) groups)
        "Effects declarations survive private expansion"))
  (testing "Conditional policy branches and public references remain exact"
    (let [fns (toolbar-fixture)
          changed (mapv (fn [fd]
                          (case (:name fd)
                            :_explorer-pressed-types (assoc-in fd [:args :then] "allow")
                            :_explorer-label-secrets (assoc-in fd [:args :children] [:public-label])
                            fd)) fns)
          changed (conj changed {:name :public-label :namespace "app.ui-explorer"
                                 :parent :const :args {:value {:value "secrets"}}})
          groups (map member-names (:groups (analyze changed)))]
      (is (not-any? #(and (contains? % :_explorer-button-fn)
                          (contains? % :_explorer-button-types)) groups))
      (is (not-any? #(and (contains? % :_explorer-button-fn)
                          (contains? % :_explorer-button-secrets)) groups))))
  (testing "Guard constants remain exact through private helper expansion"
    (let [fns (toolbar-fixture)
          helpers [{:name :_allow-fn :namespace "app.ui-explorer"
                    :parent :str :args {:parts ["allowed"]}}
                   {:name :_allow-types :namespace "app.ui-explorer"
                    :parent :str :args {:parts ["allowed"]}}]
          fns (mapv (fn [fd]
                      (case (:name fd)
                        :_explorer-pressed-fn (assoc-in fd [:args :then] :_allow-fn)
                        :_explorer-pressed-types (assoc-in fd [:args :then] :_allow-types)
                        fd)) fns)
          same (into fns helpers)
          changed (mapv (fn [fd]
                          (if (= :_allow-types (:name fd))
                            (assoc-in fd [:args :parts 0] "denied") fd)) same)
          paired? (fn [rows]
                    (some #(and (contains? % :_explorer-button-fn)
                                (contains? % :_explorer-button-types))
                          (map member-names (:groups (analyze rows)))))]
      (is (paired? same) "Helper names are abstracted by private expansion")
      (is (not (paired? changed)) "A different guard result is not a template hole")))
  (testing "Equivalent binding notation is normalized, binding policy is not"
    (let [fns (toolbar-fixture)
          ref-spec (mapv (fn [fd]
                           (if (= :_explorer-button-types (:name fd))
                             (-> fd
                                 (assoc-in [:args :attrs] {:ref :_explorer-attrs-types})
                                 (assoc-in [:args :children 0] {:ref :_explorer-glyph-types}))
                             fd)) fns)
          with-policy (mapv (fn [fd]
                              (if (= :_explorer-button-types (:name fd))
                                (assoc-in fd [:args :attrs]
                                          {:ref :_explorer-attrs-types :required true})
                                fd)) ref-spec)
          paired? (fn [rows]
                    (some #(and (contains? % :_explorer-button-fn)
                                (contains? % :_explorer-button-types))
                          (map member-names (:groups (analyze rows)))))]
      (is (paired? ref-spec))
      (is (not (paired? with-policy))))))


(deftest bounded-expansion-never-groups-truncated-subjects-test
  (let [cyclic [{:name :_cycle :namespace "test" :parent :get :args {:coll :_cycle}}]
        depth (mapv (fn [n]
                      {:name (keyword (str "_depth-" n)) :namespace "test"
                       :parent (if (zero? n) :const (keyword (str "_depth-" (dec n))))})
                    (range 28))
        size [{:name :_leaf :namespace "test" :parent :const :args {:value "x"}}
              {:name :_wide :namespace "test" :parent :list
               :args {:items (vec (repeat 520 :_leaf))}}]
        structural-depth [{:name :_nested :namespace "test" :parent :const
                           :args {:value (nth (iterate vector "leaf") 140)}}]
        structural-size [{:name :_large-literal :namespace "test" :parent :const
                          :args {:value (vec (repeat 33000 "leaf"))}}]
        cycle-result (analyze cyclic)
        depth-result (analyze depth)
        size-result (analyze size)
        structural-depth-result (analyze structural-depth)
        structural-size-result (analyze structural-size)]
    (is (= :cycle (:skipped (first (:skipped cycle-result)))))
    (is (some #(and (= ["test" :_depth-27] (:fn %)) (= :depth (:skipped %)))
              (:skipped depth-result)))
    (is (some #(and (= ["test" :_wide] (:fn %)) (= :size (:skipped %)))
              (:skipped size-result)))
    (is (= :structure-depth (:skipped (first (:skipped structural-depth-result)))))
    (is (= :structure-size (:skipped (first (:skipped structural-size-result)))))
    (is (every? (comp empty? :groups)
                [cycle-result depth-result size-result structural-depth-result structural-size-result]))))
