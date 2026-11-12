(ns graphden.executor.browser-source-frontier
  "Expand only fresh function/slot declarations before reading owner values.
   References stored in binding/list-item payload never participate here.
   All callbacks must use the same decorated immutable source snapshot."
  (:require
    [clojure.set :as set]))


(defn- reject!
  [reason]
  (throw (ex-info "Component source is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- function-edges
  [rows]
  (into #{} (remove nil?)
        (concat (mapcat :parent-ids rows)
                (mapcat #(keep % [:base-fn-id :return-type-fn-id :element-fn-id]) rows))))


(defn- check-functions!
  [seen found pending limit]
  (when (> (count (into (into seen (keys found)) pending)) limit)
    (reject! :source-function-limit)))


(defn- expand-functions
  [pending seen state {:keys [read-fns authorize! validate-owner! max-functions]}]
  (loop [pending (set/difference (set pending) seen (set (keys (:fns state))))
         state state]
    (check-functions! seen (:fns state) pending max-functions)
    (if (empty? pending)
      state
      (let [rows (vec (read-fns pending))]
        (when-not (and (= pending (set (map :id rows))) (= (count rows) (count pending)))
          (reject! :source-missing))
        (let [permissions (into {} (map (fn [row]
                                          (let [permission (authorize! row)]
                                            (validate-owner! row permission)
                                            [(:id row) permission]))) rows)
              state (-> state
                        (update :fns into (map (juxt :id identity)) rows)
                        (update :authorizations merge permissions))]
          (recur (set/difference (function-edges rows) seen (set (keys (:fns state)))) state))))))


(defn prepare
  "Return all actual metadata-reachable owners, fresh authorization receipts and
   declarations. The caller shares its normal candidate budget through callbacks
   and must still validate complete source after loading bindings/list items."
  [roots seen graph {:keys [read-fn-slots read-slots validate-structure!] :as callbacks}]
  (loop [pending roots
         state {:fns {} :authorizations {} :fn-slots []
                :slots (into {} (map (juxt :id identity)) (:slots graph)) :scanned #{}}]
    (let [state (expand-functions pending seen state callbacks)
          ids (set/difference (set (keys (:fns state))) (:scanned state))]
      (if (empty? ids)
        (let [needed (set (map :slot-id (:fn-slots state)))]
          (-> state
              (assoc :ids (set (keys (:fns state)))
                     :fns (vec (sort-by (comp str :id) (vals (:fns state))))
                     :slots (vec (vals (select-keys (:slots state) needed))))
              (dissoc :scanned)))
        (let [junctions (vec (read-fn-slots ids))
              _ (when-not (every? #(contains? ids (:fn-id %)) junctions)
                  (reject! :source-owner-mismatch))
              slot-ids (set (map :slot-id junctions))
              missing (set/difference slot-ids (set (keys (:slots state))))
              fresh-slots (if (seq missing) (vec (read-slots missing)) [])
              _ (when-not (and (= missing (set (map :id fresh-slots)))
                               (= (count missing) (count fresh-slots)))
                  (reject! :source-slot-missing))
              slots (into (:slots state) (map (juxt :id identity)) fresh-slots)
              selected-slots (vec (vals (select-keys slots slot-ids)))
              owners (mapv (:fns state) ids)]
          ;; Validate public/bare type declarations before following their types.
          ;; Own values are deliberately absent and checked again after loading.
          (validate-structure! {:fns owners :fn-slots junctions :slots selected-slots :bindings []}
                               (:authorizations state))
          (recur (keep :type-fn-id selected-slots)
                 (-> state
                     (assoc :slots slots)
                     (update :fn-slots into junctions)
                     (update :scanned into ids))))))))
