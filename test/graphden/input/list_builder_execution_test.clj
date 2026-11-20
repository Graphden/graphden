(ns graphden.input.list-builder-execution-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.compile-eager :as compile]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.input.list-builder :as builder]
    [graphden.packages.records.ids :as ids]))


(deftest builder-ref-demand-and-call-cache-match-sequence-compilation
  (let [owner (ids/fn-id "fixture" :host)
        source (ids/fn-id "fixture" :source)
        bomb (ids/fn-id "fixture" :bomb)
        binding-id (ids/binding-id owner (ids/slot-id owner :items))
        names {:source source :bomb bomb}
        form [:list-builder {:items [[:ref :source] [:ref :source] [:ref :bomb]] :tail :open}]
        records (builder/compiler-items (builder/parse-fragment binding-id form names))
        env (lookups/build-lookups {:fns (mapv (fn [[n id]] {:id id :name (name n) :parent-ids []}) names)})]
    (doseq [delays? [false true]]
      (let [calls (atom [])
            callables {source (fn [_ _] (swap! calls conj :source) 42)
                       bomb (fn [_ _] (throw (ex-info "Unselected branch" {})))}
            run (compile/compile-list-items owner records delays? callables env)
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
