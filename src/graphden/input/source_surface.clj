(ns graphden.input.source-surface
  "Reconstruct a shared public input from the original compiler's physical readers."
  (:require
    [clojure.set :as set]
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.renames :as renames]
    [graphden.executor.compile.surface :as surface]
    [graphden.executor.registry.core :as registry]
    [graphden.input.source-legacy-readers :as readers]
    [graphden.packages.records.ids :as ids]))


(def uri-reader (ids/fn-id "web.ring-adapter" :uri-segment-after))


(def ^:private fields
  [[:request "web.ring-adapter" :ring-request "web.ring-adapter" :ring-request-shape false]
   [:limit "core.strings" :str-split "core.refinements" :nullable-int false]])


(defn declare-fields
  [definition]
  (reduce (fn [source [field _ _ _ type required]]
            (assoc-in source [:args field] (cond-> {:type type} (not required) (assoc :required false))))
          definition fields))


(defn check-original!
  "Authenticate the two free V1 readers, their types and all compiler writes
   reachable from this owner before introducing explicit forwarding fields."
  [prepared owner source]
  (let [graph (readers/owner-index prepared owner)
        roots (::readers/frozen-roots prepared)]
    (when-not (and (= uri-reader owner) (seq roots)
                   (not-any? #(contains? (:args source) (first %)) fields))
      (throw (ex-info "Missing original public input" {:type :input/source-surface :code :unproved-shared-input})))
    (binding [registry/*rich-types-override* (atom {:by-id roots :by-name {}})
              registry/*per-org-rich-override* (atom {})]
      (let [public (surface/surface-entries owner graph)
            frees (renames/deep-free-ext-entries owner graph)]
        (doseq [[field module reader type-module type required] fields
                :let [id (ids/slot-id (ids/fn-id module reader) field)
                      expected {:id id :name (name field) :type-fn-id (ids/fn-id type-module type) :required required}
                      entries #(filterv (fn [entry] (= field (:ext-name entry))) %)]]
          (when-not (and (= expected (select-keys (get-in graph [:slot-map id]) (keys expected)))
                         (= [id] (mapv :slot-id (entries public)) (mapv :slot-id (entries frees)))
                         (every?
                           (fn [callee]
                             (and (not-any? #(or (= field (:env-name %)) (= id (:slot-id %)))
                                            (bindings/collect-env-bindings callee graph))
                                  (every? #(or (not= :free (:kind %)) (= id (lookups/effective-reader-slot-id callee (:slot-id %) graph)))
                                          (entries (bindings/collect-bindings callee graph)))
                                  (every? (fn [{:keys [ref-id]}]
                                            (let [changes (when ref-id (renames/build-ref-renames ref-id callee graph))]
                                              (not-any? #{field} (concat (keys changes) (vals changes)))))
                                          (bindings/collect-bindings callee graph))))
                           (keys (:fn-map graph))))
            (throw (ex-info "Ambiguous original public input" {:type :input/source-surface :code :unproved-shared-input}))))))))


(defn exact-additions?
  "The expanded legacy view may append exactly two slot and membership pairs.
   Its pre-existing declarations, bindings and list items must remain identical."
  [owner before after]
  (let [prior (set (:records before))
        rows (set (:records after))
        added (set/difference rows prior)
        n (count (filter #(= :fn-slot (:kind %)) prior))
        after-args (group-by :source-path (:arguments after))
        old-paths (set (map :source-path (:arguments before)))]
    (and (= uri-reader owner) (set/subset? prior rows)
         (= (set (keys after-args)) (into old-paths (map #(vector :args (first %))) fields))
         (every? (fn [argument]
                   (= [(dissoc argument :forwarded-certificates)]
                      (mapv #(dissoc % :forwarded-certificates) (get after-args (:source-path argument)))))
                 (:arguments before))
         (every? (fn [[field module reader _ _ _]]
                   (let [[argument & others] (get after-args [:args field])
                         destination (ids/slot-id (ids/fn-id module reader) field)
                         projections (:forwarded-certificates argument)]
                     (and (= :declaration (:kind argument)) (empty? others)
                          (= destination (:source-slot-id argument))
                          (= 3 (count projections) (count (set (map :address projections))))
                          (every? #(and (= [destination] (get-in % [:row :target-path]))
                                        (= [(ids/slot-id owner field)] (get-in % [:row :projection-path])))
                                  projections))))
                 fields)
         (= added
            (into #{} (mapcat (fn [position [field _ _ type-module type required]]
                                (let [id (ids/slot-id owner field)]
                                  [{:kind :slot :id id :name (name field) :type-fn-id (ids/fn-id type-module type)
                                    :required required :description nil}
                                   {:kind :fn-slot :id (ids/fn-slot-id owner id) :fn-id owner :slot-id id :position position}]))
                              (range n (+ n (count fields))) fields))))))
