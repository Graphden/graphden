(ns graphden.executor.browser-source-frontier
  "Expand only fresh function declarations before reading owner values.
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
  [pending seen state {:keys [read-fns prepare-authorizations! authorize! validate-owner! max-functions]}]
  (loop [pending (set/difference (set pending) seen (set (keys (:fns state))))
         state state]
    (check-functions! seen (:fns state) pending max-functions)
    (if (empty? pending)
      state
      (let [rows (vec (read-fns pending))]
        (when-not (and (= pending (set (map :id rows))) (= (count rows) (count pending)))
          (reject! :source-missing))
        (prepare-authorizations! rows)
        (let [permissions (into {} (map (fn [row]
                                          (let [permission (authorize! row)]
                                            (validate-owner! row permission)
                                            [(:id row) permission]))) rows)
              state (-> state
                        (update :fns into (map (juxt :id identity)) rows)
                        (update :authorizations merge permissions))]
          (recur (set/difference (function-edges rows) seen (set (keys (:fns state)))) state))))))


(defn prepare
  "Expand fresh function declarations before one grouped owner read. Slot type
   and binding edges remain the caller's next ordinary source frontier."
  [roots seen {:keys [max-functions] :as callbacks}]
  (let [state (expand-functions roots seen {:fns {} :authorizations {}} callbacks)]
    (check-functions! seen (:fns state) #{} max-functions)
    (assoc state :ids (set (keys (:fns state)))
           :fns (vec (sort-by (comp str :id) (vals (:fns state)))))))
