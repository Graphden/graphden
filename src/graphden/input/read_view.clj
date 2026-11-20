(ns graphden.input.read-view
  "Collect a branch-resolved structured input closure under one source policy."
  (:require
    [clojure.set :as set]
    [graphden.input.context :as context]
    [graphden.storage.protocol.core :as sp]))


(def ^:private max-functions 1024)
(def ^:private max-relations 8192)


(defn- exact-rows
  [storage entity ids]
  (let [rows (when (seq ids) (sp/read-entities storage entity ids))]
    (when-not (= ids (set (keys rows)))
      (context/reject! :missing-stored-input-dependency {}))
    (or rows {})))


(defn- owned-rows
  [storage entity owners remaining]
  (if (empty? owners)
    [[] remaining]
    (let [rows (sp/query-bounded-entities storage entity {:fn-id (vec owners)} remaining)
          used (::sp/candidate-count (meta rows) (count rows))]
      [rows (- remaining used)])))


(defn- authorized-owners
  [authorize! fns]
  (when-let [prepare! (:graphden.tenancy.context/prepare-namespaces (meta authorize!))]
    (prepare! (vals fns)))
  (into #{}
        (keep (fn [[id row]]
                (case (authorize! row)
                  nil id
                  :bare-type nil
                  ;; A browser ABI disclosure grants no authority to read that
                  ;; function's structured binding rows or arbitrary type body.
                  (context/reject! :input-source-interface-only {}))))
        fns))


(defn- dependencies
  [fns slots bindings]
  (into #{}
        (remove nil?)
        (concat (mapcat :parent-ids fns)
                (mapcat (juxt :input-type-fn-id :return-type-fn-id :base-fn-id :element-fn-id) fns)
                (map :type-fn-id slots)
                (mapcat (juxt :ref-fn-id :resolver-fn-id :type-override-fn-id) bindings))))


(defn- freeze
  [{:keys [fns members] :as view}]
  (let [empty-members (into {} (map #(vector % [])) (keep :input-type-fn-id (vals fns)))]
    (context/build-context (assoc view :members (merge empty-members members)))))


(defn collect
  "Read the reachable v2 declaration and binding rows in bounded breadth-first
   batches. The caller supplies an owned read snapshot and its authorizer.
   Every source owner is authorized before any of its relations are loaded."
  [storage root authorize!]
  (loop [pending #{root}, view {:fns {} :fields {} :members {} :bindings {}}, remaining max-relations]
    (if (empty? pending)
      (freeze view)
      (let [_ (when (> (+ (count pending) (count (:fns view))) max-functions)
                (context/reject! :stored-input-function-limit {}))
            fns (exact-rows storage :fn pending)
            owners (authorized-owners authorize! fns)
            binding-owners (into #{} (filter #(get-in fns [% :input-type-fn-id])) owners)
            [members remaining] (owned-rows storage :fn-slot owners remaining)
            [bindings remaining] (owned-rows storage :input-binding binding-owners remaining)
            slots (exact-rows storage :slot (into #{} (map :slot-id) members))
            found (set (keys (:fns view)))
            next-pending (set/difference (dependencies (vals (select-keys fns owners)) (vals slots) bindings)
                                         found pending)]
        (recur next-pending
               (-> view
                   (update :fns merge fns)
                   (update :fields merge slots)
                   (update :members merge (group-by :fn-id members))
                   (update :bindings into (map (juxt :id identity) bindings)))
               remaining)))))
