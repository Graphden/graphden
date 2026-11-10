(ns graphden.executor.browser-source-ancestry
  "Acquire actual function ancestry before owner payloads. Only freshly read,
   authorized function rows introduce source reachability."
  (:require
    [clojure.set :as set]))


(defn structural-ids
  [rows]
  (into #{} (remove nil?)
        (concat (mapcat :parent-ids rows)
                (mapcat #(map % [:base-fn-id :return-type-fn-id :element-fn-id]) rows))))


(defn- read-fresh!
  [cache read-fns ids]
  (let [missing (remove #(contains? (:rows @cache) %) ids)]
    (when (seq missing)
      (let [rows (read-fns (set missing))]
        (swap! cache update :rows merge
               (merge (zipmap missing (repeat nil)) (into {} (map (juxt :id identity)) rows))))))
  (mapv #(get-in @cache [:rows %]) ids))


(defn- payload-barrier?
  [authorization]
  ;; These interface allowances require the existing payload contract check
  ;; before their structural edges can authorize further source traversal.
  (or (= :bare-type authorization) (:type-contract authorization)))


(defn- expand
  [seeds known {:keys [cache seen read-fns authorize! limit reject!]}]
  (loop [pending (set seeds) rows [] authorizations {}]
    (if (empty? pending)
      {:ids (set (keys authorizations)) :fns rows :authorizations authorizations}
      (let [batch (read-fresh! cache read-fns pending)]
        (when (some nil? batch) (reject! :source-missing))
        (let [authorized (into {} (map (fn [row] [(:id row) (authorize! row)])) batch)
              authorizations (merge authorizations authorized)
              dependencies (structural-ids
                             (remove #(payload-barrier? (get authorized (:id %))) batch))
              reached (set/union known (set (keys authorizations)) dependencies)]
          (when (> (count reached) limit) (reject! :source-function-limit))
          (recur (set/difference dependencies (set (keys authorizations)) seen)
                 (into rows batch) authorizations))))))


(defn collector
  "Return a collection-local reader for actual function ancestry. No advisory
   identities, bindings, slot payloads or list items are loaded here. `seen`
   contains owners whose payloads already passed the ordinary collector."
  [options]
  (let [cache (atom {:rows {}})]
    (fn [seeds seen pending]
      (expand seeds (set/union seen pending) (assoc options :cache cache :seen seen)))))
