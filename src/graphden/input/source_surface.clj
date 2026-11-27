(ns graphden.input.source-surface
  "Reconstruct a shared public input from the original compiler's physical readers."
  (:require
    [clojure.set :as set]
    [graphden.packages.records.ids :as ids]))


(def uri-reader (ids/fn-id "web.ring-adapter" :uri-segment-after))


(def fields
  [[:request "web.ring-adapter" :ring-request "web.ring-adapter" :ring-request-shape false]
   [:limit "core.strings" :str-split "core.refinements" :nullable-int false]])


(defn declare-fields
  [definition]
  (reduce (fn [source [field _ _ _ type required]]
            (assoc-in source [:args field] (cond-> {:type type} (not required) (assoc :required false))))
          definition fields))


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
