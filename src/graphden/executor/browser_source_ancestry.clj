(ns graphden.executor.browser-source-ancestry
  "Acquire actual function ancestry before owner payloads. Advisory metadata
   never introduces source reachability; only freshly read function rows do."
  (:require
    [clojure.set :as set]
    [graphden.executor.browser-source-prefetch :as prefetch]))


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
        ;; Cache missing metadata too, but refuse it only if the actual fresh
        ;; traversal reaches that ID. A stale hint cannot deny the request.
        (swap! cache update :rows merge
               (merge (zipmap missing (repeat nil)) (into {} (map (juxt :id identity)) rows))))))
  (mapv #(get-in @cache [:rows %]) ids))


(defn- prefetch-identities!
  [cache hint-fns known-hints seeds read-fns limit]
  (when (seq hint-fns)
    (when-let [ids (prefetch/bounded-closure seeds known-hints
                                             #(structural-ids [(get hint-fns %)]) limit)]
      (let [missing (remove #(contains? (:rows @cache) %) ids)
            remaining (- limit (:speculative-count @cache))]
        (when (<= (count missing) remaining)
          (read-fresh! cache read-fns missing)
          (swap! cache update :speculative-count + (count missing)))))))


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
  "Return a request-local metadata collector. No bindings, slot payloads or
   list items are read here. At most `limit` speculative identities are loaded
   across all frontiers; actual source retains the independent function limit.
   `seen` contains owners whose payloads already passed the ordinary collector."
  [{:keys [hint-fns read-fns limit] :as options}]
  (let [cache (atom {:rows {} :speculative-count 0})
        known-hints (set (keys hint-fns))]
    (fn [seeds seen pending]
      (prefetch-identities! cache hint-fns known-hints seeds read-fns limit)
      (expand seeds (set/union seen pending) (assoc options :cache cache :seen seen)))))
