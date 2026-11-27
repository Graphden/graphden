(ns graphden.input.legacy-literal-routes
  "Recover the public and physical reader identities for one lexical literal."
  (:require
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.compile.surface :as surface]
    [graphden.packages.records.ids :as ids]))


(defn descriptor
  "Read the original compiler's indexed records. Return a witness for a
   single literal that shadows some, but not all, original readers of its
   field. This describes the environment at the owner's invocation. A
   consumer lowering deeper reads must still verify its entire use path."
  [graph owner field]
  (let [[writer & others] (bindings/collect-env-bindings owner graph)
        {:keys [slot-id value]} writer
        row (get-in graph [:binding-by-fn-slot [owner slot-id]])]
    (when (and (uuid? owner) (simple-keyword? field) (nil? others)
               (= writer {:kind :value :slot-id slot-id :value value :env-name field})
               (= :binding (:kind row)) (uuid? slot-id)
               (= [owner slot-id (ids/binding-id owner slot-id) value true]
                  ((juxt :fn-id :slot-id :id :value :value-present) row))
               (not-any? row [:ref-fn-id :resolver-fn-id :list-append :list-closed])
               (empty? (get-in graph [:items-by-binding (:id row)])))
      (let [routes (surface/surface-entries owner graph)
            mask (set (lookups/env-binding-route-ids owner writer (bindings/collect-bindings owner graph)
                                                     (lookups/fn-owned-slot-ids owner graph) graph))
            readers (filterv #(= field (:ext-name %)) routes)
            covered (filterv (comp mask :slot-id) readers)
            incoming (filterv (complement (comp mask :slot-id)) readers)]
        (when (and (seq covered) (seq incoming)
                   (every? (fn [{:keys [slot-id source-name]}]
                             (and (uuid? slot-id) (= slot-id (get-in graph [:slot-map slot-id :id]))
                                  (or (nil? source-name) (= field source-name)))) readers)
                   (= (count readers) (count (set (map :slot-id readers)))))
          {:owner-id owner :field field :binding-id (:id row) :slot-id slot-id
           :value value :write-mask mask :written (mapv :slot-id covered)
           :prefer-incoming (mapv :slot-id incoming)
           :boundary routes :accepted (:accepted (surface/surface-names owner graph))})))))
