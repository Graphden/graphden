(ns graphden.input.list-execution
  "Offline JVM execution of a source-verified list construction."
  (:require
    [graphden.executor.compile-eager :as compile]
    [graphden.executor.runtime :as runtime]
    [graphden.input.list-builder :as builder]
    [graphden.input.list-records :as records]
    [graphden.types.validator :as validator]))


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


(defn- delayed-literal
  [value]
  (delay value))


(defn compile-arguments
  "Build ordinary list values from the frame's lazy ABI arguments. Pull one
   operand per demand and resolve the optional continuation after the prefix."
  [operands]
  (let [ordered (apply list operands)]
    (fn [args _ctx]
      (concat (map (partial runtime/resolve-arg args) ordered)
              (lazy-seq (seq (runtime/resolve-arg args :tail)))))))


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
        evaluate (compile/compile-list-items (:owner-id (peek fragments)) items lazy-seq? child-callables lookups)
        item-types (mapcat #(map :type (:items %)) fragments)
        evaluate (if (some some? item-types)
                   (let [checks (mapv #(when % (validator/build %)) item-types)
                         checked (fn [check value]
                                   (cond (nil? check) value
                                         lazy-seq? (delay (check @value))
                                         :else (check value)))]
                     (fn [args ctx] (map checked checks (evaluate args ctx))))
                   evaluate)]
    (if (seq tail)
      (fn [free-args ctx]
        (concat (evaluate free-args ctx) (if lazy-seq? (map delayed-literal tail) tail)))
      evaluate)))


(defn compile-rows
  "Compile a complete generated record set without rereading the legacy
   argument source or its list-item rows."
  [owners target-id field name->id generated options]
  (compile-construction (records/recover-chain owners target-id field name->id generated) options))
