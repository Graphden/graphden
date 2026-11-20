(ns graphden.input.list-builder-execution-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.input.list-builder :as builder]
    [graphden.input.list-execution :as execution]
    [graphden.input.source :as source]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]))


(deftest builder-ref-demand-and-call-cache-match-sequence-compilation
  (let [owner (ids/fn-id "fixture" :host)
        source (ids/fn-id "fixture" :source)
        bomb (ids/fn-id "fixture" :bomb)
        binding-id (ids/binding-id owner (ids/slot-id owner :items))
        names {:source source :bomb bomb}
        form [:list-builder {:items [[:ref :source] [:ref :source] [:ref :bomb]] :tail :open}]
        fragment (assoc (builder/parse-fragment binding-id form names) :owner-id owner :target-id (ids/slot-id owner :items))
        trace (assoc (builder/link-fragments [fragment]) :fragments [fragment])
        env (lookups/build-lookups {:fns (mapv (fn [[n id]] {:id id :name (name n) :parent-ids []}) names)})]
    (doseq [delays? [false true]]
      (let [calls (atom [])
            callables {source (fn [_ _] (swap! calls conj :source) 42)
                       bomb (fn [_ _] (throw (ex-info "Unselected branch" {})))}
            run (execution/compile-construction trace {:lazy-seq? delays? :child-callables callables :lookups env})
            cells (run {} {:graphden.executor.compile-eager/call-cache (java.util.HashMap.)})]
        (is (empty? @calls))
        (let [values (take 2 cells)]
          (if delays?
            (do
              (is (every? delay? values))
              (is (empty? @calls))
              (is (= [42 42] (mapv deref values))))
            (is (= [42 42] (vec values)))))
        (is (= [:source] @calls))))))


(deftest continuation-is-addressed-and-inherited-in-order
  (let [target (ids/slot-id (ids/fn-id "fixture" :base) :items)
        fragment (fn [name value tail]
                   (let [owner (ids/fn-id "fixture" name)
                         form [:list-builder {:tail tail :items [[:value value]]}]]
                     (assoc (builder/parse-fragment (ids/binding-id owner target) form {})
                            :owner-id owner :target-id target)))
        head (fragment :head 1 :open)
        next-step (fragment :middle 2 :open)
        chain (fn [fragments] (assoc (builder/link-fragments fragments) :fragments fragments))
        trace (chain [head next-step])
        address (:open-continuation trace)
        opts {:lookups (lookups/build-lookups {}) :child-callables {} :lazy-seq? false}
        run (fn [trace opts] ((execution/compile-construction trace opts) {} {}))]
    (is (= [1 2] (run trace opts)))
    (is (= [1 2] (run trace (assoc opts :continuations {address [:value nil]}))))
    (is (= [1 2 nil false] (run trace (assoc opts :continuations {address [:value [nil false]]}))))
    (is (= [1 2 3] (mapv deref (run trace (assoc opts :lazy-seq? true :continuations {address [:value [3]]})))))
    (is (thrown? clojure.lang.ExceptionInfo
          (run (chain [head (fragment :middle 2 :closed)]) (assoc opts :continuations {address [:value [3]]}))))
    (is (thrown? clojure.lang.ExceptionInfo (run trace (assoc opts :continuations {address [:ref :unknown]}))))))


(deftest existing-os-info-keys-compile-from-generated-rows-alone
  (let [selected #{:zipmap :os-info :os-info-shape :os-name :os-arch :os-processors :os-load-average}
        originals (into [] (mapcat (fn [module]
                                     (let [path (str "packages/core/" module "/fns.edn")
                                           {ns-id :namespace :keys [fns]} (edn/read-string (slurp (io/resource path)))]
                                       (into [] (comp (filter #(contains? selected (:name %)))
                                                      (map #(assoc % :namespace ns-id))) fns)))) ["system" "collections"])
        ctx (parse/prepare-module originals {} {})
        host (first (filter #(= :os-info (:name %)) originals))
        entry (first (:definitions (source/plan-module [host] ctx)))
        arg (first (filter #(= [:args :keys] (:source-path %)) (get-in entry [:legacy :arguments])))
        {:keys [records field]} (:list-builder arg)
        {:keys [fn-id slot-id]} (:origin arg)
        run (execution/compile-rows [fn-id] slot-id field (:name->id ctx) records
                                    {:lazy-seq? false :child-callables {} :lookups (lookups/build-lookups {})})]
    (is (= (mapv :value (get-in host [:args :keys])) (vec (run {} {}))))))
