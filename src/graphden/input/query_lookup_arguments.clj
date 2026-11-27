(ns graphden.input.query-lookup-arguments
  "Adapt the old public argument map to five independently addressed V2 inputs."
  (:require
    [graphden.executor.compile.surface :as surface]
    [graphden.executor.runtime :as runtime]
    [graphden.input.runtime :as input]))


(def ^:private read-paths
  [[[:lookup-first :request] 0 :request]
   [[:lookup-next :lookup-first :request] 1 :request]
   [[:lookup-next :lookup-next :query-string :request] 2 :request]
   [[:lookup-next :lookup-first :path :item-1 :string :slots] 1 :param-name]
   [[:lookup-next :lookup-next :param-name :slots] 2 :param-name]])


(defn- source-record
  [tree]
  (input/record-view (update-vals tree #(if (map? %) (input/value-cell (source-record %)) %))))


(defn adapter
  "Bind only the reviewed input paths of a checked normalized graph. V1
   translated argument names in map traversal order. Its writer then
   replaced the lexical name and a specific set of physical UUIDs. Each
   actual invocation read interpreted the old missing sentinel and resolved
   the original outer thunk or IDeref once. Other inputs remain untouched."
  ([proof analysis] (adapter proof analysis []))
  ([{:keys [reads value field write-mask boundary return-type-id]} analysis prefix]
   (let [public (into {} (keep (fn [[path field]]
                                 (when (and (= :input-field (:kind field)) (< (count prefix) (count path))
                                            (= prefix (subvec path 0 (count prefix))))
                                   [(subvec path (count prefix)) field])))
                      (get-in analysis [:public-input :routes]))
         expected (into #{[:lookup-first :path :tail] [:lookup-next :lookup-first :path :tail]} (map first) read-paths)]
     (when (and (= :checked (:status analysis)) (uuid? return-type-id)
                (= expected (set (keys public)))
                (every? (fn [[path _ field]]
                          (= (if (= :request field) :any [:map :keyword :any]) (:type (get public path))))
                        read-paths))
       (fn [args]
         (let [translated (surface/translate-arguments boundary args)
               fa (reduce #(assoc %1 %2 value) translated (conj write-mask field))
               inputs (reduce (fn [m [path index field]]
                                (let [slot (get-in reads [index field])
                                      v (get fa slot :graphden.executor.compile-eager/miss)
                                      v (if (identical? v :graphden.executor.compile-eager/miss) (get fa field) v)]
                                  (assoc-in m path (input/field-cell
                                                     #(let [resolved (runtime/resolve-arg {:value v} :value)]
                                                        (if (= :request field) resolved {field resolved}))))))
                              {} read-paths)]
           (source-record inputs)))))))
