(ns graphden.input.list-execution
  "Offline JVM execution of a source-verified list construction."
  (:require
    [graphden.executor.compile-eager :as compile]
    [graphden.input.list-builder :as builder]))


(defn- reject!
  [code]
  (throw (ex-info "Cannot compile list construction" {:type :input/list-builder :code code})))


(defn- continuation-items
  [address continuations]
  (when (seq continuations)
    (when-not (and address (= #{address} (set (keys continuations)))) (reject! :invalid-list-continuation))
    (let [form (get continuations address)]
      (when-not (and (vector? form) (= 2 (count form)) (= :value (first form))
                     (or (nil? (second form)) (vector? (second form))))
        (reject! :unsupported-list-continuation))
      (second form))))


(defn compile-construction
  "Compile the checked chain with the receiving base's explicit delay policy
   and the ordinary child's execution context and call cache. A descendant
   append supplies another verified fragment. The open continuation can also
   bind a fixed literal list, closing that remaining input."
  [{:keys [fragments open-continuation] :as trace} {:keys [lazy-seq? child-callables lookups continuations]}]
  (when-not (and (vector? fragments) (seq fragments) (or (nil? continuations) (map? continuations))
                 (boolean? lazy-seq?) (map? child-callables) (map? lookups)
                 (= (select-keys trace [:bindings :open-continuation]) (builder/link-fragments fragments)))
    (reject! :invalid-list-chain))
  (let [tail (continuation-items open-continuation continuations)
        items (into [] (mapcat builder/compiler-items) fragments)
        evaluate (compile/compile-list-items (:owner-id (peek fragments)) items lazy-seq? child-callables lookups)]
    (if (seq tail)
      (fn [free-args ctx]
        (concat (evaluate free-args ctx) (if lazy-seq? (map (fn [v] (delay v)) tail) tail)))
      evaluate)))
